from __future__ import annotations

import asyncio
from dataclasses import replace
import json

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.codex_history import CodexHistoryUnsupported
from herdr_bridge.codex_identity import DaemonSession
from herdr_bridge.herdr import BridgeError
from .conftest import AUTH, TOKEN
from .test_codex_identity import selected_host, choose, metadata, THREAD, OTHER, OWNER

HEADERS = {"X-Herdr-Chat-Working-Input": "1"}


async def test_same_frontend_reconnect_keeps_explicit_choice_binding_and_original_digest(selected_host):
    phone, app, manager, requests, _, _, _, _, current = selected_host
    await choose(phone)
    first = (await phone.get("/v1/panes/w1:p2/conversation", headers=HEADERS)).json()
    original = current["runtime"].identity
    current["runtime"] = replace(current["runtime"], frontend_socket=9090, daemon_socket=8080)
    second = (await phone.get("/v1/panes/w1:p2/conversation", headers=HEADERS)).json()
    assert second["binding"] == first["binding"] and second["conversationId"] == THREAD
    assert second["messages"] == first["messages"]
    assert manager.selection_digests[(OWNER, "w1:p2")] == original
    assert current["runtime"].identity != original
    assert not any(request["method"] in {"turn/start", "turn/steer"} for request in requests)


async def test_transient_missing_peer_or_loaded_thread_does_not_forget_choice(selected_host):
    phone, _, manager, _, _, threads, _, _, current = selected_host
    await choose(phone)
    runtime = current["runtime"]
    current["runtime"] = None
    assert (await phone.get("/v1/panes/w1:p2/conversation")).status_code == 409
    assert (OWNER, "w1:p2") in manager.selections
    current["runtime"] = runtime
    value = threads.pop(THREAD)
    assert (await phone.get("/v1/panes/w1:p2/conversation")).status_code == 409
    assert (OWNER, "w1:p2") in manager.selections
    threads[THREAD] = value
    assert (await phone.get("/v1/panes/w1:p2/conversation")).status_code == 200


@pytest.mark.parametrize("changed", ["frontend_pid", "frontend_start", "terminal_id", "daemon_pid", "daemon_start",
                                     "socket_inode", "herdr_inode", "home"])
async def test_actual_runtime_replacement_never_inherits_prior_choice(selected_host, changed, tmp_path):
    phone, _, manager, requests, _, _, _, _, current = selected_host
    await choose(phone)
    value = getattr(current["runtime"], changed)
    value = tmp_path / "different-home" if changed == "home" else "different-terminal" if changed == "terminal_id" else value + 1
    current["runtime"] = replace(current["runtime"], **{changed: value})
    response = await phone.get("/v1/panes/w1:p2/conversation")
    assert response.status_code == 409 and "Choose" in response.json()["detail"]
    assert (OWNER, "w1:p2") not in manager.selections
    assert not any(request["method"] in {"turn/start", "turn/steer"} for request in requests)


async def test_remembered_selection_and_pending_delivery_survive_reconnect_then_companion_restart(selected_host, monkeypatch):
    phone, app, manager, requests, herdr, _, _, _, current = selected_host
    await choose(phone)
    original_digest = current["runtime"].identity
    first = (await phone.get("/v1/panes/w1:p2/conversation", headers=HEADERS)).json()
    # Persist a guard through the same store operation used before attachment
    # delivery, without sending anything to even the owned synthetic daemon.
    uploaded = await phone.post("/v1/panes/w1:p2/attachments", params={"kind": "file", "name": "owned.txt"},
                                headers={"X-Herdr-Chat-Binding": first["binding"], "Content-Type": "application/octet-stream"},
                                content=b"Synthetic staged contents")
    assert uploaded.status_code == 201, uploaded.text
    await app.state.attachments.consume([uploaded.json()["id"]], "w1:p2", first["binding"], OWNER)
    assert app.state.attachments.blocked("w1:p2", first["binding"])
    current["runtime"] = replace(current["runtime"], frontend_socket=9090, daemon_socket=8080)
    restarted = create_app(manager.client, TOKEN, codex_executable="/bin/sh", codex_home=manager.home,
                           attachment_dir=app.state.attachments.root)
    monkeypatch.setattr(restarted.state.conversations.daemon, "probe", manager.probe)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(restarted), base_url="http://restarted", headers=AUTH) as client:
        try:
            response = await client.get("/v1/panes/w1:p2/conversation", headers=HEADERS)
            assert response.status_code == 200, response.text
            page = response.json()
            assert page["binding"] != first["binding"] and page["conversationId"] == THREAD
            assert page["attachmentDeliveryUncertain"] and not page["canSend"]
            restored = restarted.state.conversations.daemon
            assert restored.selection_digests[(OWNER, "w1:p2")] == original_digest
            assert (await client.post("/v1/panes/w1:p2/messages", headers=HEADERS,
                                      json={"binding": page["binding"], "text": "Must remain blocked"})).status_code == 503
            assert not any(request["method"] in {"turn/start", "turn/steer"} for request in requests)
            assert not any(request["method"] in {"agent.prompt", "pane.send_input"} for request in herdr)
        finally:
            await restarted.state.attachments.close()


async def test_restart_restore_cannot_overwrite_a_later_acknowledged_deliberate_selection(selected_host, monkeypatch):
    _, _, manager, _, _, threads, _, _, _ = selected_host
    offer = await manager.list("w1:p2", OWNER)
    await manager.select("w1:p2", OWNER, THREAD, offer["selectionToken"])
    manager.selections.clear()
    manager.selection_digests.clear()
    threads[OTHER] = metadata(OTHER)
    offer = await manager.list("w1:p2", OWNER)
    entered, release = asyncio.Event(), asyncio.Event()
    original = manager.selection_store.load

    async def delayed(*args):
        record = await original(*args)
        entered.set()
        await release.wait()
        return record

    monkeypatch.setattr(manager.selection_store, "load", delayed)
    agent = await manager.client.agent("w1:p2")
    restore = asyncio.create_task(manager.target(agent, OWNER))
    await entered.wait()
    selection = asyncio.create_task(manager.select("w1:p2", OWNER, OTHER, offer["selectionToken"]))
    await asyncio.sleep(0)
    assert not selection.done()
    release.set()
    await restore
    await selection
    assert manager.selections[(OWNER, "w1:p2")][1] == OTHER


@pytest.mark.parametrize("failure", ["unsupported", "generic", "timeout", "permission"])
async def test_only_typed_unsupported_live_history_can_read_exact_saved_thread(selected_host, monkeypatch, failure):
    phone, app, manager, requests, _, _, full, _, _ = selected_host
    await choose(phone)
    saved_reads = []

    async def unavailable(*_args):
        if failure == "unsupported":
            raise CodexHistoryUnsupported()
        raise BridgeError(503 if failure != "permission" else 409, "Controlled history failure")

    async def saved(thread_id):
        saved_reads.append(thread_id)
        return full

    monkeypatch.setattr(manager, "read", unavailable)
    monkeypatch.setattr(app.state.codex_reader, "read", saved)
    response = await phone.get("/v1/panes/w1:p2/conversation", headers=HEADERS)
    assert response.status_code == (200 if failure == "unsupported" else 409 if failure == "permission" else 503)
    assert saved_reads == ([THREAD] if failure == "unsupported" else [])
    if failure == "unsupported":
        assert response.json()["conversationId"] == THREAD
        assert [message["text"] for message in response.json()["messages"]] == ["Synthetic user", "Synthetic answer"]
        assert response.json()["canSend"]
    assert not any(request["method"] in {"turn/start", "turn/steer"} for request in requests)


async def test_real_websocket_size_close_reconnects_reader_only_and_returns_explicit_summary(selected_host, monkeypatch):
    from herdr_bridge import codex_identity as identity
    phone, _, _, requests, _, _, full, _, _ = selected_host
    monkeypatch.setattr(identity, "MAX_HISTORY_RPC_BYTES", 16 * 1024)
    full["thread"]["turns"][0]["items"].insert(1, {"id": "tool", "type": "commandExecution",
                                                    "aggregatedOutput": "HIDDEN_OUTPUT" * 2000})
    await choose(phone)
    response = await phone.get("/v1/panes/w1:p2/conversation", headers=HEADERS)
    assert response.status_code == 200, response.text
    page = response.json()
    assert [message["text"] for message in page["messages"]] == ["Synthetic user", "Synthetic answer"]
    assert "intermediate messages" in page["warning"] and "HIDDEN_OUTPUT" not in response.text
    assert any(request["params"].get("itemsView") == "summary" for request in requests)
    assert not any(request["method"] in {"turn/start", "turn/steer"} for request in requests)


@pytest.mark.parametrize("method", ["thread/read", "thread/turns/list"])
async def test_actual_matched_wire_unsupported_error_retains_its_semantic_type(method):
    class Connection:
        async def send(self, _raw):
            pass

        async def recv(self):
            return json.dumps({"id": 1, "error": {"code": -32601, "message": "list_turns is not supported yet"}})

    session = DaemonSession(None)
    session.connection = Connection()
    with pytest.raises(CodexHistoryUnsupported):
        await session.rpc(method, {"threadId": THREAD, "includeTurns": True})
