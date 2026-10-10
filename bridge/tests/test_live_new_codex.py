"""Opt-in owned Herdr tab + managed fake Codex; no models or user sessions."""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path
import shutil
import sys
import uuid

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.codex import CodexHistoryNotPersisted
from herdr_bridge.codex_integration import CodexIntegrationResult
from herdr_bridge.herdr import HerdrClient
from herdr_bridge import herdr as herdr_module
from .conftest import AUTH, TOKEN


pytestmark = pytest.mark.skipif(os.environ.get("HERDR_INTEGRATION") != "1",
                                reason="Opt-in uniquely owned Herdr integration session")


async def test_phone_creates_new_managed_codex_and_sends_one_first_message_without_a_rollout(tmp_path, monkeypatch):
    executable = os.environ.get("HERDR_BIN") or shutil.which("herdr")
    assert executable, "Install Herdr 0.9.3 or set HERDR_BIN"
    session = "android-new-codex-it-" + uuid.uuid4().hex[:12]
    thread = str(uuid.uuid4())
    home = tmp_path / "codex-home"
    home.mkdir()
    received = tmp_path / "first-message.bin"
    started = tmp_path / "started.json"
    binary_directory = tmp_path / "bin"
    binary_directory.mkdir()
    fake = binary_directory / "codex"
    fake.write_text(f"#!{sys.executable}\n" +
                    "import os,json,socket,sys,tty\n"
                    "tty.setraw(sys.stdin.fileno())\n"
                    "sys.stdout.write('\\x1b[?2004l\\x1b]0;Codex\\x07Ready for a first message\\r\\n› ');sys.stdout.flush()\n"
                    f"open({str(started)!r},'w').write(json.dumps({{'codexHome':os.environ.get('CODEX_HOME'),'pane':os.environ.get('HERDR_PANE_ID')}}))\n"
                    "sock=socket.socket(socket.AF_UNIX);sock.connect(os.environ['HERDR_SOCKET_PATH'])\n"
                    "request={'id':'owned-session-start','method':'pane.report_agent','params':{'pane_id':os.environ['HERDR_PANE_ID'],\n"
                    " 'source':'herdr:codex','agent':'codex','state':'idle','agent_session_id':" + repr(thread) + "}}\n" +
                    "sock.sendall(json.dumps(request).encode()+b'\\n');sock.makefile('rb').readline();sock.close()\n"
                    "data=b''\n"
                    "while True:\n"
                    " data+=os.read(sys.stdin.fileno(),4096)\n"
                    " if b'\\r' in data:\n"
                    f"  open({str(received)!r},'wb').write(data)\n"
                    "  data=b''\n")
    fake.chmod(0o700)
    search_path = str(binary_directory) + os.pathsep + os.environ.get("PATH", os.defpath)
    environment = dict(os.environ)
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION", "HERDR_CONFIG_PATH",
                "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID", "CODEX_THREAD_ID", "CODEX_SESSION_ID"):
        environment.pop(key, None)
    server = await asyncio.create_subprocess_exec(executable, "--session", session, "server", env=environment,
                                                  stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
    try:
        assert server.stdout
        async with asyncio.timeout(10):
            while True:
                line = await server.stdout.readline()
                assert line, "Owned Herdr server failed to start"
                if line.startswith(b"api socket: "):
                    socket_path = Path(line.decode().strip().removeprefix("api socket: "))
                    break

        class OwnedHerdr(HerdrClient):
            mutation_counts = {"tab.create": 0, "agent.start": 0, "agent.prompt": 0}

            async def call(self, method, params):
                if method in self.mutation_counts:
                    self.mutation_counts[method] += 1
                try:
                    return await super().call(method, params)
                except Exception as exc:
                    if method == "agent.start":
                        self.start_error = str(exc)
                    raise

        backend = OwnedHerdr(socket_path, str(executable))
        error_codes = []
        original_error = herdr_module.upstream_error
        def record_owned_error(error, *, prompt=False):
            if isinstance(error, dict):
                error_codes.append(error.get("code"))
            return original_error(error, prompt=prompt)
        monkeypatch.setattr(herdr_module, "upstream_error", record_owned_error)
        workspace = (await backend.call("workspace.create", {"cwd": str(tmp_path), "label": "Owned creation fixture",
                                                               "focus": False}))["workspace"]["workspace_id"]
        monkeypatch.setattr("herdr_bridge.app.ensure_codex_integration", lambda *_: CodexIntegrationResult(True))
        app = create_app(backend, TOKEN, codex_executable=str(fake), codex_home=home, codex_path=search_path)

        async def missing_history(thread_id):
            assert thread_id == thread
            raise CodexHistoryNotPersisted(thread_id)

        # The actual fake runtime above reports an exact own SessionStart ID;
        # only this synthetic unmaterialized history is observed by the sidecar.
        app.state.codex_reader.read = missing_history
        headers = AUTH | {"X-Herdr-Chat-Fresh-Input": "1", "X-Herdr-Chat-Working-Input": "1"}
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://owned", headers=headers) as phone:
                created = await phone.post(f"/v1/workspaces/{workspace}/tabs", json={"kind": "codex", "label": "First chat",
                                                                                    "cwd": str(tmp_path)})
                assert created.status_code == 201, created.text
                pane = created.json()["paneId"]
                try:
                    async with asyncio.timeout(8):
                        while not started.exists():
                            await asyncio.sleep(0.05)
                except TimeoutError:
                    process = await backend.call("pane.process_info", {"pane_id": pane})
                    output = await backend.call("pane.read", {"pane_id": pane, "source": "recent_unwrapped", "format": "text", "lines": 20})
                    pytest.fail(f"Owned fake Codex did not start: upstreamCodes={error_codes} {getattr(backend, 'start_error', None)} {created.json()} {process} {output}")
                async with asyncio.timeout(15):
                    while True:
                        response = await phone.get(f"/v1/panes/{pane}/conversation")
                        if response.status_code == 200 and response.json()["canSend"]:
                            break
                        await asyncio.sleep(0.1)
                empty = response.json()
                assert empty["messages"] == [] and empty["freshBootstrap"]
                assert json.loads(started.read_text()) == {"codexHome": str(home), "pane": pane}
                text = "An explicit first message from the fixture"
                sent = await phone.post(f"/v1/panes/{pane}/messages", json={"binding": empty["binding"], "text": text})
                assert sent.status_code == 200, sent.text
                async with asyncio.timeout(5):
                    while not received.exists():
                        await asyncio.sleep(0.05)
                assert received.read_bytes() == text.encode() + b"\r"
                again = await phone.post(f"/v1/panes/{pane}/messages", json={"binding": empty["binding"], "text": text})
                assert again.status_code == 503
                assert backend.mutation_counts == {"tab.create": 1, "agent.start": 1, "agent.prompt": 1}
                # Only our own new tab was added; no preexisting pane was reused.
                assert len((await backend.snapshot())["panes"]) == 2
    finally:
        stop = await asyncio.create_subprocess_exec(executable, "--session", session, "session", "stop", session,
                                                    env=environment, stdout=asyncio.subprocess.DEVNULL,
                                                    stderr=asyncio.subprocess.PIPE)
        await asyncio.wait_for(stop.communicate(), 5)
        if server.returncode is None:
            try:
                await asyncio.wait_for(server.wait(), 5)
            except TimeoutError:
                server.terminate()
                await asyncio.wait_for(server.wait(), 5)
        await asyncio.wait_for(server.communicate(), 5)
