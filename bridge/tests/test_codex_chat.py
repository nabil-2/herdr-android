from __future__ import annotations

from copy import deepcopy
import asyncio
import json
import os
from pathlib import Path
import sys

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.codex import CodexReader, MAX_PAGE_BYTES, normalize_thread
from herdr_bridge.herdr import BridgeError
from herdr_bridge.pairing import PairingStore
from .conftest import AUTH, TOKEN

THREAD_ID = "12345678-1234-4123-8123-123456789abc"


def history(count=3):
    return {"thread": {"id": THREAD_ID, "historyMode": "legacy", "status": {"type": "notLoaded"},
                       "turns": [{"id": "turn-1", "status": "completed", "items": [
                           {"id": str(i), "type": "userMessage", "content": [{"type": "text", "text": f"Message {i}"}]} if i % 2 == 0 else
                           {"id": str(i), "type": "agentMessage", "text": f"Message {i}", "phase": "commentary"}
                           for i in range(count)]}]}}


@pytest.fixture
def codex_stub(tmp_path):
    home = tmp_path / "codex-home"
    home.mkdir()
    payload = home / "fixture.json"
    payload.write_text(json.dumps(history()))
    log = home / "requests.jsonl"
    pid_file = home / "pid"
    script = tmp_path / "codex-stub"
    script.write_text(f"#!{sys.executable}\n" + '''
import json, os, pathlib, sys, time
home = pathlib.Path(os.environ['CODEX_HOME'])
(home / 'pid').write_text(str(os.getpid()))
(home / 'env.json').write_text(json.dumps({'path': os.environ.get('PATH'), 'thread': os.environ.get('CODEX_THREAD_ID'), 'pane': os.environ.get('HERDR_PANE_ID')}))
sys.stderr.write('never forward secret diagnostic ' * 20000)
sys.stderr.flush()
for line in sys.stdin:
    request = json.loads(line)
    with (home / 'requests.jsonl').open('a') as log:
        log.write(json.dumps(request) + '\\n')
    if 'id' not in request:
        continue
    if request['method'] == 'initialize':
        response = {'id': request['id'], 'result': {'userAgent': 'synthetic-codex'}}
    else:
        fixture = json.loads((home / 'fixture.json').read_text())
        mode = fixture.pop('testMode', '')
        if mode == 'timeout':
            time.sleep(60)
        if mode == 'invalidUtf8':
            sys.stdout.buffer.write(b'\\xff\\n'); sys.stdout.buffer.flush(); continue
        if mode == 'unexpectedRequest':
            print(json.dumps({'id': 80, 'method': 'item/commandExecution/requestApproval', 'params': {'secret': 'never show'}}), flush=True)
            continue
        if mode == 'error':
            response = {'id': request['id'], 'error': {'message': 'raw secret path /private/auth.json'}}
        else:
            response = {'id': request['id'] + (1 if mode == 'wrongId' else 0), 'result': fixture}
    print(json.dumps({'method': 'deprecationNotice', 'params': {'message': 'never forward notice'}}), flush=True)
    print(json.dumps(response), flush=True)
''')
    script.chmod(0o700)
    return str(script), home, payload, log, pid_file


def assert_reaped(pid_file):
    pid = int(pid_file.read_text())
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


async def test_reader_uses_only_non_resuming_protocol_and_reads_updates(codex_stub, monkeypatch):
    executable, home, payload, log, pid = codex_stub
    monkeypatch.setenv("CODEX_THREAD_ID", "inherited-agent-thread")
    monkeypatch.setenv("HERDR_PANE_ID", "w99:p99")
    reader = CodexReader(executable, home, search_path=os.environ["PATH"])
    try:
        assert (await reader.read(THREAD_ID))["thread"]["turns"][0]["items"][0]["content"][0]["text"] == "Message 0"
        payload.write_text(json.dumps(history(4)))
        assert len((await reader.read(THREAD_ID))["thread"]["turns"][0]["items"]) == 4
        requests = [json.loads(line) for line in log.read_text().splitlines()]
        assert [r["method"] for r in requests] == ["initialize", "initialized", "thread/read", "thread/read"]
        assert all(r["params"] == {"threadId": THREAD_ID, "includeTurns": True} for r in requests if r["method"] == "thread/read")
        assert json.loads((home / "env.json").read_text()) == {"path": os.environ["PATH"], "thread": None, "pane": None}
    finally:
        await reader.close()
    assert_reaped(pid)


@pytest.mark.parametrize("mode", ["timeout", "invalidUtf8", "unexpectedRequest", "error", "wrongId"])
async def test_reader_sanitizes_failures_and_reaps(codex_stub, mode):
    executable, home, payload, _, pid = codex_stub
    payload.write_text(json.dumps({**history(), "testMode": mode}))
    reader = CodexReader(executable, home, timeout=0.15 if mode == "timeout" else 3)
    with pytest.raises(BridgeError) as exc:
        await reader.read(THREAD_ID)
    assert exc.value.status == 503
    assert "secret" not in exc.value.detail and "/private" not in exc.value.detail
    assert reader.process is None
    assert_reaped(pid)


async def test_reader_rejects_oversized_line_and_reaps(codex_stub, monkeypatch):
    import herdr_bridge.codex as codex
    executable, home, payload, _, pid = codex_stub
    monkeypatch.setattr(codex, "MAX_RPC_BYTES", 1024)
    payload.write_text(json.dumps({**history(), "ignored": "x" * 4096}))
    reader = CodexReader(executable, home)
    with pytest.raises(BridgeError):
        await reader.read(THREAD_ID)
    assert_reaped(pid)


@pytest.mark.parametrize("mode", ["legacy", "paginated"])
def test_normalized_modes_filter_injected_context_and_preserve_literal_messages(mode):
    result = history()
    result["thread"]["historyMode"] = mode
    items = result["thread"]["turns"][0]["items"]
    items[0]["content"].extend([{"type": "localImage", "path": "/private/image.png"}, {"type": "text", "text": "line two"}])
    items.extend([{"type": "hookPrompt", "id": "h", "fragments": [{"text": "secret hook"}]},
                  {"type": "reasoning", "id": "r", "content": ["private reasoning"]},
                  {"type": "commandExecution", "id": "c", "aggregatedOutput": "tool secret"},
                  {"type": "response_item", "role": "user", "content": [{"text": "injected instructions"}]}])
    messages, warning = normalize_thread(result, THREAD_ID)
    assert len(messages) == 3
    assert messages[0]["text"] == "Message 0\nline two"
    assert messages[1]["phase"] == "commentary"
    assert warning is None
    assert not any(value in json.dumps(messages) for value in ["secret", "reasoning", "/private", "instructions"])


@pytest.mark.parametrize("mutation", ["otherThread", "badTurns", "badItems", "badText", "duplicateId"])
def test_incompatible_saved_history_is_rejected(mutation):
    result = history()
    if mutation == "otherThread": result["thread"]["id"] = "another-thread"
    if mutation == "badTurns": result["thread"]["turns"] = "not turns"
    if mutation == "badItems": result["thread"]["turns"][0]["items"] = None
    if mutation == "badText": result["thread"]["turns"][0]["items"][1]["text"] = 42
    if mutation == "duplicateId": result["thread"]["turns"][0]["items"].append(result["thread"]["turns"][0]["items"][0])
    with pytest.raises(BridgeError): normalize_thread(result, THREAD_ID)


@pytest.fixture
async def chat(socket_server, codex_stub):
    backend, requests, results, options = socket_server
    executable, home, payload, log, pid = codex_stub
    agent = {"pane_id": "w1:p2", "terminal_id": "term-test-generation", "agent": "codex", "agent_status": "idle",
             "interactive_ready": True, "launch_pending": False,
             "agent_session": {"source": "herdr:codex", "agent": "codex", "kind": "id", "value": THREAD_ID}}
    results["agent.get"] = {"type": "agent", "agent": agent}
    results["agent.prompt"] = {"type": "agent_prompted", "agent": agent}
    results["session.snapshot"]["snapshot"]["panes"][1].update(agent)
    app = create_app(backend, TOKEN, codex_executable=executable, codex_home=home)
    now = [0.0]
    app.state.conversations.clock = lambda: now[0]
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost", headers=AUTH) as client:
        try:
            yield client, app, agent, requests, results, options, payload, log, now
        finally:
            await app.state.codex_reader.close()


async def test_chat_capability_and_metadata_do_not_disclose_native_session(chat):
    client, _, _, _, _, _, _, _, _ = chat
    assert (await client.get("/v1/health")).json()["capabilities"] == ["terminal-input", "terminal-history", "workspace-tabs", "codex-chat", "codex-chat-working-input", "codex-chat-fresh-input", "codex-create", "codex-chat-attachments", "codex-chat-selection"]
    response = await client.get("/v1/snapshot")
    assert response.json()["panes"][1]["conversation"] == {"provider": "codex", "available": True, "reason": None}
    assert THREAD_ID not in response.text and "term-test-generation" not in response.text


async def test_chat_requires_auth_before_read_or_write(chat):
    client, _, _, requests, _, _, _, log, _ = chat
    for method, path in [("GET", "/v1/panes/w1:p2/conversation"), ("POST", "/v1/panes/w1:p2/messages")]:
        response = await client.request(method, path, headers={"Authorization": "Bearer wrong"}, json={"binding": "x" * 43, "text": "hello"})
        assert response.status_code == 401
    assert not log.exists() and requests == []


async def test_latest_older_pages_revision_and_shared_poll_cache(chat):
    client, _, _, _, _, _, payload, log, now = chat
    payload.write_text(json.dumps(history(112)))
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert len(page["messages"]) == 50
    assert page["messages"][0]["text"] == "Message 62"
    assert page["canSend"] and not page["unchanged"]
    older = (await client.get("/v1/panes/w1:p2/conversation", params={"cursor": page["olderCursor"]})).json()
    assert older["messages"][0]["text"] == "Message 12" and len(older["messages"]) == 50
    unchanged = (await client.get("/v1/panes/w1:p2/conversation", params={"revision": page["revision"]})).json()
    assert unchanged["unchanged"] and unchanged["messages"] == [] and unchanged["historyEpoch"] == page["historyEpoch"]
    assert [json.loads(line)["method"] for line in log.read_text().splitlines()].count("thread/read") == 1
    now[0] += 1
    payload.write_text(json.dumps(history(113)))
    appended = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert appended["revision"] != page["revision"] and appended["historyEpoch"] == page["historyEpoch"]
    assert (await client.get("/v1/panes/w1:p2/conversation", params={"cursor": older["olderCursor"]})).status_code == 409


async def test_rollback_and_rewrite_rotate_epoch_and_replace_history(chat):
    client, _, _, _, _, _, payload, _, now = chat
    original = (await client.get("/v1/panes/w1:p2/conversation")).json()
    now[0] += 1
    payload.write_text(json.dumps(history(1)))
    rolled = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert len(rolled["messages"]) == 1 and rolled["historyEpoch"] != original["historyEpoch"]
    assert rolled["binding"] == original["binding"]
    now[0] += 1
    changed = history(1)
    changed["thread"]["turns"][0]["items"][0]["content"][0]["text"] = "Edited message"
    payload.write_text(json.dumps(changed))
    rewritten = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert rewritten["historyEpoch"] != rolled["historyEpoch"]
    assert rewritten["messages"][0]["text"] == "Edited message"


async def test_error_recovery_resets_history_even_if_rollback_happened_while_unavailable(chat):
    client, _, _, _, _, _, payload, _, now = chat
    original = (await client.get("/v1/panes/w1:p2/conversation")).json()
    now[0] += 1
    payload.write_text(json.dumps({**history(), "testMode": "error"}))
    assert (await client.get("/v1/panes/w1:p2/conversation")).status_code == 503
    now[0] += 1
    payload.write_text(json.dumps(history(1)))
    recovered = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert len(recovered["messages"]) == 1 and recovered["historyEpoch"] != original["historyEpoch"]


async def test_large_messages_have_visible_truncation_and_bounded_pages(chat):
    client, _, _, _, _, _, payload, _, _ = chat
    result = history(6)
    for item in result["thread"]["turns"][0]["items"]:
        if item["type"] == "userMessage": item["content"][0]["text"] = "😀" * 100000
        else: item["text"] = '"\\\t' * 100000
    payload.write_text(json.dumps(result))
    response = await client.get("/v1/panes/w1:p2/conversation")
    assert response.status_code == 200 and len(response.content) <= MAX_PAGE_BYTES
    assert response.json()["warning"] and "truncated" in response.text
    unchanged = (await client.get("/v1/panes/w1:p2/conversation", params={"revision": response.json()["revision"]})).json()
    assert unchanged["olderCursor"] == response.json()["olderCursor"] and unchanged["messages"] == []


async def test_normal_code_sized_responses_remain_complete(chat):
    client, _, _, _, _, _, payload, _, _ = chat
    result = history(2)
    text = "line of source code\n" * 4000
    result["thread"]["turns"][0]["items"][1]["text"] = text
    payload.write_text(json.dumps(result))
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert page["messages"][1]["text"] == text and page["warning"] is None


async def test_cache_evicts_older_bindings_at_total_byte_limit(chat, monkeypatch):
    import herdr_bridge.codex as codex
    client, app, agent, _, _, _, payload, _, _ = chat
    payload.write_text(json.dumps(history(20)))
    first = (await client.get("/v1/panes/w1:p2/conversation")).json()
    caches = app.state.conversations.caches
    size = caches[first["binding"]].byte_size
    monkeypatch.setattr(codex, "MAX_CACHE_BYTES", size + size // 2)
    agent["terminal_id"] = "replacement-terminal"
    second = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert first["binding"] not in caches and second["binding"] in caches
    assert sum(c.byte_size for c in caches.values()) <= codex.MAX_CACHE_BYTES


async def test_cancelled_read_reaps_the_pending_sidecar(codex_stub):
    executable, home, payload, _, pid = codex_stub
    payload.write_text(json.dumps({**history(), "testMode": "timeout"}))
    reader = CodexReader(executable, home)
    pending = asyncio.create_task(reader.read(THREAD_ID))
    for _ in range(100):
        if pid.exists(): break
        await asyncio.sleep(0.01)
    assert pid.exists()
    pending.cancel()
    with pytest.raises(asyncio.CancelledError): await pending
    assert_reaped(pid)


async def test_lifespan_shutdown_reaps_reader(chat, codex_stub):
    client, app, _, _, _, _, _, _, _ = chat
    async with app.router.lifespan_context(app):
        assert (await client.get("/v1/panes/w1:p2/conversation")).status_code == 200
    assert_reaped(codex_stub[-1])


@pytest.mark.parametrize("mutation", ["shell", "unknownSession", "wrongSource", "pathSession", "malformedId"])
async def test_unbound_or_noncanonical_panes_never_start_reader(chat, mutation):
    client, _, agent, _, _, _, _, log, _ = chat
    if mutation == "shell": agent["agent"] = None
    if mutation == "unknownSession": agent.pop("agent_session")
    if mutation == "wrongSource": agent["agent_session"]["source"] = "custom:codex"
    if mutation == "pathSession": agent["agent_session"]["kind"] = "path"
    if mutation == "malformedId": agent["agent_session"]["value"] = "/private/conversation.jsonl"
    response = await client.get("/v1/panes/w1:p2/conversation")
    assert response.status_code == 409 and not log.exists()
    assert "/private" not in response.text


@pytest.mark.parametrize("state,ready,pending", [("blocked", True, False), ("unknown", True, False), ("idle", False, False), ("idle", True, True), ("working", False, False), ("working", True, True)])
async def test_blocked_or_unready_targets_cannot_receive_chat_messages(chat, state, ready, pending):
    client, _, agent, requests, _, _, _, _, _ = chat
    client.headers["X-Herdr-Chat-Working-Input"] = "1"
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    agent.update(agent_status=state, interactive_ready=ready, launch_pending=pending)
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "Do not send"})
    assert response.status_code == 409
    assert not any(r["method"] == "agent.prompt" for r in requests)
    unavailable_page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert not unavailable_page["canSend"]
    assert isinstance(unavailable_page["canSendReason"], str) and unavailable_page["canSendReason"]


@pytest.mark.parametrize("status", ["idle", "done", "working"])
@pytest.mark.parametrize("named", [False, True], ids=["unnamed", "manually-renamed"])
async def test_manually_launched_codex_omits_managed_flags_and_can_receive_chat_messages(chat, status, named):
    client, _, agent, requests, _, _, _, _, _ = chat
    client.headers["X-Herdr-Chat-Working-Input"] = "1"
    agent.pop("interactive_ready")
    agent.pop("launch_pending")
    agent["agent_status"] = status
    if named: agent["name"] = "manually-renamed-agent"
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert page["canSend"] and page["canSendReason"] is None
    result = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "One explicit prompt"})
    assert result.status_code == 200
    prompts = [r for r in requests if r["method"] == "agent.prompt"]
    assert len(prompts) == 1
    assert prompts[0]["params"] == {"target": "w1:p2", "text": "One explicit prompt"}


@pytest.mark.parametrize("mutation", ["namedPending", "explicitFalse", "null", "string", "pending", "malformedPending", "blocked", "unknown"])
async def test_omitted_managed_readiness_never_bypasses_other_chat_guards(chat, mutation):
    client, _, agent, requests, _, _, _, _, _ = chat
    first = (await client.get("/v1/panes/w1:p2/conversation")).json()
    agent.pop("interactive_ready")
    agent.pop("launch_pending")
    if mutation == "namedPending": agent.update(name="managed-agent", launch_pending=True)
    if mutation == "explicitFalse": agent["interactive_ready"] = False
    if mutation == "null": agent["interactive_ready"] = None
    if mutation == "string": agent["interactive_ready"] = "true"
    if mutation == "pending": agent["launch_pending"] = True
    if mutation == "malformedPending": agent["launch_pending"] = "false"
    if mutation in {"blocked", "unknown"}: agent["agent_status"] = mutation
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert not page["canSend"] and page["canSendReason"]
    result = await client.post("/v1/panes/w1:p2/messages", json={"binding": first["binding"], "text": "Do not send"})
    assert result.status_code == 409
    assert not any(r["method"] == "agent.prompt" for r in requests)


@pytest.mark.parametrize("named", [False, True], ids=["ready-ordinary-agent", "ready-managed-agent"])
async def test_working_agent_accepts_one_explicit_prompt_without_waiting_for_idle(chat, named):
    client, _, agent, requests, _, _, _, _, _ = chat
    client.headers["X-Herdr-Chat-Working-Input"] = "1"
    agent["agent_status"] = "working"
    if named: agent["name"] = "managed-agent"
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert page["canSend"] and page["canSendReason"] is None
    assert page["status"] == "working"
    result = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "A deliberate follow-up"})
    assert result.status_code == 200
    prompts = [r for r in requests if r["method"] == "agent.prompt"]
    assert len(prompts) == 1
    assert prompts[0]["params"] == {"target": "w1:p2", "text": "A deliberate follow-up"}


@pytest.mark.parametrize("header", [None, "0", "true", "1, 1"])
async def test_legacy_phone_can_read_working_history_without_claiming_it_can_send(chat, header):
    client, _, agent, requests, _, _, _, _, _ = chat
    if header is not None: client.headers["X-Herdr-Chat-Working-Input"] = header
    agent["agent_status"] = "working"
    response = await client.get("/v1/panes/w1:p2/conversation")
    assert response.status_code == 200
    page = response.json()
    assert page["status"] == "working" and not page["canSend"]
    assert page["messages"] and page["canSendReason"] == "Update Herdr Pocket to send while Codex works."
    rejected = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "Do not send"})
    assert rejected.status_code == 409
    assert not any(r["method"] == "agent.prompt" for r in requests)


async def test_message_is_literal_multiline_exactly_one_agent_prompt(chat):
    client, _, agent, requests, _, _, _, _, _ = chat
    agent["agent_status"] = "done"
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    text = "Explain these lines:\n'$HOME' and `literal`\nLast line"
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": text})
    assert response.json() == {"ok": True, "warning": None}
    writes = [r for r in requests if r["method"] not in {"agent.get", "session.snapshot", "pane.process_info"}]
    assert len(writes) == 1 and writes[0]["method"] == "agent.prompt"
    assert writes[0]["params"] == {"target": "w1:p2", "text": text}


@pytest.mark.parametrize("pending,expected", [("omitted", True), (False, True), (True, False), (None, False), (0, False), ("false", False)])
async def test_stock_omitted_launch_pending_is_ready_but_malformed_values_are_not(chat, pending, expected):
    client, _, agent, requests, _, _, _, _, _ = chat
    if pending == "omitted": agent.pop("launch_pending")
    else: agent["launch_pending"] = pending
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert page["canSend"] is expected
    result = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "message"})
    assert result.status_code == (200 if expected else 409)
    assert any(r["method"] == "agent.prompt" for r in requests) is expected


async def test_replaced_terminal_rotates_binding_and_rejects_draft(chat):
    client, _, agent, requests, _, _, _, _, _ = chat
    first = (await client.get("/v1/panes/w1:p2/conversation")).json()
    agent["terminal_id"] = "new-terminal"
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": first["binding"], "text": "stale draft"})
    assert response.status_code == 409 and not any(r["method"] == "agent.prompt" for r in requests)
    assert (await client.get("/v1/panes/w1:p2/conversation")).json()["binding"] != first["binding"]


async def test_postcheck_warns_when_pane_changes_after_submission(chat):
    client, app, agent, _, _, _, _, _, _ = chat
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    prompt = app.state.conversations.client.prompt

    async def changed(pane, text):
        await prompt(pane, text)
        agent["terminal_id"] = "changed-after-preflight"

    app.state.conversations.client.prompt = changed
    result = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "one message"})
    assert result.status_code == 200 and result.json()["ok"] is True
    assert "inspect Terminal" in result.json()["warning"]


async def test_changed_binding_during_read_does_not_disclose_old_history(chat):
    client, app, agent, requests, _, _, _, _, _ = chat
    read = app.state.codex_reader.read

    async def changed(thread):
        result = await read(thread)
        agent["terminal_id"] = "new-generation"
        return result

    app.state.codex_reader.read = changed
    result = await client.get("/v1/panes/w1:p2/conversation")
    assert result.status_code == 409 and "Message 0" not in result.text
    assert not any(r["method"] == "agent.prompt" for r in requests)


@pytest.mark.parametrize("code", ["agent_prompt_failed", "timeout", "unknown_dispatch_error"])
async def test_uncertain_agent_prompt_never_retries(chat, code):
    client, _, _, requests, results, _, _, _, _ = chat
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    results["agent.prompt"] = {"error": {"code": code, "message": "private diagnostic"}}
    result = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "one message"})
    assert result.status_code == 503 and "private diagnostic" not in result.text
    assert "not confirmed" in result.json()["detail"]
    assert len([r for r in requests if r["method"] == "agent.prompt"]) == 1


@pytest.mark.parametrize("code", ["agent_blocked", "agent_not_ready", "ambiguous_agent_target"])
async def test_known_herdr_prompt_preflight_rejection_is_a_conflict(chat, code):
    client, _, _, requests, results, _, _, _, _ = chat
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    results["agent.prompt"] = {"error": {"code": code, "message": "private diagnostic"}}
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": page["binding"], "text": "one message"})
    assert response.status_code == 409 and "private diagnostic" not in response.text
    assert len([r for r in requests if r["method"] == "agent.prompt"]) == 1


async def test_invalid_cursor_and_oversized_upload_are_rejected(chat):
    client, _, _, requests, _, _, _, _, _ = chat
    assert (await client.get("/v1/panes/w1:p2/conversation", params={"cursor": "invalid.signed"})).status_code == 409
    before = len(requests)
    assert (await client.post("/v1/panes/w1:p2/messages", content=b"x" * 65537)).status_code == 413
    assert len(requests) == before


@pytest.mark.parametrize("text", ["", "   ", "x\x00y", "x\x1by", "x" * 16385, "😀" * 9000])
async def test_invalid_message_has_sanitized_error_and_no_upstream_write(chat, text):
    client, _, _, requests, _, _, _, _, _ = chat
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": "x" * 43, "text": text})
    assert response.status_code in {413, 422} and requests == []
    assert "input" not in response.json()


async def test_no_reader_disables_chat_without_breaking_terminal_api(socket_server):
    backend, _, _, _ = socket_server
    app = create_app(backend, TOKEN)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost", headers=AUTH) as client:
        assert (await client.get("/v1/health")).json()["capabilities"] == ["terminal-input", "terminal-history", "workspace-tabs"]
        assert (await client.get("/v1/panes/w1:p2/conversation")).status_code == 503
        assert (await client.post("/v1/panes/w1:p1/input", json={"keys": ["enter"]})).status_code == 200


async def test_revoked_device_cannot_read_or_send_chat(chat, tmp_path):
    client, app, _, requests, _, _, _, _, _ = chat
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Test")
    device = store.exchange(pending["code"], "Phone")
    # Middleware receives the same live store used by the installed service.
    app.user_middleware[0].kwargs["pairing_store"] = store
    client.headers["Authorization"] = f"Bearer {device['token']}"
    first = (await client.get("/v1/panes/w1:p2/conversation")).json()
    store.revoke(device["deviceId"])
    before = len(requests)
    assert (await client.get("/v1/panes/w1:p2/conversation")).status_code == 401
    assert (await client.post("/v1/panes/w1:p2/messages", json={"binding": first["binding"], "text": "blocked"})).status_code == 401
    assert len(requests) == before
