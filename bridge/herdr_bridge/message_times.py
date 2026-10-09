"""Optional timestamps from one verified Codex rollout, never a history source.

Codex's legacy ``thread/read`` projection drops JSONL envelope timestamps.
Only messages already projected by that read are eligible for enrichment here.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import stat
import time


MAX_ROLLOUT_BYTES = 32 * 1024 * 1024
MAX_ROLLOUT_LINE_BYTES = 2 * 1024 * 1024
MAX_ROLLOUT_LINES = 200_000
READ_BUDGET_SECONDS = 0.5
MAX_TIMESTAMP = 253402300799
ITEM_TIME_FIELD = "_herdrPocketRecordedMessageTime"

Signature = tuple[str, str, str | None]
Identity = tuple[str, str]


def _identifier(value: object) -> bool:
    return isinstance(value, str) and 0 < len(value) <= 1024


def _seconds(value: object) -> int | None:
    return value if type(value) is int and 0 <= value <= MAX_TIMESTAMP else None


def _envelope_time(value: object) -> int | None:
    if not isinstance(value, str) or len(value) > 64:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return None
        # Do not silently round negative fractions up to the Unix epoch.
        seconds = (parsed.astimezone(timezone.utc) - datetime(1970, 1, 1, tzinfo=timezone.utc)).total_seconds()
        return _seconds(int(seconds)) if seconds >= 0 else None
    except (ValueError, OverflowError):
        return None


def _signature(item: object, *, core: bool = False) -> Signature | None:
    if not isinstance(item, dict):
        return None
    kind = item.get("type")
    if kind == ("UserMessage" if core else "userMessage"):
        content = item.get("content")
        if not isinstance(content, list):
            return None
        texts = []
        for entry in content:
            if not isinstance(entry, dict):
                return None
            if entry.get("type") == "text":
                if not isinstance(entry.get("text"), str):
                    return None
                texts.append(entry["text"])
        text = "\n".join(texts)
        return ("userMessage", text, None) if text else None
    if kind != ("AgentMessage" if core else "agentMessage"):
        return None
    if core:
        content = item.get("content")
        if not isinstance(content, list) or any(not isinstance(x, dict) or x.get("type") != "Text"
                                               or not isinstance(x.get("text"), str) for x in content):
            return None
        # This is the official CoreTurnItem -> ThreadItem conversion.
        text = "".join(x["text"] for x in content)
    else:
        text = item.get("text")
    if not isinstance(text, str) or not text:
        return None
    phase = item.get("phase")
    return "agentMessage", text, phase if phase in ("commentary", "final_answer") else None


@dataclass
class SavedMessage:
    signature: Signature
    timestamp: int | None
    item_id: str | None = None


@dataclass
class SavedTurn:
    turn_id: str | None
    messages: list[SavedMessage] = field(default_factory=list)
    # Materialized items can be persisted more than once; the last snapshot is
    # the one the paginated projection exposes.
    indexed_messages: dict[str, SavedMessage] = field(default_factory=dict)


def _open_rollout(home: Path, value: object) -> int:
    """Open only the exact scoped file, without following any symlink component."""
    if not isinstance(value, str) or not value or len(value) > 8192 or any(ord(c) < 32 for c in value):
        raise ValueError("Invalid rollout path")
    candidate = Path(value)
    home = Path(home)
    if not home.is_absolute() or not candidate.is_absolute() or ".." in home.parts or ".." in candidate.parts:
        raise ValueError("Unscoped rollout path")
    relative = candidate.relative_to(home)
    if not 2 <= len(relative.parts) <= 16 or relative.parts[0] not in ("sessions", "archived_sessions") or candidate.suffix != ".jsonl":
        raise ValueError("Unsupported rollout path")
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    directory = os.open(os.sep, directory_flags)
    try:
        for part in home.parts[1:]:
            next_directory = os.open(part, directory_flags, dir_fd=directory)
            os.close(directory)
            directory = next_directory
        if os.fstat(directory).st_uid != os.getuid():
            raise ValueError("Unowned Codex home")
        for part in relative.parts[:-1]:
            next_directory = os.open(part, directory_flags, dir_fd=directory)
            os.close(directory)
            directory = next_directory
            if os.fstat(directory).st_uid != os.getuid():
                raise ValueError("Unowned rollout directory")
        descriptor = os.open(relative.parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                             dir_fd=directory)
        try:
            info = os.fstat(descriptor)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
                raise ValueError("Rollout is not an owned regular file")
        except BaseException:
            os.close(descriptor)
            raise
        return descriptor
    finally:
        os.close(directory)


def _saved_turns(descriptor: int, thread_id: str, deadline: float) -> tuple[str, list[SavedTurn]]:
    turns: list[SavedTurn] = []
    active: SavedTurn | None = None
    mode: str | None = None
    with os.fdopen(descriptor, "rb") as stream:
        initial = os.fstat(stream.fileno())
        if initial.st_size > MAX_ROLLOUT_BYTES:
            raise ValueError("Rollout exceeds timestamp read limit")
        remaining = initial.st_size
        count = 0
        while remaining:
            if time.monotonic() >= deadline or count >= MAX_ROLLOUT_LINES:
                raise ValueError("Timestamp read exceeded its budget")
            raw = stream.readline(min(remaining, MAX_ROLLOUT_LINE_BYTES + 1))
            if not raw or len(raw) > MAX_ROLLOUT_LINE_BYTES:
                raise ValueError("Incomplete or oversized rollout")
            remaining -= len(raw)
            # A running CLI can be midway through appending the final record.
            # Earlier complete records remain usable; never parse that suffix.
            if not raw.endswith(b"\n"):
                if remaining:
                    raise ValueError("Oversized rollout record")
                break
            count += 1
            line = json.loads(raw)
            if not isinstance(line, dict) or not isinstance(line.get("payload"), dict):
                raise ValueError("Malformed rollout record")
            payload = line["payload"]
            kind = line.get("type")
            if count == 1:
                if kind != "session_meta" or payload.get("id") != thread_id:
                    raise ValueError("Rollout belongs to a different thread")
                mode = payload.get("history_mode", "legacy")
                if mode not in ("legacy", "paginated"):
                    raise ValueError("Unsupported rollout history")
                continue
            if kind == "session_meta":
                raise ValueError("Ambiguous rollout identity")
            if kind != "event_msg":
                # An implicit hook/compaction turn cannot be correlated safely.
                # Retain a placeholder so later rollback counts cannot shift
                # timestamps onto an earlier explicit turn.
                if active is None and kind in ("response_item", "compacted"):
                    active = SavedTurn(None)
                    turns.append(active)
                continue
            event = payload.get("type")
            if event == "task_started":
                turn_id = payload.get("turn_id")
                active = SavedTurn(turn_id if _identifier(turn_id) else None)
                turns.append(active)
            elif event == "thread_rolled_back":
                number = payload.get("num_turns")
                if type(number) is not int or number < 0:
                    raise ValueError("Malformed rollback")
                if number:
                    del turns[max(0, len(turns) - number):]
                active = None
            elif event == "task_complete":
                turn_id = payload.get("turn_id")
                if active is not None and (active.turn_id == turn_id
                                           or not any(turn.turn_id == turn_id for turn in turns)):
                    active = None
            elif mode == "legacy" and event in ("user_message", "agent_message"):
                text = payload.get("message")
                if not isinstance(text, str):
                    raise ValueError("Malformed visible message")
                if not text or event == "user_message" and not text.strip():
                    continue
                if active is None:
                    active = SavedTurn(None)
                    turns.append(active)
                phase = payload.get("phase")
                signature = ("userMessage" if event == "user_message" else "agentMessage", text,
                             phase if event == "agent_message" and phase in ("commentary", "final_answer") else None)
                active.messages.append(SavedMessage(signature, _envelope_time(line.get("timestamp"))))
            elif mode == "paginated" and event == "item_completed":
                if payload.get("thread_id") != thread_id or not _identifier(payload.get("turn_id")):
                    continue
                item = payload.get("item")
                signature = _signature(item, core=True)
                if signature is None or not _identifier(item.get("id")):
                    continue
                matching = [turn for turn in turns if turn.turn_id == payload["turn_id"]]
                if len(matching) != 1:
                    continue
                milliseconds = payload.get("completed_at_ms")
                timestamp = (_seconds(milliseconds // 1000) if type(milliseconds) is int and milliseconds > 0
                             else _envelope_time(line.get("timestamp")))
                matching[0].indexed_messages[item["id"]] = SavedMessage(signature, timestamp, item["id"])
            elif active is None and event not in ("token_count", "turn_aborted", "item_started", "item_completed"):
                active = SavedTurn(None)
                turns.append(active)
        final = os.fstat(stream.fileno())
        if final.st_size < initial.st_size or (final.st_size == initial.st_size and final.st_mtime_ns != initial.st_mtime_ns):
            raise ValueError("Rollout was rewritten during timestamp read")
    if mode is None:
        raise ValueError("Missing rollout identity")
    return mode, turns


def read_message_times(result: dict, thread_id: str, home: Path, *, deadline: float | None = None) -> dict[Identity, int]:
    """Return optional clocks only when saved and projected message identities agree.

    Failure to enrich never makes the existing app-server history unavailable.
    This synchronous function runs on a worker and enforces its own short budget.
    """
    try:
        thread = result.get("thread")
        if not isinstance(thread, dict) or thread.get("id") != thread_id or not isinstance(thread.get("turns"), list):
            return {}
        descriptor = _open_rollout(home, thread.get("path"))
        # _saved_turns takes ownership of the descriptor, including on failure.
        mode, saved = _saved_turns(descriptor, thread_id,
                                  deadline if deadline is not None else time.monotonic() + READ_BUDGET_SECONDS)
        by_turn: dict[str, list[SavedTurn]] = {}
        for turn in saved:
            if turn.turn_id is not None:
                by_turn.setdefault(turn.turn_id, []).append(turn)
        clocks = {}
        for turn in thread["turns"]:
            if not isinstance(turn, dict) or not _identifier(turn.get("id")) or not isinstance(turn.get("items"), list):
                continue
            candidates = by_turn.get(turn["id"], [])
            if len(candidates) != 1:
                continue
            visible = [(item, _signature(item)) for item in turn["items"]]
            visible = [(item, signature) for item, signature in visible if signature is not None]
            if any(not _identifier(item.get("id")) for item, _ in visible):
                continue
            saved_turn = candidates[0]
            if mode == "legacy":
                # Occurrence order, not a text dictionary, distinguishes repeated
                # messages. A saved suffix may be ahead of thread/read's snapshot.
                shared = min(len(visible), len(saved_turn.messages))
                if [signature for _, signature in visible[:shared]] != [message.signature for message in saved_turn.messages[:shared]]:
                    continue
                for (item, _), message in zip(visible, saved_turn.messages):
                    if message.timestamp is not None:
                        clocks[turn["id"], item["id"]] = message.timestamp
            else:
                for item, signature in visible:
                    message = saved_turn.indexed_messages.get(item["id"])
                    if message is not None and message.signature == signature and message.timestamp is not None:
                        clocks[turn["id"], item["id"]] = message.timestamp
        return clocks
    except (OSError, ValueError, TypeError, UnicodeError, RecursionError):
        return {}


async def enrich_message_times(result: dict, thread_id: str, home: Path) -> None:
    """Annotate verified items without ever extending the read-only RPC surface."""
    thread = result.get("thread")
    if not isinstance(thread, dict) or not isinstance(thread.get("turns"), list):
        return
    for turn in thread["turns"]:
        if isinstance(turn, dict) and isinstance(turn.get("items"), list):
            for item in turn["items"]:
                if isinstance(item, dict):
                    item.pop(ITEM_TIME_FIELD, None)
    try:
        async with asyncio.timeout(READ_BUDGET_SECONDS + 0.1):
            clocks = await asyncio.to_thread(read_message_times, result, thread_id, home,
                                            deadline=time.monotonic() + READ_BUDGET_SECONDS)
    except TimeoutError:
        return
    for turn in thread["turns"]:
        if isinstance(turn, dict) and isinstance(turn.get("items"), list):
            for item in turn["items"]:
                if isinstance(item, dict) and _identifier(turn.get("id")) and _identifier(item.get("id")):
                    timestamp = clocks.get((turn.get("id"), item.get("id")))
                    if timestamp is not None:
                        item[ITEM_TIME_FIELD] = timestamp
