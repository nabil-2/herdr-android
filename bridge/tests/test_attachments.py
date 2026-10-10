from __future__ import annotations

import asyncio
import hashlib
from io import BytesIO
import json
import os
import stat
import threading

import httpx
from PIL import Image
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.attachments import AttachmentStore
from herdr_bridge.herdr import BridgeError
from herdr_bridge.pairing import PairingStore
from .conftest import AUTH, TOKEN
from .test_codex_chat import THREAD_ID, codex_stub, history


def png(width=12, height=8):
    stream = BytesIO()
    Image.new("RGB", (width, height), "green").save(stream, format="PNG")
    return stream.getvalue()


async def chunks(*values):
    for value in values:
        yield value


@pytest.fixture
async def attachment_chat(socket_server, codex_stub, tmp_path):
    backend, requests, results, options = socket_server
    executable, home, payload, _, _ = codex_stub
    agent = {"pane_id": "w1:p2", "terminal_id": "term-attachment-owned", "agent": "codex", "agent_status": "idle",
             "interactive_ready": True, "launch_pending": False,
             "agent_session": {"source": "herdr:codex", "agent": "codex", "kind": "id", "value": THREAD_ID}}
    results["agent.get"] = {"type": "agent", "agent": agent}
    results["agent.prompt"] = {"type": "agent_prompted", "agent": agent}
    root = tmp_path / "private attachments"
    devices = PairingStore(tmp_path / "test-devices.json")
    app = create_app(backend, TOKEN, devices, codex_executable=executable, codex_home=home, attachment_dir=root)
    app.state.test_devices = devices
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost", headers=AUTH) as client:
        try:
            page = (await client.get("/v1/panes/w1:p2/conversation")).json()
            yield client, app, page["binding"], agent, requests, results, options, payload
        finally:
            await app.state.codex_reader.close()
            await app.state.attachments.close()


async def upload(client, binding, data=b"file bytes", kind="file", name="notes.txt", **kwargs):
    return await client.post("/v1/panes/w1:p2/attachments", params={"kind": kind, "name": name}, content=data,
                             headers={"X-Herdr-Chat-Binding": binding, "Content-Type": "application/octet-stream", **kwargs})


async def test_raw_upload_is_private_scoped_and_does_not_type(attachment_chat):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    requests.clear()
    response = await upload(client, binding, png(), "image", "screen shot.png")
    assert response.status_code == 201
    receipt = response.json()
    assert set(receipt) == {"id", "name", "kind", "mimeType", "sizeBytes"}
    assert receipt["name"] == "screen shot.png" and receipt["mimeType"] == "image/png"
    assert len(receipt["id"]) == 32 and all(c in "0123456789abcdef" for c in receipt["id"])
    assert all(r["method"] in ("agent.get", "pane.process_info") for r in requests)
    stored = app.state.attachments.receipts[receipt["id"]]
    assert stat.S_IMODE(stored.path.stat().st_mode) == 0o600
    assert stat.S_IMODE(stored.path.parent.stat().st_mode) == 0o700
    assert stored.path.name != receipt["name"] and stored.path.read_bytes() == png()
    assert str(stored.path) not in response.text


async def test_upload_does_not_relax_json_body_limit(attachment_chat):
    client, _, binding, _, _, _, _, _ = attachment_chat
    assert (await upload(client, binding, b"x" * 100_000)).status_code == 201
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "x" * 70_000})
    assert response.status_code == 413 and "64 KiB" in response.text


@pytest.mark.parametrize("name", ["../bad", "a/b", "a\\b", ".", "..", "", " ", "secret\nname", "x\x00", "x\x7f", "x" * 161])
async def test_reject_bad_name_without_file(attachment_chat, name):
    client, app, binding, _, _, _, _, _ = attachment_chat
    response = await upload(client, binding, name=name)
    assert response.status_code in (400, 422)
    assert app.state.attachments.receipts == {}


@pytest.mark.parametrize("case", ["auth", "binding", "kind", "media", "empty", "image", "truncated"])
async def test_reject_upload_before_any_pane_input(attachment_chat, case):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    requests.clear()
    if case == "auth":
        response = await upload(client, binding, **{"Authorization": "Bearer invalid"})
        assert response.status_code == 401 and requests == []
    elif case == "binding":
        response = await upload(client, "x" * 43)
        assert response.status_code == 409
    elif case == "kind":
        response = await upload(client, binding, kind="other")
        assert response.status_code == 422
    elif case == "media":
        response = await client.post("/v1/panes/w1:p2/attachments", params={"kind": "file", "name": "n"},
                                     content=b"x", headers={"X-Herdr-Chat-Binding": binding, "Content-Type": "text/plain"})
        assert response.status_code == 415
    else:
        data = b"" if case == "empty" else (b"not an image" if case == "image" else png()[:40])
        response = await upload(client, binding, data, kind="file" if case == "empty" else "image")
        assert response.status_code in (400, 415)
    assert not any(r["method"] in ("agent.prompt", "pane.send_input") for r in requests)
    assert not app.state.attachments.receipts


async def test_oversized_length_and_chunked_upload_are_bounded(attachment_chat, monkeypatch):
    import herdr_bridge.attachments as storage
    import herdr_bridge.app as api
    client, app, binding, _, _, _, _, _ = attachment_chat
    monkeypatch.setattr(storage, "MAX_ATTACHMENT_BYTES", 16)
    monkeypatch.setattr(api, "MAX_ATTACHMENT_BYTES", 16)
    response = await upload(client, binding, b"x" * 17)
    assert response.status_code == 413
    response = await client.post("/v1/panes/w1:p2/attachments", params={"kind": "file", "name": "n"},
                                 content=chunks(b"x" * 8, b"y" * 9),
                                 headers={"X-Herdr-Chat-Binding": binding, "Content-Type": "application/octet-stream"})
    assert response.status_code == 413
    assert not app.state.attachments.receipts
    assert not list(app.state.attachments.root.glob("hp-*"))


async def test_multiple_images_and_file_use_separate_pastes_and_one_submit(attachment_chat):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    ids = [(await upload(client, binding, png(), "image", name)).json()["id"] for name in ["one.png", "two.png"]]
    ids.append((await upload(client, binding, b"contents", "file", "r\u00e9sum\u00e9.txt")).json()["id"])
    requests.clear()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "Review these", "attachmentIds": ids})
    assert response.status_code == 200
    writes = [r for r in requests if r["method"] in ("pane.send_input", "agent.prompt")]
    assert [r["method"] for r in writes] == ["pane.send_input", "pane.send_input", "agent.prompt"]
    for request, attachment_id in zip(writes, ids):
        if request["method"] == "pane.send_input":
            assert set(request["params"]) == {"pane_id", "text"}
            assert request["params"]["pane_id"] == "w1:p2"
            assert str(app.state.attachments.receipts[attachment_id].path) in request["params"]["text"]
    text = writes[-1]["params"]["text"]
    assert text.startswith("Review these") and "r\u00e9sum\u00e9.txt" in text
    assert all(app.state.attachments.receipts[i].consumed for i in ids)
    assert not app.state.attachments.blocked("w1:p2", binding)
    again = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "again", "attachmentIds": ids})
    assert again.status_code == 503
    assert len([r for r in requests if r["method"] == "agent.prompt"]) == 1


async def test_image_only_message_is_one_valid_submit(attachment_chat):
    client, _, binding, _, requests, _, _, _ = attachment_chat
    receipt = (await upload(client, binding, png(), "image", "one.png")).json()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "attachmentIds": [receipt["id"]]})
    assert response.status_code == 200
    prompts = [r for r in requests if r["method"] == "agent.prompt"]
    assert len(prompts) == 1 and prompts[0]["params"]["text"] == "Image attachment: one.png"


async def test_uncertain_image_paste_blocks_text_and_poll_does_not_clear(attachment_chat):
    client, app, binding, _, requests, results, _, _ = attachment_chat
    receipt = (await upload(client, binding, png(), "image", "one.png")).json()
    results["pane.send_input"] = {"error": {"code": "pane_send_failed", "message": "private input"}}
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "review", "attachmentIds": [receipt["id"]]})
    assert response.status_code == 503 and "private input" not in response.text
    assert not any(r["method"] == "agent.prompt" for r in requests)
    assert app.state.attachments.blocked("w1:p2", binding)
    page = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert not page["canSend"] and "Terminal" in page["canSendReason"]
    assert page["attachmentDeliveryUncertain"]
    assert (await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "retry"})).status_code == 503
    ack = await client.post("/v1/panes/w1:p2/attachments/acknowledge", headers={"X-Herdr-Chat-Binding": binding})
    assert ack.status_code == 200
    assert not app.state.attachments.blocked("w1:p2", binding)
    # Explicit acknowledgement permits a new reviewed message, never reuse of
    # the attachment whose path may already be in the Terminal composer.
    assert (await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "attachmentIds": [receipt["id"]]})).status_code == 503


async def test_partial_paste_guard_survives_companion_restart_and_new_binding(attachment_chat):
    client, app, binding, _, requests, results, _, _ = attachment_chat
    uploaded = (await upload(client, binding, png(), "image", "restart.png")).json()
    results["pane.send_input"] = {"error": {"code": "pane_send_failed", "message": "fixture uncertain"}}
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "attachmentIds": [uploaded["id"]]})
    assert response.status_code == 503
    markers = list(app.state.attachments.root.glob("pending-*.json"))
    assert len(markers) == 1 and stat.S_IMODE(markers[0].stat().st_mode) == 0o600
    assert TOKEN not in markers[0].read_text()
    await app.state.codex_reader.close()
    await app.state.attachments.close()
    restarted = create_app(app.state.conversations.client, TOKEN, app.state.test_devices,
                           codex_executable=app.state.codex_reader.executable, codex_home=app.state.codex_reader.home,
                           attachment_dir=app.state.attachments.root)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=restarted), base_url="http://restarted", headers=AUTH) as phone:
        try:
            requests.clear()
            page = (await phone.get("/v1/panes/w1:p2/conversation")).json()
            new_binding = page["binding"]
            assert new_binding != binding and page["attachmentDeliveryUncertain"] and not page["canSend"]
            assert not restarted.state.attachments.receipts
            for _ in range(2):
                assert (await phone.get("/v1/panes/w1:p2/conversation")).json()["attachmentDeliveryUncertain"]
            assert (await phone.post("/v1/panes/w1:p2/messages", json={"binding": new_binding, "text": "Do not append"})).status_code == 503
            invitation = app.state.test_devices.begin_pairing("Owned restart")
            paired = app.state.test_devices.exchange(invitation["code"], "Other fixture phone")
            other_headers = {"Authorization": "Bearer " + paired["token"], "X-Herdr-Chat-Binding": new_binding}
            assert (await phone.post("/v1/panes/w1:p2/attachments/acknowledge", headers=other_headers)).status_code == 200
            assert (await phone.get("/v1/panes/w1:p2/conversation")).json()["attachmentDeliveryUncertain"]
            assert not any(r["method"] in ("pane.send_input", "agent.prompt") for r in requests)
            assert (await phone.post("/v1/panes/w1:p2/attachments/acknowledge", headers={"X-Herdr-Chat-Binding": new_binding})).status_code == 200
            assert not list(restarted.state.attachments.root.glob("pending-*.json"))
            assert not (await phone.get("/v1/panes/w1:p2/conversation")).json()["attachmentDeliveryUncertain"]
            assert (await phone.post("/v1/panes/w1:p2/messages", json={"binding": new_binding, "attachmentIds": [uploaded["id"]]})).status_code == 409
            assert not any(r["method"] in ("pane.send_input", "agent.prompt") for r in requests)
        finally:
            await restarted.state.codex_reader.close()
            await restarted.state.attachments.close()


@pytest.mark.parametrize("mutation", ["blocked", "pending", "binding", "tamper", "symlink", "duplicate", "unknown"])
async def test_attachment_preflight_rejection_never_types(attachment_chat, mutation):
    client, app, binding, agent, requests, _, _, _ = attachment_chat
    receipt = (await upload(client, binding)).json()
    ids = [receipt["id"]]
    if mutation == "blocked": agent["agent_status"] = "blocked"
    elif mutation == "pending": agent["launch_pending"] = True
    elif mutation == "binding": agent["terminal_id"] = "other"
    elif mutation == "tamper": app.state.attachments.receipts[receipt["id"]].path.write_bytes(b"changed")
    elif mutation == "symlink":
        path = app.state.attachments.receipts[receipt["id"]].path
        path.unlink()
        path.symlink_to("/dev/null")
    elif mutation == "duplicate": ids *= 2
    else: ids = ["0" * 32]
    requests.clear()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "review", "attachmentIds": ids})
    assert response.status_code in (409, 422, 503)
    assert not any(r["method"] in ("agent.prompt", "pane.send_input") for r in requests)


async def test_saved_user_file_reference_preserves_name_and_redacts_host_path(attachment_chat):
    client, app, binding, _, requests, _, _, payload = attachment_chat
    receipt = (await upload(client, binding, name="data.csv")).json()
    await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "review", "attachmentIds": [receipt["id"]]})
    submitted = next(r["params"]["text"] for r in requests if r["method"] == "agent.prompt")
    result = history(1)
    result["thread"]["turns"][0]["items"][0]["content"][0]["text"] = submitted
    payload.write_text(json.dumps(result))
    app.state.conversations.caches[binding].refreshed_at = float("-inf")
    page = await client.get("/v1/panes/w1:p2/conversation")
    assert "File attachment: data.csv" in page.text
    assert str(app.state.attachments.root) not in page.text


async def test_saved_image_only_turn_remains_visible_with_original_name(attachment_chat):
    client, app, binding, _, requests, _, _, payload = attachment_chat
    receipt = (await upload(client, binding, png(), "image", "screenshot.png")).json()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "attachmentIds": [receipt["id"]]})
    assert response.status_code == 200
    submitted = next(r["params"]["text"] for r in requests if r["method"] == "agent.prompt")
    result = history(1)
    result["thread"]["turns"][0]["items"][0]["content"] = [
        {"type": "localImage", "path": str(app.state.attachments.receipts[receipt["id"]].path)},
        {"type": "text", "text": submitted},
    ]
    payload.write_text(json.dumps(result))
    app.state.conversations.caches[binding].refreshed_at = float("-inf")
    page = await client.get("/v1/panes/w1:p2/conversation")
    assert page.status_code == 200
    assert [m["text"] for m in page.json()["messages"]] == ["Image attachment: screenshot.png"]
    assert str(app.state.attachments.root) not in page.text


async def test_another_authenticated_device_cannot_delete_or_submit_stage(attachment_chat):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    receipt = (await upload(client, binding)).json()
    invitation = app.state.test_devices.begin_pairing("Owned test")
    paired = app.state.test_devices.exchange(invitation["code"], "Second fixture phone")
    headers = {"Authorization": "Bearer " + paired["token"]}
    requests.clear()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "attachmentIds": [receipt["id"]]}, headers=headers)
    assert response.status_code == 409
    response = await client.delete("/v1/panes/w1:p2/attachments/" + receipt["id"],
                                   headers=headers | {"X-Herdr-Chat-Binding": binding})
    assert response.status_code == 409
    assert not any(r["method"] in ("agent.prompt", "pane.send_input") for r in requests)
    assert receipt["id"] in app.state.attachments.receipts


async def test_cancelled_submit_after_image_paste_never_reuses_receipt(attachment_chat, monkeypatch):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    receipt = (await upload(client, binding, png(), "image", "one.png")).json()
    async def cancel_prompt(*_args):
        raise asyncio.CancelledError()
    monkeypatch.setattr(app.state.conversations.client, "prompt", cancel_prompt)
    with pytest.raises(asyncio.CancelledError):
        await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "attachmentIds": [receipt["id"]]})
    assert len([r for r in requests if r["method"] == "pane.send_input"]) == 1
    assert not any(r["method"] == "agent.prompt" for r in requests)
    assert app.state.attachments.receipts[receipt["id"]].consumed
    assert app.state.attachments.blocked("w1:p2", binding)


async def test_rebinding_between_image_pastes_stops_without_final_submit(attachment_chat, monkeypatch):
    client, app, binding, agent, requests, _, _, _ = attachment_chat
    ids = [(await upload(client, binding, png(), "image", name)).json()["id"] for name in ["one.png", "two.png"]]
    original = app.state.conversations.client.send_input
    async def change_after_paste(*args):
        await original(*args)
        agent["terminal_id"] = "replacement-generation"
    monkeypatch.setattr(app.state.conversations.client, "send_input", change_after_paste)
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "review", "attachmentIds": ids})
    assert response.status_code == 503
    assert len([r for r in requests if r["method"] == "pane.send_input"]) == 1
    assert not any(r["method"] == "agent.prompt" for r in requests)
    assert all(app.state.attachments.receipts[i].consumed for i in ids)


@pytest.mark.parametrize("result", ["ack", "lostSelection", "uncertain"])
async def test_selected_daemon_uses_typed_images_and_never_falls_back_to_pty(attachment_chat, monkeypatch, result):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    original_target = app.state.conversations.target
    async def selected_target(pane_id, *, owner=""):
        agent, thread, current = await original_target(pane_id, owner=owner)
        return agent | {"_pocket_daemon_selected": True, "_pocket_daemon_identity": "owned-selected-runtime"}, thread, current
    monkeypatch.setattr(app.state.conversations, "target", selected_target)
    deliveries = []
    class SelectedDaemon:
        async def target(self, agent, owner):
            return None
        async def deliver(self, pane, owner, thread, text, images):
            deliveries.append((pane, owner, thread, text, images))
            if result == "uncertain":
                raise BridgeError(503, "Selected delivery was not confirmed")
            return result == "ack"
    monkeypatch.setattr(app.state.conversations, "daemon", SelectedDaemon(), raising=False)
    image_id = (await upload(client, binding, png(), "image", "screenshot.png")).json()["id"]
    file_id = (await upload(client, binding, b"file", "file", "notes.txt")).json()["id"]
    requests.clear()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "Review", "attachmentIds": [image_id, file_id]})
    assert response.status_code == (200 if result == "ack" else 503)
    assert len(deliveries) == 1
    pane, owner, thread, text, images = deliveries[0]
    assert pane == "w1:p2" and len(owner) == 64 and thread == THREAD_ID
    assert text.startswith("Review") and "screenshot.png" in text and "notes.txt" in text
    assert images == [app.state.attachments.receipts[image_id].path]
    assert images[0].read_bytes() == png()
    assert not any(r["method"] in ("pane.send_input", "agent.prompt") for r in requests)
    assert all(app.state.attachments.receipts[i].consumed for i in [image_id, file_id])
    assert app.state.attachments.blocked("w1:p2", binding) == (result != "ack")


async def test_selected_text_only_send_never_uses_terminal(attachment_chat, monkeypatch):
    client, app, binding, _, requests, _, _, _ = attachment_chat
    original_target = app.state.conversations.target
    async def selected_target(pane_id, *, owner=""):
        agent, thread, current = await original_target(pane_id, owner=owner)
        return agent | {"_pocket_daemon_selected": True, "_pocket_daemon_identity": "owned-selected-runtime"}, thread, current
    monkeypatch.setattr(app.state.conversations, "target", selected_target)
    deliveries = []
    class SelectedDaemon:
        async def target(self, agent, owner):
            return None
        async def deliver(self, *args):
            deliveries.append(args)
            return True
    monkeypatch.setattr(app.state.conversations, "daemon", SelectedDaemon(), raising=False)
    requests.clear()
    response = await client.post("/v1/panes/w1:p2/messages", json={"binding": binding, "text": "Exact selected thread"})
    assert response.status_code == 200
    assert len(deliveries) == 1 and deliveries[0][3:] == ("Exact selected thread", [])
    assert not any(r["method"] in ("pane.send_input", "agent.prompt") for r in requests)


async def test_store_device_and_pane_scope_expiry_and_delete(tmp_path):
    now = [100.0]
    store = AttachmentStore(tmp_path / "private", clock=lambda: now[0])
    receipt = await store.upload("w1:p2", "binding", "device1", "n.txt", "file", chunks(b"hello"))
    for pane, binding, owner in [("w1:p1", "binding", "device1"), ("w1:p2", "other", "device1"), ("w1:p2", "binding", "device2")]:
        with pytest.raises(BridgeError) as exc:
            await store.consume([receipt["id"]], pane, binding, owner)
        assert exc.value.status == 409
    now[0] += 3601
    with pytest.raises(BridgeError):
        await store.consume([receipt["id"]], "w1:p2", "binding", "device1")
    next_receipt = await store.upload("w1:p2", "binding", "device1", "next.txt", "file", chunks(b"x"))
    assert receipt["id"] not in store.receipts
    await store.delete(next_receipt["id"], "w1:p2", "binding", "device1")
    assert not list(store.root.glob("hp-*"))


async def test_store_preserves_sent_files_across_close_and_restart_until_expiry(tmp_path):
    now = [100.0]
    store = AttachmentStore(tmp_path / "private", clock=lambda: now[0])
    receipt = await store.upload("w1:p2", "b", "d", "n", "file", chunks(b"keep"))
    records = await store.consume([receipt["id"]], "w1:p2", "b", "d")
    await store.close()
    assert records[0].path.read_bytes() == b"keep"
    restarted = AttachmentStore(store.root, clock=lambda: now[0])
    next_receipt = await restarted.upload("w1:p2", "b", "d", "next", "file", chunks(b"x"))
    assert records[0].path.exists()
    now[0] += 7 * 24 * 3600 + 1
    await restarted.upload("w1:p2", "b", "d", "last", "file", chunks(b"z"))
    assert not records[0].path.exists() and next_receipt["id"] not in restarted.receipts


async def test_durable_uncertainty_requires_exact_runtime_thread_pane_and_owner_ack(tmp_path):
    owner = hashlib.sha256(b"owned first credential").hexdigest()
    other_owner = hashlib.sha256(b"owned second credential").hexdigest()
    store = AttachmentStore(tmp_path / "private")
    await store.register("w1:p2", "old", THREAD_ID, "1" * 64)
    uploaded = await store.upload("w1:p2", "old", owner, "n.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
    await store.consume([uploaded["id"]], "w1:p2", "old", owner)
    restarted = AttachmentStore(store.root)
    for pane, thread, runtime, binding in [
        ("w1:p1", THREAD_ID, "1" * 64, "pane-changed"),
        ("w1:p2", "87654321-4321-4321-8321-cba987654321", "1" * 64, "thread-changed"),
        ("w1:p2", THREAD_ID, "2" * 64, "terminal-changed"),
    ]:
        await restarted.register(pane, binding, thread, runtime)
        assert not restarted.blocked(pane, binding)
    await restarted.register("w1:p2", "new", THREAD_ID, "1" * 64)
    assert restarted.blocked("w1:p2", "new")
    await restarted.confirmed("w1:p2", "new", other_owner)
    await restarted.register("w1:p2", "new", THREAD_ID, "1" * 64)
    assert restarted.blocked("w1:p2", "new")
    await restarted.cleanup()
    assert list(restarted.root.glob("pending-*.json"))
    await restarted.confirmed("w1:p2", "new", owner)
    assert not restarted.blocked("w1:p2", "new")
    assert not list(restarted.root.glob("pending-*.json"))
    with pytest.raises(BridgeError):
        await restarted.consume([uploaded["id"]], "w1:p2", "new", owner)


async def test_pending_marker_quota_fails_before_any_receipt_is_consumed(tmp_path, monkeypatch):
    import herdr_bridge.attachments as module
    monkeypatch.setattr(module, "MAX_PENDING_ITEMS", 1)
    owner = hashlib.sha256(b"owned credential").hexdigest()
    store = AttachmentStore(tmp_path / "private")
    await store.register("w1:p2", "first", THREAD_ID, "1" * 64)
    first = await store.upload("w1:p2", "first", owner, "first.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
    await store.consume([first["id"]], "w1:p2", "first", owner)
    await store.register("w1:p2", "replacement", THREAD_ID, "2" * 64)
    second = await store.upload("w1:p2", "replacement", owner, "second.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
    with pytest.raises(BridgeError) as error:
        await store.consume([second["id"]], "w1:p2", "replacement", owner)
    assert error.value.status == 413 and not store.receipts[second["id"]].consumed
    assert len(list(store.root.glob("pending-*.json"))) == 1


@pytest.mark.parametrize("corruption", ["wrongOwner", "symlink"])
async def test_corrupt_pending_marker_cannot_silently_release_guard(tmp_path, corruption):
    owner = hashlib.sha256(b"owned credential").hexdigest()
    store = AttachmentStore(tmp_path / "private")
    await store.register("w1:p2", "old", THREAD_ID, "1" * 64)
    receipt = await store.upload("w1:p2", "old", owner, "n.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
    await store.consume([receipt["id"]], "w1:p2", "old", owner)
    [marker] = list(store.root.glob("pending-*.json"))
    if corruption == "wrongOwner":
        record = json.loads(marker.read_text())
        record["ownerHash"] = "f" * 64
        marker.write_text(json.dumps(record))
    else:
        outside = tmp_path / "outside.json"
        outside.write_text("{}")
        marker.unlink()
        marker.symlink_to(outside)
    restarted = AttachmentStore(store.root)
    with pytest.raises(BridgeError) as error:
        await restarted.register("w1:p2", "new", THREAD_ID, "1" * 64)
    assert error.value.status == 503


async def test_store_rejects_symlink_root_without_creating_target(tmp_path):
    outside = tmp_path / "outside"
    outside.mkdir()
    link = tmp_path / "link"
    link.symlink_to(outside, target_is_directory=True)
    store = AttachmentStore(link / "staging")
    with pytest.raises(BridgeError):
        await store.upload("w1:p2", "b", "d", "n", "file", chunks(b"x"))
    assert not (outside / "staging").exists()


async def test_store_storage_and_submission_limits(tmp_path, monkeypatch):
    import herdr_bridge.attachments as module
    monkeypatch.setattr(module, "MAX_STORED_BYTES", 10)
    monkeypatch.setattr(module, "MAX_SUBMISSION_BYTES", 5)
    store = AttachmentStore(tmp_path / "private")
    ids = [(await store.upload("w1:p2", "b", "d", "n", "file", chunks(b"123")))["id"] for _ in range(2)]
    with pytest.raises(BridgeError) as exc:
        await store.consume(ids, "w1:p2", "b", "d")
    assert exc.value.status == 413 and not any(r.consumed for r in store.receipts.values())
    with pytest.raises(BridgeError) as exc:
        await store.upload("w1:p2", "b", "d", "n", "file", chunks(b"12345"))
    assert exc.value.status == 413
    assert sum(p.stat().st_size for p in store.root.glob("hp-*.bin")) == 6


async def test_only_exact_consumed_user_refs_in_verified_thread_and_pane_are_presented(tmp_path):
    store = AttachmentStore(tmp_path / "private")
    first = await store.upload("w1:p2", "b", "d", "real.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
    pending = await store.upload("w1:p2", "b", "d", "pending.txt", "file", chunks(b"y"), thread_id=THREAD_ID)
    await store.consume([first["id"]], "w1:p2", "b", "d")
    real_path = str(store.receipts[first["id"]].path)
    pending_path = str(store.receipts[pending["id"]].path)
    authored = "/user/project/normal.txt"
    unknown_path = str(store.root / ("hp-" + "f" * 32 + ".txt"))
    source = real_path + "\n" + pending_path + "\n" + authored + "\n" + unknown_path + "\n" + real_path + ".bak"
    messages = [{"role": "user", "text": source}, {"role": "assistant", "text": source}]
    await store.present_history(messages, "w1:p2", THREAD_ID)
    assert "[File attachment: real.txt]" in messages[0]["text"]
    assert all(path in messages[0]["text"] for path in [pending_path, authored, unknown_path, real_path + ".bak"])
    assert messages[1]["text"] == source
    for pane, thread in [("w1:p1", THREAD_ID), ("w1:p2", "87654321-4321-4321-8321-cba987654321")]:
        messages = [{"role": "user", "text": real_path}]
        await store.present_history(messages, pane, thread)
        assert messages[0]["text"] == real_path
    # The private display record survives restart, without granting a new
    # device the right to reuse the consumed upload or invent a history message.
    restarted = AttachmentStore(store.root)
    messages = [{"role": "user", "text": real_path}]
    await restarted.present_history(messages, "w1:p2", THREAD_ID)
    assert messages[0]["text"] == "[File attachment: real.txt]"
    assert restarted.receipts == {}


async def test_history_names_survive_payload_expiry_without_reusable_receipts(tmp_path):
    now = [100.0]
    store = AttachmentStore(tmp_path / "private", clock=lambda: now[0])
    uploaded = await store.upload("w1:p2", "b", "d", "old notes.txt", "file", chunks(b"payload"), thread_id=THREAD_ID)
    [receipt] = await store.consume([uploaded["id"]], "w1:p2", "b", "d")
    original = store.prompt_text("Review", [receipt])
    now[0] += 7 * 24 * 3600 + 1
    await store.cleanup()
    assert not receipt.path.exists() and receipt.path.with_name(receipt.path.name + ".json").exists()
    assert not store.receipts
    restarted = AttachmentStore(store.root, clock=lambda: now[0])
    messages = [{"role": "user", "text": original}]
    await restarted.present_history(messages, "w1:p2", THREAD_ID)
    assert messages[0]["text"] == "Review\n\nFile attachment: old notes.txt"
    assert restarted.receipts == {}
    with pytest.raises(BridgeError):
        await restarted.consume([uploaded["id"]], "w1:p2", "b", "d")
    wrong_thread = [{"role": "user", "text": original}]
    await restarted.present_history(wrong_thread, "w1:p2", "87654321-4321-4321-8321-cba987654321")
    assert wrong_thread[0]["text"] == original


@pytest.mark.parametrize("limit", ["items", "bytes"])
async def test_orphaned_display_ledger_is_bounded_and_pending_records_are_removed(tmp_path, monkeypatch, limit):
    import herdr_bridge.attachments as module
    now = [100.0]
    store = AttachmentStore(tmp_path / "private", clock=lambda: now[0])
    records = []
    for index in range(3):
        uploaded = await store.upload("w1:p2", "b", "d", f"{index}.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
        [receipt] = await store.consume([uploaded["id"]], "w1:p2", "b", "d")
        await store.confirmed("w1:p2", "b", "d")
        os.utime(receipt.path.with_name(receipt.path.name + ".json"), (10 + index, 10 + index))
        records.append(receipt)
    pending = await store.upload("w1:p2", "b", "d", "pending.txt", "file", chunks(b"x"), thread_id=THREAD_ID)
    monkeypatch.setattr(module, "MAX_DISPLAY_ITEMS" if limit == "items" else "MAX_DISPLAY_BYTES", 2 if limit == "items" else 1)
    now[0] += 7 * 24 * 3600 + 1
    await store.cleanup()
    remaining = list(store.root.glob("hp-*.json"))
    assert len(remaining) == (2 if limit == "items" else 0)
    assert pending["id"] not in store.receipts
    assert not records[0].path.with_name(records[0].path.name + ".json").exists()
    if limit == "items":
        assert all(record.path.with_name(record.path.name + ".json").exists() for record in records[1:])


async def test_large_body_allowance_is_exact_upload_route_only(attachment_chat):
    client, _, _, _, _, _, _, _ = attachment_chat
    for path in ["/v1/panes/w1:p2/extra/attachments", "/v1/panes/not-a-pane/attachments", "/v1/panes/w1:p2/attachments/other"]:
        response = await client.post(path, content=b"x" * 70_000)
        assert response.status_code == 413 and "64 KiB" in response.text


async def test_store_cancelled_upload_removes_partial_file(tmp_path):
    store = AttachmentStore(tmp_path / "private")
    async def interrupted():
        yield b"partial"
        raise asyncio.CancelledError()
    with pytest.raises(asyncio.CancelledError):
        await store.upload("w1:p2", "b", "d", "n", "file", interrupted())
    assert not store.receipts and not list(store.root.glob("hp-*"))


async def test_cancelled_worker_finishes_before_borrowed_directory_is_closed(tmp_path, monkeypatch):
    store = AttachmentStore(tmp_path / "private")
    entered, release = threading.Event(), threading.Event()
    original = store._metadata
    checked = []
    def slow_metadata(fd, *args):
        entered.set()
        assert release.wait(3)
        checked.append(stat.S_ISDIR(os.fstat(fd).st_mode))
        original(fd, *args)
    monkeypatch.setattr(store, "_metadata", slow_metadata)
    pending = asyncio.create_task(store.upload("w1:p2", "b", "d", "n", "file", chunks(b"x")))
    assert await asyncio.to_thread(entered.wait, 3)
    pending.cancel()
    await asyncio.sleep(0)
    assert not pending.done()
    release.set()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert checked == [True]
    assert not store.receipts and not list(store.root.glob("hp-*"))


async def test_cancelled_directory_open_closes_fd_returned_after_cancel(tmp_path):
    from herdr_bridge.attachments import _disk
    entered, release = threading.Event(), threading.Event()
    opened = []
    def slow_open():
        fd = os.open(tmp_path, os.O_RDONLY | os.O_DIRECTORY)
        opened.append(fd)
        entered.set()
        assert release.wait(3)
        return fd
    pending = asyncio.create_task(_disk(slow_open, close_cancelled_fd=True))
    assert await asyncio.to_thread(entered.wait, 3)
    pending.cancel()
    await asyncio.sleep(0)
    release.set()
    with pytest.raises(asyncio.CancelledError):
        await pending
    with pytest.raises(OSError):
        os.fstat(opened[0])


async def test_cleanup_expires_stages_without_another_upload(tmp_path):
    now = [100.0]
    store = AttachmentStore(tmp_path / "private", clock=lambda: now[0])
    receipt = await store.upload("w1:p2", "b", "d", "n", "file", chunks(b"x"))
    path = store.receipts[receipt["id"]].path
    now[0] += 3601
    await store.cleanup()
    assert not path.exists() and not store.receipts


async def test_store_rejects_pixel_bomb_and_animation(tmp_path):
    store = AttachmentStore(tmp_path / "private")
    with pytest.raises(BridgeError) as exc:
        await store.upload("w1:p2", "b", "d", "big.png", "image", chunks(png(4001, 4001)))
    assert exc.value.status == 413
    stream = BytesIO()
    Image.new("RGB", (3, 3), "red").save(stream, format="WEBP", save_all=True,
                                        append_images=[Image.new("RGB", (3, 3), "blue")], duration=100)
    with pytest.raises(BridgeError) as exc:
        await store.upload("w1:p2", "b", "d", "animated.webp", "image", chunks(stream.getvalue()))
    assert exc.value.status == 415
