from __future__ import annotations

import httpx
import pytest

from herdr_bridge.app import create_app
from .conftest import AUTH, TOKEN


async def test_history_is_read_only_bounded_and_always_targets_selected_pane(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.get("/v1/panes/w1:p1/scrollback")
    assert response.json() == {"paneId": "w1:p1", "text": "shell history\n", "truncated": False}
    assert len(requests) == 1 and requests[0]["method"] == "pane.read"
    assert requests[0]["params"] == {"pane_id": "w1:p1", "source": "recent_unwrapped", "format": "text", "lines": 1000, "strip_ansi": True, "intent": "interactive"}


@pytest.mark.parametrize("text", ["😀" * 20000, "line\n" * 1100, "x" * 65537])
async def test_large_history_keeps_recent_complete_unicode_and_marks_truncation(socket_server, text):
    backend, _, results, _ = socket_server
    results["pane.read"]["read"]["text"] = text
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.get("/v1/panes/w1:p1/scrollback")
    value = response.json()
    assert response.status_code == 200 and value["truncated"] is True
    assert len(value["text"].encode("utf-8")) <= 65536 and len(value["text"].splitlines()) <= 1000
    assert text.endswith(value["text"])


@pytest.mark.parametrize("change", [{"pane_id": "w1:p2"}, {"format": "ansi"}, {"source": "visible"}, {"text": 7}, {"truncated": "false"}, {"text": "\ud800"}])
async def test_mismatched_or_malformed_history_is_sanitized(socket_server, change):
    backend, requests, results, _ = socket_server
    results["pane.read"]["read"].update(change)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.get("/v1/panes/w1:p1/scrollback")
    assert response.status_code == 503 and len(requests) == 1
    assert "text" not in response.json() and "read" not in response.json()


async def test_history_cannot_select_desktop_focus(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.get("/v1/panes/current/scrollback")
    assert response.status_code == 400 and requests == []
