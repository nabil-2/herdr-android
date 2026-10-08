from __future__ import annotations

import base64
import json
import os
from pathlib import Path

from fastapi.testclient import TestClient
import pytest
from starlette.testclient import WebSocketDenialResponse
from starlette.websockets import WebSocketDisconnect

from herdr_bridge.app import create_app
from herdr_bridge.herdr import BridgeError, HerdrClient, normalize_snapshot, validate_terminal_frame
from .conftest import AUTH, SNAPSHOT, TOKEN


FRAME = {"type": "terminal.frame", "encoding": "ansi", "bytes": base64.b64encode(b"\x1b[2J\x1b[31mHello\x1b[0m").decode(), "width": 80, "height": 30, "full": True, "seq": 1}


@pytest.fixture
def observer(tmp_path, monkeypatch):
    pid_path = tmp_path / "pid"
    args_path = tmp_path / "args.json"
    script = tmp_path / "herdr-stub"
    script.write_text("""#!/usr/bin/env python3
import json, os, pathlib, signal, sys, time
pathlib.Path(os.environ['TEST_PID']).write_text(str(os.getpid()))
pathlib.Path(os.environ['TEST_ARGS']).write_text(json.dumps({'args': sys.argv[1:], 'socket': os.environ['HERDR_SOCKET_PATH'], 'libraryPath': os.environ.get('LD_LIBRARY_PATH')}))
if os.environ.get('TEST_IGNORE_TERM'):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
# A large diagnostic pipe must be drained while the stdout stream stays live.
sys.stderr.write('diagnostic ' * 20000)
sys.stderr.flush()
print(os.environ['TEST_FRAME'], flush=True)
if os.environ.get('TEST_CLOSED'):
    print(json.dumps({'type': 'terminal.closed', 'reason': 'test terminal exited'}), flush=True)
else:
    while True:
        time.sleep(1)
""")
    script.chmod(0o700)
    monkeypatch.setenv("TEST_PID", str(pid_path))
    monkeypatch.setenv("TEST_ARGS", str(args_path))
    monkeypatch.setenv("TEST_FRAME", json.dumps(FRAME))

    class Client(HerdrClient):
        async def snapshot(self):
            return normalize_snapshot(SNAPSHOT)

    backend = Client(tmp_path / "chosen-session.sock", str(script))
    app = create_app(backend, TOKEN)
    app.state.test_backend = backend
    return app, pid_path, args_path


@pytest.fixture
def paired_observer(observer, tmp_path):
    from herdr_bridge.pairing import PairingStore
    old_app, pid_path, args_path = observer
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Test host")
    device = store.exchange(pending["code"], "Test phone")
    app = create_app(old_app.state.test_backend, TOKEN, store)
    return app, pid_path, args_path, store, device


def assert_reaped(pid_path: Path):
    pid = int(pid_path.read_text())
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


def test_observer_forwards_native_frame_and_reaps_on_disconnect(observer):
    app, pid_path, args_path = observer
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p2/stream?cols=80&rows=30", headers=AUTH) as websocket:
            frame = websocket.receive_json()
            assert frame == FRAME
    assert_reaped(pid_path)
    selected = json.loads(args_path.read_text())
    assert selected["args"] == ["terminal", "session", "observe", "w1:p2", "--cols", "80", "--rows", "30"]
    assert selected["socket"].endswith("chosen-session.sock")


def test_natural_terminal_close_is_forwarded_and_reaped(observer, monkeypatch):
    app, pid_path, _ = observer
    monkeypatch.setenv("TEST_CLOSED", "1")
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=AUTH) as websocket:
            assert websocket.receive_json() == FRAME
            assert websocket.receive_json() == {"type": "terminal.closed", "reason": "test terminal exited"}
            with pytest.raises(WebSocketDisconnect) as exc:
                websocket.receive_text()
            assert exc.value.code == 1000
    assert_reaped(pid_path)


def test_unresponsive_observer_is_killed_and_reaped(observer, monkeypatch):
    app, pid_path, _ = observer
    monkeypatch.setenv("TEST_IGNORE_TERM", "1")
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=AUTH) as websocket:
            assert websocket.receive_json() == FRAME
    assert_reaped(pid_path)


def test_socket_does_not_accept_input(observer):
    app, pid_path, _ = observer
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=AUTH) as websocket:
            websocket.receive_json()
            websocket.send_json({"type": "input", "text": "unsafe"})
            with pytest.raises(WebSocketDisconnect) as exc:
                websocket.receive_text()
            assert exc.value.code == 1008
    assert_reaped(pid_path)


@pytest.mark.parametrize("header", [None, "Bearer wrong", "Basic test"])
def test_websocket_auth_rejected_before_observer_spawn(observer, header):
    app, pid_path, _ = observer
    with TestClient(app) as client:
        with pytest.raises(WebSocketDenialResponse) as exc:
            with client.websocket_connect("/v1/terminals/w1:p1/stream", headers={} if header is None else {"Authorization": header}):
                pytest.fail("must not connect")
        assert exc.value.status_code == 401
    assert not pid_path.exists()


@pytest.mark.parametrize("target,query,status", [("w1:p99", "", 409), ("current", "", 400), ("w1:p1", "?cols=301", 400), ("w1:p1", "?rows=4", 400), ("w1:p1", "?cols=nope", 400)])
def test_stale_targets_and_invalid_dimensions_do_not_spawn(observer, target, query, status):
    app, pid_path, _ = observer
    with TestClient(app) as client:
        with pytest.raises(WebSocketDenialResponse) as exc:
            with client.websocket_connect(f"/v1/terminals/{target}/stream{query}", headers=AUTH):
                pytest.fail("must not connect")
        assert exc.value.status_code == status
    assert not pid_path.exists()


def test_malformed_cli_frame_closes_stream_and_reaps(observer, monkeypatch):
    app, pid_path, _ = observer
    monkeypatch.setenv("TEST_FRAME", '{"type":"terminal.frame","bytes":"invalid"}')
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=AUTH) as websocket:
            with pytest.raises(WebSocketDisconnect) as exc:
                websocket.receive_text()
            assert exc.value.code == 1011
    assert_reaped(pid_path)


@pytest.mark.parametrize("frame", [[], {"type": "other"}, {**FRAME, "bytes": "!bad"}, {**FRAME, "full": "true"}, {**FRAME, "width": -1}, {**FRAME, "seq": True}, {**FRAME, "height": 10001}, {"type": "terminal.closed", "reason": 7}])
def test_invalid_terminal_envelopes_rejected(frame):
    with pytest.raises(BridgeError, match="invalid terminal frame"):
        validate_terminal_frame(json.dumps(frame).encode())


def test_paired_device_can_observe_and_owner_token_remains_valid(paired_observer):
    app, pid_path, _, store, device = paired_observer
    device_headers = {"Authorization": f"Bearer {device['token']}"}
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=device_headers) as websocket:
            assert websocket.receive_json() == FRAME
        # A revoked device never prevents the independent legacy owner from access.
        assert store.revoke(device["deviceId"])
        store.path.write_bytes(b"unavailable device registry")
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=AUTH) as websocket:
            assert websocket.receive_json() == FRAME
    assert_reaped(pid_path)


def test_revoked_device_is_rejected_before_websocket_observer_spawn(paired_observer):
    app, pid_path, _, store, device = paired_observer
    assert store.revoke(device["deviceId"])
    with TestClient(app) as client:
        with pytest.raises(WebSocketDenialResponse) as exc:
            with client.websocket_connect("/v1/terminals/w1:p1/stream", headers={"Authorization": f"Bearer {device['token']}"}):
                pytest.fail("Revoked device must not connect")
        assert exc.value.status_code == 401
    assert not pid_path.exists()


def test_revocation_closes_active_device_observer_and_reaps_process(paired_observer):
    app, pid_path, _, store, device = paired_observer
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers={"Authorization": f"Bearer {device['token']}"}) as websocket:
            assert websocket.receive_json() == FRAME
            from herdr_bridge.pairing import PairingStore
            assert PairingStore(store.path).revoke(device["deviceId"])
            with pytest.raises(WebSocketDisconnect) as exc:
                websocket.receive_text()
            assert exc.value.code == 1008
    assert_reaped(pid_path)


def test_frozen_terminal_observer_uses_system_libraries_and_exact_socket(observer, monkeypatch):
    from herdr_bridge import runtime
    app, pid_path, args_path = observer
    monkeypatch.setattr(runtime.sys, "frozen", True, raising=False)
    monkeypatch.setattr(runtime.sys, "platform", "linux")
    monkeypatch.setenv("LD_LIBRARY_PATH", "/fake-pyinstaller-bundle")
    monkeypatch.setenv("LD_LIBRARY_PATH_ORIG", "/observer-original-system-libs")
    with TestClient(app) as client:
        with client.websocket_connect("/v1/terminals/w1:p1/stream", headers=AUTH) as websocket:
            assert websocket.receive_json() == FRAME
    child = json.loads(args_path.read_text())
    assert child["libraryPath"] == "/observer-original-system-libs"
    assert child["socket"].endswith("chosen-session.sock")
    assert_reaped(pid_path)
