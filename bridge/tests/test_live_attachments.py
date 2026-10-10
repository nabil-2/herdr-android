"""Real, uniquely owned Herdr PTY; fake Codex never contacts a model."""

from __future__ import annotations

import asyncio
from io import BytesIO
import json
import os
from pathlib import Path
import shutil
import sys
import uuid

import httpx
from PIL import Image
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.codex import CodexHistoryNotPersisted
from herdr_bridge.codex_integration import CodexIntegrationResult
from herdr_bridge.herdr import HerdrClient
from .conftest import AUTH, TOKEN

pytestmark = pytest.mark.skipif(os.environ.get("HERDR_INTEGRATION") != "1",
                                reason="Opt-in uniquely owned Herdr attachment fixture")


async def test_real_herdr_delivers_two_image_pastes_and_one_reviewed_file_prompt(tmp_path, monkeypatch):
    executable = os.environ.get("HERDR_BIN") or shutil.which("herdr")
    assert executable
    session = "pocket-attachments-it-" + uuid.uuid4().hex[:12]
    thread = str(uuid.uuid4())
    home = tmp_path / "codex-home"
    home.mkdir()
    received = tmp_path / "received.bin"
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    fake = bin_dir / "codex"
    fake.write_text(f"#!{sys.executable}\n" +
                    "import os,json,socket,sys,tty\n"
                    "tty.setraw(sys.stdin.fileno())\n"
                    "sys.stdout.write('\\x1b[?2004h\\x1b]0;Codex\\x07Owned attachment fixture\\r\\n› ');sys.stdout.flush()\n"
                    "sock=socket.socket(socket.AF_UNIX);sock.connect(os.environ['HERDR_SOCKET_PATH'])\n"
                    "req={'id':'own-start','method':'pane.report_agent','params':{'pane_id':os.environ['HERDR_PANE_ID'],\n"
                    " 'source':'herdr:codex','agent':'codex','state':'idle','agent_session_id':" + repr(thread) + "}}\n" +
                    "sock.sendall(json.dumps(req).encode()+b'\\n');sock.makefile('rb').readline();sock.close()\n"
                    "data=b''\n"
                    "while True:\n"
                    " data+=os.read(sys.stdin.fileno(),4096)\n"
                    " if b'\\r' in data:\n"
                    f"  open({str(received)!r},'wb').write(data)\n"
                    "  data=b''\n")
    fake.chmod(0o700)
    environment = dict(os.environ)
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION", "HERDR_CONFIG_PATH",
                "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID", "CODEX_THREAD_ID", "CODEX_SESSION_ID"):
        environment.pop(key, None)
    server = await asyncio.create_subprocess_exec(executable, "--session", session, "server", env=environment,
                                                  stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        async with asyncio.timeout(10):
            while True:
                line = await server.stdout.readline()
                assert line
                if line.startswith(b"api socket: "):
                    socket_path = Path(line.decode().strip().removeprefix("api socket: "))
                    break

        class OwnedClient(HerdrClient):
            writes = []

            async def call(self, method, params):
                if method in ("pane.send_input", "agent.prompt"):
                    self.writes.append((method, params))
                return await super().call(method, params)

        backend = OwnedClient(socket_path, str(executable))
        workspace = (await backend.call("workspace.create", {"cwd": str(tmp_path), "label": "Owned attachments", "focus": False}))["workspace"]["workspace_id"]
        monkeypatch.setattr("herdr_bridge.app.ensure_codex_integration", lambda *_: CodexIntegrationResult(True))
        path = str(bin_dir) + os.pathsep + os.environ.get("PATH", os.defpath)
        app = create_app(backend, TOKEN, codex_executable=str(fake), codex_home=home, codex_path=path,
                         attachment_dir=tmp_path / "private uploads")

        async def missing_history(thread_id):
            assert thread_id == thread
            raise CodexHistoryNotPersisted(thread_id)

        app.state.codex_reader.read = missing_history
        headers = AUTH | {"X-Herdr-Chat-Fresh-Input": "1", "X-Herdr-Chat-Working-Input": "1"}
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://owned", headers=headers) as phone:
                created = await phone.post(f"/v1/workspaces/{workspace}/tabs", json={"kind": "codex", "label": "Images", "cwd": str(tmp_path)})
                assert created.status_code == 201, created.text
                pane = created.json()["paneId"]
                async with asyncio.timeout(15):
                    while True:
                        page = await phone.get(f"/v1/panes/{pane}/conversation")
                        if page.status_code == 200 and page.json()["canSend"]:
                            break
                        await asyncio.sleep(0.1)
                binding = page.json()["binding"]
                stream = BytesIO()
                Image.new("RGB", (8, 8), "blue").save(stream, format="PNG")
                ids = []
                for kind, name, data in [("image", "one.png", stream.getvalue()), ("image", "two.png", stream.getvalue()),
                                         ("file", "notes.txt", b"owned file attachment")]:
                    response = await phone.post(f"/v1/panes/{pane}/attachments", params={"kind": kind, "name": name}, content=data,
                                                headers={"X-Herdr-Chat-Binding": binding, "Content-Type": "application/octet-stream"})
                    assert response.status_code == 201, response.text
                    ids.append(response.json()["id"])
                assert backend.writes == []
                submitted = await phone.post(f"/v1/panes/{pane}/messages", json={"binding": binding, "text": "Review the owned attachments", "attachmentIds": ids})
                assert submitted.status_code == 200, submitted.text
                async with asyncio.timeout(5):
                    while not received.exists():
                        await asyncio.sleep(0.05)
                assert [method for method, _ in backend.writes] == ["pane.send_input", "pane.send_input", "agent.prompt"]
                expected = b""
                for _, params in backend.writes:
                    expected += b"\x1b[200~" + params["text"].encode() + b"\x1b[201~"
                assert received.read_bytes() == expected + b"\r"
                for attachment_id in ids:
                    assert app.state.attachments.receipts[attachment_id].path.exists()
                assert not app.state.attachments.blocked(pane, binding)
                again = await phone.post(f"/v1/panes/{pane}/messages", json={"binding": binding, "attachmentIds": ids})
                assert again.status_code == 503
                assert len(backend.writes) == 3
    finally:
        stop = await asyncio.create_subprocess_exec(executable, "--session", session, "session", "stop", session,
                                                    env=environment, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        await asyncio.wait_for(stop.communicate(), 5)
        if server.returncode is None:
            try:
                await asyncio.wait_for(server.wait(), 5)
            except TimeoutError:
                server.terminate()
                await asyncio.wait_for(server.wait(), 5)
        await asyncio.wait_for(server.communicate(), 5)
