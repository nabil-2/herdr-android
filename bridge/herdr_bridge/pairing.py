"""Short-lived pairing challenges and private, persistent per-device credentials.

Only SHA-256 digests of challenges and device tokens are persisted. A stable
sibling lock file protects atomic registry replacements across processes. Raw
codes and tokens are returned once to the local pairing helper or requesting
device, respectively; metadata APIs never expose either secret or its digest.
"""

from __future__ import annotations

from contextlib import contextmanager, suppress
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import stat
import tempfile
import time
from typing import Callable
import unicodedata
import uuid

from .herdr import BridgeError


MAX_REGISTRY_BYTES = 1024 * 1024
MAX_DEVICES = 4096
MAX_CHALLENGES = 64
SECRET_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}\Z")
HASH_PATTERN = re.compile(r"[0-9a-f]{64}\Z")


class PairingError(BridgeError):
    """Safe pairing failure with an HTTP status and credential-free explanation."""


def valid_name(value: object) -> bool:
    return isinstance(value, str) and 1 <= len(value) <= 100 and bool(value.strip()) and not any(
        unicodedata.category(char) in {"Cc", "Cf", "Cs"} for char in value
    )


def _secret_hash(value: str) -> str:
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def _is_timestamp(value: object) -> bool:
    return type(value) is int and value >= 0


def _canonical_device_id(value: object) -> bool:
    if not isinstance(value, str) or len(value) != 36:
        return False
    try:
        parsed = uuid.UUID(value)
        return str(parsed) == value and parsed.version == 4
    except ValueError:
        return False


def _object_without_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate registry field")
        result[key] = value
    return result


class PairingStore:
    def __init__(self, path: Path, *, clock: Callable[[], float] = time.time):
        self.path = Path(path).expanduser().absolute()
        self.lock_path = self.path.with_name(self.path.name + ".lock")
        self.clock = clock

    @staticmethod
    def _unavailable() -> PairingError:
        return PairingError(503, "Pairing storage is unavailable; check its ownership and permissions locally")

    @staticmethod
    def _invalid_code() -> PairingError:
        # Wrong, expired and already-used challenges deliberately share one error.
        return PairingError(401, "Pairing code is invalid or expired; create a new pairing code on the host")

    def _now(self) -> int:
        value = int(self.clock())
        if value < 0:
            raise self._unavailable()
        return value

    @staticmethod
    def _validate_private_file(fd: int) -> None:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600:
            raise ValueError("Private registry file required")

    @contextmanager
    def _locked(self):
        fd = None
        locked = False
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            parent = self.path.parent.stat()
            if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) & 0o022:
                raise ValueError("Registry directory must belong to the service user and not be shared for writing")
            flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
            try:
                fd = os.open(self.lock_path, flags | os.O_CREAT | os.O_EXCL, 0o600)
                os.fchmod(fd, 0o600)
            except FileExistsError:
                fd = os.open(self.lock_path, flags)
            self._validate_private_file(fd)
            deadline = time.monotonic() + 5
            while True:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    locked = True
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise self._unavailable()
                    time.sleep(0.01)
            yield
        except PairingError:
            raise
        except (OSError, ValueError, UnicodeError, OverflowError, RecursionError) as exc:
            raise self._unavailable() from exc
        finally:
            if fd is not None:
                try:
                    if locked:
                        fcntl.flock(fd, fcntl.LOCK_UN)
                finally:
                    os.close(fd)

    def _read(self) -> dict:
        try:
            fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        except FileNotFoundError:
            return {"version": 1, "challenges": [], "devices": []}
        try:
            self._validate_private_file(fd)
            if os.fstat(fd).st_size > MAX_REGISTRY_BYTES:
                raise ValueError("Registry is too large")
            with os.fdopen(fd, "rb", closefd=False) as file:
                raw = file.read(MAX_REGISTRY_BYTES + 1)
            if len(raw) > MAX_REGISTRY_BYTES:
                raise ValueError("Registry is too large")
            registry = json.loads(raw, object_pairs_hook=_object_without_duplicates)
            self._validate_registry(registry)
            return registry
        finally:
            os.close(fd)

    @staticmethod
    def _validate_registry(registry: object) -> None:
        if not isinstance(registry, dict) or set(registry) != {"version", "challenges", "devices"} or type(registry["version"]) is not int or registry["version"] != 1:
            raise ValueError("Unsupported registry")
        challenges = registry["challenges"]
        devices = registry["devices"]
        if not isinstance(challenges, list) or len(challenges) > MAX_CHALLENGES or not isinstance(devices, list) or len(devices) > MAX_DEVICES:
            raise ValueError("Registry entries exceed limits")
        code_hashes = set()
        for entry in challenges:
            if not isinstance(entry, dict) or set(entry) != {"codeHash", "serverName", "createdAt", "expiresAt"}:
                raise ValueError("Invalid challenge")
            code_hash = entry["codeHash"]
            if not isinstance(code_hash, str) or not HASH_PATTERN.fullmatch(code_hash) or code_hash in code_hashes or not valid_name(entry["serverName"]):
                raise ValueError("Invalid challenge")
            if not _is_timestamp(entry["createdAt"]) or not _is_timestamp(entry["expiresAt"]) or not 1 <= entry["expiresAt"] - entry["createdAt"] <= 300:
                raise ValueError("Invalid challenge lifetime")
            code_hashes.add(code_hash)
        device_ids = set()
        token_hashes = set()
        for entry in devices:
            if not isinstance(entry, dict) or set(entry) != {"deviceId", "tokenHash", "deviceName", "serverName", "createdAt", "revokedAt"}:
                raise ValueError("Invalid device")
            device_id, token_hash = entry["deviceId"], entry["tokenHash"]
            if not _canonical_device_id(device_id) or device_id in device_ids or not isinstance(token_hash, str) or not HASH_PATTERN.fullmatch(token_hash) or token_hash in token_hashes:
                raise ValueError("Invalid device")
            if not valid_name(entry["deviceName"]) or not valid_name(entry["serverName"]) or not _is_timestamp(entry["createdAt"]):
                raise ValueError("Invalid device metadata")
            if entry["revokedAt"] is not None and not _is_timestamp(entry["revokedAt"]):
                raise ValueError("Invalid revocation")
            device_ids.add(device_id)
            token_hashes.add(token_hash)

    def _write(self, registry: dict) -> None:
        self._validate_registry(registry)
        data = json.dumps(registry, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(data) > MAX_REGISTRY_BYTES:
            raise self._unavailable()
        fd, name = tempfile.mkstemp(prefix="." + self.path.name + ".", dir=self.path.parent)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as file:
                fd = None
                file.write(data)
                file.flush()
                os.fsync(file.fileno())
            os.replace(name, self.path)
            directory = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if fd is not None:
                os.close(fd)
            with suppress(FileNotFoundError):
                os.unlink(name)

    def _challenge(self, registry: dict, code: str, now: int) -> dict:
        if not isinstance(code, str) or not SECRET_PATTERN.fullmatch(code):
            raise self._invalid_code()
        supplied = _secret_hash(code)
        match = None
        for entry in registry["challenges"]:
            equal = hmac.compare_digest(supplied, entry["codeHash"])
            if equal and now < entry["expiresAt"]:
                match = entry
        if match is None:
            raise self._invalid_code()
        return match

    def begin_pairing(self, server_name: str, ttl_seconds: int = 300) -> dict:
        if not valid_name(server_name):
            raise PairingError(400, "Server name must be 1–100 nonblank characters without controls")
        if type(ttl_seconds) is not int or not 1 <= ttl_seconds <= 300:
            raise PairingError(400, "Pairing lifetime must be 1–300 seconds")
        with self._locked():
            registry = self._read()
            now = self._now()
            registry["challenges"] = [entry for entry in registry["challenges"] if now < entry["expiresAt"]]
            if len(registry["challenges"]) >= MAX_CHALLENGES:
                raise PairingError(503, "Too many pending pairing codes; wait for an existing code to expire")
            for _attempt in range(5):
                code = secrets.token_urlsafe(32)
                code_hash = _secret_hash(code)
                if not any(hmac.compare_digest(code_hash, entry["codeHash"]) for entry in registry["challenges"]) and not any(
                    hmac.compare_digest(code_hash, entry["tokenHash"]) for entry in registry["devices"]
                ):
                    break
            else:
                raise self._unavailable()
            expires_at = now + ttl_seconds
            registry["challenges"].append({"codeHash": code_hash, "serverName": server_name, "createdAt": now, "expiresAt": expires_at})
            self._write(registry)
            return {"code": code, "expiresAt": expires_at}

    def check_challenge(self, code: str) -> None:
        """Verify without consuming, allowing a live-daemon preflight before exchange."""
        with self._locked():
            self._challenge(self._read(), code, self._now())

    def exchange(self, code: str, device_name: str) -> dict:
        if not valid_name(device_name):
            raise PairingError(400, "Device name must be 1–100 nonblank characters without controls")
        with self._locked():
            registry = self._read()
            now = self._now()
            challenge = self._challenge(registry, code, now)
            if len(registry["devices"]) >= MAX_DEVICES:
                raise PairingError(503, "Device registry is full; review the local registry before pairing")
            for _attempt in range(5):
                token = secrets.token_urlsafe(32)
                token_hash = _secret_hash(token)
                if not any(hmac.compare_digest(token_hash, entry["tokenHash"]) for entry in registry["devices"]) and not any(
                    hmac.compare_digest(token_hash, entry["codeHash"]) for entry in registry["challenges"]
                ):
                    break
            else:
                raise self._unavailable()
            device_id = str(uuid.uuid4())
            registry["devices"].append({
                "deviceId": device_id, "tokenHash": token_hash, "deviceName": device_name,
                "serverName": challenge["serverName"], "createdAt": now, "revokedAt": None,
            })
            registry["challenges"] = [entry for entry in registry["challenges"] if entry is not challenge and now < entry["expiresAt"]]
            self._write(registry)
            return {"service": "herdr-android-bridge", "protocol": 1, "token": token, "deviceId": device_id, "serverName": challenge["serverName"]}

    def authorized(self, token: str) -> bool:
        if not isinstance(token, str) or not SECRET_PATTERN.fullmatch(token):
            return False
        try:
            with self._locked():
                registry = self._read()
                supplied = _secret_hash(token)
                allowed = False
                for entry in registry["devices"]:
                    equal = hmac.compare_digest(supplied, entry["tokenHash"])
                    allowed |= equal and entry["revokedAt"] is None
                return allowed
        except PairingError:
            return False

    def devices(self) -> list[dict]:
        with self._locked():
            registry = self._read()
            return [{key: entry[key] for key in ("deviceId", "deviceName", "serverName", "createdAt", "revokedAt")} for entry in registry["devices"]]

    def revoke(self, device_id: str) -> bool:
        if not _canonical_device_id(device_id):
            return False
        with self._locked():
            registry = self._read()
            for entry in registry["devices"]:
                if entry["deviceId"] == device_id and entry["revokedAt"] is None:
                    entry["revokedAt"] = self._now()
                    self._write(registry)
                    return True
            return False
