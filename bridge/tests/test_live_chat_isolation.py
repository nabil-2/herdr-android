"""Two uniquely owned fake Codex panes, using the actual pinned hook helper."""

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
from herdr_bridge.codex_integration import CodexIntegrationResult, _LEGACY_PANE_PROCESS_GUARD, _PANE_PROCESS_GUARD, _patch_helper
from herdr_bridge.herdr import HerdrClient, canonical_codex_session
from herdr_bridge import herdr as herdr_module
from .conftest import AUTH, TOKEN
from .test_codex_integration import OFFICIAL_HELPER


pytestmark = pytest.mark.skipif(os.environ.get("HERDR_INTEGRATION") != "1",
                                reason="Opt-in uniquely owned two-pane Codex isolation fixture")


@pytest.mark.parametrize(("misroute", "legacy"), [(False, False), (True, False), (True, True)],
                         ids=["distinct-new-chats", "reject-stale-pane-hook", "legacy-hook-misattribution-control"])
async def test_owned_codex_hooks_and_first_messages_remain_in_their_created_panes(tmp_path, monkeypatch, misroute, legacy):
    executable = os.environ.get("HERDR_BIN") or shutil.which("herdr")
    assert executable
    session = "pocket-chat-isolation-it-" + uuid.uuid4().hex[:12]
    namespace = uuid.uuid4()
    inherited_id = str(uuid.uuid4())
    home = tmp_path / "codex-home"
    home.mkdir(mode=0o700)
    helper = home / "herdr-agent-state.sh"
    helper.write_bytes(OFFICIAL_HELPER)
    helper.chmod(0o755)
    _patch_helper(helper, OFFICIAL_HELPER, helper.stat())
    if legacy:
        # A private old-version control proves the rejected behavior; never
        # install this helper or target any session outside this fixture.
        contents = helper.read_bytes()
        assert contents.count(_PANE_PROCESS_GUARD) == 1
        helper.write_bytes(contents.replace(_PANE_PROCESS_GUARD, _LEGACY_PANE_PROCESS_GUARD, 1))
    bin_directory = tmp_path / "bin"
    bin_directory.mkdir()
    override = tmp_path / "stale-pane.txt"
    fake = bin_directory / "codex"
    fake.write_text(f"#!{sys.executable}\n" +
                    "import os,json,sys,tty,uuid,subprocess,pathlib\n"
                    "tty.setraw(sys.stdin.fileno())\n"
                    "pane=os.environ['HERDR_PANE_ID']\n"
                    f"thread=str(uuid.uuid5(uuid.UUID({str(namespace)!r}),pane))\n"
                    f"root=pathlib.Path({str(tmp_path)!r})\n"
                    "record={'pane':pane,'thread':thread,'args':sys.argv[1:],'inheritedThread':os.environ.get('CODEX_THREAD_ID'),"
                    "'inheritedSession':os.environ.get('CODEX_SESSION_ID'),'socket':os.environ.get('HERDR_SOCKET_PATH')}\n"
                    f"override=pathlib.Path({str(override)!r})\n"
                    "source='startup'\n"
                    "if override.exists():\n"
                    " os.environ['HERDR_PANE_ID']=override.read_text();source='resume'\n"
                    "payload={'hook_event_name':'SessionStart','session_id':thread,'source':source,'transcript_path':None}\n"
                    f"hook=subprocess.run(['bash',{str(helper)!r},'session'],input=json.dumps(payload).encode(),capture_output=True,timeout=5)\n"
                    "record['hookReturnCode']=hook.returncode\n"
                    "record['hookStderr']=hook.stderr.decode(errors='replace')\n"
                    "(root/('started-'+pane.replace(':','_')+'.json')).write_text(json.dumps(record))\n"
                    "sys.stdout.write('\\x1b[?2004l\\x1b]0;Codex\\x07Owned fresh chat\\r\\n› ');sys.stdout.flush()\n"
                    "data=b''\n"
                    "while True:\n"
                    " data+=os.read(sys.stdin.fileno(),4096)\n"
                    " if b'\\r' in data:\n"
                    "  (root/('received-'+pane.replace(':','_')+'.bin')).write_bytes(data);data=b''\n")
    fake.chmod(0o700)
    environment = dict(os.environ)
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION", "HERDR_CONFIG_PATH",
                "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID", "CODEX_THREAD_ID", "CODEX_SESSION_ID"):
        environment.pop(key, None)
    # Herdr explicitly clears a parent's thread identity but currently retains
    # the older session env key. Neither may choose a new pane's conversation.
    environment.update(CODEX_THREAD_ID=inherited_id, CODEX_SESSION_ID=inherited_id)
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
                if method in {"tab.create", "agent.start", "agent.prompt", "pane.send_input"}:
                    self.writes.append((method, params))
                return await super().call(method, params)

        backend = OwnedClient(socket_path, str(executable))
        error_codes = []
        original_error = herdr_module.upstream_error
        def record_owned_error(error, *, prompt=False):
            if isinstance(error, dict):
                error_codes.append(error.get("code"))
            return original_error(error, prompt=prompt)
        monkeypatch.setattr(herdr_module, "upstream_error", record_owned_error)
        workspace = (await backend.call("workspace.create", {"cwd": str(tmp_path), "label": "Owned chat isolation", "focus": False}))["workspace"]["workspace_id"]
        monkeypatch.setattr("herdr_bridge.app.ensure_codex_integration", lambda *_: CodexIntegrationResult(True))
        app = create_app(backend, TOKEN, codex_executable=str(fake), codex_home=home,
                         codex_path=str(bin_directory) + os.pathsep + os.environ.get("PATH", os.defpath),
                         attachment_dir=tmp_path / "private uploads")
        panes = []
        async def missing_history(thread_id):
            assert thread_id in [str(uuid.uuid5(namespace, pane)) for pane in panes]
            raise CodexHistoryNotPersisted(thread_id)
        app.state.codex_reader.read = missing_history
        headers = AUTH | {"X-Herdr-Chat-Fresh-Input": "1", "X-Herdr-Chat-Working-Input": "1"}
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://owned", headers=headers) as phone:
                for index in range(2):
                    if index == 1 and misroute:
                        override.write_text(panes[0])
                    created = await phone.post(f"/v1/workspaces/{workspace}/tabs", json={"kind": "codex", "label": f"Isolated {index}", "cwd": str(tmp_path)})
                    assert created.status_code == 201, created.text
                    pane = created.json()["paneId"]
                    panes.append(pane)
                    started = tmp_path / ("started-" + pane.replace(":", "_") + ".json")
                    try:
                        async with asyncio.timeout(12):
                            while not started.exists():
                                await asyncio.sleep(0.05)
                    except TimeoutError:
                        # Diagnostics remain inside this uniquely owned fake
                        # session. Never retry a launch or inspect user panes.
                        process = await backend.call("pane.process_info", {"pane_id": pane})
                        output = await backend.call("pane.read", {"pane_id": pane, "source": "recent_unwrapped",
                                                               "format": "text", "lines": 20})
                        pytest.fail(f"Owned fake Codex did not start: upstreamCodes={error_codes} {created.json()} {process} {output}")
                    metadata = json.loads(started.read_text())
                    assert metadata["pane"] == pane and metadata["args"] == ["--no-daemon"]
                    assert metadata["inheritedThread"] is None and metadata["inheritedSession"] == inherited_id
                    assert metadata["socket"] == str(socket_path) and metadata["hookReturnCode"] == 0
                    if index == 0 or not misroute:
                        # Managed agent metadata can adopt its already accepted
                        # session claim only after foreground detection catches
                        # up. Wait for both normal panes, never replay a report.
                        async with asyncio.timeout(5):
                            while canonical_codex_session(await backend.agent(pane)) != str(uuid.uuid5(namespace, pane)):
                                await asyncio.sleep(0.05)
                assert panes[0] != panes[1]
                expected = [str(uuid.uuid5(namespace, pane)) for pane in panes]
                identities = [canonical_codex_session(await backend.agent(pane)) for pane in panes]
                if misroute:
                    if legacy:
                        assert identities == [expected[1], None], "The old helper's stale-pane metadata redirection was not reproduced"
                    else:
                        assert identities == [expected[0], None], "A stale pane environment redirected a new pane's hook into the old pane"
                    assert not any(method == "agent.prompt" for method, _ in backend.writes)
                    assert not (home / "sessions").exists() and not (home / "archived_sessions").exists()
                    return
                assert identities == expected and inherited_id not in identities, [
                    json.loads((tmp_path / ("started-" + pane.replace(":", "_") + ".json")).read_text())
                    for pane in panes
                ]
                pages = []
                for pane in panes:
                    async with asyncio.timeout(15):
                        while True:
                            response = await phone.get(f"/v1/panes/{pane}/conversation")
                            if response.status_code == 200 and response.json()["canSend"]:
                                break
                            await asyncio.sleep(0.05)
                    pages.append(response.json())
                assert pages[0]["binding"] != pages[1]["binding"]
                assert [page["conversationId"] for page in pages] == expected
                swapped = await phone.post(f"/v1/panes/{panes[1]}/messages", json={"binding": pages[0]["binding"], "text": "Must not cross panes"})
                assert swapped.status_code == 409
                assert not any(method == "agent.prompt" for method, _ in backend.writes)
                for index in (1, 0):
                    text = f"Reviewed synthetic first message for pane {index}"
                    sent = await phone.post(f"/v1/panes/{panes[index]}/messages", json={"binding": pages[index]["binding"], "text": text})
                    assert sent.status_code == 200, sent.text
                    received = tmp_path / ("received-" + panes[index].replace(":", "_") + ".bin")
                    async with asyncio.timeout(5):
                        while not received.exists():
                            await asyncio.sleep(0.05)
                    assert received.read_bytes() == text.encode() + b"\r"
                prompts = [params for method, params in backend.writes if method == "agent.prompt"]
                assert [params["target"] for params in prompts] == [panes[1], panes[0]]
                assert [canonical_codex_session(await backend.agent(pane)) for pane in panes] == expected
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
