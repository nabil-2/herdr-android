from __future__ import annotations

import json
import sys

import pytest

from herdr_bridge import codex_history as history
from herdr_bridge.codex import CodexReader, normalize_thread
from herdr_bridge.herdr import BridgeError

THREAD = "12345678-90ab-7cde-8fab-0123456789ab"


def turn(number: int, *, output: str = "", commentary: str = "Progress") -> dict:
    return {"id": f"turn-{number}", "status": "completed", "startedAt": number, "completedAt": number + 1,
            "items": [{"id": "u", "type": "userMessage", "content": [{"type": "text", "text": f"Question {number}"}]},
                      {"id": "c", "type": "agentMessage", "text": commentary, "phase": "commentary"},
                      {"id": "tool", "type": "commandExecution", "aggregatedOutput": output},
                      {"id": "a", "type": "agentMessage", "text": f"Answer {number}", "phase": "final_answer"}]}


class HistoryServer:
    def __init__(self, turns: list[dict], *, limit: int = 1000):
        self.turns = turns
        self.limit = limit
        self.requests = []

    async def rpc(self, method: str, params: dict) -> dict:
        self.requests.append((method, dict(params)))
        if method == "thread/read":
            result = {"thread": {"id": THREAD, "turns": self.turns if params["includeTurns"] else []}}
        else:
            assert method == "thread/turns/list" and params["threadId"] == THREAD
            assert params["sortDirection"] == "desc"
            offset = int(params.get("cursor", "0"))
            data = list(reversed(self.turns))[offset:offset + params["limit"]]
            if params["itemsView"] == "summary":
                data = [value | {"items": [value["items"][0], value["items"][-1]]} for value in data]
            end = offset + len(data)
            result = {"data": data, "nextCursor": str(end) if end < len(self.turns) else None}
        if len(json.dumps(result).encode()) > self.limit:
            raise history.CodexHistoryTooLarge()
        return result


async def test_under_limit_history_preserves_single_read_and_all_visible_messages():
    server = HistoryServer([turn(1)], limit=2000)
    result = await history.read_history(server.rpc, THREAD)
    messages, warning = normalize_thread(result, THREAD)
    assert [message["text"] for message in messages] == ["Question 1", "Progress", "Answer 1"]
    assert server.requests == [("thread/read", {"threadId": THREAD, "includeTurns": True})]
    assert warning is None and history.HISTORY_PAGED_FIELD not in result


async def test_large_multi_turn_history_pages_and_preserves_every_visible_message():
    server = HistoryServer([turn(number, output="HIDDEN_TOOL" * 70) for number in range(12)], limit=2300)
    result = await history.read_history(server.rpc, THREAD)
    messages, warning = normalize_thread(result, THREAD)
    assert len(messages) == 36
    assert [message["text"] for message in messages] == [text for number in range(12)
                                                       for text in (f"Question {number}", "Progress", f"Answer {number}")]
    assert "HIDDEN_TOOL" not in json.dumps(result) and warning is None
    assert result[history.HISTORY_PAGED_FIELD] is True
    assert {params["limit"] for method, params in server.requests if method == "thread/turns/list"} == {5, 2, 1}
    assert all(params["itemsView"] == "full" for method, params in server.requests if method == "thread/turns/list")


async def test_single_oversized_turn_has_explicit_summary_warning_and_stable_ids():
    server = HistoryServer([turn(1, output="HIDDEN_TOOL" * 2000)], limit=1000)
    original_messages, _ = normalize_thread({"thread": {"id": THREAD, "turns": server.turns}}, THREAD)
    result = await history.read_history(server.rpc, THREAD)
    messages, warning = normalize_thread(result, THREAD)
    assert [message["text"] for message in messages] == ["Question 1", "Answer 1"]
    assert [message["id"] for message in messages] == [original_messages[0]["id"], original_messages[-1]["id"]]
    assert "intermediate messages" in warning and "HIDDEN_TOOL" not in json.dumps(result)
    summaries = [params for method, params in server.requests if params.get("itemsView") == "summary"]
    assert summaries == [{"threadId": THREAD, "limit": 1, "sortDirection": "desc", "itemsView": "summary"}]


@pytest.mark.parametrize("failure", [BridgeError(503, "timeout"), BridgeError(409, "permission"),
                                      history.CodexHistoryUnsupported(), history.CodexHistoryNotPersisted(THREAD)])
async def test_ordinary_failures_never_trigger_summary_or_read_other_thread(failure):
    requests = []

    async def rpc(method, params):
        requests.append((method, params))
        raise failure

    with pytest.raises(BridgeError) as caught:
        await history.read_history(rpc, THREAD)
    assert caught.value is failure
    assert requests == [("thread/read", {"threadId": THREAD, "includeTurns": True})]


@pytest.mark.parametrize("mutation", ["other-thread", "metadata-turns", "bad-cursor", "loop-cursor", "duplicate-turn",
                                      "bad-page", "bad-item", "duplicate-visible-id"])
async def test_malformed_pages_fail_closed_without_summary_fallback(mutation):
    requests = []
    number = 0

    async def rpc(method, params):
        nonlocal number
        requests.append((method, params))
        if method == "thread/read":
            return {"thread": {"id": "another-thread" if mutation == "other-thread" else THREAD,
                               "turns": [turn(1)] if mutation == "metadata-turns" else []}}
        number += 1
        value = turn(1 if mutation == "duplicate-turn" else number)
        if mutation == "bad-item":
            value["items"].append(None)
        if mutation == "duplicate-visible-id":
            value["items"].append(value["items"][0])
        return {"data": "invalid" if mutation == "bad-page" else [value],
                "nextCursor": 2 if mutation == "bad-cursor" else "cursor" if mutation == "loop-cursor" else str(number)}

    with pytest.raises(BridgeError, match="incompatible"):
        await history.read_history(rpc, THREAD, prefer_pages=True)
    assert not any(params.get("itemsView") == "summary" for _, params in requests)


async def test_history_scan_and_visible_memory_are_bounded(monkeypatch):
    monkeypatch.setattr(history, "MAX_HISTORY_TURNS", 2)
    server = HistoryServer([turn(number) for number in range(10)], limit=10000)
    result = await history.read_history(server.rpc, THREAD, prefer_pages=True)
    assert [value["id"] for value in result["thread"]["turns"]] == ["turn-8", "turn-9"]
    assert "latest" in result[history.HISTORY_WARNING_FIELD]
    monkeypatch.setattr(history, "MAX_HISTORY_TURNS", 1024)
    monkeypatch.setattr(history, "MAX_VISIBLE_HISTORY_BYTES", len(json.dumps(turn(9)).encode()))
    result = await history.read_history(server.rpc, THREAD, prefer_pages=True)
    assert len(result["thread"]["turns"]) == 1
    assert "cache limit" in result[history.HISTORY_WARNING_FIELD]


@pytest.mark.parametrize("method", ["thread/read", "thread/turns/list"])
def test_only_exact_matched_semantic_rpc_errors_are_classified(method):
    params = {"threadId": THREAD}
    unsupported = {"code": -32601, "message": "list_turns is not supported yet"}
    assert isinstance(history.history_rpc_error(method, params, unsupported), history.CodexHistoryUnsupported)
    assert history.history_rpc_error(method, params, unsupported | {"code": -32603}) is None
    assert history.history_rpc_error(method, params, unsupported | {"message": "private arbitrary error"}) is None
    assert history.history_rpc_error("turn/start", params, unsupported) is None
    missing = {"code": -32600, "message": f"no rollout found for thread id {THREAD}"}
    assert isinstance(history.history_rpc_error(method, params, missing), history.CodexHistoryNotPersisted)
    assert history.history_rpc_error(method, {"threadId": "other-thread"}, missing) is None


async def test_actual_stdio_size_violation_reconnects_only_reader_then_remembers_paging(tmp_path, monkeypatch):
    import herdr_bridge.codex as adapter
    monkeypatch.setattr(adapter, "MAX_RPC_BYTES", 16 * 1024)
    home = tmp_path / "home"
    home.mkdir()
    fixture = {"thread": {"id": THREAD, "turns": [turn(1, output="HIDDEN_TOOL" * 5000)]}}
    (home / "fixture.json").write_text(json.dumps(fixture))
    stub = tmp_path / "codex"
    stub.write_text(f"#!{sys.executable}\n" + '''
import json,os,pathlib,sys
home=pathlib.Path(os.environ['CODEX_HOME'])
with (home/'pids').open('a') as log: log.write(str(os.getpid())+'\\n')
for line in sys.stdin:
    r=json.loads(line)
    with (home/'requests').open('a') as log: log.write(json.dumps(r)+'\\n')
    if 'id' not in r: continue
    fixture=json.loads((home/'fixture.json').read_text())
    if r['method']=='initialize': result={'userAgent':'owned-synthetic'}
    elif r['method']=='thread/read':
        result=fixture if r['params']['includeTurns'] else {'thread':{'id':fixture['thread']['id'],'turns':[]}}
    else:
        turns=fixture['thread']['turns']
        if r['params']['itemsView']=='summary': turns=[t|{'items':[t['items'][0],t['items'][-1]]} for t in turns]
        result={'data':turns,'nextCursor':None}
    print(json.dumps({'id':r['id'],'result':result}),flush=True)
''')
    stub.chmod(0o700)
    reader = CodexReader(str(stub), home)
    try:
        messages, warning = normalize_thread(await reader.read(THREAD), THREAD)
        assert [message["text"] for message in messages] == ["Question 1", "Answer 1"]
        assert "intermediate messages" in warning
        (home / "requests").write_text("")
        await reader.read(THREAD)
        calls = [json.loads(line) for line in (home / "requests").read_text().splitlines()]
        assert not any(call["method"] == "thread/read" and call["params"]["includeTurns"] for call in calls)
        assert all(call["method"] in {"initialize", "initialized", "thread/read", "thread/turns/list"} for call in calls)
    finally:
        await reader.close()
