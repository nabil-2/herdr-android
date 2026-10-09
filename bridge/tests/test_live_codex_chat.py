"""Optional real-Codex reader checks, using only synthetic isolated histories.

CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
No model is called and no existing agent or conversation is accessed.
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import sqlite3
import uuid

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.herdr import HerdrClient
from .conftest import AUTH, SNAPSHOT, TOKEN


pytestmark = pytest.mark.skipif(os.environ.get("CODEX_INTEGRATION") != "1", reason="Opt-in isolated Codex reader test")
STAMP = "2026-10-09T10:00:00Z"
FIRST_START = 1791540000
FIRST_END = FIRST_START + 12
SECOND_START = FIRST_START + 300
SECOND_END = SECOND_START + 24


def history(home: Path, thread: str, mode: str, *, with_turn_times: bool = True) -> tuple[Path, list[dict]]:
    path = home / "sessions/2026/10/09" / f"rollout-2026-10-09T10-00-00-{thread}.jsonl"
    path.parent.mkdir(parents=True)
    lines = [{"timestamp": STAMP, "type": "session_meta", "payload": {
        "id": thread, "session_id": thread, "timestamp": STAMP, "cwd": str(home),
        "originator": "codex", "cli_version": "0.162.0", "source": "cli", "model_provider": "openai",
        "base_instructions": None, "dynamic_tools": None, "history_mode": mode,
    }}]
    append_turn(lines, thread, mode, "turn-1", "Hello from the phone", "**Ready.**", "final_answer",
                with_turn_times=with_turn_times)
    write_history(path, lines, mode)
    return path, lines


def append_turn(lines: list[dict], thread: str, mode: str, turn: str, user: str, reply: str, phase: str, *,
                started_at: int = FIRST_START, completed_at: int = FIRST_END, with_turn_times: bool = True) -> None:
    def event(payload, timestamp: int):
        stamp = datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        lines.append({"timestamp": stamp, "type": "event_msg", "payload": payload})

    # Codex exports the optional turn payload times, not the JSONL envelope or
    # item-completion times. Untimed fixtures deliberately retain both of those.
    start_fields = {"started_at": started_at} if with_turn_times else {}
    end_fields = {"started_at": started_at, "completed_at": completed_at,
                  "duration_ms": (completed_at - started_at) * 1000} if with_turn_times else {}
    event({"type": "task_started", "turn_id": turn, "model_context_window": None} | start_fields, started_at)
    if mode == "legacy":
        event({"type": "user_message", "message": user, "images": [], "local_images": []}, started_at + 1)
        event({"type": "agent_message", "message": reply, "phase": phase}, started_at + 7)
    else:
        for index, item in enumerate([
            {"type": "UserMessage", "id": turn + "-user", "content": [{"type": "text", "text": user}]},
            {"type": "AgentMessage", "id": turn + "-agent", "content": [{"type": "Text", "text": reply}], "phase": phase},
        ]):
            item_completed_at = started_at + (1 if index == 0 else 7)
            event({"type": "item_completed", "thread_id": thread, "turn_id": turn, "item": item,
                   "started_at_ms": (item_completed_at - 1) * 1000,
                   "completed_at_ms": item_completed_at * 1000}, item_completed_at)
    # Raw model context must not become a human conversation message.
    lines.append({"timestamp": STAMP, "type": "response_item", "payload": {
        "type": "message", "role": "user", "content": [{"type": "input_text", "text": "HIDDEN_AGENTS_CONTEXT"}],
    }})
    event({"type": "agent_reasoning", "text": "HIDDEN_REASONING"}, started_at + 9)
    event({"type": "task_complete", "turn_id": turn, "last_agent_message": reply} | end_fields, completed_at)


def write_history(path: Path, lines: list[dict], mode: str) -> None:
    with path.open("w") as output:
        for ordinal, line in enumerate(lines):
            if mode == "paginated":
                line = line | {"ordinal": ordinal}
            output.write(json.dumps(line) + "\n")


def project_paginated(home: Path, thread: str, lines: list[dict]) -> None:
    """Seed only this fixture's 0.162 projection, as a CLI writer would.

    Paginated history reads an existing indexed store; it deliberately does not
    rebuild UI items from JSONL on read. The app-server creates/migrates its schema.
    Shapes follow codex-rs/thread-store/src/{types.rs,local/thread_history/read.rs}.
    """
    with sqlite3.connect(home / "thread_history_1.sqlite") as db:
        db.execute("DELETE FROM thread_items WHERE thread_id=?", (thread,))
        db.execute("DELETE FROM thread_turns WHERE thread_id=?", (thread,))
        for ordinal, line in enumerate(lines):
            payload = line.get("payload", {})
            kind = payload.get("type")
            if line.get("type") != "event_msg":
                continue
            turn = payload.get("turn_id")
            if kind == "task_started":
                db.execute("INSERT INTO thread_turns (thread_id,turn_id,rollout_ordinal,status,started_at) VALUES (?,?,?,?,?)",
                           (thread, turn, ordinal, "completed", payload.get("started_at")))
            elif kind == "item_completed":
                item = payload["item"]
                user = item["type"] == "UserMessage"
                wire = ({"type": "userMessage", "id": item["id"], "content": item["content"]} if user else
                        {"type": "agentMessage", "id": item["id"], "text": item["content"][0]["text"], "phase": item.get("phase")})
                db.execute("INSERT INTO thread_items (thread_id,turn_id,item_id,rollout_ordinal,created_at_ms,item_json,item_type,updated_at_ordinal) VALUES (?,?,?,?,?,?,?,?)",
                           (thread, turn, item["id"], ordinal, payload["completed_at_ms"], json.dumps(wire), wire["type"], ordinal))
                if user:
                    db.execute("UPDATE thread_turns SET first_user_item_id=COALESCE(first_user_item_id,?) WHERE thread_id=? AND turn_id=?",
                               (item["id"], thread, turn))
                elif item.get("phase") == "final_answer":
                    db.execute("UPDATE thread_turns SET final_agent_item_id=? WHERE thread_id=? AND turn_id=?",
                               (item["id"], thread, turn))
            elif kind == "task_complete":
                db.execute("UPDATE thread_turns SET completed_at=?,duration_ms=? WHERE thread_id=? AND turn_id=?",
                           (payload.get("completed_at"), payload.get("duration_ms"), thread, turn))


@asynccontextmanager
async def mock_herdr(path: Path, thread: str):
    snapshot = json.loads(json.dumps(SNAPSHOT))
    pane = snapshot["panes"][1]
    pane.update(agent="codex", terminal_id="term_fixture", agent_status="idle",
                agent_session={"source": "herdr:codex", "agent": "codex", "kind": "id", "value": thread})
    # Herdr omits launch_pending when false; this must still allow a ready agent.
    agent = pane | {"interactive_ready": True}
    requests = []

    async def reply(reader, writer):
        try:
            request = json.loads(await reader.readline())
            requests.append(request)
            method = request["method"]
            if method == "session.snapshot":
                result = {"type": "session_snapshot", "snapshot": snapshot}
            elif method == "agent.get":
                result = {"type": "agent", "agent": agent}
            elif method == "agent.prompt":
                result = {"type": "agent_prompted", "agent": agent}
            else:
                raise AssertionError("Unexpected Herdr operation: " + method)
            writer.write(json.dumps({"id": request["id"], "result": result}).encode() + b"\n")
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    server = await asyncio.start_unix_server(reply, path=str(path))
    async with server:
        yield HerdrClient(path), requests


@pytest.mark.parametrize("mode", ["legacy", "paginated"])
@pytest.mark.parametrize("with_turn_times", [True, False], ids=["official-turn-times", "untimed-history"])
async def test_real_codex_reads_updates_without_resuming_and_excludes_model_context(tmp_path, mode, with_turn_times):
    executable = os.environ.get("CODEX_BIN") or shutil.which("codex")
    if executable is None:
        pytest.skip("Codex executable unavailable")
    home = tmp_path / "codex"
    thread = str(uuid.uuid4())
    path, lines = history(home, thread, mode, with_turn_times=with_turn_times)
    original_config = home / "config.toml"
    original_config.write_text("check_for_update_on_startup = false\n[analytics]\nenabled = false\n")
    async with mock_herdr(tmp_path / "herdr.sock", thread) as (herdr, requests):
        app = create_app(herdr, TOKEN, codex_executable=executable, codex_home=home)
        async with app.router.lifespan_context(app):
            if mode == "paginated":
                # Initialize only this temporary store's schema, then act as its writer.
                await app.state.codex_reader.read(thread)
                project_paginated(home, thread, lines)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test", headers=AUTH) as phone:
                response = await phone.get("/v1/panes/w1:p2/conversation")
                assert response.status_code == 200, response.text
                page = response.json()
                assert [(m["role"], m["text"]) for m in page["messages"]] == [
                    ("user", "Hello from the phone"), ("assistant", "**Ready.**")]
                first_times = [(FIRST_START, "turn_start"), (FIRST_END, "turn_end")] if with_turn_times else [(None, None)] * 2
                assert [(m.get("timestamp"), m.get("timestampSource")) for m in page["messages"]] == first_times
                first_message_times = [FIRST_START + 1, FIRST_START + 7]
                assert [m.get("messageTimestamp") for m in page["messages"]] == first_message_times
                if not with_turn_times:
                    assert all("timestamp" not in m and "timestampSource" not in m for m in page["messages"])
                assert "HIDDEN_" not in response.text
                first_ids = [m["id"] for m in page["messages"]]
                first_epoch = page.get("historyEpoch")
                # Simulate a running CLI persisting a completed message externally.
                append_turn(lines, thread, mode, "turn-2", "Hello from the phone", "Second reply", "commentary",
                            started_at=SECOND_START, completed_at=SECOND_END, with_turn_times=with_turn_times)
                write_history(path, lines, mode)
                if mode == "paginated":
                    project_paginated(home, thread, lines)
                await asyncio.sleep(1.1)
                response = await phone.get("/v1/panes/w1:p2/conversation")
                assert response.status_code == 200, response.text
                updated = response.json()
                assert [m["text"] for m in updated["messages"]] == ["Hello from the phone", "**Ready.**", "Hello from the phone", "Second reply"]
                assert [m["id"] for m in updated["messages"][:2]] == first_ids
                assert len({m["id"] for m in updated["messages"]}) == 4
                updated_times = first_times + ([(SECOND_START, "turn_start")] * 2 if with_turn_times else [(None, None)] * 2)
                assert [(m.get("timestamp"), m.get("timestampSource")) for m in updated["messages"]] == updated_times
                assert [m.get("messageTimestamp") for m in updated["messages"]] == first_message_times + [SECOND_START + 1, SECOND_START + 7]
                assert updated.get("historyEpoch") == first_epoch
                assert "HIDDEN_" not in response.text
                sent = await phone.post("/v1/panes/w1:p2/messages", json={"binding": updated["binding"], "text": "First line\nSecond line"})
                assert sent.status_code == 200, sent.text
                assert sent.json()["ok"] is True
                prompts = [r for r in requests if r["method"] == "agent.prompt"]
                assert len(prompts) == 1
                assert prompts[0]["params"] == {"target": "w1:p2", "text": "First line\nSecond line"}
                if mode == "legacy":
                    lines.append({"timestamp": STAMP, "type": "event_msg", "payload": {"type": "thread_rolled_back", "num_turns": 1}})
                    write_history(path, lines, mode)
                else:
                    with sqlite3.connect(home / "thread_history_1.sqlite") as db:
                        db.execute("DELETE FROM thread_items WHERE thread_id=? AND turn_id=?", (thread, "turn-2"))
                        db.execute("DELETE FROM thread_turns WHERE thread_id=? AND turn_id=?", (thread, "turn-2"))
                await asyncio.sleep(1.1)
                rolled_back = await phone.get("/v1/panes/w1:p2/conversation")
                assert rolled_back.status_code == 200, rolled_back.text
                assert [m["id"] for m in rolled_back.json()["messages"]] == first_ids
                assert [(m.get("timestamp"), m.get("timestampSource")) for m in rolled_back.json()["messages"]] == first_times
                assert [m.get("messageTimestamp") for m in rolled_back.json()["messages"]] == first_message_times
                assert rolled_back.json()["historyEpoch"] != first_epoch
        assert original_config.read_text().startswith("check_for_update_on_startup = false")
    # No second transcript/thread was created by the read-only sidecar.
    assert list((home / "sessions").rglob("rollout-*.jsonl")) == [path]


def append_steering_turn(lines: list[dict], thread: str, mode: str, *, started_at: int) -> list[tuple[str, str, int]]:
    """Many visible messages in one turn, with repeated text and non-message records."""
    turn = "turn-steering"
    expected = []

    def stamp(timestamp):
        return datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def event(payload, timestamp):
        lines.append({"timestamp": stamp(timestamp), "type": "event_msg", "payload": payload})

    event({"type": "task_started", "turn_id": turn, "model_context_window": None,
           "started_at": started_at}, started_at)
    for index in range(54):
        # More than one bridge page, with distinct minutes and a midnight crossing.
        timestamp = started_at + index * 150 + 1
        user = index % 2 == 0
        role = "user" if user else "assistant"
        text = "Keep this synthetic task small." if user else "I am checking the synthetic task."
        phase = None if user else "final_answer" if index == 53 else "commentary"
        expected.append((role, text, timestamp))
        if mode == "legacy":
            event({"type": "user_message", "message": text, "images": [], "local_images": []} if user else
                  {"type": "agent_message", "message": text, "phase": phase}, timestamp)
        else:
            item = ({"type": "UserMessage", "id": f"{turn}-m{index}", "content": [{"type": "text", "text": text}]} if user else
                    {"type": "AgentMessage", "id": f"{turn}-m{index}", "content": [{"type": "Text", "text": text}], "phase": phase})
            event({"type": "item_completed", "thread_id": thread, "turn_id": turn, "item": item,
                   "started_at_ms": (timestamp - 1) * 1000, "completed_at_ms": timestamp * 1000}, timestamp)
        # Identical text in raw model context/reasoning must not steal a message's clock.
        lines.append({"timestamp": stamp(timestamp + 37), "type": "response_item", "payload": {
            "type": "message", "role": "user", "content": [{"type": "input_text", "text": "Keep this synthetic task small."}],
        }})
        event({"type": "agent_reasoning", "text": "I am checking the synthetic task."}, timestamp + 38)
    completed_at = expected[-1][2] + 60
    event({"type": "task_complete", "turn_id": turn, "last_agent_message": expected[-1][1],
           "started_at": started_at, "completed_at": completed_at,
           "duration_ms": (completed_at - started_at) * 1000}, completed_at)
    return expected


@pytest.mark.parametrize("mode", ["legacy", "paginated"])
async def test_real_message_clocks_in_one_turn_survive_polls_pagination_and_rollback(tmp_path, mode):
    executable = os.environ.get("CODEX_BIN") or shutil.which("codex")
    if executable is None:
        pytest.skip("Codex executable unavailable")
    home = tmp_path / "codex"
    thread = str(uuid.uuid4())
    path, initial = history(home, thread, mode)
    lines = initial[:1]
    started_at = FIRST_START + 13 * 3600 + 58 * 60  # 23:58 UTC, then next-day messages.
    expected = append_steering_turn(lines, thread, mode, started_at=started_at)
    write_history(path, lines, mode)
    (home / "config.toml").write_text("check_for_update_on_startup = false\n[analytics]\nenabled = false\n")

    async with mock_herdr(tmp_path / "herdr.sock", thread) as (herdr, requests):
        app = create_app(herdr, TOKEN, codex_executable=executable, codex_home=home)
        async with app.router.lifespan_context(app):
            if mode == "paginated":
                await app.state.codex_reader.read(thread)
                project_paginated(home, thread, lines)
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test", headers=AUTH) as phone:
                async def read_all():
                    response = await phone.get("/v1/panes/w1:p2/conversation")
                    assert response.status_code == 200, response.text
                    latest = response.json()
                    messages = latest["messages"]
                    cursor = latest["olderCursor"]
                    while cursor:
                        older = await phone.get("/v1/panes/w1:p2/conversation", params={"cursor": cursor})
                        assert older.status_code == 200, older.text
                        older_page = older.json()
                        assert older_page["revision"] == latest["revision"]
                        messages = older_page["messages"] + messages
                        cursor = older_page["olderCursor"]
                    return latest, messages

                latest, messages = await read_all()
                assert len(latest["messages"]) == 50 and latest["olderCursor"]
                assert [(m["role"], m["text"], m.get("messageTimestamp")) for m in messages] == expected
                assert len({m["id"] for m in messages}) == len(expected)
                clocks = {m["id"]: m["messageTimestamp"] for m in messages}
                assert len(set(clocks.values())) == len(expected)
                # Old turn metadata stays separate from each precise message clock.
                assert messages[0]["timestamp"] == started_at
                assert messages[-1]["timestamp"] == expected[-1][2] + 60

                await asyncio.sleep(1.1)
                unchanged = await phone.get("/v1/panes/w1:p2/conversation", params={"revision": latest["revision"]})
                assert unchanged.status_code == 200, unchanged.text
                assert unchanged.json()["unchanged"] is True
                assert unchanged.json()["messages"] == []
                repeated, repeated_messages = await read_all()
                assert repeated["revision"] == latest["revision"]
                assert {m["id"]: m["messageTimestamp"] for m in repeated_messages} == clocks

                second_start = expected[-1][2] + 600
                append_turn(lines, thread, mode, "turn-2", "Keep this synthetic task small.", "A second synthetic final.", "final_answer",
                            started_at=second_start, completed_at=second_start + 12)
                write_history(path, lines, mode)
                if mode == "paginated":
                    project_paginated(home, thread, lines)
                await asyncio.sleep(1.1)
                updated, updated_messages = await read_all()
                assert [(m["role"], m["text"], m.get("messageTimestamp")) for m in updated_messages] == expected + [
                    ("user", "Keep this synthetic task small.", second_start + 1),
                    ("assistant", "A second synthetic final.", second_start + 7),
                ]
                assert {m["id"]: m["messageTimestamp"] for m in updated_messages[:-2]} == clocks
                stale = await phone.get("/v1/panes/w1:p2/conversation", params={"cursor": latest["olderCursor"]})
                assert stale.status_code == 409

                lines.append({"timestamp": datetime.fromtimestamp(second_start + 13, timezone.utc).isoformat(),
                              "type": "event_msg", "payload": {"type": "thread_rolled_back", "num_turns": 1}})
                write_history(path, lines, mode)
                if mode == "paginated":
                    with sqlite3.connect(home / "thread_history_1.sqlite") as db:
                        db.execute("DELETE FROM thread_items WHERE thread_id=? AND turn_id=?", (thread, "turn-2"))
                        db.execute("DELETE FROM thread_turns WHERE thread_id=? AND turn_id=?", (thread, "turn-2"))
                await asyncio.sleep(1.1)
                rolled_back, rolled_back_messages = await read_all()
                assert {m["id"]: m["messageTimestamp"] for m in rolled_back_messages} == clocks
                assert rolled_back["historyEpoch"] != updated["historyEpoch"]
                stale_after_rollback = await phone.get("/v1/panes/w1:p2/conversation", params={"cursor": updated["olderCursor"]})
                assert stale_after_rollback.status_code == 409
        assert all(request["method"] in {"session.snapshot", "agent.get"} for request in requests)
    assert list((home / "sessions").rglob("rollout-*.jsonl")) == [path]
