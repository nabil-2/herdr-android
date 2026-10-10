"""Read saved Codex conversations without loading or controlling their agents."""

from __future__ import annotations

import asyncio
import base64
from collections import OrderedDict
from dataclasses import dataclass, field
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import shlex
import time

import anyio

from .herdr import BridgeError, CreatedTab, HerdrClient, canonical_codex_session, stop_observer
from .message_times import ITEM_TIME_FIELD, MAX_TIMESTAMP, enrich_message_times
from .runtime import external_environment
from .attachments import AttachmentStore
from .codex_identity import DaemonConversations

MAX_RPC_BYTES = 32 * 1024 * 1024
MAX_PAGE_BYTES = 1024 * 1024
MAX_TEXT_BYTES = 256 * 1024
PAGE_SIZE = 50
MAX_CACHES = 32
MAX_CACHE_BYTES = 64 * 1024 * 1024
FRESH_CHAT_TTL_SECONDS = 30 * 60
TRUNCATED = "\n[Message truncated; open Terminal to inspect the full output]"


def opaque(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def bounded_text(text: str) -> tuple[str, bool]:
    # Strip active terminal controls from native text; retain meaningful line/tab spacing.
    text = "".join(c for c in text if c in "\n\t" or ord(c) >= 32 and ord(c) != 127)
    encoded = text.encode("utf-8")
    if len(encoded) <= MAX_TEXT_BYTES:
        return text, False
    return encoded[:MAX_TEXT_BYTES - len(TRUNCATED.encode())].decode("utf-8", errors="ignore") + TRUNCATED, True


def normalize_thread(result: dict, thread_id: str) -> tuple[list[dict], str | None]:
    """Only UI-authored messages: never raw model context, tools, hooks or reasoning."""
    try:
        thread = result["thread"]
        if not isinstance(thread, dict) or thread["id"] != thread_id or not isinstance(thread["turns"], list):
            raise ValueError()
        messages = []
        seen = set()
        truncated = False
        for turn in thread["turns"]:
            if not isinstance(turn, dict) or not isinstance(turn.get("id"), str) or not 1 <= len(turn["id"]) <= 1024 or not isinstance(turn.get("items"), list):
                raise ValueError()
            # App-server supplies turn times, not exact item times. Keep their
            # provenance and never substitute the clock or the thread creation time.
            started = turn.get("startedAt")
            completed = turn.get("completedAt")
            started = started if type(started) is int and 0 <= started <= 253402300799 else None
            completed = completed if type(completed) is int and 0 <= completed <= 253402300799 else None
            for item in turn["items"]:
                if not isinstance(item, dict):
                    raise ValueError()
                kind = item.get("type")
                if kind not in {"userMessage", "agentMessage"}:
                    continue
                item_id = item.get("id")
                if not isinstance(item_id, str) or not item_id or len(item_id) > 1024:
                    raise ValueError()
                identity = (turn["id"], item_id)
                if identity in seen:
                    raise ValueError()
                seen.add(identity)
                phase = None
                if kind == "userMessage":
                    content = item.get("content")
                    if not isinstance(content, list):
                        raise ValueError()
                    parts = []
                    for part in content:
                        if not isinstance(part, dict):
                            raise ValueError()
                        if part.get("type") == "text":
                            if not isinstance(part.get("text"), str):
                                raise ValueError()
                            parts.append(part["text"])
                    text = "\n".join(parts)
                else:
                    text = item.get("text")
                    if not isinstance(text, str):
                        raise ValueError()
                    phase = item.get("phase")
                    if phase not in {None, "commentary", "final_answer"}:
                        phase = None
                text, cut = bounded_text(text)
                truncated |= cut
                if not text:
                    continue
                message_id = opaque(hashlib.sha256(json.dumps(identity).encode()).digest())
                message = {"id": message_id, "role": "user" if kind == "userMessage" else "assistant", "text": text, "phase": phase}
                timestamp = completed if phase == "final_answer" and completed is not None else started
                if timestamp is not None:
                    message.update(timestamp=timestamp, timestampSource="turn_end" if phase == "final_answer" and completed is not None else "turn_start")
                recorded = item.get(ITEM_TIME_FIELD)
                if type(recorded) is int and 0 <= recorded <= MAX_TIMESTAMP:
                    message["messageTimestamp"] = recorded
                messages.append(message)
        return messages, "Some messages were truncated; use Terminal for complete output" if truncated else None
    except (KeyError, TypeError, ValueError, UnicodeError) as exc:
        raise BridgeError(503, "Codex returned incompatible saved history; use Terminal") from exc


class CodexReader:
    """A bounded stdio client with a hard-coded, read-only RPC allowlist."""

    def __init__(self, executable: str | None, home: Path | None = None, search_path: str | None = None, *, timeout: float = 10):
        self.executable = shutil.which(executable, path=search_path) if executable else None
        self.home = (home or Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))).expanduser().absolute()
        self.search_path = search_path
        self.timeout = timeout
        self.process: asyncio.subprocess.Process | None = None
        self.stderr_task: asyncio.Task | None = None
        self.lock = asyncio.Lock()
        self.next_id = 0

    @property
    def enabled(self) -> bool:
        return self.executable is not None

    async def _rpc(self, method: str, params: dict) -> dict:
        if method not in {"initialize", "thread/read"}:
            raise ValueError("Codex adapter permits only initialization and saved-history reads")
        assert self.process and self.process.stdin and self.process.stdout
        self.next_id += 1
        request_id = self.next_id
        self.process.stdin.write(json.dumps({"id": request_id, "method": method, "params": params}, separators=(",", ":")).encode() + b"\n")
        await self.process.stdin.drain()
        while True:
            line = await self.process.stdout.readline()
            if not line or len(line) > MAX_RPC_BYTES:
                raise BridgeError(503, "Codex saved history is unavailable or too large; use Terminal")
            response = json.loads(line)
            if not isinstance(response, dict):
                raise ValueError()
            # Notifications (including deprecation notices) are never forwarded.
            if "method" in response:
                if "id" in response:
                    raise ValueError("Unexpected server request")
                continue
            if response.get("id") != request_id:
                raise BridgeError(503, "Codex could not read this saved conversation; use Terminal")
            if "error" in response:
                error = response["error"]
                thread_id = params.get("threadId")
                # These exact 0.162 errors mean there is no stored thread in this
                # read-only sidecar. They prove an empty first conversation only
                # together with a separately confirmed, fresh creation receipt.
                if (method == "thread/read" and isinstance(thread_id, str) and isinstance(error, dict)
                        and type(error.get("code")) is int and error["code"] == -32600
                        and error.get("message") in (f"thread not loaded: {thread_id}",
                                                      f"no rollout found for thread id {thread_id}")):
                    raise CodexHistoryNotPersisted(thread_id)
                raise BridgeError(503, "Codex could not read this saved conversation; use Terminal")
            if not isinstance(response.get("result"), dict):
                raise BridgeError(503, "Codex could not read this saved conversation; use Terminal")
            return response["result"]

    async def read(self, thread_id: str) -> dict:
        try:
            # Include waiting for the shared RPC channel in the caller's deadline.
            async with asyncio.timeout(self.timeout):
                return await self._read(thread_id)
        except TimeoutError as exc:
            raise BridgeError(503, "Codex saved-history reader timed out; use Terminal") from exc

    async def _read(self, thread_id: str) -> dict:
        async with self.lock:
            try:
                async with asyncio.timeout(self.timeout):
                    if self.process is None or self.process.returncode is not None:
                        if not self.enabled:
                            raise BridgeError(503, "Codex conversation reader is not configured; use Terminal")
                        info = self.home.stat()
                        if not self.home.is_dir() or info.st_uid != os.getuid():
                            raise BridgeError(503, "Codex home must belong to the bridge user; use Terminal")
                        env = external_environment()
                        env["CODEX_HOME"] = str(self.home)
                        if self.search_path is not None:
                            env["PATH"] = self.search_path
                        for name in ("CODEX_THREAD_ID", "CODEX_SESSION_ID", "HERDR_PANE_ID", "HERDR_ENV"):
                            env.pop(name, None)
                        self.process = await asyncio.create_subprocess_exec(
                            self.executable, "app-server", "--listen", "stdio://",
                            env=env, cwd=str(self.home), stdin=asyncio.subprocess.PIPE,
                            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                            limit=MAX_RPC_BYTES + 1,
                        )

                        async def drain_stderr():
                            assert self.process and self.process.stderr
                            while await self.process.stderr.read(65536):
                                pass

                        self.stderr_task = asyncio.create_task(drain_stderr())
                        await self._rpc("initialize", {"clientInfo": {"name": "herdr_pocket_reader", "title": "Herdr Pocket", "version": "1"}})
                        assert self.process.stdin
                        self.process.stdin.write(b'{"method":"initialized","params":{}}\n')
                        await self.process.stdin.drain()
                    result = await self._rpc("thread/read", {"threadId": thread_id, "includeTurns": True})
                    await enrich_message_times(result, thread_id, self.home)
                    return result
            except CodexHistoryNotPersisted:
                # The request completed normally; the RPC channel remains usable.
                raise
            except asyncio.CancelledError:
                await self._stop()
                raise
            except (BridgeError, OSError, TimeoutError, ValueError, UnicodeError) as exc:
                await self._stop()
                if isinstance(exc, BridgeError):
                    raise
                raise BridgeError(503, "Codex saved history is unavailable or exceeds reader limits; use Terminal") from exc

    async def _stop(self) -> None:
        with anyio.CancelScope(shield=True):
            process, self.process = self.process, None
            if self.stderr_task:
                self.stderr_task.cancel()
                await asyncio.gather(self.stderr_task, return_exceptions=True)
                self.stderr_task = None
            if process:
                await stop_observer(process)

    async def close(self) -> None:
        async with self.lock:
            await self._stop()


@dataclass
class ConversationCache:
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    refreshed_at: float = float("-inf")
    messages: list[dict] = field(default_factory=list)
    revision: str = ""
    warning: str | None = None
    error: BridgeError | None = None
    history_epoch: str = field(default_factory=lambda: secrets.token_urlsafe(32))
    byte_size: int = 0
    bootstrapping: bool = False


class CodexHistoryNotPersisted(BridgeError):
    def __init__(self, thread_id: str):
        super().__init__(503, "Codex has not saved this conversation yet; use Terminal")
        self.thread_id = thread_id


@dataclass
class FreshChatReceipt:
    tab: CreatedTab
    name: str
    socket_identity: tuple[int, int]
    expires_at: float
    thread_id: str | None = None


class Conversations:
    def __init__(self, client: HerdrClient, reader: CodexReader, *, clock=time.monotonic):
        self.client, self.reader, self.clock = client, reader, clock
        self.secret = secrets.token_bytes(32)
        self.caches: OrderedDict[str, ConversationCache] = OrderedDict()
        self.send_lock = asyncio.Lock()
        self.fresh_chats: OrderedDict[str, FreshChatReceipt] = OrderedDict()
        self.attachments: AttachmentStore | None = None
        self.daemon: DaemonConversations | None = None

    def register_created_codex(self, tab: CreatedTab, agent: dict) -> None:
        """Only an acknowledged, newly created terminal can bootstrap empty chat."""
        name = agent.get("name")
        if (not isinstance(name, str) or not name.startswith("pocket-") or len(name) != 31
                or agent.get("pane_id") != tab.pane_id or agent.get("terminal_id") != tab.terminal_id
                or agent.get("workspace_id") != tab.workspace_id or agent.get("tab_id") != tab.tab_id):
            return
        try:
            info = self.client.socket_path.stat()
        except OSError:
            return
        self.fresh_chats[tab.pane_id] = FreshChatReceipt(tab, name, (info.st_dev, info.st_ino),
                                                       self.clock() + FRESH_CHAT_TTL_SECONDS,
                                                       canonical_codex_session(agent))
        self.fresh_chats.move_to_end(tab.pane_id)
        while len(self.fresh_chats) > MAX_CACHES:
            self.fresh_chats.popitem(last=False)

    def _fresh_chat(self, pane_id: str, agent: dict, thread_id: str) -> FreshChatReceipt | None:
        receipt = self.fresh_chats.get(pane_id)
        if receipt is None:
            return None
        try:
            info = self.client.socket_path.stat()
            matches = (self.clock() < receipt.expires_at
                       and (info.st_dev, info.st_ino) == receipt.socket_identity
                       and agent.get("pane_id") == receipt.tab.pane_id
                       and agent.get("workspace_id") == receipt.tab.workspace_id
                       and agent.get("tab_id") == receipt.tab.tab_id
                       and agent.get("terminal_id") == receipt.tab.terminal_id
                       and agent.get("name") == receipt.name
                       and canonical_codex_session(agent) == thread_id
                       and receipt.thread_id in (None, thread_id))
        except OSError:
            matches = False
        if not matches:
            self.fresh_chats.pop(pane_id, None)
            return None
        # SessionStart's exact canonical hook report is pinned once; a later
        # session in the same terminal cannot inherit this empty-chat receipt.
        receipt.thread_id = thread_id
        return receipt

    async def target(self, pane_id: str, *, owner: str = "") -> tuple[dict, str, str]:
        agent = await self.client.agent(pane_id)
        thread_id = canonical_codex_session(agent)
        if self.daemon is not None:
            selected = await self.daemon.target(agent, owner)
            if selected is not None:
                agent, thread_id = selected
        if thread_id is None:
            raise BridgeError(409, "This pane has no canonical saved Codex session; use Terminal")
        terminal_id = agent.get("terminal_id")
        if not isinstance(terminal_id, str) or not terminal_id or len(terminal_id) > 256:
            raise BridgeError(503, "Herdr returned incompatible terminal metadata; use Terminal")
        try:
            info = self.client.socket_path.stat()
        except OSError as exc:
            raise BridgeError(503, "The selected Herdr session is unavailable; use Terminal") from exc
        identity = [str(self.client.socket_path.absolute()), info.st_dev, info.st_ino, pane_id, terminal_id, thread_id, str(self.reader.home)]
        if agent.get("_pocket_daemon_selected") is True:
            identity.extend([owner, agent["_pocket_daemon_identity"]])
        binding = opaque(hmac.digest(self.secret, json.dumps(identity).encode(), "sha256"))
        return agent, thread_id, binding

    @staticmethod
    def ready(agent: dict, *, allow_working: bool = True) -> bool:
        return Conversations.send_unavailable_reason(agent, allow_working=allow_working) is None

    def attachment_runtime(self, pane_id: str, agent: dict, thread_id: str) -> str:
        """Stable scope survives companion restarts, never terminal replacement."""
        try:
            info = self.client.socket_path.stat()
        except OSError as exc:
            raise BridgeError(503, "The selected Herdr session is unavailable; use Terminal") from exc
        identity = [str(self.client.socket_path.absolute()), info.st_dev, info.st_ino, pane_id,
                    agent.get("terminal_id"), thread_id, str(self.reader.home)]
        if agent.get("_pocket_daemon_selected") is True:
            identity.append(agent["_pocket_daemon_identity"])
        return hashlib.sha256(json.dumps(identity, separators=(",", ":")).encode()).hexdigest()

    @staticmethod
    def send_unavailable_reason(agent: dict, *, allow_working: bool = True, fresh_bootstrap: bool = False) -> str | None:
        status = agent.get("agent_status")
        if status == "working" and not allow_working:
            return "Update Herdr Pocket to send while Codex works."
        if status == "blocked":
            return "Codex needs your attention. Open Terminal to answer a question or approve an action."
        if status not in ("idle", "done", "working") and not (status == "unknown" and fresh_bootstrap
                                                               and agent.get("interactive_ready") is True):
            return "Codex has not reported that it is ready for a message. Check Terminal."
        pending = agent.get("launch_pending", False)
        if pending is True:
            return "Codex is starting. Wait for its prompt to become ready."
        if pending is not False:
            return "The server returned invalid Codex readiness information. Check Terminal."
        if agent.get("interactive_ready") is True:
            return None
        # Herdr 0.9.3 reports interactive_ready only for managed agent.start launches.
        # Manually launched Codex panes omit this false-valued managed-only field.
        # agent.rename only adds a display name; it does not make a launch managed.
        # Managed Pending/Blocked phases are already rejected by launch_pending.
        # Their canonical conversation was verified by target(). Herdr's agent.prompt
        # permits input while working and rechecks the live foreground agent itself.
        if "interactive_ready" not in agent:
            return None
        return "Codex has not reported interactive readiness. Check Terminal before sending."

    def _cursor(self, binding: str, revision: str, end: int) -> str:
        payload = json.dumps([binding, revision, end], separators=(",", ":")).encode()
        return opaque(payload) + "." + opaque(hmac.digest(self.secret, payload, "sha256"))

    def _end(self, cursor: str, binding: str, revision: str, count: int) -> int:
        try:
            if len(cursor) > 2048:
                raise ValueError()
            value, signature = cursor.split(".")
            payload = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
            if not hmac.compare_digest(signature, opaque(hmac.digest(self.secret, payload, "sha256"))):
                raise ValueError()
            cursor_binding, cursor_revision, end = json.loads(payload)
            if cursor_binding != binding or cursor_revision != revision or type(end) is not int or not 0 < end <= count:
                raise ValueError()
            return end
        except (ValueError, TypeError, UnicodeError) as exc:
            raise BridgeError(409, "Conversation changed or the cursor is invalid; refresh the latest messages") from exc

    def _bound_cache(self, active_binding: str) -> None:
        # Eviction may rotate historyEpoch on the next read, which makes clients
        # discard retained pages instead of pretending their cursors remain valid.
        while sum(cache.byte_size for cache in self.caches.values()) > MAX_CACHE_BYTES:
            victim = next((key for key in self.caches if key != active_binding), None)
            if victim is None:
                break
            self.caches.pop(victim)

    async def page(self, pane_id: str, cursor: str | None = None, revision: str | None = None, *, allow_working: bool = True,
                   allow_fresh: bool = False, owner: str = "") -> dict:
        if not self.reader.enabled:
            raise BridgeError(503, "Codex conversation reader is not configured; use Terminal")
        agent, thread_id, binding = await self.target(pane_id, owner=owner)
        if self.attachments is not None:
            await self.attachments.register(pane_id, binding, thread_id,
                                            self.attachment_runtime(pane_id, agent, thread_id))
        cache = self.caches.setdefault(binding, ConversationCache())
        self.caches.move_to_end(binding)
        while len(self.caches) > MAX_CACHES:
            self.caches.popitem(last=False)
        async with cache.lock:
            if self.clock() - cache.refreshed_at >= 1:
                try:
                    try:
                        if agent.get("_pocket_daemon_selected") is True and self.daemon is not None:
                            result = await self.daemon.read(pane_id, owner, thread_id)
                        else:
                            result = await self.reader.read(thread_id)
                    except CodexHistoryNotPersisted as exc:
                        if exc.thread_id != thread_id or self._fresh_chat(pane_id, agent, thread_id) is None:
                            raise
                        messages, cache.warning, cache.bootstrapping = [], None, True
                    else:
                        messages, cache.warning = normalize_thread(result, thread_id)
                        if self.attachments is not None:
                            await self.attachments.present_history(messages, pane_id, thread_id)
                        # Startup can persist an empty thread before its first
                        # turn. Retain only our verified new-chat receipt until
                        # there is a real saved turn or a first prompt attempt.
                        cache.bootstrapping = (result["thread"]["turns"] == []
                                               and (cache.bootstrapping
                                                    or self._fresh_chat(pane_id, agent, thread_id) is not None))
                        if not cache.bootstrapping:
                            self.fresh_chats.pop(pane_id, None)
                    # Timing metadata can arrive after a partial rollout append.
                    # It changes the revision, but is not a rewritten conversation.
                    timing = {"timestamp", "timestampSource", "messageTimestamp"}
                    unchanged_prefix = len(messages) >= len(cache.messages) and all(
                        {key: value for key, value in before.items() if key not in timing}
                        == {key: value for key, value in after.items() if key not in timing}
                        for before, after in zip(cache.messages, messages)
                    )
                    if not unchanged_prefix:
                        cache.history_epoch = secrets.token_urlsafe(32)
                    serialized = json.dumps(messages, ensure_ascii=False, separators=(",", ":")).encode()
                    if len(serialized) > MAX_CACHE_BYTES:
                        raise BridgeError(503, "Codex normalized history exceeds the cache limit; use Terminal")
                    cache.messages = messages
                    cache.byte_size = len(serialized)
                    cache.revision = opaque(hashlib.sha256(serialized).digest())
                    self._bound_cache(binding)
                    cache.error = None
                except BridgeError as exc:
                    cache.error = exc
                    cache.messages = []
                    cache.byte_size = 0
                    cache.history_epoch = secrets.token_urlsafe(32)
                finally:
                    cache.refreshed_at = self.clock()
            if cache.error:
                raise cache.error
            # Recheck binding after a potentially slow saved-history read.
            agent, thread_id, current_binding = await self.target(pane_id, owner=owner)
            if current_binding != binding:
                raise BridgeError(409, "Pane changed while history was loading; refresh the workspace")
            end = self._end(cursor, binding, cache.revision, len(cache.messages)) if cursor else len(cache.messages)
            start = max(0, end - PAGE_SIZE)
            unchanged = not cursor and revision == cache.revision
            fresh_bootstrap = cache.bootstrapping and self._fresh_chat(pane_id, agent, thread_id) is not None
            send_unavailable_reason = self.send_unavailable_reason(agent, allow_working=allow_working,
                                                                   fresh_bootstrap=allow_fresh and fresh_bootstrap)
            if cache.bootstrapping and not fresh_bootstrap:
                send_unavailable_reason = "The first message needs saved-history confirmation. Refresh or inspect Terminal before sending again."
            if self.attachments is not None and self.attachments.blocked(pane_id, binding):
                send_unavailable_reason = "Attachment delivery was not confirmed. Inspect and clear Terminal, then acknowledge it before sending again."
            response = {"binding": binding, "conversationId": thread_id, "revision": cache.revision, "historyEpoch": cache.history_epoch,
                        "messages": cache.messages[start:end],
                        "olderCursor": self._cursor(binding, cache.revision, start) if start else None,
                        "unchanged": unchanged, "canSend": send_unavailable_reason is None,
                        "freshBootstrap": fresh_bootstrap,
                        "attachmentDeliveryUncertain": self.attachments is not None and self.attachments.blocked(pane_id, binding),
                        "canSendReason": send_unavailable_reason,
                        "status": agent.get("agent_status") if agent.get("agent_status") in ("idle", "working", "blocked", "done", "unknown") else "unknown",
                        "warning": cache.warning}
            # JSON escaping can expand controls. Shrink the page, never truncate JSON.
            while len(json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode()) > MAX_PAGE_BYTES and start < end:
                start += 1
                response["messages"] = cache.messages[start:end]
                response["olderCursor"] = self._cursor(binding, cache.revision, start)
            if len(json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode()) > MAX_PAGE_BYTES:
                raise BridgeError(503, "Conversation page exceeds the size limit; use Terminal")
            if unchanged:
                response["messages"] = []
            return response

    async def send(self, pane_id: str, binding: str, text: str, *, allow_working: bool = True,
                   allow_fresh: bool = False, attachment_ids: list[str] | None = None,
                   attachments: AttachmentStore | None = None, attachment_owner: str = "") -> dict:
        if not self.reader.enabled:
            raise BridgeError(503, "Codex conversation reader is not configured; use Terminal")
        async with self.send_lock:
            agent, thread_id, current_binding = await self.target(pane_id, owner=attachment_owner)
            if not hmac.compare_digest(binding, current_binding):
                raise BridgeError(409, "Pane or conversation changed; refresh before sending")
            cache = self.caches.get(binding)
            if cache is None or cache.error is not None:
                raise BridgeError(409, "Read the saved conversation successfully before sending")
            fresh_bootstrap = cache.bootstrapping and self._fresh_chat(pane_id, agent, thread_id) is not None
            send_unavailable_reason = self.send_unavailable_reason(agent, allow_working=allow_working,
                                                                   fresh_bootstrap=allow_fresh and fresh_bootstrap)
            if send_unavailable_reason is not None:
                raise BridgeError(409, send_unavailable_reason)
            daemon_selected = agent.get("_pocket_daemon_selected") is True
            store = attachments or self.attachments
            if store is not None:
                await store.register(pane_id, binding, thread_id, self.attachment_runtime(pane_id, agent, thread_id))
            if store is not None and store.blocked(pane_id, binding):
                raise BridgeError(503, "Attachment delivery was not confirmed; inspect and clear Terminal, then acknowledge it before sending again")
            receipts = []
            if attachment_ids:
                if store is None or not attachment_owner:
                    raise BridgeError(503, "Attachment support is unavailable; update the host companion")
                receipts = await store.consume(attachment_ids, pane_id, binding, attachment_owner)
            if cache.bootstrapping:
                if self._fresh_chat(pane_id, agent, thread_id) is None:
                    raise BridgeError(503, "The first message has not been confirmed in saved history; inspect Terminal before sending again")
                # Consume before the one permitted mutation. An uncertain result
                # cannot authorize a duplicate first prompt on the next request.
                self.fresh_chats.pop(pane_id, None)
                cache.refreshed_at = float("-inf")
            submitted_text = store.prompt_text(text, receipts) if receipts else text
            if daemon_selected:
                # A selected daemon thread receives real typed image inputs.
                # This mode is captured at preflight: losing the selection can
                # never redirect the same request into a Terminal composer.
                manager = getattr(self, "daemon", None)
                if manager is None:
                    raise BridgeError(503, "Selected Codex conversation is unavailable; refresh before sending again")
                current_agent, _, checked_binding = await self.target(pane_id, owner=attachment_owner)
                if (checked_binding != binding or current_agent.get("_pocket_daemon_selected") is not True
                        or self.send_unavailable_reason(current_agent, allow_working=allow_working) is not None):
                    raise BridgeError(503, "Selected Codex conversation changed; refresh before sending again")
                delivered = await manager.deliver(pane_id, attachment_owner, thread_id, submitted_text,
                                                  [receipt.path for receipt in receipts if receipt.kind == "image"])
                if not delivered:
                    raise BridgeError(503, "Selected Codex conversation changed; refresh before sending again")
            else:
                for receipt in receipts:
                    if receipt.kind != "image":
                        continue
                    # Codex recognizes exactly one image path per bracketed paste.
                    # These pastes never include Enter; the reviewed message below
                    # is the sole turn/steering submission.
                    current_agent, _, checked_binding = await self.target(pane_id, owner=attachment_owner)
                    if (checked_binding != binding or self.send_unavailable_reason(current_agent, allow_working=allow_working,
                            fresh_bootstrap=allow_fresh and fresh_bootstrap) is not None):
                        raise BridgeError(503, "Pane changed while preparing attachments; inspect Terminal before sending again")
                    try:
                        await self.client.send_input(pane_id, shlex.quote(str(receipt.path)), [])
                    except BridgeError as exc:
                        raise BridgeError(503, "Image preparation was not confirmed; inspect Terminal before sending again") from exc
                if receipts:
                    current_agent, _, checked_binding = await self.target(pane_id, owner=attachment_owner)
                    if (checked_binding != binding or self.send_unavailable_reason(current_agent, allow_working=allow_working,
                            fresh_bootstrap=allow_fresh and fresh_bootstrap) is not None):
                        raise BridgeError(503, "Pane changed while preparing attachments; inspect Terminal before sending again")
                try:
                    await self.client.prompt(pane_id, submitted_text)
                except BridgeError as exc:
                    if receipts and exc.status != 503:
                        raise BridgeError(503, "Attachment submission was not confirmed; inspect Terminal before sending again") from exc
                    raise
            if receipts:
                await store.confirmed(pane_id, binding, attachment_owner)
            warning = None
            try:
                _, _, after_binding = await self.target(pane_id, owner=attachment_owner)
                if after_binding != binding:
                    warning = "Pane changed after submission; inspect Terminal before sending again"
            except BridgeError:
                warning = "Message submitted but its target could not be rechecked; inspect Terminal before sending again"
            # Herdr has no expected-session parameter: the preflight is not atomic.
            return {"ok": True, "warning": warning}
