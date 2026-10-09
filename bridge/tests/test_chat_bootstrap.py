"""A fresh owned Codex launch may send once before its first rollout exists."""

from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from types import SimpleNamespace

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.codex import CodexHistoryNotPersisted, CodexReader, FRESH_CHAT_TTL_SECONDS
from herdr_bridge.codex_integration import CodexIntegrationResult
from herdr_bridge.herdr import BridgeError
from .conftest import AUTH, TOKEN


THREAD = "019c711f-ecf0-7111-9222-333344445555"
FRESH_HEADERS = {"X-Herdr-Chat-Fresh-Input": "1", "X-Herdr-Chat-Working-Input": "1"}


@pytest.fixture
async def fresh_chat(socket_server, tmp_path, monkeypatch):
    backend, requests, results, options = socket_server
    home = tmp_path / "synthetic-codex"
    home.mkdir()
    tab = {"workspace_id": "w1", "tab_id": "w1:t3"}
    agent = {"workspace_id": "w1", "tab_id": "w1:t3", "pane_id": "w1:p3", "terminal_id": "owned-new-terminal",
             "agent": "codex", "agent_status": "idle", "interactive_ready": True,
             "agent_session": {"source": "herdr:codex", "agent": "codex", "kind": "id", "value": THREAD}}
    results["tab.create"] = {"type": "tab_created", "tab": tab, "root_pane": agent}
    results["agent.start"] = {"type": "agent_started", "agent": agent, "argv": ["codex"]}
    results["agent.get"] = {"type": "agent_info", "agent": agent}
    results["agent.prompt"] = {"type": "agent_prompted", "agent": agent}
    results["pane.get"] = {"type": "pane", "pane": agent}
    results["pane.process_info"] = {"type": "pane_process_info", "process_info": {
        "pane_id": "w1:p3", "shell_pid": 42, "foreground_process_group_id": 42,
        "foreground_processes": [{"pid": 42, "name": "bash"}]}}
    original_call = backend.call

    async def call(method, params):
        if method == "agent.start":
            agent["name"] = params["name"]
        result = await original_call(method, params)
        if method == "tab.create":
            results["session.snapshot"]["snapshot"]["panes"].append(agent)
            results["session.snapshot"]["snapshot"]["tabs"].append(tab | {"label": "New Codex"})
        return result

    backend.call = call
    monkeypatch.setattr("herdr_bridge.app.ensure_codex_integration", lambda *_: CodexIntegrationResult(True))
    app = create_app(backend, TOKEN, codex_executable="/bin/sh", codex_home=home)
    now = [0.0]
    app.state.conversations.clock = lambda: now[0]
    state = {"missing": True, "error": None, "saved_turns": None}

    async def read(thread):
        if state["error"]:
            raise state["error"]
        if state["missing"]:
            raise CodexHistoryNotPersisted(thread)
        if state["saved_turns"] is not None:
            return {"thread": {"id": thread, "turns": state["saved_turns"]}}
        return {"thread": {"id": thread, "turns": [{"id": "first-turn", "items": [
            {"type": "userMessage", "id": "first-user", "content": [{"type": "text", "text": "First message"}]}]}]}}

    app.state.codex_reader.read = read
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test", headers=AUTH) as phone:
            yield phone, app, agent, requests, results, state, now


async def create(phone):
    response = await phone.post("/v1/workspaces/w1/tabs", json={"kind": "codex", "label": "New Codex"})
    assert response.status_code == 201, response.text
    assert response.json()["paneId"] == "w1:p3"
    return response.json()


async def page(phone, *, headers=FRESH_HEADERS):
    response = await phone.get("/v1/panes/w1:p3/conversation", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


async def send(phone, binding, text="First message", *, headers=FRESH_HEADERS):
    return await phone.post("/v1/panes/w1:p3/messages", headers=headers, json={"binding": binding, "text": text})


async def test_owned_new_chat_accepts_first_message_then_reads_materialized_history(fresh_chat):
    phone, app, _, requests, _, state, now = fresh_chat
    await create(phone)
    empty = await page(phone)
    assert empty["messages"] == [] and empty["freshBootstrap"] and empty["canSend"]
    assert (await send(phone, empty["binding"])).status_code == 200
    assert "w1:p3" not in app.state.conversations.fresh_chats
    assert [x["method"] for x in requests].count("agent.prompt") == 1
    state["missing"] = False
    now[0] += 1
    saved = await page(phone)
    assert not saved["freshBootstrap"] and saved["messages"][0]["text"] == "First message"
    assert saved["binding"] == empty["binding"] and saved["canSend"]
    assert (await send(phone, saved["binding"], "Next message")).status_code == 200
    assert [x["method"] for x in requests].count("agent.prompt") == 2


async def test_owned_saved_zero_turn_thread_keeps_first_message_permission_only_until_attempt(fresh_chat):
    phone, app, agent, requests, _, state, now = fresh_chat
    await create(phone)
    state.update(missing=False, saved_turns=[])
    agent["agent_status"] = "unknown"
    empty = await page(phone)
    assert empty["messages"] == [] and empty["freshBootstrap"] and empty["canSend"]
    legacy = await page(phone, headers={})
    assert not legacy["canSend"]
    assert (await send(phone, empty["binding"])).status_code == 200
    assert "w1:p3" not in app.state.conversations.fresh_chats
    now[0] += 1
    after = await page(phone)
    assert not after["freshBootstrap"] and not after["canSend"]
    assert (await send(phone, after["binding"])).status_code == 409
    assert [x["method"] for x in requests].count("agent.prompt") == 1


async def test_any_saved_turn_even_without_visible_items_retires_fresh_permission(fresh_chat):
    phone, app, agent, requests, _, state, _ = fresh_chat
    await create(phone)
    state.update(missing=False, saved_turns=[{"id": "existing-turn", "items": []}])
    agent["agent_status"] = "unknown"
    empty = await page(phone)
    assert empty["messages"] == [] and not empty["freshBootstrap"] and not empty["canSend"]
    assert "w1:p3" not in app.state.conversations.fresh_chats
    assert (await send(phone, empty["binding"])).status_code == 409
    assert all(x["method"] != "agent.prompt" for x in requests)


async def test_saved_zero_turn_thread_does_not_restore_consumed_first_attempt(fresh_chat):
    phone, _, _, requests, _, state, now = fresh_chat
    await create(phone)
    empty = await page(phone)
    assert (await send(phone, empty["binding"])).status_code == 200
    state.update(missing=False, saved_turns=[])
    now[0] += 1
    after = await page(phone)
    assert not after["freshBootstrap"] and not after["canSend"]
    assert (await send(phone, after["binding"])).status_code == 503
    assert [x["method"] for x in requests].count("agent.prompt") == 1


@pytest.mark.parametrize("error_code", [None, "agent_prompt_failed", "agent_blocked"])
async def test_first_attempt_is_consumed_even_when_delivery_is_uncertain_or_rejected(fresh_chat, error_code):
    phone, app, _, requests, results, _, _ = fresh_chat
    await create(phone)
    empty = await page(phone)
    if error_code:
        results["agent.prompt"] = {"error": {"code": error_code, "message": "PRIVATE never expose"}}
    first = await send(phone, empty["binding"])
    assert first.status_code == (200 if error_code is None else 409 if error_code == "agent_blocked" else 503)
    assert "PRIVATE" not in first.text
    assert "w1:p3" not in app.state.conversations.fresh_chats
    second = await send(phone, empty["binding"])
    assert second.status_code == 503
    assert [x["method"] for x in requests].count("agent.prompt") == 1
    assert (await phone.get("/v1/panes/w1:p3/conversation", headers=FRESH_HEADERS)).status_code == 503


@pytest.mark.parametrize("header", [None, "0", "true", "1"])
async def test_ready_unknown_fresh_chat_requires_exact_opt_in_header(fresh_chat, header):
    phone, _, agent, requests, _, _, _ = fresh_chat
    agent["agent_status"] = "unknown"
    await create(phone)
    headers = {} if header is None else {"X-Herdr-Chat-Fresh-Input": header}
    empty = await page(phone, headers=headers)
    assert empty["freshBootstrap"] and empty["status"] == "unknown"
    assert empty["canSend"] is (header == "1")
    response = await send(phone, empty["binding"], headers=headers)
    assert response.status_code == (200 if header == "1" else 409)
    assert [x["method"] for x in requests].count("agent.prompt") == (1 if header == "1" else 0)


@pytest.mark.parametrize("mutation", ["blocked", "pending", "malformedPending", "falseReady", "missingReady", "malformedReady"])
async def test_fresh_receipt_never_bypasses_explicit_readiness_guards(fresh_chat, mutation):
    phone, _, agent, requests, _, _, _ = fresh_chat
    agent["agent_status"] = "unknown"
    if mutation == "blocked": agent["agent_status"] = "blocked"
    if mutation == "pending": agent["launch_pending"] = True
    if mutation == "malformedPending": agent["launch_pending"] = "false"
    if mutation == "falseReady": agent["interactive_ready"] = False
    if mutation == "missingReady": agent.pop("interactive_ready")
    if mutation == "malformedReady": agent["interactive_ready"] = "true"
    await create(phone)
    empty = await page(phone)
    assert empty["freshBootstrap"] and not empty["canSend"]
    assert (await send(phone, empty["binding"])).status_code == 409
    assert not any(x["method"] == "agent.prompt" for x in requests)


@pytest.mark.parametrize("mutation", ["terminal", "name", "workspace", "tab", "thread", "expiry", "socket"])
async def test_fresh_receipt_is_retired_when_identity_or_lifetime_changes(fresh_chat, mutation):
    phone, app, agent, requests, _, _, now = fresh_chat
    await create(phone)
    initial = await page(phone)
    if mutation == "terminal": agent["terminal_id"] = "replacement-terminal"
    if mutation == "name": agent["name"] = "replaced"
    if mutation == "workspace": agent["workspace_id"] = "w2"
    if mutation == "tab": agent["tab_id"] = "w1:t8"
    if mutation == "thread": agent["agent_session"]["value"] = "019c711f-ecf0-7111-9222-333344445556"
    if mutation == "expiry": now[0] += FRESH_CHAT_TTL_SECONDS + 1
    if mutation == "socket": app.state.conversations.fresh_chats["w1:p3"].socket_identity = (-1, -1)
    now[0] += 1
    assert (await phone.get("/v1/panes/w1:p3/conversation", headers=FRESH_HEADERS)).status_code == 503
    assert "w1:p3" not in app.state.conversations.fresh_chats
    assert (await send(phone, initial["binding"])).status_code in (409, 503)
    assert not any(x["method"] == "agent.prompt" for x in requests)


async def test_existing_pane_and_other_history_failures_never_become_empty_chat(fresh_chat):
    phone, app, _, _, _, state, now = fresh_chat
    # An agent name alone is not a creation receipt.
    assert (await phone.get("/v1/panes/w1:p3/conversation", headers=FRESH_HEADERS)).status_code == 503
    await create(phone)
    state["error"] = BridgeError(503, "Incompatible saved history")
    now[0] += 1
    assert (await phone.get("/v1/panes/w1:p3/conversation", headers=FRESH_HEADERS)).status_code == 503
    state["error"], state["missing"] = None, False
    now[0] += 1
    saved = await page(phone)
    assert not saved["freshBootstrap"]
    assert "w1:p3" not in app.state.conversations.fresh_chats
    state["missing"] = True
    now[0] += 1
    assert (await phone.get("/v1/panes/w1:p3/conversation", headers=FRESH_HEADERS)).status_code == 503


class Stdin:
    def __init__(self):
        self.data = b""

    def write(self, data):
        self.data += data

    async def drain(self):
        pass


@pytest.mark.parametrize("code,message,expected", [
    (-32600, f"thread not loaded: {THREAD}", True),
    (-32600, f"no rollout found for thread id {THREAD}", True),
    (-32600, "thread not loaded: another-thread", False),
    (-32600, f"prefix thread not loaded: {THREAD}", False),
    (-32601, f"thread not loaded: {THREAD}", False),
    (-32603, f"thread not loaded: {THREAD}", False),
    ("-32600", f"thread not loaded: {THREAD}", False),
    (-32600, "permission denied", False),
])
async def test_only_exact_matching_official_rpc_errors_are_classified_as_unpersisted(tmp_path, code, message, expected):
    reader = CodexReader(None, tmp_path)
    stdout = asyncio.StreamReader()
    stdout.feed_data(json.dumps({"id": 1, "error": {"code": code, "message": message}}).encode() + b"\n")
    stdout.feed_eof()
    stdin = Stdin()
    reader.process = SimpleNamespace(stdin=stdin, stdout=stdout)
    with pytest.raises(BridgeError) as result:
        await reader._rpc("thread/read", {"threadId": THREAD, "includeTurns": True})
    assert isinstance(result.value, CodexHistoryNotPersisted) is expected
    assert json.loads(stdin.data)["method"] == "thread/read"
