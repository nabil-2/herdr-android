"""Explicit, runtime-bound selection for Codex's shared local app-server.

The shared server does not expose which thread each TUI is displaying. Its
inherited Herdr environment is therefore never evidence of that association.
Selection uses loaded root CLI thread metadata and writes directly to that
chosen server/thread, rather than forwarding a prompt to an unrelated PTY.
"""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from contextlib import asynccontextmanager
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import stat
import struct
import sys
import time
import uuid

import anyio
from websockets.asyncio.client import unix_connect
from websockets.exceptions import WebSocketException, ConnectionClosed

from .herdr import BridgeError, HerdrClient
from .message_times import enrich_message_times
from .codex_history import (CodexHistoryTooLarge, HISTORY_PAGED_FIELD, history_rpc_error, read_history)
from .codex_selection_store import CodexSelectionStore, selection_fingerprint

MAX_SELECTIONS = 32
MAX_CANDIDATES = 32
MAX_LOADED_PAGES = 8
MAX_RPC_BYTES = 2 * 1024 * 1024
MAX_HISTORY_RPC_BYTES = 32 * 1024 * 1024
SELECTION_TTL = 5 * 60
UNAVAILABLE = "The selected Codex runtime changed or is unavailable; choose its conversation again"


def canonical_id(value: object) -> bool:
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def _read_bounded(path: Path, limit: int) -> bytes:
    with path.open("rb") as handle:
        value = handle.read(limit + 1)
    if len(value) > limit:
        raise ValueError("Process metadata exceeded its bound")
    return value


def _process(pid: int) -> tuple[list[bytes], list[bytes], dict[str, str]]:
    path = Path("/proc") / str(pid)
    if path.stat().st_uid != os.getuid():
        raise ValueError("Process belongs to another user")
    before = _read_bounded(path / "stat", 4096).rsplit(b")", 1)[1].split()
    argv = _read_bounded(path / "cmdline", 65536).split(b"\0")
    executable = Path(os.readlink(path / "exe")).name
    if executable == "codex":
        args = argv[1:]
    elif len(argv) > 1 and Path(os.fsdecode(argv[1])).name == "codex":
        args = argv[2:]
    else:
        raise ValueError("Foreground process is not Codex")
    environment = {}
    for item in _read_bounded(path / "environ", 262144).split(b"\0"):
        name, separator, value = item.partition(b"=")
        if separator and name in (b"CODEX_HOME", b"HERDR_PANE_ID", b"HERDR_SOCKET_PATH"):
            environment[name.decode()] = os.fsdecode(value)
    after = _read_bounded(path / "stat", 4096).rsplit(b")", 1)[1].split()
    if len(before) < 20 or len(after) < 20 or before[19] != after[19]:
        raise ValueError("Process changed while inspected")
    return after, args, environment


def _socket_inodes(pid: int) -> set[int]:
    result = set()
    for count, descriptor in enumerate((Path("/proc") / str(pid) / "fd").iterdir()):
        if count >= 512:
            raise ValueError("Process has too many descriptors")
        try:
            value = os.readlink(descriptor)
            if value.startswith("socket:[") and value.endswith("]"):
                result.add(int(value[8:-1]))
        except (OSError, ValueError):
            continue
    return result


def _unix_peers(wanted: set[int]) -> dict[int, int]:
    """Linux socket metadata only: no reads from a frontend's protocol stream."""
    request = struct.pack("=BBHIIIII", socket.AF_UNIX, 0, 0, 0xFFFFFFFF, 0, 4, 0xFFFFFFFF, 0xFFFFFFFF)
    result = {}
    deadline = time.monotonic() + 1
    with socket.socket(socket.AF_NETLINK, socket.SOCK_RAW, 4) as diagnostic:
        diagnostic.settimeout(1)
        diagnostic.send(struct.pack("=IHHII", 16 + len(request), 20, 0x301, 1, 0) + request)
        received = 0
        while received <= 4 * 1024 * 1024 and time.monotonic() < deadline:
            packet = diagnostic.recv(262144)
            received += len(packet)
            offset = 0
            while offset + 16 <= len(packet):
                length, kind, _, sequence, _ = struct.unpack_from("=IHHII", packet, offset)
                if length < 16 or offset + length > len(packet) or sequence != 1:
                    raise ValueError("Invalid socket diagnostic metadata")
                if kind == 3:
                    return result
                if kind == 2:
                    raise ValueError("Socket diagnostic metadata unavailable")
                if kind == 20:
                    body = packet[offset + 16:offset + length]
                    if len(body) < 16:
                        raise ValueError("Invalid socket diagnostic metadata")
                    family, _, state, _, inode, _, _ = struct.unpack_from("=BBBBIII", body)
                    if family == socket.AF_UNIX and state == 1 and inode in wanted:
                        attribute = 16
                        while attribute + 4 <= len(body):
                            size, attribute_type = struct.unpack_from("=HH", body, attribute)
                            if size < 4 or attribute + size > len(body):
                                raise ValueError("Invalid socket peer metadata")
                            if attribute_type == 2 and size == 8:
                                result[inode] = struct.unpack_from("=I", body, attribute + 4)[0]
                            attribute += (size + 3) & ~3
                offset += (length + 3) & ~3
    raise ValueError("Socket diagnostics exceeded their bound")


def _daemon_socket(home: Path) -> Path | None:
    # The official rendezvous is a deterministic symlink into this user's
    # private /tmp/codex-daemon-UID directory; arbitrary symlinks are rejected.
    home = home.resolve(strict=True)
    if home.stat().st_uid != os.getuid() or not home.is_dir():
        raise ValueError("Codex home belongs to another user")
    directory = home / "app-server-control"
    try:
        info = directory.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
        raise ValueError("Unsafe Codex control directory")
    rendezvous = directory / "app-server-control.sock"
    try:
        info = rendezvous.lstat()
    except FileNotFoundError:
        return None
    if info.st_uid != os.getuid():
        raise ValueError("Codex socket belongs to another user")
    if stat.S_ISLNK(info.st_mode):
        private = Path("/tmp").resolve() / f"codex-daemon-{os.getuid()}"
        private_info = private.lstat()
        if (not stat.S_ISDIR(private_info.st_mode) or private_info.st_uid != os.getuid()
                or stat.S_IMODE(private_info.st_mode) != 0o700):
            raise ValueError("Unsafe Codex daemon directory")
        expected = private / hashlib.sha256(os.fsencode(rendezvous)).hexdigest()
        if os.readlink(rendezvous) != str(expected):
            raise ValueError("Unexpected Codex daemon socket alias")
        path = expected
    elif stat.S_ISSOCK(info.st_mode):
        path = rendezvous
    else:
        raise ValueError("Codex control path is not a socket")
    info = path.lstat()
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError("Unsafe Codex daemon socket")
    return path


@dataclass(frozen=True)
class DaemonRuntime:
    pane_id: str
    terminal_id: str
    frontend_pid: int
    frontend_start: int
    frontend_socket: int
    daemon_socket: int
    daemon_pid: int
    daemon_start: int
    socket_path: Path
    socket_device: int
    socket_inode: int
    home: Path
    herdr_device: int
    herdr_inode: int

    @property
    def identity(self) -> str:
        values = [str(value) for value in self.__dict__.values()]
        return hashlib.sha256(json.dumps(values, separators=(",", ":")).encode()).hexdigest()


def _inspect_runtime(client: HerdrClient, home: Path, agent: dict, metadata: dict) -> DaemonRuntime | None:
    processes = metadata.get("foreground_processes")
    if not isinstance(processes, list):
        return None
    candidates = [item for item in processes if isinstance(item, dict) and item.get("name") == "codex"
                  and type(item.get("pid")) is int and item["pid"] > 0]
    if len(candidates) != 1:
        return None
    pid = candidates[0]["pid"]
    details, args, environment = _process(pid)
    group = metadata.get("foreground_process_group_id")
    if (type(group) is not int or int(details[2]) != group or int(details[5]) != group
            or int(details[4]) == 0 or environment.get("HERDR_PANE_ID") != agent["pane_id"]
            or Path(environment.get("HERDR_SOCKET_PATH", "")).absolute() != client.socket_path.absolute()):
        raise ValueError("Codex foreground pane could not be verified")
    if b"--no-daemon" in args or b"--remote" in args or any(value.startswith(b"--remote=") for value in args):
        return None
    configured_home = Path(environment.get("CODEX_HOME", Path.home() / ".codex")).expanduser().resolve(strict=True)
    if configured_home != home.resolve(strict=True):
        raise ValueError("Foreground Codex uses a different home")
    path = _daemon_socket(home)
    if path is None:
        return None
    info = path.lstat()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(0.5)
        connection.connect(str(path))
        daemon_pid, uid, _ = struct.unpack("=iii", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
    if uid != os.getuid() or daemon_pid <= 1:
        raise ValueError("Daemon peer belongs to another user")
    daemon_details, daemon_args, daemon_environment = _process(daemon_pid)
    if (not daemon_args or daemon_args[0] != b"app-server" or int(daemon_details[4]) != 0
            or Path(daemon_environment.get("CODEX_HOME", Path.home() / ".codex")).expanduser().resolve(strict=True) != configured_home):
        raise ValueError("Socket peer is not the selected local Codex daemon")
    frontend_sockets, daemon_sockets = _socket_inodes(pid), _socket_inodes(daemon_pid)
    peers = _unix_peers(frontend_sockets)
    matches = [(source, target) for source, target in peers.items() if target in daemon_sockets]
    if len(matches) != 1:
        # A shared TUI can reconnect while socket metadata is sampled. Never
        # fall back to its daemon-inherited hook UUID during that transition.
        raise ValueError("Shared Codex peer connection is not ready")
    after, _, _ = _process(pid)
    if after[19] != details[19] or int(after[2]) != group or int(after[5]) != group:
        raise ValueError("Foreground Codex changed while inspected")
    current = path.lstat()
    if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
        raise ValueError("Codex socket changed while inspected")
    herdr = client.socket_path.stat()
    return DaemonRuntime(agent["pane_id"], agent["terminal_id"], pid, int(details[19]), *matches[0], daemon_pid,
                         int(daemon_details[19]), path, info.st_dev, info.st_ino, configured_home,
                         herdr.st_dev, herdr.st_ino)


class DaemonSession:
    """A separate connection to the existing owned daemon, never a sidecar."""

    def __init__(self, runtime: DaemonRuntime):
        self.runtime = runtime
        self.connection = None
        self.next_id = 0

    async def open(self) -> None:
        peer = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        peer.setblocking(False)
        try:
            await asyncio.get_running_loop().sock_connect(peer, str(self.runtime.socket_path))
            pid, uid, _ = struct.unpack("=iii", peer.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if pid != self.runtime.daemon_pid or uid != os.getuid():
                raise BridgeError(409, UNAVAILABLE)
            info = self.runtime.socket_path.lstat()
            details, _, _ = await asyncio.to_thread(_process, pid)
            if ((info.st_dev, info.st_ino) != (self.runtime.socket_device, self.runtime.socket_inode)
                    or int(details[19]) != self.runtime.daemon_start):
                raise BridgeError(409, UNAVAILABLE)
            # Codex's Unix control socket upgrades to WebSocket, unlike its
            # separate stdio transport. Reuse this exact peer-verified socket.
            self.connection = await unix_connect(sock=peer, uri="ws://localhost/", open_timeout=2,
                                                  close_timeout=1, max_size=MAX_HISTORY_RPC_BYTES,
                                                  max_queue=1, ping_interval=None, compression=None)
        except BaseException:
            peer.close()
            raise
        await self.rpc("initialize", {"clientInfo": {"name": "herdr_pocket_selected", "title": "Herdr Pocket", "version": "1"},
                                      "capabilities": {"experimentalApi": True}})
        await self.connection.send('{"method":"initialized","params":{}}')

    async def rpc(self, method: str, params: dict) -> dict:
        if method not in {"initialize", "thread/loaded/list", "thread/read", "thread/turns/list", "turn/start", "turn/steer"}:
            raise ValueError("Unsupported selected-daemon operation")
        assert self.connection is not None
        self.next_id += 1
        request_id = self.next_id
        await self.connection.send(json.dumps({"id": request_id, "method": method, "params": params}, separators=(",", ":")))
        skipped = 0
        history_page = (method == "thread/read" and params.get("includeTurns") is True
                        or method == "thread/turns/list" and params.get("itemsView") in {"full", "summary"})
        limit = MAX_HISTORY_RPC_BYTES if history_page else MAX_RPC_BYTES
        for _ in range(128):
            try:
                raw = await self.connection.recv()
            except ConnectionClosed as exc:
                if history_page and exc.sent is not None and exc.sent.code == 1009:
                    raise CodexHistoryTooLarge() from exc
                raise
            size = len(raw.encode("utf-8")) if isinstance(raw, str) else len(raw)
            skipped += size
            if size > limit and history_page:
                raise CodexHistoryTooLarge()
            if not raw or size > limit or skipped > limit + 4 * MAX_RPC_BYTES:
                raise BridgeError(503, "Codex daemon response exceeded its limits")
            response = json.loads(raw)
            if not isinstance(response, dict):
                raise ValueError("Invalid daemon response")
            if "method" in response:
                # No approval, tool or input request is answered automatically.
                if "id" in response:
                    raise BridgeError(503, "Codex needs attention in its existing Terminal")
                continue
            if type(response.get("id")) is not int or response["id"] != request_id:
                raise ValueError("Daemon response did not match its request")
            if "error" in response:
                semantic = history_rpc_error(method, params, response["error"])
                if semantic is not None:
                    raise semantic
                raise BridgeError(503 if method in {"turn/start", "turn/steer"} else 409,
                                  "Codex could not confirm the selected operation; refresh or inspect its existing Terminal")
            if not isinstance(response.get("result"), dict):
                raise ValueError("Invalid daemon result")
            return response["result"]
        raise BridgeError(503, "Codex daemon response exceeded its limits")

    async def close(self) -> None:
        if self.connection is not None:
            with anyio.CancelScope(shield=True):
                try:
                    async with asyncio.timeout(1):
                        await self.connection.close()
                except (OSError, TimeoutError, WebSocketException):
                    pass


@dataclass(frozen=True)
class SelectionOffer:
    owner: str
    runtime: DaemonRuntime
    candidates: frozenset[str]
    expires_at: float


def thread_metadata(thread: object, expected_id: str) -> dict | None:
    if (not isinstance(thread, dict) or thread.get("id") != expected_id or not canonical_id(expected_id)
            or thread.get("sessionId") != expected_id or thread.get("parentThreadId") is not None
            # source is the creation origin, not the currently attached client.
            # A CLI `resume` of a VS Code/desktop chat retains source=vscode.
            or thread.get("source") not in {"cli", "vscode"} or thread.get("canAcceptDirectInput") is not True
            or thread.get("ephemeral") is not False or thread.get("turns") != []):
        return None
    value = thread.get("status")
    if not isinstance(value, dict):
        return None
    kind = value.get("type")
    if kind == "idle":
        status = "idle"
    elif kind == "active" and isinstance(value.get("activeFlags"), list):
        flags = value["activeFlags"]
        status = "working" if flags == [] else "blocked"
    elif kind == "systemError":
        status = "blocked"
    else:
        return None
    created = thread.get("createdAt")
    if type(created) is not int or not 0 <= created <= 253402300799:
        return None
    name = thread.get("name")
    title = name.strip() if isinstance(name, str) else ""
    title = "".join(character for character in title if ord(character) >= 32 and ord(character) != 127)[:160]
    # preview is deliberately never consumed: it is conversation content.
    return {"id": expected_id, "title": title or f"Codex {expected_id[:8]}", "createdAt": created, "status": status}


class DaemonConversations:
    def __init__(self, client: HerdrClient, home: Path, *, clock=time.monotonic, selection_root: Path | None = None):
        self.client, self.home, self.clock = client, home, clock
        self.offers: OrderedDict[str, SelectionOffer] = OrderedDict()
        self.selections: OrderedDict[tuple[str, str], tuple[DaemonRuntime, str]] = OrderedDict()
        self.selection_digests: dict[tuple[str, str], str] = {}
        self.selection_store = CodexSelectionStore(selection_root) if selection_root is not None else None
        self.lock = asyncio.Lock()
        self.paged_threads: OrderedDict[tuple[str, str], None] = OrderedDict()

    async def same_runtime(self, first: DaemonRuntime, second: DaemonRuntime) -> bool:
        # Every caller has freshly verified the actual peer connection. Its
        # reconnecting endpoints are not the lifetime of the TUI or daemon.
        fingerprints = await asyncio.to_thread(lambda: (selection_fingerprint(first), selection_fingerprint(second)))
        return fingerprints[0] is not None and fingerprints[0] == fingerprints[1]

    def _retain(self, key: tuple[str, str], runtime: DaemonRuntime, thread_id: str, digest: str) -> None:
        self.selections[key] = runtime, thread_id
        self.selections.move_to_end(key)
        self.selection_digests[key] = digest
        while len(self.selections) > MAX_SELECTIONS:
            victim, _ = self.selections.popitem(last=False)
            self.selection_digests.pop(victim, None)

    async def _remembered(self, runtime: DaemonRuntime, owner: str) -> tuple[DaemonRuntime, str] | None:
        # Selection acknowledgement and restart recovery share one ordering
        # lock. A slow disk/RPC restore cannot overwrite a newer user choice.
        async with self.lock:
            return await self._remembered_locked(runtime, owner)

    async def _remembered_locked(self, runtime: DaemonRuntime, owner: str) -> tuple[DaemonRuntime, str] | None:
        key = owner, runtime.pane_id
        selected = self.selections.get(key)
        if selected is not None:
            if await self.same_runtime(selected[0], runtime):
                return selected
            # A proven process, terminal, daemon or host replacement cannot
            # inherit a prior choice, even when the same thread stays loaded.
            self.selections.pop(key, None)
            self.selection_digests.pop(key, None)
            if self.selection_store is not None:
                await self.selection_store.forget(*key)
            return None
        if self.selection_store is not None:
            fingerprint = await asyncio.to_thread(selection_fingerprint, runtime)
            if fingerprint is not None:
                record = await self.selection_store.load(*key, fingerprint)
                if record is not None:
                    thread_id, digest = record
                    async with self.session(runtime) as session:
                        if thread_id not in await self.loaded(session):
                            return None
                        await self.metadata(session, thread_id)
                    self._retain(key, runtime, thread_id, digest)
                    return self.selections[key]
        return None

    async def probe(self, agent: dict) -> DaemonRuntime | None:
        # Unix peer/process proof is Linux-specific. Other supported hosts keep
        # their existing pane-local Codex flow and never advertise a selector.
        if not sys.platform.startswith("linux"):
            return None
        if not isinstance(agent.get("pane_id"), str) or not isinstance(agent.get("terminal_id"), str):
            return None
        try:
            response = await self.client.call("pane.process_info", {"pane_id": agent["pane_id"]})
            metadata = response.get("process_info")
            if not isinstance(metadata, dict) or metadata.get("pane_id") != agent["pane_id"]:
                return None
            return await asyncio.to_thread(_inspect_runtime, self.client, self.home, agent, metadata)
        except (OSError, ValueError, IndexError, UnicodeError):
            raise BridgeError(409, "This Codex runtime could not be verified; use its existing Terminal") from None

    @asynccontextmanager
    async def session(self, runtime: DaemonRuntime):
        connection = DaemonSession(runtime)
        try:
            async with asyncio.timeout(8):
                await connection.open()
                yield connection
        except (OSError, TimeoutError, ValueError, UnicodeError, WebSocketException) as exc:
            raise BridgeError(503, "The selected Codex daemon could not be reached; refresh or inspect Terminal") from exc
        finally:
            await connection.close()

    async def loaded(self, session: DaemonSession) -> set[str]:
        loaded = set()
        cursor = None
        for _ in range(MAX_LOADED_PAGES):
            params = {"limit": MAX_CANDIDATES}
            if cursor is not None:
                params["cursor"] = cursor
            result = await session.rpc("thread/loaded/list", params)
            ids = result.get("data")
            next_cursor = result.get("nextCursor")
            if (not isinstance(ids, list) or len(ids) > MAX_CANDIDATES
                    or any(not canonical_id(value) for value in ids) or len(ids) != len(set(ids))
                    or any(value in loaded for value in ids)
                    or next_cursor is not None and (not ids or next_cursor != ids[-1] or next_cursor == cursor)):
                raise BridgeError(503, "Codex returned incompatible loaded conversation metadata")
            loaded.update(ids)
            if next_cursor is None:
                break
            cursor = next_cursor
        return loaded

    async def metadata(self, session: DaemonSession, thread_id: str) -> dict:
        result = await session.rpc("thread/read", {"threadId": thread_id, "includeTurns": False})
        metadata = thread_metadata(result.get("thread"), thread_id)
        if metadata is None:
            raise BridgeError(409, "This conversation is no longer a loaded primary Codex chat; choose again")
        return metadata

    async def list(self, pane_id: str, owner: str) -> dict:
        agent = await self.client.agent(pane_id)
        runtime = await self.probe(agent)
        if runtime is None:
            raise BridgeError(409, "This pane does not use a shared local Codex daemon")
        candidates = []
        async with self.session(runtime) as session:
            for thread_id in sorted(await self.loaded(session)):
                try:
                    candidates.append(await self.metadata(session, thread_id))
                except BridgeError as error:
                    if error.status != 409:
                        raise
        # Recheck the exact frontend, daemon, terminal and socket after RPCs.
        current = await self.probe(await self.client.agent(pane_id))
        if current != runtime:
            raise BridgeError(409, UNAVAILABLE)
        now = self.clock()
        for token in list(self.offers):
            if self.offers[token].expires_at <= now:
                self.offers.pop(token)
        token = secrets.token_urlsafe(32)
        self.offers[token] = SelectionOffer(owner, runtime, frozenset(item["id"] for item in candidates), now + SELECTION_TTL)
        while len(self.offers) > MAX_SELECTIONS:
            self.offers.popitem(last=False)
        candidates.sort(key=lambda item: (item["createdAt"], item["id"]), reverse=True)
        candidates = candidates[:MAX_CANDIDATES]
        # Offer only displayed IDs, never an undisplayed caller-supplied UUID.
        self.offers[token] = SelectionOffer(owner, runtime, frozenset(item["id"] for item in candidates), now + SELECTION_TTL)
        return {"conversations": candidates, "selectionToken": token}

    async def select(self, pane_id: str, owner: str, thread_id: str, token: str) -> dict:
        async with self.lock:
            offer = self.offers.pop(token, None)
            if (offer is None or offer.owner != owner or offer.runtime.pane_id != pane_id
                    or offer.expires_at <= self.clock() or thread_id not in offer.candidates):
                raise BridgeError(409, "Conversation choices expired or changed; load them again")
            runtime = await self.probe(await self.client.agent(pane_id))
            if runtime != offer.runtime:
                raise BridgeError(409, UNAVAILABLE)
            async with self.session(runtime) as session:
                if thread_id not in await self.loaded(session):
                    raise BridgeError(409, UNAVAILABLE)
                await self.metadata(session, thread_id)
            self._retain((owner, pane_id), runtime, thread_id, runtime.identity)
            if self.selection_store is not None:
                fingerprint = await asyncio.to_thread(selection_fingerprint, runtime)
                if fingerprint is not None:
                    await self.selection_store.remember(owner, pane_id, fingerprint, thread_id, runtime.identity)
            return {"ok": True}

    async def target(self, agent: dict, owner: str) -> tuple[dict, str] | None:
        runtime = await self.probe(agent)
        key = owner, agent["pane_id"]
        selected = self.selections.get(key)
        if runtime is None:
            if selected is not None:
                raise BridgeError(409, UNAVAILABLE)
            return None
        selected = await self._remembered(runtime, owner)
        if selected is None:
            raise BridgeError(409, "Choose the conversation shown in this Codex Terminal before opening chat")
        thread_id = selected[1]
        digest = self.selection_digests.get(key, selected[0].identity)
        async with self.session(runtime) as session:
            if thread_id not in await self.loaded(session):
                raise BridgeError(409, UNAVAILABLE)
            metadata = await self.metadata(session, thread_id)
        mapped = dict(agent)
        mapped.update(agent_status=metadata["status"], interactive_ready=True, launch_pending=False,
                      _pocket_daemon_selected=True, _pocket_daemon_identity=digest)
        return mapped, thread_id

    async def read(self, pane_id: str, owner: str, thread_id: str) -> dict:
        selected = self.selections.get((owner, pane_id))
        if selected is None or selected[1] != thread_id:
            raise BridgeError(409, UNAVAILABLE)
        runtime = await self.probe(await self.client.agent(pane_id))
        if runtime is None or not await self.same_runtime(runtime, selected[0]):
            raise BridgeError(409, UNAVAILABLE)
        async with self.session(runtime) as session:
            if thread_id not in await self.loaded(session):
                raise BridgeError(409, UNAVAILABLE)
            await self.metadata(session, thread_id)
            key = self.selection_digests.get((owner, pane_id), selected[0].identity), thread_id

            async def history_rpc(method: str, params: dict) -> dict:
                try:
                    return await session.rpc(method, params)
                except CodexHistoryTooLarge:
                    # Retire only our reading connection; other TUI listeners
                    # and their approvals remain attached to the live daemon.
                    await session.close()
                    await session.open()
                    self.paged_threads[key] = None
                    self.paged_threads.move_to_end(key)
                    while len(self.paged_threads) > MAX_SELECTIONS:
                        self.paged_threads.popitem(last=False)
                    raise

            result = await read_history(history_rpc, thread_id, prefer_pages=key in self.paged_threads)
        await enrich_message_times(result, thread_id, runtime.home)
        return result

    async def decorate_snapshot(self, snapshot: dict, owner: str) -> None:
        semaphore = asyncio.Semaphore(4)

        async def decorate(pane: dict):
            async with semaphore:
                try:
                    agent = await self.client.agent(pane["id"])
                    runtime = await self.probe(agent)
                    if runtime is None:
                        return
                    selected = await self._remembered(runtime, owner)
                    available = selected is not None
                    pane["conversation"] = {"provider": "codex", "available": available, "selectable": True,
                                            "reason": None if available else "Choose the conversation shown in this Codex Terminal"}
                except BridgeError:
                    # A failed runtime proof must never expose a potentially
                    # inherited daemon hook ID as an available conversation.
                    pane["conversation"] = {"provider": "codex", "available": False,
                                            "reason": "This Codex runtime could not be verified; use Terminal"}

        candidates = [pane for pane in snapshot["panes"] if pane.get("conversation", {}).get("provider") == "codex"]
        await asyncio.gather(*(decorate(pane) for pane in candidates[:MAX_SELECTIONS]))
        for pane in candidates[MAX_SELECTIONS:]:
            pane["conversation"] = {"provider": "codex", "available": False, "reason": "Too many Codex panes to verify; use Terminal"}

    async def deliver(self, pane_id: str, owner: str, thread_id: str, text: str, images: list[Path]) -> bool:
        async with self.lock:
            selected = self.selections.get((owner, pane_id))
            if selected is None:
                return False
            runtime = await self.probe(await self.client.agent(pane_id))
            if runtime is None or not await self.same_runtime(runtime, selected[0]) or thread_id != selected[1]:
                raise BridgeError(409, UNAVAILABLE)
            async with self.session(runtime) as session:
                if thread_id not in await self.loaded(session):
                    raise BridgeError(409, UNAVAILABLE)
                metadata = await self.metadata(session, thread_id)
                if metadata["status"] not in {"idle", "working"}:
                    raise BridgeError(409, "Codex needs attention in its existing Terminal before sending")
                inputs = [{"type": "text", "text": text, "textElements": []}]
                inputs.extend({"type": "localImage", "path": str(path)} for path in images)
                params = {"threadId": thread_id, "input": inputs, "clientUserMessageId": str(uuid.uuid4())}
                method = "turn/start"
                expected = None
                if metadata["status"] == "working":
                    turns = await session.rpc("thread/turns/list", {"threadId": thread_id, "limit": 1,
                                                                   "sortDirection": "desc", "itemsView": "notLoaded"})
                    data = turns.get("data")
                    if (not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict)
                            or data[0].get("status") != "inProgress" or not canonical_id(data[0].get("id"))
                            or data[0].get("items") != []):
                        raise BridgeError(409, "Codex's active turn changed; refresh before sending")
                    expected = data[0]["id"]
                    params["expectedTurnId"] = expected
                    method = "turn/steer"
                # From this point every failure is delivery uncertainty. There
                # is no retry, alternate method, sidecar write or PTY fallback.
                try:
                    result = await session.rpc(method, params)
                    if method == "turn/steer":
                        confirmed = result.get("turnId") == expected
                    else:
                        turn = result.get("turn")
                        confirmed = isinstance(turn, dict) and canonical_id(turn.get("id")) and turn.get("status") in {"inProgress", "completed"}
                    if not confirmed:
                        raise ValueError("Invalid turn acknowledgement")
                except (BridgeError, OSError, TimeoutError, ValueError, UnicodeError, WebSocketException) as error:
                    raise BridgeError(503, "Message delivery was not confirmed; inspect the selected conversation before sending again") from error
                return True
