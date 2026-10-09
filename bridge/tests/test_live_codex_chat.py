"""Optional real-Codex reader checks, using only synthetic isolated histories.

CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
No model is called and no existing agent or conversation is accessed.
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
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


def history(home: Path, thread: str, mode: str) -> tuple[Path, list[dict]]:
    path = home / "sessions/2026/10/09" / f"rollout-2026-10-09T10-00-00-{thread}.jsonl"
    path.parent.mkdir(parents=True)
    lines = [{"timestamp": STAMP, "type": "session_meta", "payload": {
        "id": thread, "session_id": thread, "timestamp": STAMP, "cwd": str(home),
        "originator": "codex", "cli_version": "0.162.0", "source": "cli", "model_provider": "openai",
        "base_instructions": None, "dynamic_tools": None, "history_mode": mode,
    }}]
    append_turn(lines, thread, mode, "turn-1", "Hello from the phone", "**Ready.**", "final_answer")
    write_history(path, lines, mode)
    return path, lines


def append_turn(lines: list[dict], thread: str, mode: str, turn: str, user: str, reply: str, phase: str) -> None:
    def event(payload):
        lines.append({"timestamp": STAMP, "type": "event_msg", "payload": payload})

    event({"type": "task_started", "turn_id": turn, "model_context_window": None})
    if mode == "legacy":
        event({"type": "user_message", "message": user, "images": [], "local_images": []})
        event({"type": "agent_message", "message": reply, "phase": phase})
    else:
        for item in [{"type": "UserMessage", "id": turn + "-user", "content": [{"type": "text", "text": user}]},
                     {"type": "AgentMessage", "id": turn + "-agent", "content": [{"type": "Text", "text": reply}], "phase": phase}]:
            event({"type": "item_completed", "thread_id": thread, "turn_id": turn, "item": item, "completed_at_ms": 1})
    # Raw model context must not become a human conversation message.
    lines.append({"timestamp": STAMP, "type": "response_item", "payload": {
        "type": "message", "role": "user", "content": [{"type": "input_text", "text": "HIDDEN_AGENTS_CONTEXT"}],
    }})
    event({"type": "agent_reasoning", "text": "HIDDEN_REASONING"})
    event({"type": "task_complete", "turn_id": turn, "last_agent_message": reply})


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
                db.execute("INSERT INTO thread_turns (thread_id,turn_id,rollout_ordinal,status) VALUES (?,?,?,?)",
                           (thread, turn, ordinal, "completed"))
            elif kind == "item_completed":
                item = payload["item"]
                user = item["type"] == "UserMessage"
                wire = ({"type": "userMessage", "id": item["id"], "content": item["content"]} if user else
                        {"type": "agentMessage", "id": item["id"], "text": item["content"][0]["text"], "phase": item.get("phase")})
                db.execute("INSERT INTO thread_items (thread_id,turn_id,item_id,rollout_ordinal,created_at_ms,item_json,item_type,updated_at_ordinal) VALUES (?,?,?,?,?,?,?,?)",
                           (thread, turn, item["id"], ordinal, 1, json.dumps(wire), wire["type"], ordinal))
                column = "first_user_item_id" if user else "final_agent_item_id"
                db.execute(f"UPDATE thread_turns SET {column}=? WHERE thread_id=? AND turn_id=?", (item["id"], thread, turn))


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
async def test_real_codex_reads_updates_without_resuming_and_excludes_model_context(tmp_path, mode):
    executable = os.environ.get("CODEX_BIN") or shutil.which("codex")
    if executable is None:
        pytest.skip("Codex executable unavailable")
    home = tmp_path / "codex"
    thread = str(uuid.uuid4())
    path, lines = history(home, thread, mode)
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
                assert "HIDDEN_" not in response.text
                first_ids = [m["id"] for m in page["messages"]]
                first_epoch = page.get("historyEpoch")
                # Simulate a running CLI persisting a completed message externally.
                append_turn(lines, thread, mode, "turn-2", "Hello from the phone", "Second reply", "commentary")
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
                assert rolled_back.json()["historyEpoch"] != first_epoch
        assert original_config.read_text().startswith("check_for_update_on_startup = false")
    # No second transcript/thread was created by the read-only sidecar.
    assert list((home / "sessions").rglob("rollout-*.jsonl")) == [path]
