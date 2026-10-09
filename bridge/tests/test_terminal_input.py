"""Direct keyboard input must bypass Herdr's bracketed-paste text encoder."""
from __future__ import annotations

import httpx
import pytest

from herdr_bridge.app import create_app
from .conftest import AUTH, TOKEN


@pytest.mark.parametrize("text", ["hello 世界 😀", "\x03", "\x00", "\x1b[A\r\x7f", "\x1b[200~literal\ntext\x1b[201~"])
async def test_raw_keyboard_controls_are_preserved_once_to_explicit_target(socket_server, text):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.post("/v1/panes/w1:p2/terminal-input", json={"text": text})
    assert response.status_code == 200 and response.json() == {"ok": True}
    assert len(requests) == 1
    assert requests[0]["method"] == "pane.send_text"
    assert requests[0]["params"] == {"pane_id": "w1:p2", "text": text}


@pytest.mark.parametrize("body", [{}, {"text": ""}, {"text": 3}, {"text": "x", "keys": ["enter"]}, {"text": "x", "method": "pane.close"}, {"text": "x" * 16385}, {"text": "😀" * 9000}])
async def test_invalid_keyboard_requests_never_dispatch_or_echo_text(socket_server, body):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.post("/v1/panes/w1:p2/terminal-input", json=body)
    assert response.status_code in {413, 422}
    assert "input" not in response.json() and requests == []


@pytest.mark.parametrize("pane_id", ["current", "w0:p1", "w1:p0", "w1:p1;pane.close"])
async def test_keyboard_never_falls_back_to_focused_pane(socket_server, pane_id):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.post(f"/v1/panes/{pane_id}/terminal-input", json={"text": "safe"})
    assert response.status_code == 400 and requests == []


@pytest.mark.parametrize("option", ["drop", "wrong_id"])
async def test_keyboard_transport_uncertainty_never_retries(socket_server, option):
    backend, requests, _, options = socket_server
    options[option] = True
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.post("/v1/panes/w1:p2/terminal-input", json={"text": "\x03"})
    assert response.status_code == 503 and len(requests) == 1


@pytest.mark.parametrize("result", [{"type": "session_snapshot"}, {"error": {"code": "pane_send_failed", "message": TOKEN}}])
async def test_keyboard_unconfirmed_ack_does_not_expose_upstream_input(socket_server, result):
    backend, requests, results, _ = socket_server
    results["pane.send_text"] = result
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as phone:
        response = await phone.post("/v1/panes/w1:p2/terminal-input", json={"text": "\r"})
    assert response.status_code == 503 and TOKEN not in response.text and len(requests) == 1
