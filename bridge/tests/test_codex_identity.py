from __future__ import annotations

import asyncio
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import socket
import tempfile

import httpx
import pytest
from websockets.asyncio.server import unix_serve

from herdr_bridge.app import create_app
from herdr_bridge.codex_identity import DaemonRuntime, thread_metadata, _unix_peers, _daemon_socket
from herdr_bridge.herdr import BridgeError

from tests.conftest import AUTH, TOKEN

THREAD = "12345678-90ab-7cde-8fab-0123456789ab"
OTHER = "22345678-90ab-7cde-8fab-0123456789ab"
TURN = "32345678-90ab-7cde-8fab-0123456789ab"
OWNER = hashlib.sha256(TOKEN.encode()).hexdigest()


def metadata(identity=THREAD, **changes):
    value = {"id": identity, "sessionId": identity, "parentThreadId": None, "source": "cli",
             "canAcceptDirectInput": True, "ephemeral": False, "turns": [],
             "createdAt": 100, "name": "Chosen conversation", "preview": "PRIVATE USER CONTENT",
             "status": {"type": "active", "activeFlags": []}}
    return value | changes


@pytest.fixture
async def selected_host(socket_server, tmp_path, monkeypatch):
    from herdr_bridge import codex_identity as identity
    backend, herdr_requests, results, _ = socket_server
    agent = {"pane_id": "w1:p2", "terminal_id": "term_fixture", "workspace_id": "w1", "tab_id": "w1:t2",
             "agent": "codex", "agent_status": "blocked", "agent_session": {
                 "source": "herdr:codex", "agent": "codex", "kind": "id", "value": OTHER}}
    results["agent.get"] = {"type": "agent", "agent": agent}
    results["session.snapshot"]["snapshot"]["panes"][1].update(agent="codex", agent_session=agent["agent_session"])
    home = tmp_path / "codex-home"
    home.mkdir()
    path = tmp_path / "daemon.sock"
    requests = []
    threads = {THREAD: metadata(), OTHER: metadata(OTHER, source="exec")}
    full_history = {"thread": {"id": THREAD, "turns": [{"id": TURN, "status": "completed", "items": [
        {"id": "u1", "type": "userMessage", "content": [{"type": "text", "text": "Synthetic user"}]},
        {"id": "a1", "type": "agentMessage", "text": "Synthetic answer", "phase": "final_answer"}]}]}}
    control = {"mode": "ack", "turn": TURN, "turnStatus": "inProgress", "turnItems": []}

    async def respond(connection):
        async for raw in connection:
                request = json.loads(raw)
                requests.append(request)
                if "id" not in request:
                    continue
                method, params = request["method"], request["params"]
                if method == "initialize":
                    result = {"userAgent": "synthetic-daemon"}
                elif method == "thread/loaded/list":
                    ids = sorted(threads)
                    if params.get("cursor") is not None:
                        ids = [value for value in ids if value > params["cursor"]]
                    page = ids[:params["limit"]]
                    result = {"data": page, "nextCursor": page[-1] if len(ids) > params["limit"] else None}
                elif method == "thread/read":
                    result = full_history if params["includeTurns"] else {"thread": threads[params["threadId"]]}
                elif method == "thread/turns/list":
                    assert params["sortDirection"] == "desc"
                    if params["itemsView"] == "notLoaded":
                        assert params["limit"] == 1
                        result = {"data": [{"id": control["turn"], "status": control["turnStatus"], "items": control["turnItems"]}],
                                  "nextCursor": None}
                    else:
                        assert params["itemsView"] in {"full", "summary"} and params["threadId"] == full_history["thread"]["id"]
                        assert 1 <= params["limit"] <= 5
                        turns = list(reversed(full_history["thread"]["turns"]))
                        offset = int(params.get("cursor") or 0)
                        end = offset + params["limit"]
                        page = turns[offset:end]
                        if params["itemsView"] == "summary":
                            page = [turn | {"items": [item for item in (
                                next((item for item in turn["items"] if item.get("type") == "userMessage"), None),
                                next((item for item in reversed(turn["items"]) if item.get("type") == "agentMessage"), None),
                            ) if item is not None]} for turn in page]
                        result = {"data": page,
                                  "nextCursor": str(end) if end < len(turns) else None}
                elif method in {"turn/start", "turn/steer"}:
                    if control["mode"] == "drop":
                        return
                    if control["mode"] == "serverRequest":
                        await connection.send(json.dumps({"id": 99, "method": "item/commandExecution/requestApproval", "params": {}}))
                        return
                    result = {"turnId": control["turn"]} if method == "turn/steer" else {"turn": {"id": TURN, "status": "inProgress"}}
                    if control["mode"] == "wrongAck":
                        result = {"turnId": OTHER}
                else:
                    raise AssertionError(f"Unexpected selected operation {method}")
                await connection.send(json.dumps({"id": request["id"], "result": result}))

    server = await unix_serve(respond, path=str(path), max_size=32 * 1024 * 1024, compression=None)
    info, herdr = path.stat(), backend.socket_path.stat()
    runtime = DaemonRuntime("w1:p2", "term_fixture", 101, 1001, 2001, 2002, os.getpid(), 3001,
                            path, info.st_dev, info.st_ino, home, herdr.st_dev, herdr.st_ino)
    current = {"runtime": runtime}
    details = [b"0"] * 20
    details[19] = b"3001"
    monkeypatch.setattr(identity, "_process", lambda _pid: (details, [b"app-server"], {}))
    app = create_app(backend, TOKEN, codex_executable="/bin/sh", codex_home=home, attachment_dir=tmp_path / "attachments")
    manager = app.state.conversations.daemon

    async def probe(_agent):
        return current["runtime"]

    monkeypatch.setattr(manager, "probe", probe)
    async with server, httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://bridge", headers=AUTH) as phone:
        yield phone, app, manager, requests, herdr_requests, threads, full_history, control, current
    await app.state.attachments.close()


async def choose(phone):
    response = await phone.get("/v1/panes/w1:p2/codex-conversations")
    assert response.status_code == 200, response.text
    selection = response.json()
    response = await phone.post("/v1/panes/w1:p2/codex-conversation",
                                json={"id": THREAD, "selectionToken": selection["selectionToken"]})
    assert response.status_code == 200, response.text
    return selection


async def test_explicit_selection_never_uses_stale_hook_or_user_preview(selected_host):
    phone, app, _, requests, herdr, _, _, _, _ = selected_host
    before = await phone.get("/v1/panes/w1:p2/conversation")
    assert before.status_code == 409 and "Choose" in before.json()["detail"]
    snapshot = (await phone.get("/v1/snapshot")).json()
    assert snapshot["panes"][1]["conversation"]["selectable"]
    assert not snapshot["panes"][1]["conversation"]["available"]
    selection = await choose(phone)
    assert selection["conversations"] == [{"id": THREAD, "title": "Chosen conversation", "createdAt": 100, "status": "working"}]
    assert "PRIVATE USER CONTENT" not in json.dumps(selection)
    page = (await phone.get("/v1/panes/w1:p2/conversation", headers={"X-Herdr-Chat-Working-Input": "1"})).json()
    assert page["canSend"] and page["status"] == "working"
    assert [message["text"] for message in page["messages"]] == ["Synthetic user", "Synthetic answer"]
    assert app.state.codex_reader.process is None  # Reads use the selected live server.
    assert all(request["method"] not in {"turn/start", "turn/steer"} for request in requests)
    assert all(request["method"] not in {"agent.prompt", "pane.send_input", "pane.send_text"} for request in herdr)
    assert all(request["params"]["includeTurns"] is False for request in requests
               if request["method"] == "thread/read" and request["params"]["threadId"] == OTHER)


@pytest.mark.parametrize("change", ["owner", "pane", "expired", "id", "runtime", "unloaded", "replay"])
async def test_selection_proof_rejects_scope_expiry_reuse_and_changed_runtime(selected_host, change):
    _, _, manager, requests, _, threads, _, _, current = selected_host
    offered = await manager.list("w1:p2", OWNER)
    token = offered["selectionToken"]
    pane, owner, thread = "w1:p2", OWNER, THREAD
    if change == "owner":
        owner = "another-credential"
    elif change == "pane":
        pane = "w1:p1"
    elif change == "expired":
        expired = manager.offers[token].expires_at + 1
        manager.clock = lambda: expired
    elif change == "id":
        thread = OTHER
    elif change == "runtime":
        current["runtime"] = replace(current["runtime"], frontend_start=9999)
    elif change == "unloaded":
        threads.pop(THREAD)
    elif change == "replay":
        await manager.select(pane, owner, thread, token)
    with pytest.raises(BridgeError) as error:
        await manager.select(pane, owner, thread, token)
    assert error.value.status == 409
    assert all(request["method"] not in {"turn/start", "turn/steer"} for request in requests)


async def test_selection_does_not_transfer_between_bearer_profiles(selected_host):
    _, _, manager, _, _, _, _, _, _ = selected_host
    offered = await manager.list("w1:p2", OWNER)
    await manager.select("w1:p2", OWNER, THREAD, offered["selectionToken"])
    agent = await manager.client.agent("w1:p2")
    with pytest.raises(BridgeError, match="Choose"):
        await manager.target(agent, "another-owner")


async def test_real_selection_http_chat_send_uses_exact_daemon_binding(selected_host):
    phone, app, _, requests, herdr, _, _, _, _ = selected_host
    await choose(phone)
    headers = {"X-Herdr-Chat-Working-Input": "1"}
    page = (await phone.get("/v1/panes/w1:p2/conversation", headers=headers)).json()
    response = await phone.post("/v1/panes/w1:p2/messages", headers=headers,
                                json={"binding": page["binding"], "text": "Synthetic explicit Send"})
    assert response.status_code == 200 and response.json() == {"ok": True, "warning": None}
    writes = [request for request in requests if request["method"] in {"turn/start", "turn/steer"}]
    assert len(writes) == 1 and writes[0]["params"]["threadId"] == THREAD
    assert all(request["method"] not in {"agent.prompt", "pane.send_input"} for request in herdr)
    assert app.state.codex_reader.process is None


async def test_switching_selected_conversation_invalidates_the_old_send_binding(selected_host):
    phone, _, manager, requests, herdr, threads, _, _, _ = selected_host
    await choose(phone)
    headers = {"X-Herdr-Chat-Working-Input": "1"}
    page = (await phone.get("/v1/panes/w1:p2/conversation", headers=headers)).json()
    threads[OTHER] = metadata(OTHER)
    offer = await manager.list("w1:p2", OWNER)
    await manager.select("w1:p2", OWNER, OTHER, offer["selectionToken"])
    response = await phone.post("/v1/panes/w1:p2/messages", headers=headers,
                                json={"binding": page["binding"], "text": "Must be rejected"})
    assert response.status_code == 409
    assert all(request["method"] not in {"turn/start", "turn/steer"} for request in requests)
    assert all(request["method"] not in {"agent.prompt", "pane.send_input"} for request in herdr)


async def test_loaded_children_do_not_hide_primary_chat_on_later_pages(selected_host):
    phone, _, manager, requests, _, threads, _, _, _ = selected_host
    for index in range(65):
        child = f"00000000-0000-7000-8000-{index:012x}"
        threads[child] = metadata(child, parentThreadId=THREAD, canAcceptDirectInput=False)
    choices = await choose(phone)
    assert [item["id"] for item in choices["conversations"]] == [THREAD]
    assert any(request["method"] == "thread/loaded/list" and "cursor" in request["params"] for request in requests)
    page = (await phone.get("/v1/panes/w1:p2/conversation", headers={"X-Herdr-Chat-Working-Input": "1"})).json()
    assert page["canSend"]
    assert await manager.deliver("w1:p2", OWNER, THREAD, "Synthetic later-page message", [])
    writes = [request for request in requests if request["method"] == "turn/steer"]
    assert len(writes) == 1 and writes[0]["params"]["threadId"] == THREAD


async def test_loaded_pagination_and_displayed_selection_are_bounded(selected_host):
    _, _, manager, requests, _, threads, _, _, _ = selected_host
    threads.clear()
    for index in range(300):
        identity = f"00000000-0000-7000-8000-{index:012x}"
        threads[identity] = metadata(identity, createdAt=index)
    choices = await manager.list("w1:p2", OWNER)
    assert len(choices["conversations"]) == 32
    assert sum(request["method"] == "thread/loaded/list" for request in requests) == 8
    undisplayed = next(identity for identity in threads if identity not in {item["id"] for item in choices["conversations"]})
    with pytest.raises(BridgeError) as error:
        await manager.select("w1:p2", OWNER, undisplayed, choices["selectionToken"])
    assert error.value.status == 409


@pytest.mark.parametrize("status", ["idle", "working"])
async def test_selected_send_is_one_exact_typed_daemon_submission_without_pty(selected_host, status):
    phone, _, manager, requests, herdr, threads, _, _, _ = selected_host
    threads[THREAD]["status"] = {"type": "idle"} if status == "idle" else {"type": "active", "activeFlags": []}
    await choose(phone)
    requests.clear()
    assert await manager.deliver("w1:p2", OWNER, THREAD, "Literal $HOME\n`text`", [Path("/tmp/synthetic.png")])
    writes = [request for request in requests if request["method"] in {"turn/start", "turn/steer"}]
    assert len(writes) == 1
    write = writes[0]
    assert write["method"] == ("turn/start" if status == "idle" else "turn/steer")
    assert write["params"]["threadId"] == THREAD
    assert write["params"]["input"] == [{"type": "text", "text": "Literal $HOME\n`text`", "textElements": []},
                                        {"type": "localImage", "path": "/tmp/synthetic.png"}]
    assert write["params"].get("expectedTurnId") == (TURN if status == "working" else None)
    assert all(request["method"] not in {"agent.prompt", "pane.send_input"} for request in herdr)


@pytest.mark.parametrize("mode", ["drop", "wrongAck", "serverRequest"])
async def test_uncertain_daemon_submission_never_retries_or_falls_back(selected_host, mode):
    phone, _, manager, requests, herdr, _, _, control, _ = selected_host
    await choose(phone)
    control["mode"] = mode
    requests.clear()
    with pytest.raises(BridgeError) as error:
        await manager.deliver("w1:p2", OWNER, THREAD, "Synthetic only", [])
    assert error.value.status == 503
    assert sum(request["method"] == "turn/steer" for request in requests) == 1
    assert all(request["method"] not in {"agent.prompt", "pane.send_input"} for request in herdr)
    assert all("result" not in request and "error" not in request for request in requests)  # No approval response.


@pytest.mark.parametrize("change", ["blocked", "completedTurn", "turnItems", "changedRuntime", "wrongThread"])
async def test_preflight_rejection_never_delivers_to_any_target(selected_host, change):
    phone, _, manager, requests, herdr, threads, _, control, current = selected_host
    await choose(phone)
    thread = THREAD
    if change == "blocked":
        threads[THREAD]["status"]["activeFlags"] = ["waitingOnApproval"]
    elif change == "completedTurn":
        control["turnStatus"] = "completed"
    elif change == "turnItems":
        control["turnItems"] = [{"type": "userMessage", "text": "must not be read"}]
    elif change == "changedRuntime":
        current["runtime"] = replace(current["runtime"], daemon_start=9999)
    else:
        thread = OTHER
    with pytest.raises(BridgeError) as error:
        await manager.deliver("w1:p2", OWNER, thread, "Synthetic only", [])
    assert error.value.status == 409
    assert all(request["method"] not in {"turn/start", "turn/steer"} for request in requests)
    assert all(request["method"] not in {"agent.prompt", "pane.send_input"} for request in herdr)


async def test_selected_history_retains_original_32_mib_rpc_bound(selected_host):
    phone, _, _, _, _, _, history, _, _ = selected_host
    history["thread"]["turns"][0]["items"][1]["text"] = "x" * (2 * 1024 * 1024 + 1)
    await choose(phone)
    page = await phone.get("/v1/panes/w1:p2/conversation", headers={"X-Herdr-Chat-Working-Input": "1"})
    assert page.status_code == 200
    assert page.json()["warning"] and page.json()["messages"]


@pytest.mark.parametrize("changes", [
    {"id": OTHER}, {"sessionId": OTHER}, {"parentThreadId": OTHER}, {"source": "exec"},
    {"canAcceptDirectInput": False}, {"canAcceptDirectInput": None}, {"ephemeral": True},
    {"turns": [{}]}, {"createdAt": True}, {"status": {"type": "notLoaded"}},
    {"status": {"type": "active", "activeFlags": None}},
])
def test_only_loaded_primary_cli_thread_metadata_can_be_selected(changes):
    assert thread_metadata(metadata(**changes), THREAD) is None


def test_cli_resume_preserves_vscode_creation_origin_without_becoming_a_subagent():
    assert thread_metadata(metadata(source="vscode"), THREAD)["id"] == THREAD
    assert thread_metadata(metadata(source="vscode", parentThreadId=OTHER), THREAD) is None
    assert thread_metadata(metadata(source="vscode", sessionId=OTHER), THREAD) is None


async def test_non_linux_native_chat_probe_preserves_existing_flow_without_proc(monkeypatch, tmp_path):
    from herdr_bridge import codex_identity as identity
    monkeypatch.setattr(identity.sys, "platform", "darwin")
    class ForbiddenClient:
        async def call(self, *_args):
            raise AssertionError("Non-Linux probe must not request Linux process proof")
    manager = identity.DaemonConversations(ForbiddenClient(), tmp_path)
    assert await manager.probe({"pane_id": "w1:p1", "terminal_id": "term"}) is None


def test_linux_socket_peer_proof_uses_only_owned_descriptor_metadata():
    left, right = socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        left_inode = int(os.readlink(f"/proc/self/fd/{left.fileno()}")[8:-1])
        right_inode = int(os.readlink(f"/proc/self/fd/{right.fileno()}")[8:-1])
        peers = _unix_peers({left_inode, right_inode})
        assert peers == {left_inode: right_inode, right_inode: left_inode}
    finally:
        left.close()
        right.close()


@pytest.mark.parametrize("private_directory_exists", [True, False])
def test_arbitrary_rendezvous_symlink_cannot_select_a_different_daemon(
    tmp_path, monkeypatch, private_directory_exists,
):
    from herdr_bridge import codex_identity as identity

    # Both daemon-directory states must be independent of any Codex daemon
    # running on the developer's machine or absent from a clean CI runner.
    private_root = tmp_path / "private-tmp"
    private_root.mkdir()
    monkeypatch.setattr(
        identity, "Path", lambda value: private_root if value == "/tmp" else Path(value),
    )
    if private_directory_exists:
        (private_root / f"codex-daemon-{os.getuid()}").mkdir(mode=0o700)

    home = tmp_path / "home"
    control = home / "app-server-control"
    control.mkdir(parents=True, mode=0o700)
    (control / "app-server-control.sock").symlink_to(tmp_path / "arbitrary.sock")
    expected_error = ValueError if private_directory_exists else FileNotFoundError
    with pytest.raises(expected_error, match="alias" if private_directory_exists else None):
        _daemon_socket(home)


def test_direct_owned_socket_requires_private_permissions(tmp_path):
    with tempfile.TemporaryDirectory(prefix="hp-socket-", dir="/tmp") as directory:
        home = Path(directory)
        control = home / "app-server-control"
        control.mkdir(mode=0o700)
        path = control / "app-server-control.sock"
        with socket.socket(socket.AF_UNIX) as server:
            server.bind(str(path))
            path.chmod(0o600)
            assert _daemon_socket(home) == path
            path.chmod(0o666)
            with pytest.raises(ValueError, match="Unsafe"):
                _daemon_socket(home)
