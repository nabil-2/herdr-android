"""Preserve Herdr's Codex hooks while supporting sessions without a local rollout."""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import shlex
import tempfile
import time
import tomllib

from .runtime import external_environment


# Official assets from herdrdev/herdr v0.9.3 and v0.8.0, respectively.
# Pin complete files: a version comment alone does not authorize replacing a
# custom helper. No hook configuration or Codex trust state is rewritten here.
OFFICIAL_V8_SHA256 = "38a90a2a99872d06dcbc978e7613406cdd852dbb07503abd7a113f518d5951f3"
OFFICIAL_V7_SHA256 = "5d16f1d4a7bfbf844e035362331e7295b5aacd4e27783fcdef4bbe2049ae58e8"
PATCH_MARKER = b"# HERDR_POCKET_CODEX_ID_ONLY=1\n"
_ORIGINAL_ID_GUARD = b'''session_id = hook_input.get("session_id")
agent_session_id = session_id if isinstance(session_id, str) and session_id else None
transcript_path = hook_input.get("transcript_path")
if not isinstance(transcript_path, str) or not transcript_path.strip():
    raise SystemExit(0)
'''
_COMPATIBLE_ID_GUARD = b'''session_id = hook_input.get("session_id")
# SessionStart provides an exact root identity even without a local transcript.
import uuid
try:
    agent_session_id = session_id if isinstance(session_id, str) and str(uuid.UUID(session_id)) == session_id else None
except ValueError:
    agent_session_id = None
'''
_ORIGINAL_EVENT_GUARD = b'if hook_event_name and hook_event_name != "SessionStart":\n'
_COMPATIBLE_EVENT_GUARD = b'if hook_event_name != "SessionStart":\n'
_MAX_HELPER_BYTES = 65536
_LOCK_WAIT_SECONDS = 35


@dataclass(frozen=True)
class CodexIntegrationResult:
    ready: bool
    changed: bool = False
    advisory: str | None = None


class _UnsafeIntegration(Exception):
    pass


@contextmanager
def _integration_lock(home: Path):
    """Serialize setup, service startup and connection requests across processes."""
    path = home / ".herdr-pocket-codex-integration.lock"
    _check_file(path)
    descriptor = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
    try:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_nlink != 1 or info.st_mode & 0o077):
            raise _UnsafeIntegration("The Codex integration lock must be an owner-only regular file.")
        deadline = time.monotonic() + _LOCK_WAIT_SECONDS
        while True:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise _UnsafeIntegration("Another Codex integration update is still running; try setup again.")
                time.sleep(0.05)
        yield
    finally:
        os.close(descriptor)  # Closing also releases only this descriptor's lock.


def _check_directory_tree(path: Path) -> None:
    if not path.is_absolute() or ".." in path.parts:
        raise _UnsafeIntegration("The Codex home must be an absolute directory.")
    for directory in reversed((path, *path.parents)):
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, os.getuid()):
            raise _UnsafeIntegration("Codex integration directories must not contain symbolic links or have another owner.")
        # Root-owned sticky temporary directories are safe ancestors of private
        # test/install directories; writable integration directories are not.
        sticky_root = info.st_uid == 0 and bool(info.st_mode & stat.S_ISVTX) and directory != path
        if info.st_mode & 0o022 and not sticky_root:
            raise _UnsafeIntegration("Codex integration directories must not allow others to write.")
    if path.lstat().st_uid != os.getuid():
        raise _UnsafeIntegration("The Codex home must be owned by you.")


def _check_file(path: Path) -> os.stat_result | None:
    try:
        info = path.lstat()
    except FileNotFoundError:
        return None
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or info.st_nlink != 1 or info.st_mode & 0o022):
        raise _UnsafeIntegration("Codex integration files must be owned regular files without symbolic links.")
    return info


def _read_helper(path: Path) -> tuple[bytes, os.stat_result] | None:
    expected = _check_file(path)
    if expected is None:
        return None
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (info.st_dev, info.st_ino) != (expected.st_dev, expected.st_ino):
            raise _UnsafeIntegration("The Codex helper changed during setup; run setup again.")
        data = os.read(fd, _MAX_HELPER_BYTES + 1)
        if len(data) > _MAX_HELPER_BYTES:
            raise _UnsafeIntegration("The Codex helper has custom contents; it was left unchanged.")
        return data, info
    finally:
        os.close(fd)


def _patched_original(data: bytes) -> bytes | None:
    """Recognize only our exact transform of the pinned official asset."""
    if data.count(PATCH_MARKER) != 1 or data.count(_COMPATIBLE_ID_GUARD) != 1:
        return None
    original = data.replace(PATCH_MARKER, b"", 1).replace(_COMPATIBLE_ID_GUARD, _ORIGINAL_ID_GUARD, 1)
    original = original.replace(_COMPATIBLE_EVENT_GUARD, _ORIGINAL_EVENT_GUARD, 1)
    return original if hashlib.sha256(original).hexdigest() == OFFICIAL_V8_SHA256 else None


def _hook_configured(home: Path, helper: Path) -> bool:
    """Herdr's Codex status checks versions, so also verify the actual registration."""
    config = _read_helper(home / "config.toml")
    hooks = _read_helper(home / "hooks.json")
    if config is None or hooks is None:
        return False
    try:
        settings = tomllib.loads(config[0].decode())
        features = settings.get("features", {})
        if not isinstance(features, dict) or features.get("hooks") is not True:
            return False
        groups = json.loads(hooks[0]).get("hooks", {}).get("SessionStart", [])
        if not isinstance(groups, list):
            return False
        for group in groups:
            if not isinstance(group, dict) or group.get("matcher") not in (None, "") or not isinstance(group.get("hooks"), list):
                continue
            for hook in group["hooks"]:
                if isinstance(hook, dict) and hook.get("type") == "command" and isinstance(hook.get("command"), str):
                    if shlex.split(hook["command"]) == ["bash", str(helper), "session"]:
                        return True
    except (ValueError, TypeError, AttributeError, UnicodeError):
        return False
    return False


def _patch_helper(path: Path, original: bytes, info: os.stat_result) -> None:
    if original.count(_ORIGINAL_ID_GUARD) != 1 or original.count(_ORIGINAL_EVENT_GUARD) != 1:
        raise _UnsafeIntegration("The Codex helper has custom contents; it was left unchanged.")
    patched = original.replace(b"set -eu\n", PATCH_MARKER + b"set -eu\n", 1)
    patched = patched.replace(_ORIGINAL_ID_GUARD, _COMPATIBLE_ID_GUARD, 1)
    patched = patched.replace(_ORIGINAL_EVENT_GUARD, _COMPATIBLE_EVENT_GUARD, 1)
    backup = path.with_name(path.name + ".herdr-pocket-v8.original")
    prior_backup = _read_helper(backup)
    if prior_backup is not None and prior_backup[0] != original:
        raise _UnsafeIntegration("The Codex helper backup has custom contents; the helper was left unchanged.")
    if prior_backup is None:
        fd = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(original)
            output.flush()
            os.fsync(output.fileno())
    fd, temporary = tempfile.mkstemp(prefix=".herdr-pocket-codex-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(patched)
            output.flush()
            os.fchmod(output.fileno(), stat.S_IMODE(info.st_mode))
            os.fsync(output.fileno())
        current = _read_helper(path)
        if current is None or current[0] != original or (current[1].st_dev, current[1].st_ino) != (info.st_dev, info.st_ino):
            raise _UnsafeIntegration("The Codex helper changed during setup; it was left unchanged.")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def ensure_codex_integration(herdr_bin: str, codex_home: Path) -> CodexIntegrationResult:
    """Configure future exact session reports, without starting or touching agents.

    Failures are advisory: the companion's terminal access remains usable.
    Existing sessions that missed SessionStart still need an exact provider ID.
    """
    try:
        home = Path(codex_home).expanduser().absolute()
        _check_directory_tree(home)
        with _integration_lock(home):
            return _ensure_locked(herdr_bin, home)
    except (_UnsafeIntegration, OSError) as error:
        message = str(error) if isinstance(error, _UnsafeIntegration) else "Could not prepare Herdr's Codex integration."
        return CodexIntegrationResult(ready=False, advisory=message)


def _ensure_locked(herdr_bin: str, codex_home: Path) -> CodexIntegrationResult:
    changed = False
    try:
        home = Path(codex_home).expanduser().absolute()
        _check_directory_tree(home)
        for filename in ("config.toml", "hooks.json"):
            _check_file(home / filename)
        helper = home / "herdr-agent-state.sh"
        initial = _read_helper(helper)
        if initial is not None:
            digest = hashlib.sha256(initial[0]).hexdigest()
            if digest not in (OFFICIAL_V8_SHA256, OFFICIAL_V7_SHA256) and _patched_original(initial[0]) is None:
                raise _UnsafeIntegration("The Codex helper has custom contents; it was left unchanged.")
        environment = external_environment()
        environment["CODEX_HOME"] = str(home)
        result = subprocess.run([herdr_bin, "integration", "status"], env=environment,
                                capture_output=True, text=True, timeout=30, check=False)
        if result.returncode:
            raise _UnsafeIntegration("Could not check Herdr's Codex integration.")
        codex_status = next((line for line in result.stdout.splitlines() if line.startswith("codex: ")), "")
        if not codex_status:
            raise _UnsafeIntegration("Herdr did not report a supported Codex integration.")
        if (initial is None or hashlib.sha256(initial[0]).hexdigest() == OFFICIAL_V7_SHA256
                or "codex: current (v8)" not in codex_status or not _hook_configured(home, helper)):
            result = subprocess.run([herdr_bin, "integration", "install", "codex"], env=environment,
                                    capture_output=True, text=True, timeout=30, check=False)
            if result.returncode:
                raise _UnsafeIntegration("Could not install Herdr's Codex integration; terminal access is available.")
            changed = True
        _check_directory_tree(home)
        for filename in ("config.toml", "hooks.json"):
            _check_file(home / filename)
        installed = _read_helper(helper)
        if installed is None:
            raise _UnsafeIntegration("Herdr did not install its Codex helper.")
        if not _hook_configured(home, helper):
            raise _UnsafeIntegration("Herdr's Codex hook is not configured; terminal access is available.")
        if _patched_original(installed[0]) is not None:
            return CodexIntegrationResult(ready=True, changed=changed)
        if hashlib.sha256(installed[0]).hexdigest() != OFFICIAL_V8_SHA256:
            raise _UnsafeIntegration("This Codex helper version is unsupported; it was left unchanged.")
        _patch_helper(helper, *installed)
        return CodexIntegrationResult(ready=True, changed=True)
    except (_UnsafeIntegration, OSError, subprocess.SubprocessError) as error:
        message = str(error) if isinstance(error, _UnsafeIntegration) else "Could not prepare Herdr's Codex integration."
        return CodexIntegrationResult(ready=False, changed=changed, advisory=message)
