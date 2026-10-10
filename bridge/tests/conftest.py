from __future__ import annotations

import asyncio
from copy import deepcopy
import json

import pytest

from herdr_bridge.herdr import HerdrClient

TOKEN = "test-only-not-a-real-token-0123456789abcdef"
AUTH = {"Authorization": f"Bearer {TOKEN}"}

# Matches the bundled Herdr 0.9.3 API schema (workspace has no cwd).
SNAPSHOT = {
    "version": "0.9.3", "protocol": 22,
    "workspaces": [{"workspace_id": "w1", "label": "Project", "active_tab_id": "w1:t2"}],
    "tabs": [
        {"tab_id": "w1:t1", "workspace_id": "w1", "label": "Shell"},
        {"tab_id": "w1:t2", "workspace_id": "w1", "label": "Agent"},
    ],
    "panes": [
        {"pane_id": "w1:p1", "workspace_id": "w1", "tab_id": "w1:t1", "cwd": "/repo", "agent_status": "unknown"},
        {"pane_id": "w1:p2", "workspace_id": "w1", "tab_id": "w1:t2", "cwd": "/repo", "foreground_cwd": "/repo/subdir", "agent": "claude", "agent_status": "blocked", "terminal_title_stripped": "Claude Code"},
    ],
    "agents": [], "layouts": [],
}


@pytest.fixture
async def socket_server(tmp_path):
    path = tmp_path / "herdr.sock"
    requests = []
    results = {
        "session.snapshot": {"type": "session_snapshot", "snapshot": deepcopy(SNAPSHOT)},
        "pane.send_input": {"type": "ok"},
        "pane.send_text": {"type": "ok"},
        "pane.read": {"type": "pane_read", "read": {"pane_id": "w1:p1", "source": "recent_unwrapped", "format": "text", "text": "shell history\n", "truncated": False}},
        "workspace.create": {"type": "workspace_created", "workspace": {"workspace_id": "w2"}},
    }
    options = {"drop": False, "wrong_id": False}

    async def respond(reader, writer):
        try:
            request = json.loads(await reader.readline())
            requests.append(request)
            if options["drop"]:
                return
            if request["method"] == "pane.process_info" and request["method"] not in results:
                result = {"type": "pane_process_info", "process_info": {
                    "pane_id": request["params"]["pane_id"], "foreground_processes": []}}
            else:
                result = results[request["method"]]
            response = {"id": "wrong" if options["wrong_id"] else request["id"]}
            if "error" in result:
                response.update(result)
            else:
                response["result"] = result
            writer.write(json.dumps(response).encode() + b"\n")
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    server = await asyncio.start_unix_server(respond, path=str(path))
    async with server:
        yield HerdrClient(path, timeout=1), requests, results, options
