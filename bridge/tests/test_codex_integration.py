from __future__ import annotations

import hashlib
import fcntl
import json
import os
from pathlib import Path
import socket
import subprocess
import shlex
import sys
import threading
import pty
import termios
from types import SimpleNamespace
import uuid

import pytest

from herdr_bridge import codex_integration as integration


# Pinned official Herdr v0.9.3 asset. Tests execute this actual helper after
# applying the compatibility patch; the fake socket never reaches a user pane.
OFFICIAL_HELPER = b'''#!/bin/sh
# installed by herdr
# managed by herdr; reinstalling or updating the integration overwrites this file.
# add custom hooks beside this file instead of editing it.
# HERDR_INTEGRATION_ID=codex
# HERDR_INTEGRATION_VERSION=8

set -eu

action="${1:-}"
hook_input_file="$(mktemp "${TMPDIR:-/tmp}/herdr-codex-hook.XXXXXX")" || exit 0
trap 'rm -f "$hook_input_file"' EXIT HUP INT TERM
cat >"$hook_input_file" 2>/dev/null || true

case "$action" in
  session) ;;
  *) exit 0 ;;
esac

[ "${HERDR_ENV:-}" = "1" ] || exit 0
[ -n "${HERDR_SOCKET_PATH:-}" ] || exit 0
[ -n "${HERDR_PANE_ID:-}" ] || exit 0
command -v python3 >/dev/null 2>&1 || exit 0

HERDR_ACTION="$action" HERDR_HOOK_INPUT_FILE="$hook_input_file" python3 - <<'PY'
import json
import os
import random
import socket
import time

source = "herdr:codex"
action = os.environ.get("HERDR_ACTION", "")
pane_id = os.environ.get("HERDR_PANE_ID")
socket_path = os.environ.get("HERDR_SOCKET_PATH")
hook_input_file = os.environ.get("HERDR_HOOK_INPUT_FILE")

if not pane_id or not socket_path:
    raise SystemExit(0)

hook_input = {}
if hook_input_file:
    try:
        with open(hook_input_file, encoding="utf-8") as handle:
            content = handle.read()
        if content.strip():
            hook_input = json.loads(content)
    except Exception:
        hook_input = {}

hook_event_name = str(hook_input.get("hook_event_name") or "")
if hook_event_name and hook_event_name != "SessionStart":
    raise SystemExit(0)

request_id = f"{source}:{int(time.time() * 1000)}:{random.randrange(1_000_000):06d}"
report_seq = time.time_ns()
session_id = hook_input.get("session_id")
agent_session_id = session_id if isinstance(session_id, str) and session_id else None
transcript_path = hook_input.get("transcript_path")
if not isinstance(transcript_path, str) or not transcript_path.strip():
    raise SystemExit(0)
inherited_session_id = os.environ.get("CODEX_THREAD_ID")
if inherited_session_id and inherited_session_id != agent_session_id:
    raise SystemExit(0)
session_start_source = hook_input.get("source") if hook_event_name == "SessionStart" else None
if not isinstance(session_start_source, str) or not session_start_source:
    session_start_source = None
if agent_session_id:
    params = {
        "pane_id": pane_id,
        "source": source,
        "agent": "codex",
        "seq": report_seq,
        "agent_session_id": agent_session_id,
    }
    if session_start_source:
        params["session_start_source"] = session_start_source
    request = {
        "id": request_id,
        "method": "pane.report_agent_session",
        "params": params,
    }
else:
    raise SystemExit(0)

try:
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(0.5)
    client.connect(socket_path)
    client.sendall((json.dumps(request) + "\\n").encode())
    try:
        client.recv(4096)
    except Exception:
        pass
    client.close()
except Exception:
    pass
PY
'''


def prepare_home(tmp_path: Path, data: bytes | None = OFFICIAL_HELPER) -> tuple[Path, Path]:
    home = tmp_path / "codex"
    home.mkdir(mode=0o700)
    helper = home / "herdr-agent-state.sh"
    if data is not None:
        helper.write_bytes(data)
        helper.chmod(0o755)
    (home / "config.toml").write_text('[features]\nhooks = true\n')
    (home / "hooks.json").write_text(json.dumps({"hooks": {"SessionStart": [{"hooks": [official_hook(helper)]}]}}))
    return home, helper


def official_hook(helper: Path) -> dict:
    return {"type": "command", "command": f"bash {shlex.quote(str(helper))} session", "timeout": 10}


def fake_cli(monkeypatch, helper: Path, *, status="current (v8)", fail_install=False):
    calls = []

    def run(args, **kwargs):
        calls.append((args, kwargs))
        if args == ["/test/herdr", "integration", "status"]:
            return SimpleNamespace(returncode=0, stdout=f"codex: {status} ({helper})\n", stderr="")
        assert args == ["/test/herdr", "integration", "install", "codex"]
        if fail_install:
            return SimpleNamespace(returncode=1, stdout="", stderr="")
        helper.write_bytes(OFFICIAL_HELPER)
        helper.chmod(0o755)
        config = helper.parent / "config.toml"
        config.write_text(config.read_text().replace("hooks = false", "hooks = true") if config.exists() else '[features]\nhooks = true\n')
        hooks = helper.parent / "hooks.json"
        value = json.loads(hooks.read_text()) if hooks.exists() else {}
        value.setdefault("hooks", {}).setdefault("SessionStart", []).append({"hooks": [official_hook(helper)]})
        hooks.write_text(json.dumps(value))
        return SimpleNamespace(returncode=0, stdout="", stderr="")

    monkeypatch.setattr(integration.subprocess, "run", run)
    return calls


def test_pinned_official_asset_is_patched_once_without_changing_hook_settings(tmp_path, monkeypatch):
    assert hashlib.sha256(OFFICIAL_HELPER).hexdigest() == integration.OFFICIAL_V8_SHA256
    home, helper = prepare_home(tmp_path)
    hooks = home / "hooks.json"
    hooks.write_text(json.dumps({"hooks": {"SessionStart": [{"hooks": [official_hook(helper), {"command": "custom hook"}]}]}}))
    config = home / "config.toml"
    config.write_text('[features]\nhooks = true\n[hooks.state.custom]\ntrusted_hash = "retained"\n')
    before = (hooks.read_bytes(), config.read_bytes())
    calls = fake_cli(monkeypatch, helper)
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert result.ready and result.changed and result.advisory is None
    patched = helper.read_bytes()
    assert integration.PATCH_MARKER in patched
    assert helper.stat().st_mode & 0o777 == 0o755
    assert helper.with_name(helper.name + ".herdr-pocket-v8.original").read_bytes() == OFFICIAL_HELPER
    assert (hooks.read_bytes(), config.read_bytes()) == before
    again = integration.ensure_codex_integration("/test/herdr", home)
    assert again.ready and not again.changed
    assert helper.read_bytes() == patched
    assert [call[0] for call in calls] == [["/test/herdr", "integration", "status"]] * 2


def test_previous_exact_pocket_helper_gets_daemon_guard_without_reinstall(tmp_path, monkeypatch):
    previous = OFFICIAL_HELPER.replace(b"set -eu\n", integration.PATCH_MARKER + b"set -eu\n", 1)
    previous = previous.replace(integration._ORIGINAL_ID_GUARD, integration._COMPATIBLE_ID_GUARD, 1)
    previous = previous.replace(integration._ORIGINAL_EVENT_GUARD, integration._COMPATIBLE_EVENT_GUARD, 1)
    home, helper = prepare_home(tmp_path, previous)
    before = ((home / "hooks.json").read_bytes(), (home / "config.toml").read_bytes())
    calls = fake_cli(monkeypatch, helper)
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert result.ready and result.changed
    assert integration._PANE_PROCESS_GUARD in helper.read_bytes()
    assert integration._patched_original(helper.read_bytes()) == OFFICIAL_HELPER
    assert helper.with_name(helper.name + ".herdr-pocket-v8.original").read_bytes() == OFFICIAL_HELPER
    assert ((home / "hooks.json").read_bytes(), (home / "config.toml").read_bytes()) == before
    assert [call[0] for call in calls] == [["/test/herdr", "integration", "status"]]


def test_previous_exact_pane_ancestor_guard_gets_foreground_proof_without_reinstall(tmp_path, monkeypatch):
    assert hashlib.sha256(integration._LEGACY_PANE_PROCESS_GUARD).hexdigest() == "14bc5e11615a60adee0933585e851e054d6a2ba046b218fc8bd7f5a620e6a1fc"
    previous = OFFICIAL_HELPER.replace(b"set -eu\n", integration.PATCH_MARKER + b"set -eu\n", 1)
    previous = previous.replace(integration._ORIGINAL_ID_GUARD,
                                integration._LEGACY_PANE_PROCESS_GUARD + integration._COMPATIBLE_ID_GUARD, 1)
    previous = previous.replace(integration._ORIGINAL_EVENT_GUARD, integration._COMPATIBLE_EVENT_GUARD, 1)
    home, helper = prepare_home(tmp_path, previous)
    settings = ((home / "hooks.json").read_bytes(), (home / "config.toml").read_bytes())
    calls = fake_cli(monkeypatch, helper)
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert result.ready and result.changed
    assert integration._PANE_PROCESS_GUARD in helper.read_bytes()
    assert integration._patched_original(helper.read_bytes()) == OFFICIAL_HELPER
    assert helper.with_name(helper.name + ".herdr-pocket-v8.original").read_bytes() == OFFICIAL_HELPER
    assert ((home / "hooks.json").read_bytes(), (home / "config.toml").read_bytes()) == settings
    assert [call[0] for call in calls] == [["/test/herdr", "integration", "status"]]


def test_non_linux_helper_preserves_canonical_id_flow_without_proc(monkeypatch):
    monkeypatch.setattr(sys, "platform", "darwin")
    def forbidden():
        raise AssertionError("Non-Linux helper must not inspect /proc")
    fake_os = SimpleNamespace(getppid=forbidden)
    exec(integration._PANE_PROCESS_GUARD.decode(), {"os": fake_os})


@pytest.mark.parametrize("data,status", [
    (None, "not installed"),
    (OFFICIAL_HELPER.replace(b"HERDR_INTEGRATION_VERSION=8", b"HERDR_INTEGRATION_VERSION=7"), "outdated (v7, current v8)"),
])
def test_missing_or_pinned_old_integration_uses_official_installer(tmp_path, monkeypatch, data, status):
    home, helper = prepare_home(tmp_path, data)
    calls = fake_cli(monkeypatch, helper, status=status)
    monkeypatch.setenv("CODEX_THREAD_ID", str(uuid.uuid4()))
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert result.ready and result.changed
    assert [call[0] for call in calls] == [
        ["/test/herdr", "integration", "status"],
        ["/test/herdr", "integration", "install", "codex"],
    ]
    assert all(call[1]["env"]["CODEX_HOME"] == str(home) for call in calls)
    assert integration.PATCH_MARKER in helper.read_bytes()


@pytest.mark.parametrize("missing", ["config", "hooks", "registration", "feature_disabled"])
def test_current_helper_with_missing_hook_configuration_uses_official_installer(tmp_path, monkeypatch, missing):
    home, helper = prepare_home(tmp_path)
    if missing == "config":
        (home / "config.toml").unlink()
    elif missing == "hooks":
        (home / "hooks.json").unlink()
    elif missing == "registration":
        (home / "hooks.json").write_text('{"hooks":{"SessionStart":[{"hooks":[{"command":"custom hook"}]}]}}')
    else:
        (home / "config.toml").write_text('[features]\nhooks = false\n')
    calls = fake_cli(monkeypatch, helper)
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert result.ready and result.changed
    assert [call[0][-1] for call in calls] == ["status", "codex"]
    assert integration._hook_configured(home, helper)
    if missing == "registration":
        assert "custom hook" in (home / "hooks.json").read_text()


@pytest.mark.parametrize("kind", ["custom", "helper_symlink", "parent_symlink", "hooks_symlink", "config_hardlink", "writable_directory", "custom_backup", "lock_symlink"])
def test_unsafe_or_custom_files_are_not_overwritten(tmp_path, monkeypatch, kind):
    home, helper = prepare_home(tmp_path)
    original = helper.read_bytes()
    if kind == "custom":
        helper.write_bytes(original + b"# User changes\n")
    elif kind == "helper_symlink":
        actual = home / "user-helper"
        helper.rename(actual)
        helper.symlink_to(actual)
    elif kind == "parent_symlink":
        link = tmp_path / "codex-link"
        link.symlink_to(home, target_is_directory=True)
        home = link
    elif kind == "hooks_symlink":
        actual = tmp_path / "user-hooks"
        actual.write_text('{}')
        (home / "hooks.json").unlink()
        (home / "hooks.json").symlink_to(actual)
    elif kind == "config_hardlink":
        actual = tmp_path / "user-config"
        actual.write_text('[features]\nhooks=true\n')
        (home / "config.toml").unlink()
        os.link(actual, home / "config.toml")
    elif kind == "writable_directory":
        home.chmod(0o777)
    elif kind == "custom_backup":
        helper.with_name(helper.name + ".herdr-pocket-v8.original").write_bytes(b"User backup")
    elif kind == "lock_symlink":
        actual = tmp_path / "user-file"
        actual.write_text("untouched")
        (home / ".herdr-pocket-codex-integration.lock").symlink_to(actual)
    before = helper.read_bytes()
    calls = fake_cli(monkeypatch, helper)
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert not result.ready and result.advisory
    assert helper.read_bytes() == before
    assert all(call[0][-1] == "status" for call in calls)


def test_simultaneous_ensure_calls_serialize_and_patch_only_once(tmp_path, monkeypatch):
    home, helper = prepare_home(tmp_path)
    calls = fake_cli(monkeypatch, helper)
    results = []
    barrier = threading.Barrier(3)

    def ensure():
        barrier.wait(timeout=3)
        results.append(integration.ensure_codex_integration("/test/herdr", home))

    workers = [threading.Thread(target=ensure) for _ in range(2)]
    for worker in workers:
        worker.start()
    barrier.wait(timeout=3)
    for worker in workers:
        worker.join(timeout=3)
        assert not worker.is_alive()
    assert len(results) == 2 and all(result.ready for result in results)
    assert sum(result.changed for result in results) == 1
    assert len(calls) == 2
    assert helper.read_bytes().count(integration.PATCH_MARKER) == 1


def test_contended_cross_process_lock_times_out_without_replacing_helper(tmp_path, monkeypatch):
    home, helper = prepare_home(tmp_path)
    lock = home / ".herdr-pocket-codex-integration.lock"
    descriptor = os.open(lock, os.O_RDWR | os.O_CREAT, 0o600)
    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    monkeypatch.setattr(integration, "_LOCK_WAIT_SECONDS", 0.05)
    calls = fake_cli(monkeypatch, helper)
    try:
        result = integration.ensure_codex_integration("/test/herdr", home)
    finally:
        os.close(descriptor)
    assert not result.ready and "still running" in result.advisory
    assert helper.read_bytes() == OFFICIAL_HELPER
    assert calls == []


def test_cli_install_failure_is_advisory_and_does_not_launch_an_agent(tmp_path, monkeypatch):
    home, helper = prepare_home(tmp_path, None)
    calls = fake_cli(monkeypatch, helper, status="not installed", fail_install=True)
    result = integration.ensure_codex_integration("/test/herdr", home)
    assert not result.ready and "terminal access" in result.advisory
    assert not helper.exists()
    assert all(call[0][1] == "integration" for call in calls)


def hook_parent(tmp_path: Path) -> Path:
    directory = tmp_path / "hook-parent"
    directory.mkdir(exist_ok=True)
    codex = directory / "codex"
    codex.write_text("import subprocess,sys\n"
                     "raise SystemExit(subprocess.run(['sh', *sys.argv[-2:]], input=sys.stdin.buffer.readline()).returncode)\n")
    return codex


def run_hook(helper: Path, tmp_path: Path, payload: dict, *, inherited_thread: str | None = None,
             herdr_env="1", action="session", parent_role="resume", metadata_change=None,
             pending_reads=0, metadata_requests=None) -> list[dict]:
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    socket_path = tmp_path / "hook.sock"
    server.bind(str(socket_path))
    server.listen(1)
    server.settimeout(0.1)
    requests = []
    finished = threading.Event()
    parent = None
    reads = 0

    def receive():
        nonlocal reads
        try:
            while not finished.is_set():
                try:
                    connection, _ = server.accept()
                except TimeoutError:
                    continue
                with connection:
                    connection.settimeout(1)
                    raw = b""
                    while not raw.endswith(b"\n"):
                        raw += connection.recv(4096)
                    request = json.loads(raw)
                    if request["method"] == "pane.process_info":
                        assert parent is not None
                        reads += 1
                        if metadata_requests is not None:
                            metadata_requests.append(request)
                        metadata = {"pane_id": "w1:p1", "shell_pid": parent.pid,
                                    "foreground_process_group_id": parent.pid,
                                    "foreground_processes": [{"pid": parent.pid, "name": "codex"}]}
                        if reads <= pending_reads:
                            metadata["foreground_processes"] = []
                        if metadata_change == "wrong_pane":
                            metadata["pane_id"] = "w1:p2"
                        elif metadata_change == "wrong_group":
                            metadata["foreground_process_group_id"] = parent.pid + 1
                        elif metadata_change == "wrong_pid":
                            metadata["foreground_processes"] = [{"pid": parent.pid + 1, "name": "codex"}]
                        elif metadata_change == "wrong_terminal":
                            metadata["shell_pid"] = os.getpid()
                        response = {"id": request["id"], "result": {"type": "pane_process_info", "process_info": metadata}}
                    else:
                        requests.append(request)
                        response = {"id": request["id"], "result": {"type": "ok"}}
                    encoded = json.dumps(response).encode() + b"\n"
                    if metadata_change == "trickle" and request["method"] == "pane.process_info":
                        for byte in encoded:
                            try:
                                connection.sendall(bytes([byte]))
                            except BrokenPipeError:
                                break
                            if finished.wait(0.05):
                                break
                    else:
                        connection.sendall(encoded)
        finally:
            server.close()

    worker = threading.Thread(target=receive)
    worker.start()
    environment = dict(os.environ)
    environment.update(HERDR_ENV=herdr_env, HERDR_PANE_ID="w1:p1", HERDR_SOCKET_PATH=str(socket_path), TMPDIR=str(tmp_path))
    environment.pop("CODEX_THREAD_ID", None)
    if inherited_thread is not None:
        environment["CODEX_THREAD_ID"] = inherited_thread
    master, slave = pty.openpty()
    def owned_terminal():
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
    try:
        parent_args = [parent_role] if isinstance(parent_role, str) else parent_role
        parent = subprocess.Popen([sys.executable, str(hook_parent(tmp_path)), *parent_args, str(helper), action],
                                  stdin=slave, stdout=slave, stderr=subprocess.PIPE, env=environment,
                                  preexec_fn=owned_terminal)
        os.write(master, json.dumps(payload).encode() + b"\n")
        _, stderr = parent.communicate(timeout=5)
        assert parent.returncode == 0, stderr.decode()
    finally:
        finished.set()
        worker.join(timeout=2)
        os.close(master)
        os.close(slave)
    assert not worker.is_alive()
    return requests


@pytest.mark.parametrize("transcript", [None, "", "/a/nonexistent/rollout.jsonl"])
def test_exact_session_start_reports_identity_without_requiring_transcript(tmp_path, monkeypatch, transcript):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()  # Execute the patched shell helper against an isolated API socket.
    identity = str(uuid.uuid4())
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "resume", "transcript_path": transcript}
    requests = run_hook(helper, tmp_path, payload, inherited_thread=identity)
    assert len(requests) == 1
    assert requests[0]["method"] == "pane.report_agent_session"
    params = requests[0]["params"]
    assert params["source"] == "herdr:codex" and params["agent"] == "codex"
    assert params["agent_session_id"] == identity and params["session_start_source"] == "resume"
    assert params["pane_id"] == "w1:p1" and type(params["seq"]) is int and params["seq"] > 0
    assert not list(tmp_path.glob("herdr-codex-hook.*"))


def test_shared_daemon_never_reports_another_frontend_using_inherited_pane(tmp_path, monkeypatch):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    identity = str(uuid.uuid4())
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "resume", "transcript_path": None}
    assert run_hook(helper, tmp_path, payload, inherited_thread=identity, parent_role="app-server") == []


@pytest.mark.parametrize("role", ["exec", "e", "review", "app-server", "mcp-server", "exec-server", "sandbox"])
def test_noninteractive_codex_tool_child_cannot_rebind_its_parent_pane(tmp_path, monkeypatch, role):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    identity = str(uuid.uuid4())
    metadata_requests = []
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "startup", "transcript_path": None}
    assert run_hook(helper, tmp_path, payload, parent_role=role, metadata_requests=metadata_requests) == []
    assert metadata_requests == []  # Rejected even if its inherited foreground job would match.


@pytest.mark.parametrize("args", [["--no-daemon"], ["resume"], ["fork"], ["--model", "exec", "resume"], ["--", "exec"]])
def test_interactive_codex_forms_keep_exact_foreground_session_reporting(tmp_path, monkeypatch, args):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    identity = str(uuid.uuid4())
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "startup", "transcript_path": None}
    reports = run_hook(helper, tmp_path, payload, parent_role=args)
    assert len(reports) == 1 and reports[0]["params"]["agent_session_id"] == identity


@pytest.mark.parametrize("change", ["wrong_pane", "wrong_group", "wrong_pid", "wrong_terminal", "trickle"])
def test_hook_cannot_attribute_session_to_another_foreground_pane(tmp_path, monkeypatch, change):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    identity = str(uuid.uuid4())
    metadata_requests = []
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "startup", "transcript_path": None}
    assert run_hook(helper, tmp_path, payload, metadata_change=change, metadata_requests=metadata_requests) == []
    assert metadata_requests and len(metadata_requests) <= 41
    assert all(request["method"] == "pane.process_info" and request["params"] == {"pane_id": "w1:p1"}
               for request in metadata_requests)


def test_session_start_waits_for_exact_foreground_metadata_then_reports_once(tmp_path, monkeypatch):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    identity = str(uuid.uuid4())
    metadata_requests = []
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "startup", "transcript_path": None}
    reports = run_hook(helper, tmp_path, payload, pending_reads=2, metadata_requests=metadata_requests)
    assert len(metadata_requests) == 3
    assert len(reports) == 1 and reports[0]["params"]["agent_session_id"] == identity


@pytest.mark.parametrize("change", ["invalid_id", "uppercase_id", "child_thread", "subagent", "missing_event", "outside_herdr", "wrong_action"])
def test_unproven_or_child_session_inputs_never_report(tmp_path, monkeypatch, change):
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    identity = "12345678-90ab-7cde-8fab-0123456789ab"
    payload = {"hook_event_name": "SessionStart", "session_id": identity, "source": "startup", "transcript_path": None}
    inherited = identity
    if change == "invalid_id":
        payload["session_id"] = "newest"
    elif change == "uppercase_id":
        payload["session_id"] = identity.upper()
    elif change == "child_thread":
        inherited = str(uuid.uuid4())
    elif change == "subagent":
        payload["hook_event_name"] = "SubagentStart"
    elif change == "missing_event":
        payload.pop("hook_event_name")
    requests = run_hook(helper, tmp_path, payload, inherited_thread=inherited,
                        herdr_env="0" if change == "outside_herdr" else "1",
                        action="stop" if change == "wrong_action" else "session")
    assert requests == []


@pytest.mark.skipif(os.environ.get("HERDR_INTEGRATION") != "1", reason="Opt-in isolated live Herdr hook test")
async def test_null_transcript_hook_replaces_only_owned_exact_codex_session(tmp_path, monkeypatch):
    import asyncio
    import shlex
    import shutil
    import sys

    from herdr_bridge.herdr import HerdrClient, canonical_codex_session

    executable = os.environ.get("HERDR_BIN") or shutil.which("herdr")
    assert executable, "Install Herdr 0.9.3 or set HERDR_BIN"
    home, helper = prepare_home(tmp_path)
    fake_cli(monkeypatch, helper)
    assert integration.ensure_codex_integration("/test/herdr", home).ready
    monkeypatch.undo()
    name = "android-codex-hook-it-" + uuid.uuid4().hex[:12]
    environment = dict(os.environ)
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION",
                "HERDR_CONFIG_PATH", "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID"):
        environment.pop(key, None)
    server = await asyncio.create_subprocess_exec(executable, "--session", name, "server", env=environment,
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
        backend = HerdrClient(socket_path, str(executable))
        pane = (await backend.call("workspace.create", {"cwd": str(tmp_path), "focus": False}))["root_pane"]["pane_id"]
        fake_bin = tmp_path / "fake-bin"
        fake_bin.mkdir()
        fake_codex = fake_bin / "codex"
        hook_input = tmp_path / "owned-hook-input.json"
        hook_completed = tmp_path / "owned-hook-completed"
        fake_codex.write_text("import pathlib,signal,subprocess,sys,time\n"
                              "sys.stdout.write('\\x1b]0;Codex ⠋\\x07• Working (1s)\\n');sys.stdout.flush()\n"
                              f"payload=pathlib.Path({str(hook_input)!r})\n"
                              "while not payload.exists(): time.sleep(0.02)\n"
                              f"result=subprocess.run(['sh',{str(helper)!r},'session'],input=payload.read_bytes())\n"
                              f"pathlib.Path({str(hook_completed)!r}).write_text(str(result.returncode))\n"
                              "signal.pause()\n")
        # Start only our own synthetic process in the uniquely named fixture.
        await backend.send_input(pane, f"{shlex.quote(sys.executable)} {shlex.quote(str(fake_codex))}", ["enter"])
        async with asyncio.timeout(10):
            while True:
                snapshot = (await backend.call("session.snapshot", {}))["snapshot"]
                if any(item["pane_id"] == pane and item.get("agent") == "codex" for item in snapshot["panes"]):
                    break
                await asyncio.sleep(0.05)
        old_id, new_id = str(uuid.uuid4()), str(uuid.uuid4())
        await backend.call("pane.report_agent_session", {
            "pane_id": pane, "source": "herdr:codex", "agent": "codex", "seq": 1,
            "agent_session_id": old_id, "session_start_source": "startup",
        })
        assert canonical_codex_session(await backend.agent(pane)) == old_id
        payload = {"hook_event_name": "SessionStart", "session_id": new_id, "source": "resume", "transcript_path": None}
        # SessionStart runs in the exact owned foreground Codex ancestry,
        # rather than an unrelated process carrying a copied pane environment.
        hook_input.write_text(json.dumps(payload))
        async with asyncio.timeout(5):
            while not hook_completed.exists() or canonical_codex_session(await backend.agent(pane)) != new_id:
                await asyncio.sleep(0.05)
        assert hook_completed.read_text() == "0"
    finally:
        stop = await asyncio.create_subprocess_exec(executable, "--session", name, "session", "stop", name,
                                                   env=environment, stdout=asyncio.subprocess.DEVNULL,
                                                   stderr=asyncio.subprocess.PIPE)
        await asyncio.wait_for(stop.communicate(), timeout=5)
        if server.returncode is None:
            try:
                await asyncio.wait_for(server.wait(), timeout=5)
            except TimeoutError:
                server.terminate()
                await asyncio.wait_for(server.wait(), timeout=5)
        await asyncio.wait_for(server.communicate(), timeout=5)
