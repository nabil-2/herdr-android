"""Official app-server history paging, using only owned synthetic rollouts."""

from __future__ import annotations

import json
import os
import shutil
import uuid

import pytest

from herdr_bridge.codex import CodexReader, MAX_RPC_BYTES, normalize_thread
from .test_live_codex_chat import history, append_turn, write_history, STAMP

pytestmark = pytest.mark.skipif(os.environ.get("CODEX_INTEGRATION") != "1", reason="Opt-in isolated Codex reader test")


def insert_owned_hidden_output(lines, home, turn_id, output_bytes):
    # The official projection retains this hidden reasoning event verbatim.
    # It exercises the same response-byte cap as a large command payload.
    first = next(index for index, record in enumerate(lines) if record.get("type") == "event_msg"
                 and record["payload"].get("type") == "task_started" and record["payload"].get("turn_id") == turn_id)
    reasoning = next(index for index in range(first + 1, len(lines))
                     if lines[index].get("type") == "event_msg" and lines[index]["payload"].get("type") == "agent_reasoning")
    lines[reasoning]["payload"]["text"] = "X" * output_bytes
    position = next(index for index in range(first + 1, len(lines))
                    if lines[index].get("type") == "event_msg" and lines[index]["payload"].get("type") == "agent_message")
    lines.insert(position, {"timestamp": STAMP, "type": "event_msg", "payload": {
        "type": "agent_message", "message": "Synthetic progress " + turn_id, "phase": "commentary"}})


@pytest.mark.parametrize("single_oversized", [False, True], ids=["large-thread-full-turn-pages", "single-turn-explicit-summary"])
async def test_official_codex_recovers_oversized_hidden_output_without_resuming(tmp_path, single_oversized, monkeypatch):
    executable = os.environ.get("CODEX_BIN") or shutil.which("codex")
    if executable is None:
        pytest.skip("Codex executable unavailable")
    home = tmp_path / "codex"
    thread = str(uuid.uuid4())
    path, lines = history(home, thread, "legacy")
    (home / "config.toml").write_text("check_for_update_on_startup = false\n[analytics]\nenabled = false\n")
    insert_owned_hidden_output(lines, home, "turn-1", MAX_RPC_BYTES + 1024 if single_oversized else 17 * 1024 * 1024)
    if not single_oversized:
        append_turn(lines, thread, "legacy", "turn-2", "Second synthetic question", "Second synthetic answer", "final_answer")
        insert_owned_hidden_output(lines, home, "turn-2", 17 * 1024 * 1024)
    write_history(path, lines, "legacy")
    assert path.stat().st_size > MAX_RPC_BYTES
    reader = CodexReader(executable, home, timeout=20)
    requests = []
    original = reader._rpc

    async def recorded(method, params):
        requests.append((method, dict(params)))
        return await original(method, params)

    monkeypatch.setattr(reader, "_rpc", recorded)
    try:
        messages, warning = normalize_thread(await reader.read(thread), thread)
        expected = ["Hello from the phone", "**Ready.**"] if single_oversized else [
            "Hello from the phone", "Synthetic progress turn-1", "**Ready.**",
            "Second synthetic question", "Synthetic progress turn-2", "Second synthetic answer"]
        assert [message["text"] for message in messages] == expected
        assert bool(warning) is single_oversized
        if single_oversized:
            assert "intermediate messages" in warning
        assert thread in reader.paged_threads
        assert all(method in {"initialize", "thread/read", "thread/turns/list"} for method, _ in requests)
        assert all(params.get("threadId", thread) == thread for _, params in requests)
        requests.clear()
        updated, updated_warning = normalize_thread(await reader.read(thread), thread)
        assert [message["id"] for message in updated] == [message["id"] for message in messages]
        assert [message["text"] for message in updated] == expected and updated_warning == warning
        assert not any(method == "thread/read" and params.get("includeTurns") for method, params in requests)
    finally:
        await reader.close()
