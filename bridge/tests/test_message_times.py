"""Recorded message clocks use only an owned, exact-thread synthetic rollout."""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import threading
import time

import pytest

from herdr_bridge.codex import normalize_thread
from herdr_bridge import message_times
from herdr_bridge.message_times import ITEM_TIME_FIELD, enrich_message_times, read_message_times
from .test_codex_chat import chat, codex_stub  # Reuse isolated authenticated API fixtures.


THREAD = "019c711f-ecf0-7111-9222-333344445555"
BASE = 1791540000


def record(payload: dict, offset: int = 0, *, kind: str = "event_msg") -> dict:
    stamp = datetime.fromtimestamp(BASE + offset, timezone.utc).isoformat()
    return {"timestamp": stamp, "type": kind, "payload": payload}


def user(text: str, identifier: str) -> dict:
    return {"type": "userMessage", "id": identifier, "content": [{"type": "text", "text": text}]}


def assistant(text: str, identifier: str, phase: str = "commentary") -> dict:
    return {"type": "agentMessage", "id": identifier, "text": text, "phase": phase}


def turn(identifier: str, *items: dict) -> dict:
    return {"id": identifier, "items": list(items), "startedAt": BASE, "completedAt": BASE + 90}


def fixture(tmp_path: Path, *turns: dict, mode: str = "legacy", archive: bool = False) -> tuple[Path, Path, dict, list[dict]]:
    home = tmp_path / "codex"
    path = home / ("archived_sessions" if archive else "sessions/2026/10/09") / f"rollout-{THREAD}.jsonl"
    path.parent.mkdir(parents=True)
    lines = [record({"id": THREAD, "history_mode": mode}, kind="session_meta")]
    result = {"thread": {"id": THREAD, "path": str(path), "turns": list(turns)}}
    return home, path, result, lines


def write(path: Path, lines: list[dict], *, suffix: bytes = b"") -> None:
    path.write_bytes(b"".join(json.dumps(line).encode() + b"\n" for line in lines) + suffix)


def legacy_turn(identifier: str, messages: list[tuple[str, str, int, str | None]]) -> list[dict]:
    result = [record({"type": "task_started", "turn_id": identifier})]
    for role, text, offset, phase in messages:
        result.append(record({"type": "user_message" if role == "user" else "agent_message", "message": text,
                              "phase": phase}, offset))
    result.append(record({"type": "task_complete", "turn_id": identifier}, 90))
    return result


async def test_legacy_duplicates_get_occurrence_times_without_exposing_other_events(tmp_path):
    projected = turn("turn-1", user("Repeat", "u1"), assistant("Same", "a1"),
                     user("Repeat", "u2"), assistant("Same", "a2"), assistant("Done", "a3", "final_answer"))
    home, path, result, lines = fixture(tmp_path, projected)
    lines += legacy_turn("turn-1", [("user", "Repeat", 1, None), ("assistant", "Same", 11, "commentary"),
                                     ("user", "Repeat", 21, None), ("assistant", "Same", 31, "commentary"),
                                     ("assistant", "Done", 41, "final_answer")])
    lines.insert(3, record({"type": "message", "role": "user", "content": [{"text": "HIDDEN_CONTEXT"}]},
                           123, kind="response_item"))
    lines.insert(4, record({"type": "agent_reasoning", "text": "HIDDEN_REASONING"}, 124))
    write(path, lines)
    before, _ = normalize_thread(result, THREAD)
    await enrich_message_times(result, THREAD, home)
    after, _ = normalize_thread(result, THREAD)
    assert [m["messageTimestamp"] for m in after] == [BASE + x for x in (1, 11, 21, 31, 41)]
    assert [{k: v for k, v in m.items() if k != "messageTimestamp"} for m in after] == before
    assert "HIDDEN_" not in json.dumps(after)


@pytest.mark.parametrize("projected_count,saved_count,partial", [(2, 3, False), (3, 2, True)])
def test_appended_or_partial_suffix_keeps_verified_earlier_occurrences(tmp_path, projected_count, saved_count, partial):
    items = [user("Repeat", f"u{x}") for x in range(projected_count)]
    home, path, result, lines = fixture(tmp_path, turn("turn-1", *items))
    lines += legacy_turn("turn-1", [("user", "Repeat", x + 1, None) for x in range(saved_count)])[:-1]
    write(path, lines, suffix=b'{"timestamp": "unfinished' if partial else b"")
    assert read_message_times(result, THREAD, home) == {
        ("turn-1", f"u{x}"): BASE + x + 1 for x in range(min(projected_count, saved_count))}


@pytest.mark.parametrize("mutation", ["text", "phase", "order", "missingEarlier"])
def test_mismatched_turn_is_not_correlated_by_matching_text(tmp_path, mutation):
    projected = turn("turn-1", user("First", "u1"), assistant("Second", "a1"))
    home, path, result, lines = fixture(tmp_path, projected)
    messages = [("user", "First", 1, None), ("assistant", "Second", 2, "commentary")]
    if mutation == "text": messages[0] = ("user", "Different", 1, None)
    if mutation == "phase": messages[1] = ("assistant", "Second", 2, "final_answer")
    if mutation == "order": messages.reverse()
    if mutation == "missingEarlier": messages.pop(0)
    lines += legacy_turn("turn-1", messages)
    write(path, lines)
    assert read_message_times(result, THREAD, home) == {}


@pytest.mark.parametrize("mode", ["legacy", "paginated"])
def test_rollback_removes_abandoned_duplicate_turn_and_retains_earlier_clocks(tmp_path, mode):
    home, path, result, lines = fixture(tmp_path, turn("kept", user("Repeat", "u1")), mode=mode)
    for name, offset in [("kept", 1), ("removed", 2)]:
        if mode == "legacy":
            lines += legacy_turn(name, [("user", "Repeat", offset, None)])
        else:
            lines += [record({"type": "task_started", "turn_id": name}),
                      record({"type": "item_completed", "thread_id": THREAD, "turn_id": name,
                              "item": {"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "Repeat"}]},
                              "completed_at_ms": (BASE + offset) * 1000}),
                      record({"type": "task_complete", "turn_id": name})]
    lines.append(record({"type": "thread_rolled_back", "num_turns": 1}))
    write(path, lines)
    assert read_message_times(result, THREAD, home) == {("kept", "u1"): BASE + 1}
    result["thread"]["turns"].append(turn("removed", user("Repeat", "u1")))
    assert read_message_times(result, THREAD, home) == {("kept", "u1"): BASE + 1}


def test_repeated_turn_id_and_implicit_turn_are_ambiguous(tmp_path):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("Repeat", "u1")))
    lines += legacy_turn("turn-1", [("user", "Repeat", 1, None)])
    lines += legacy_turn("turn-1", [("user", "Repeat", 2, None)])
    write(path, lines)
    assert read_message_times(result, THREAD, home) == {}
    write(path, lines[:1] + [record({"type": "user_message", "message": "Repeat"}, 3)])
    assert read_message_times(result, THREAD, home) == {}


def test_paginated_timestamps_use_canonical_ids_and_completed_snapshot_content(tmp_path):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("Repeat", "u1"), user("Repeat", "u2"),
                                                   assistant("Twoparts", "a1")), mode="paginated")
    lines.append(record({"type": "task_started", "turn_id": "turn-1"}))
    for identifier, offset in [("u1", 1), ("u2", 2)]:
        lines.append(record({"type": "item_completed", "thread_id": THREAD, "turn_id": "turn-1",
                             "item": {"type": "UserMessage", "id": identifier, "content": [{"type": "text", "text": "Repeat"}]},
                             "started_at_ms": BASE * 1000, "completed_at_ms": (BASE + offset) * 1000 + 999}, 200))
    lines.append(record({"type": "item_completed", "thread_id": THREAD, "turn_id": "turn-1",
                         "item": {"type": "AgentMessage", "id": "a1", "content": [{"type": "Text", "text": "Two"},
                                                                                       {"type": "Text", "text": "parts"}],
                                  "phase": "commentary"}, "completed_at_ms": (BASE + 3) * 1000}, 200))
    lines.append(record({"type": "item_completed", "thread_id": "foreign", "turn_id": "turn-1",
                         "item": {"type": "UserMessage", "id": "u1", "content": [{"type": "text", "text": "Repeat"}]},
                         "completed_at_ms": (BASE + 900) * 1000}))
    write(path, lines)
    assert read_message_times(result, THREAD, home) == {("turn-1", "u1"): BASE + 1,
                                                     ("turn-1", "u2"): BASE + 2,
                                                     ("turn-1", "a1"): BASE + 3}
    # Same text at another ID/turn cannot acquire the existing item's time.
    result["thread"]["turns"][0]["items"][0]["id"] = "not-recorded"
    result["thread"]["turns"][0]["items"][2]["text"] = "Old snapshot"
    assert read_message_times(result, THREAD, home) == {("turn-1", "u2"): BASE + 2}


@pytest.mark.parametrize("stamp", [None, "not-a-time", "2026-10-09T10:00:00", "1969-12-31T23:59:59.999Z", True])
def test_invalid_message_time_stays_unknown_without_borrowing_turn_time(tmp_path, stamp):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1"), user("Second", "u2")))
    lines += legacy_turn("turn-1", [("user", "First", 1, None), ("user", "Second", 2, None)])
    lines[2]["timestamp"] = stamp
    write(path, lines)
    assert read_message_times(result, THREAD, home) == {("turn-1", "u2"): BASE + 2}


def test_message_time_parses_timezone_and_fraction_as_unix_seconds(tmp_path):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1")))
    lines += legacy_turn("turn-1", [("user", "First", 1, None)])
    lines[2]["timestamp"] = "2026-10-09T12:00:01.999+02:00"
    write(path, lines)
    assert read_message_times(result, THREAD, home) == {("turn-1", "u1"): BASE + 1}


@pytest.mark.parametrize("archive", [False, True])
def test_scoped_owned_active_and_archived_rollout_are_supported(tmp_path, archive):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1")), archive=archive)
    write(path, lines + legacy_turn("turn-1", [("user", "First", 1, None)]))
    assert read_message_times(result, THREAD, home) == {("turn-1", "u1"): BASE + 1}


@pytest.mark.parametrize("where", ["home", "sessions", "date", "file"])
def test_no_symlink_component_is_followed(tmp_path, where, monkeypatch):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1")))
    write(path, lines + legacy_turn("turn-1", [("user", "First", 1, None)]))
    target = {"home": home, "sessions": home / "sessions", "date": path.parent, "file": path}[where]
    moved = target.with_name(target.name + "-real")
    target.rename(moved)
    target.symlink_to(moved, target_is_directory=where != "file")
    monkeypatch.setattr(message_times, "_saved_turns", lambda *_: pytest.fail("A symlink must never be read"))
    assert read_message_times(result, THREAD, home) == {}


@pytest.mark.parametrize("mutation", ["foreignThread", "wrongProjectedThread", "outside", "traversal", "hardlink", "fifo", "unowned"])
def test_wrong_identity_or_unsafe_file_does_not_supply_clocks(tmp_path, mutation, monkeypatch):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1")))
    write(path, lines + legacy_turn("turn-1", [("user", "First", 1, None)]))
    if mutation == "foreignThread":
        lines[0]["payload"]["id"] = "other-thread"
        write(path, lines)
    if mutation == "wrongProjectedThread": result["thread"]["id"] = "other-thread"
    if mutation == "outside":
        outside = tmp_path / "unselected.jsonl"
        outside.write_bytes(path.read_bytes())
        result["thread"]["path"] = str(outside)
    if mutation == "traversal": result["thread"]["path"] = str(home / "sessions/../../unselected.jsonl")
    if mutation == "hardlink": os.link(path, tmp_path / "linked.jsonl")
    if mutation == "fifo":
        path.unlink()
        os.mkfifo(path)
    if mutation == "unowned": monkeypatch.setattr(message_times.os, "getuid", lambda: os.geteuid() + 1)
    assert read_message_times(result, THREAD, home) == {}


@pytest.mark.parametrize("mutation", ["fileLimit", "lineLimit", "recordLimit", "deadline", "malformed", "duplicateMeta", "badRollback"])
def test_optional_reader_limits_and_malformed_records_fail_closed(tmp_path, mutation, monkeypatch):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1")))
    write(path, lines + legacy_turn("turn-1", [("user", "First", 1, None)]))
    deadline = None
    if mutation == "fileLimit": monkeypatch.setattr(message_times, "MAX_ROLLOUT_BYTES", 1)
    if mutation == "lineLimit": monkeypatch.setattr(message_times, "MAX_ROLLOUT_LINE_BYTES", 1)
    if mutation == "recordLimit": monkeypatch.setattr(message_times, "MAX_ROLLOUT_LINES", 1)
    if mutation == "deadline": deadline = time.monotonic() - 1
    if mutation == "malformed": path.write_bytes(path.read_bytes() + b"not-json\n")
    if mutation == "duplicateMeta": write(path, lines + lines)
    if mutation == "badRollback": write(path, lines + [record({"type": "thread_rolled_back", "num_turns": True})])
    assert read_message_times(result, THREAD, home, deadline=deadline) == {}


async def test_file_work_runs_off_event_loop_and_unverified_annotations_are_removed(tmp_path, monkeypatch):
    home, path, result, lines = fixture(tmp_path, turn("turn-1", user("First", "u1")))
    write(path, lines)
    item = result["thread"]["turns"][0]["items"][0]
    item[ITEM_TIME_FIELD] = BASE + 999
    worker_thread = []
    main_thread = threading.get_ident()

    def slow_read(*args, **kwargs):
        worker_thread.append(threading.get_ident())
        time.sleep(0.04)
        return {}

    monkeypatch.setattr(message_times, "read_message_times", slow_read)
    pending = asyncio.create_task(enrich_message_times(result, THREAD, home))
    await asyncio.sleep(0.01)
    assert not pending.done()
    assert ITEM_TIME_FIELD not in item
    await pending
    assert worker_thread and worker_thread[0] != main_thread
    assert "messageTimestamp" not in normalize_thread(result, THREAD)[0][0]


async def test_timing_updates_change_revision_without_resetting_conversation_history(chat):
    client, app, _, _, _, _, payload, _, now = chat
    saved = json.loads(payload.read_text())

    async def read_fixture(_thread):
        # The recorded-time worker is tested above; this exercises downstream
        # normalized page/cache behavior when optional clocks arrive or vanish.
        return json.loads(json.dumps(saved))

    app.state.codex_reader.read = read_fixture
    original = (await client.get("/v1/panes/w1:p2/conversation")).json()
    saved["thread"]["turns"][0]["items"][0][ITEM_TIME_FIELD] = BASE + 1
    now[0] += 1
    timed = (await client.get("/v1/panes/w1:p2/conversation", params={"revision": original["revision"]})).json()
    assert timed["messages"][0]["messageTimestamp"] == BASE + 1
    assert timed["revision"] != original["revision"] and not timed["unchanged"]
    assert timed["historyEpoch"] == original["historyEpoch"]
    assert [m["id"] for m in timed["messages"]] == [m["id"] for m in original["messages"]]

    del saved["thread"]["turns"][0]["items"][0][ITEM_TIME_FIELD]
    saved["thread"]["turns"][0]["startedAt"] = BASE
    now[0] += 1
    fallback = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert fallback["revision"] != timed["revision"]
    assert fallback["historyEpoch"] == original["historyEpoch"]
    assert "messageTimestamp" not in fallback["messages"][0]
    assert fallback["messages"][0]["timestamp"] == BASE

    saved["thread"]["turns"][0]["items"][0]["content"][0]["text"] = "Changed saved message"
    now[0] += 1
    edited = (await client.get("/v1/panes/w1:p2/conversation")).json()
    assert edited["historyEpoch"] != original["historyEpoch"]
