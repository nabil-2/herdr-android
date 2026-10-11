"""Remember an explicit chat choice for one proven, unchanged Codex runtime.

This stores no history or display names. A fingerprint identifies processes,
not the thread currently displayed by an unmodified shared-daemon TUI. The
caller must re-prove the live Unix peer and eligibility before restoring it.
"""

from __future__ import annotations

import asyncio
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat
import time

try:
    import fcntl
except ImportError:  # Shared-daemon attribution is Linux-only.
    fcntl = None

MAX_SELECTIONS = 32
MAX_STORE_BYTES = 32 * 1024
_LEDGER = "selections.json"
_HASH = re.compile(r"[a-f0-9]{64}\Z")
_UUID = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\Z")
_PANE = re.compile(r"w[1-9][0-9]*:p[1-9][0-9]*\Z")
_FIELDS = {"ownerHash", "paneId", "fingerprint", "threadId", "bindingDigest"}


def _pane(value: object) -> bool:
    return isinstance(value, str) and len(value) <= 128 and bool(_PANE.fullmatch(value))


def _hash(value: object) -> bool:
    return isinstance(value, str) and bool(_HASH.fullmatch(value))


def _json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate selection field")
        result[key] = value
    return result


async def _disk(function, *args):
    # Cancelling to_thread does not stop its worker. Finish the bounded write
    # before cancellation releases the manager's selection-ordering lock.
    worker = asyncio.create_task(asyncio.to_thread(function, *args))
    try:
        return await asyncio.shield(worker)
    except asyncio.CancelledError:
        while not worker.done():
            try:
                await asyncio.shield(worker)
            except asyncio.CancelledError:
                continue
            except Exception:
                break
        try:
            worker.result()
        except Exception:
            pass
        raise


def selection_fingerprint(runtime, *, boot_id: str | None = None) -> str | None:
    """Exclude reconnecting socket endpoints, retain all process/host identity."""
    try:
        if boot_id is None:
            with open("/proc/sys/kernel/random/boot_id", "rb") as source:
                boot_id = source.read(128).decode("ascii").strip()
        if not isinstance(boot_id, str) or not _UUID.fullmatch(boot_id):
            return None
        if not _pane(runtime.pane_id):
            return None
        terminal = runtime.terminal_id
        if (not isinstance(terminal, str) or not 1 <= len(terminal) <= 512
                or any(ord(character) < 32 or ord(character) == 127 for character in terminal)):
            return None
        positive = [runtime.frontend_pid, runtime.frontend_start, runtime.daemon_pid, runtime.daemon_start]
        counters = [runtime.socket_device, runtime.socket_inode, runtime.herdr_device, runtime.herdr_inode]
        if any(type(value) is not int or value <= 0 for value in positive):
            return None
        if any(type(value) is not int or value < 0 for value in counters):
            return None
        paths = [str(runtime.socket_path), str(runtime.home)]
        if any(not Path(value).is_absolute() or len(value) > 4096
               or any(ord(character) < 32 or ord(character) == 127 for character in value) for value in paths):
            return None
        identity = [boot_id, runtime.pane_id, terminal, *positive, *counters, *paths]
        return hashlib.sha256(json.dumps(identity, separators=(",", ":")).encode()).hexdigest()
    except (AttributeError, OSError, ValueError, UnicodeError):
        return None


class CodexSelectionStore:
    def __init__(self, root: Path):
        self.root = root.expanduser().absolute()

    def _root_fd(self) -> int:
        # Open each directory relative to the preceding descriptor. A replaced
        # ancestor or symlink cannot redirect later operations to another path.
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
        current = os.open(self.root.anchor, flags)
        try:
            for part in self.root.parts[1:]:
                try:
                    child = os.open(part, flags, dir_fd=current)
                except FileNotFoundError:
                    try:
                        os.mkdir(part, 0o700, dir_fd=current)
                    except FileExistsError:
                        pass
                    child = os.open(part, flags, dir_fd=current)
                os.close(current)
                current = child
            info = os.fstat(current)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError("Selection directory must be private")
            return current
        except BaseException:
            os.close(current)
            raise

    @staticmethod
    def _private_file(fd: int) -> os.stat_result:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600):
            raise ValueError("Selection files must be private regular files")
        return info

    @contextmanager
    def _locked(self):
        if fcntl is None:
            raise ValueError("Selection storage is unavailable")
        directory = self._root_fd()
        lock = None
        try:
            lock = os.open(".lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600, dir_fd=directory)
            self._private_file(lock)
            deadline = time.monotonic() + 0.5
            while True:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise ValueError("Selection storage is busy")
                    time.sleep(0.005)
            yield directory
        finally:
            if lock is not None:
                os.close(lock)
            os.close(directory)

    @staticmethod
    def _read(directory: int) -> list[dict]:
        try:
            source = os.open(_LEDGER, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        except FileNotFoundError:
            return []
        try:
            info = CodexSelectionStore._private_file(source)
            if info.st_size > MAX_STORE_BYTES:
                raise ValueError("Selection ledger is too large")
            with os.fdopen(source, "rb", closefd=False) as stream:
                encoded = stream.read(MAX_STORE_BYTES + 1)
            if len(encoded) > MAX_STORE_BYTES:
                raise ValueError("Selection ledger is too large")
            data = json.loads(encoded, object_pairs_hook=_json_object)
            if not isinstance(data, dict) or set(data) != {"version", "records"} or type(data["version"]) is not int or data["version"] != 1:
                raise ValueError("Unknown selection ledger")
            records = data["records"]
            if not isinstance(records, list) or len(records) > MAX_SELECTIONS:
                raise ValueError("Invalid selection records")
            scopes = set()
            for record in records:
                if (not isinstance(record, dict) or set(record) != _FIELDS
                        or not _hash(record["ownerHash"]) or not _pane(record["paneId"])
                        or not _hash(record["fingerprint"]) or not _hash(record["bindingDigest"])
                        or not isinstance(record["threadId"], str) or not _UUID.fullmatch(record["threadId"])):
                    raise ValueError("Invalid selection record")
                scope = record["ownerHash"], record["paneId"]
                if scope in scopes:
                    raise ValueError("Duplicate selection scope")
                scopes.add(scope)
            return records
        finally:
            os.close(source)

    @staticmethod
    def _write(directory: int, records: list[dict]) -> None:
        payload = json.dumps({"version": 1, "records": records}, separators=(",", ":")).encode()
        if len(payload) > MAX_STORE_BYTES:
            raise ValueError("Selection ledger is too large")
        temporary = f".selections-{secrets.token_hex(16)}.tmp"
        out = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=directory)
        try:
            with os.fdopen(out, "wb", closefd=False) as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(out)
            os.replace(temporary, _LEDGER, src_dir_fd=directory, dst_dir_fd=directory)
            os.fsync(directory)
        finally:
            os.close(out)
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass

    def _operation(self, operation: str, owner: str, pane_id: str, fingerprint: str | None = None,
                   thread_id: str | None = None, binding_digest: str | None = None):
        if not _hash(owner) or not _pane(pane_id):
            return False if operation == "remember" else None
        if operation != "forget" and not _hash(fingerprint):
            return False if operation == "remember" else None
        if operation == "remember" and (not isinstance(thread_id, str) or not _UUID.fullmatch(thread_id) or not _hash(binding_digest)):
            return False
        try:
            with self._locked() as directory:
                records = self._read(directory)
                matching = [record for record in records if (record["ownerHash"], record["paneId"]) == (owner, pane_id)]
                if operation == "load":
                    if matching and matching[0]["fingerprint"] == fingerprint:
                        return matching[0]["threadId"], matching[0]["bindingDigest"]
                    return None
                records = [record for record in records if (record["ownerHash"], record["paneId"]) != (owner, pane_id)]
                if operation == "remember":
                    records.append({"ownerHash": owner, "paneId": pane_id, "fingerprint": fingerprint,
                                    "threadId": thread_id, "bindingDigest": binding_digest})
                    self._write(directory, records[-MAX_SELECTIONS:])
                    return True
                if matching:
                    self._write(directory, records)
                return None
        except (OSError, ValueError, UnicodeError, RecursionError):
            return False if operation == "remember" else None

    async def load(self, owner: str, pane_id: str, fingerprint: str) -> tuple[str, str] | None:
        return await _disk(self._operation, "load", owner, pane_id, fingerprint)

    async def remember(self, owner: str, pane_id: str, fingerprint: str, thread_id: str, binding_digest: str) -> bool:
        return await _disk(self._operation, "remember", owner, pane_id, fingerprint, thread_id, binding_digest)

    async def forget(self, owner: str, pane_id: str) -> None:
        await _disk(self._operation, "forget", owner, pane_id)
