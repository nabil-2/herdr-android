"""Turn-time provenance stays truthful across SDK versions and malformed metadata."""

import pytest

from herdr_bridge.codex import normalize_thread


def thread(**times):
    return {"thread": {"id": "thread-1", "createdAt": 100, "turns": [{
        "id": "turn-1", **times, "items": [
            {"id": "user-1", "type": "userMessage", "content": [{"type": "text", "text": "Hello"}]},
            {"id": "comment-1", "type": "agentMessage", "text": "Checking", "phase": "commentary"},
            {"id": "final-1", "type": "agentMessage", "text": "Done", "phase": "final_answer"},
        ],
    }]}}


def test_official_turn_times_keep_start_end_provenance_and_stable_message_ids():
    messages, warning = normalize_thread(thread(startedAt=1791540000, completedAt=1791540060), "thread-1")
    assert warning is None
    assert [(m["timestamp"], m["timestampSource"]) for m in messages] == [
        (1791540000, "turn_start"), (1791540000, "turn_start"), (1791540060, "turn_end"),
    ]
    untimed, _ = normalize_thread(thread(), "thread-1")
    assert [m["id"] for m in messages] == [m["id"] for m in untimed]
    assert all("timestamp" not in m and "timestampSource" not in m for m in untimed)


def test_running_turn_uses_start_time_without_claiming_a_completion():
    messages, _ = normalize_thread(thread(startedAt=1791540000, completedAt=None), "thread-1")
    assert all(m["timestamp"] == 1791540000 and m["timestampSource"] == "turn_start" for m in messages)


@pytest.mark.parametrize("invalid", [True, False, "1791540000", 1791540000.5, -1, 1791540000000, {}, []])
def test_invalid_optional_times_do_not_hide_valid_messages_or_invent_dates(invalid):
    messages, warning = normalize_thread(thread(startedAt=invalid, completedAt=invalid), "thread-1")
    assert len(messages) == 3 and warning is None
    assert all("timestamp" not in m for m in messages)


def test_only_known_completion_is_used_when_start_is_unavailable():
    messages, _ = normalize_thread(thread(completedAt=1791540060), "thread-1")
    assert all("timestamp" not in m for m in messages[:2])
    assert messages[2]["timestampSource"] == "turn_end"
