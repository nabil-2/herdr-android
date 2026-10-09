"""Opt-in integration: only create, operate on, and stop our own unique session."""

from __future__ import annotations

import asyncio
import base64
import json
import os
from pathlib import Path
import shutil
import shlex
import uuid

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.herdr import HerdrClient, stop_observer, validate_terminal_frame
from .conftest import AUTH, TOKEN


@pytest.mark.skipif(os.environ.get("HERDR_INTEGRATION") != "1", reason="Set HERDR_INTEGRATION=1 to test an isolated live Herdr session")
async def test_live_snapshot_input_and_observer_on_isolated_session(tmp_path):
    executable = os.environ.get("HERDR_BIN") or shutil.which("herdr")
    assert executable, "Install Herdr 0.9.3 or set HERDR_BIN"
    name = "android-bridge-it-" + uuid.uuid4().hex[:12]
    env = dict(os.environ)
    # CLI must start and select ONLY this new named session, regardless of caller.
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION", "HERDR_CONFIG_PATH", "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID"):
        env.pop(key, None)
    server = await asyncio.create_subprocess_exec(executable, "--session", name, "server", env=env, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    observer = None
    try:
        assert server.stdout
        async with asyncio.timeout(10):
            while True:
                line = await server.stdout.readline()
                assert line, "Herdr server failed to start"
                if line.startswith(b"api socket: "):
                    socket_path = Path(line.decode().strip().removeprefix("api socket: "))
                    break
        backend = HerdrClient(socket_path, str(executable))
        app = create_app(backend, TOKEN)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost", headers=AUTH) as phone:
            health = (await phone.get("/v1/health")).json()
            assert health["herdrVersion"] == "0.9.3"
            assert "terminal-input" in health["capabilities"]
            created = await phone.post("/v1/workspaces", json={"cwd": str(tmp_path), "label": "Isolated Android bridge test"})
            assert created.status_code == 201
            snapshot = (await phone.get("/v1/snapshot")).json()
            assert len(snapshot["panes"]) == 1
            pane_id = snapshot["panes"][0]["id"]
            assert snapshot["panes"][0]["cwd"] == str(tmp_path)
            observer = await backend.observe(pane_id, 80, 30)
            assert observer.stdout
            async with asyncio.timeout(5):
                first = json.loads(validate_terminal_frame(await observer.stdout.readline()))
                assert first["type"] == "terminal.frame" and first["full"]
                assert first["width"] == 80 and first["height"] == 30
            # Marker has no side effect besides output in our own shell.
            marker = "HERDR_ANDROID_BRIDGE_SMOKE_" + uuid.uuid4().hex[:8]
            sent = await phone.post(f"/v1/panes/{pane_id}/input", json={"text": f"printf '{marker}\\n'", "keys": ["enter"]})
            assert sent.json() == {"ok": True}
            output = b""
            async with asyncio.timeout(8):
                while marker.encode() not in output:
                    frame = json.loads(validate_terminal_frame(await observer.stdout.readline()))
                    assert frame["type"] == "terminal.frame"
                    output += base64.b64decode(frame["bytes"])
            # Invalid logical keys must reject the entire text+keys submission.
            rejected = await phone.post(f"/v1/panes/{pane_id}/input", json={"text": "invalid-key-input-must-not-be-written", "keys": ["not-a-real-key"]})
            assert rejected.status_code == 400
            toolbar = await phone.post(f"/v1/panes/{pane_id}/input", json={"keys": ["ctrl+c", "esc", "tab", "up", "down", "left", "right", "ctrl+c", "enter"]})
            assert toolbar.json() == {"ok": True}
            # Direct typing must not bracket Ctrl+U or Enter as pasted text. Check
            # execution in our temporary directory, rather than echoed ANSI text.
            raw_result = tmp_path / "direct-keyboard-result.txt"
            abandoned = tmp_path / "abandoned-command-must-not-run.txt"
            queued_text = await phone.post(f"/v1/panes/{pane_id}/terminal-input", json={"text": f"printf unsafe > {shlex.quote(str(abandoned))}"})
            assert queued_text.json() == {"ok": True}
            raw_marker = "RAW_KEYBOARD_" + uuid.uuid4().hex[:8]
            direct = await phone.post(f"/v1/panes/{pane_id}/terminal-input", json={"text": f"\x15printf '%s' {shlex.quote(raw_marker)} > {shlex.quote(str(raw_result))}\r"})
            assert direct.json() == {"ok": True}
            async with asyncio.timeout(8):
                while not raw_result.exists():
                    await asyncio.sleep(0.05)
            assert raw_result.read_text() == raw_marker
            assert not abandoned.exists()
            history = await phone.get(f"/v1/panes/{pane_id}/scrollback")
            assert history.status_code == 200
            assert history.json()["paneId"] == pane_id
            assert marker in history.json()["text"]
            assert len(history.json()["text"].encode("utf-8")) <= 65536
    finally:
        if observer is not None:
            await stop_observer(observer)
        # Stop only the uniquely named session created above; never the default server.
        stop = await asyncio.create_subprocess_exec(executable, "--session", name, "session", "stop", name, env=env, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        await asyncio.wait_for(stop.communicate(), timeout=5)
        if server.returncode is None:
            try:
                await asyncio.wait_for(server.wait(), timeout=5)
            except TimeoutError:
                server.terminate()
                await asyncio.wait_for(server.wait(), timeout=5)
        await asyncio.wait_for(server.communicate(), timeout=5)
