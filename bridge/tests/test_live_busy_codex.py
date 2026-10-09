"""Opt-in real Herdr submission to our own synthetic Codex terminal; no model calls."""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
import shlex
import shutil
import sys
import uuid

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.herdr import HerdrClient, canonical_codex_session
from .conftest import AUTH, TOKEN


pytestmark = pytest.mark.skipif(
    os.environ.get("HERDR_INTEGRATION") != "1",
    reason="Set HERDR_INTEGRATION=1 to test an isolated live Herdr session",
)


@pytest.mark.parametrize("named", [False, True], ids=["unnamed", "manually-renamed"])
async def test_working_manual_codex_accepts_exactly_one_phone_message_in_an_isolated_session(tmp_path, named):
    executable = os.environ.get("HERDR_BIN") or shutil.which("herdr")
    assert executable, "Install Herdr 0.9.3 or set HERDR_BIN"
    name = "android-busy-codex-it-" + uuid.uuid4().hex[:12]
    env = dict(os.environ)
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION",
                "HERDR_CONFIG_PATH", "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID"):
        env.pop(key, None)
    server = await asyncio.create_subprocess_exec(
        executable, "--session", name, "server", env=env,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
    )
    try:
        assert server.stdout
        async with asyncio.timeout(10):
            while True:
                line = await server.stdout.readline()
                assert line, "Herdr server failed to start"
                if line.startswith(b"api socket: "):
                    socket_path = Path(line.decode().strip().removeprefix("api socket: "))
                    break

        class CountingHerdr(HerdrClient):
            prompt_calls = 0

            async def prompt(self, pane_id: str, text: str) -> None:
                self.prompt_calls += 1
                await super().prompt(pane_id, text)

        backend = CountingHerdr(socket_path, str(executable))
        pane_id = (await backend.call("workspace.create", {
            "cwd": str(tmp_path), "label": "Owned busy Codex fixture", "focus": False,
        }))["root_pane"]["pane_id"]
        thread = str(uuid.uuid4())
        received = tmp_path / "received.bin"
        started = tmp_path / "started"
        fake_codex = tmp_path / "codex"
        fake_codex.write_text(
            "import os,sys,tty\n"
            "tty.setraw(sys.stdin.fileno())\n"
            "sys.stdout.write('\\x1b[?2004l\\x1b]0;Codex ⠋\\x07• Working (1s • esc to interrupt)\\r\\n');sys.stdout.flush()\n"
            f"open({str(started)!r},'w').write('ready')\n"
            "data=b''\n"
            "while True:\n"
            " data+=os.read(sys.stdin.fileno(),4096)\n"
            " if b'\\r' in data:\n"
            f"  open({str(received)!r},'wb').write(data)\n"
            "  data=b''\n"
        )
        fake_codex.chmod(0o700)
        await backend.send_input(pane_id, f"{shlex.quote(sys.executable)} {shlex.quote(str(fake_codex))}", ["enter"])
        async with asyncio.timeout(10):
            while not started.exists():
                await asyncio.sleep(0.05)
        # This fixture reports the same canonical metadata a Codex hook reports,
        # only into its own new terminal. The foreground process is a fake Codex
        # script recognized by Herdr's normal Python-wrapper process detector.
        await backend.call("pane.report_agent", {
            "pane_id": pane_id, "source": "herdr:codex", "agent": "codex",
            "state": "working", "agent_session_id": thread,
        })
        reported = await backend.agent(pane_id)
        assert canonical_codex_session(reported) == thread, reported
        async with asyncio.timeout(10):
            while True:
                agent = await backend.agent(pane_id)
                if agent.get("agent_status") == "working" and canonical_codex_session(agent) == thread:
                    break
                await asyncio.sleep(0.05)
        assert "name" not in agent and "interactive_ready" not in agent and "launch_pending" not in agent
        if named:
            await backend.call("agent.rename", {"target": pane_id, "name": "owned-codex"})
            agent = await backend.agent(pane_id)
            assert agent["name"] == "owned-codex"
            assert "interactive_ready" not in agent and "launch_pending" not in agent
        home = tmp_path / "codex-home"
        home.mkdir()
        app = create_app(backend, TOKEN, codex_executable=str(fake_codex), codex_home=home)

        async def saved_fixture_history(thread_id: str) -> dict:
            assert thread_id == thread
            return {"thread": {"id": thread, "turns": []}}

        # Read only a synthetic history; never launch a model or inspect user history.
        app.state.codex_reader.read = saved_fixture_history
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test",
                                     headers=AUTH | {"X-Herdr-Chat-Working-Input": "1"}) as phone:
            page = (await phone.get(f"/v1/panes/{pane_id}/conversation")).json()
            assert page["status"] == "working" and page["canSend"] and page["canSendReason"] is None
            follow_up = "Explicit synthetic follow-up"
            response = await phone.post(f"/v1/panes/{pane_id}/messages", json={"binding": page["binding"], "text": follow_up})
            assert response.status_code == 200, response.text
            assert response.json()["ok"] is True
            async with asyncio.timeout(10):
                while not received.exists():
                    await asyncio.sleep(0.05)
            assert received.read_bytes() == follow_up.encode() + b"\r"
            assert backend.prompt_calls == 1
    finally:
        stop = await asyncio.create_subprocess_exec(
            executable, "--session", name, "session", "stop", name, env=env,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE,
        )
        await asyncio.wait_for(stop.communicate(), timeout=5)
        if server.returncode is None:
            try:
                await asyncio.wait_for(server.wait(), timeout=5)
            except TimeoutError:
                server.terminate()
                await asyncio.wait_for(server.wait(), timeout=5)
        await asyncio.wait_for(server.communicate(), timeout=5)
