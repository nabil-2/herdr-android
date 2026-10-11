"""Bounded, read-only turn pages for an already verified Codex thread.

The app-server's full thread response includes command output and can grow far
larger than the chat itself. Read one turn at a time and discard hidden items
before retaining history. A single oversized turn may use its official summary;
that exception is explicit and never triggered by ordinary RPC failures.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
import json

from .herdr import BridgeError

MAX_HISTORY_TURNS = 1024
MAX_VISIBLE_HISTORY_BYTES = 64 * 1024 * 1024
MAX_CURSOR_BYTES = 8192
HISTORY_WARNING_FIELD = "_herdrPocketHistoryWarning"
HISTORY_PAGED_FIELD = "_herdrPocketPagedHistory"
TURN_PAGE_SIZE = 5


class CodexHistoryTooLarge(BridgeError):
    def __init__(self):
        super().__init__(503, "A Codex history page exceeds reader limits; use Terminal")


class CodexHistoryNotPersisted(BridgeError):
    def __init__(self, thread_id: str):
        super().__init__(503, "Codex has not saved this conversation yet; use Terminal")
        self.thread_id = thread_id


class CodexHistoryUnsupported(BridgeError):
    def __init__(self):
        super().__init__(503, "This Codex runtime does not support saved history pages; use Terminal")


def history_rpc_error(method: str, params: dict, error: object) -> BridgeError | None:
    """Recognize only exact, source-defined errors from a matched RPC response."""
    thread_id = params.get("threadId")
    if (method not in {"thread/read", "thread/turns/list"} or not isinstance(thread_id, str)
            or not isinstance(error, dict) or type(error.get("code")) is not int):
        return None
    if error["code"] == -32600 and error.get("message") in {
        f"thread not loaded: {thread_id}",
        f"no rollout found for thread id {thread_id}",
        f"thread {thread_id} is not materialized yet; includeTurns is unavailable before first user message",
        f"thread {thread_id} is not materialized yet; thread/turns/list is unavailable before first user message",
    }:
        return CodexHistoryNotPersisted(thread_id)
    if error["code"] == -32601 and error.get("message") == "list_turns is not supported yet":
        return CodexHistoryUnsupported()
    return None


def _identifier(value: object) -> bool:
    return isinstance(value, str) and 0 < len(value) <= 1024


def _visible_turn(raw: object) -> dict:
    if not isinstance(raw, dict) or not _identifier(raw.get("id")) or not isinstance(raw.get("items"), list):
        raise ValueError("Invalid turn page")
    turn = {key: raw[key] for key in ("id", "status", "startedAt", "completedAt", "durationMs") if key in raw}
    items = []
    seen = set()
    for item in raw["items"]:
        if not isinstance(item, dict):
            raise ValueError("Invalid turn item")
        if item.get("type") not in {"userMessage", "agentMessage"}:
            continue
        if not _identifier(item.get("id")) or item["id"] in seen:
            raise ValueError("Invalid visible item identity")
        seen.add(item["id"])
        visible = {"id": item["id"], "type": item["type"]}
        if item["type"] == "userMessage":
            content = item.get("content")
            if not isinstance(content, list) or any(not isinstance(part, dict) for part in content):
                raise ValueError("Invalid user message")
            text = [part for part in content if part.get("type") == "text"]
            if any(not isinstance(part.get("text"), str) for part in text):
                raise ValueError("Invalid user text")
            visible["content"] = [{"type": "text", "text": part["text"]} for part in text]
        else:
            if not isinstance(item.get("text"), str):
                raise ValueError("Invalid agent text")
            visible.update(text=item["text"], phase=item.get("phase"))
        items.append(visible)
    turn["items"] = items
    return turn


async def read_history(rpc: Callable[[str, dict], Awaitable[dict]], thread_id: str, *, prefer_pages: bool = False) -> dict:
    """Read newest-first, then return the legacy normalized chronological shape.

    The transport must reconnect its own read-only channel on a size violation
    before raising CodexHistoryTooLarge, so the same cursor can request summary.
    The caller supplies an overall deadline; requests never resume or load agents.
    """
    try:
        # Legacy Codex reconstructs its entire rollout on every turn page.
        # Keep the efficient single read for histories that fit; only a typed
        # transport size violation selects the bounded paging path.
        if not prefer_pages:
            try:
                return await rpc("thread/read", {"threadId": thread_id, "includeTurns": True})
            except CodexHistoryTooLarge:
                pass
        metadata = await rpc("thread/read", {"threadId": thread_id, "includeTurns": False})
        thread = metadata.get("thread")
        if not isinstance(thread, dict) or thread.get("id") != thread_id or thread.get("turns") != []:
            raise ValueError("History metadata does not match the requested thread")
        turns = []
        cursor = None
        cursors = set()
        identities = set()
        visible_bytes = 0
        warnings = []
        page_size = TURN_PAGE_SIZE
        while len(turns) < MAX_HISTORY_TURNS:
            params = {"threadId": thread_id, "limit": min(page_size, MAX_HISTORY_TURNS - len(turns)),
                      "sortDirection": "desc", "itemsView": "full"}
            if cursor is not None:
                params["cursor"] = cursor
            try:
                page = await rpc("thread/turns/list", params)
            except CodexHistoryTooLarge:
                if page_size > 1:
                    page_size = max(1, page_size // 2)
                    continue
                page = await rpc("thread/turns/list", params | {"itemsView": "summary"})
                if not warnings:
                    warnings.append("An oversized turn shows its first user message and last agent message. Open Terminal for intermediate messages.")
            data = page.get("data")
            next_cursor = page.get("nextCursor")
            if not isinstance(data, list) or len(data) > params["limit"] or next_cursor is not None and (
                    not isinstance(next_cursor, str) or not next_cursor or len(next_cursor.encode()) > MAX_CURSOR_BYTES
                    or not data or next_cursor in cursors):
                raise ValueError("Invalid history page or cursor")
            if not data:
                break
            oversized = False
            for raw_turn in data:
                turn = _visible_turn(raw_turn)
                if turn["id"] in identities:
                    raise ValueError("Repeated history turn")
                identities.add(turn["id"])
                size = len(json.dumps(turn, ensure_ascii=False, separators=(",", ":")).encode())
                if visible_bytes + size > MAX_VISIBLE_HISTORY_BYTES:
                    oversized = True
                    break
                visible_bytes += size
                turns.append(turn)
            if oversized:
                warnings.append("Older history exceeds the chat cache limit. Open Terminal for earlier messages.")
                break
            if next_cursor is None:
                break
            cursors.add(next_cursor)
            cursor = next_cursor
        else:
            warnings.append("Only the latest 1024 turns are available in chat. Open Terminal for earlier messages.")
        result = {"thread": thread | {"turns": list(reversed(turns))}, HISTORY_PAGED_FIELD: True}
        if warnings:
            result[HISTORY_WARNING_FIELD] = " ".join(warnings)
        return result
    except (ValueError, TypeError, UnicodeError) as exc:
        raise BridgeError(503, "Codex returned incompatible saved history pages; use Terminal") from exc
