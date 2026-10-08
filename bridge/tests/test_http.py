from __future__ import annotations

import httpx
import pytest

from herdr_bridge.app import create_app
from .conftest import AUTH, TOKEN


@pytest.mark.parametrize("path,method", [("/v1/health", "GET"), ("/v1/snapshot", "GET"), ("/v1/panes/w1:p1/input", "POST"), ("/v1/workspaces", "POST"), ("/v1/unknown", "GET")])
async def test_every_v1_http_request_requires_token(socket_server, path, method):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost") as client:
        for headers in [{}, {"Authorization": "Bearer wrong"}, {"Authorization": "Basic whatever"}]:
            response = await client.request(method, path, headers=headers, content=b"invalid json")
            assert response.status_code == 401
            assert "wrong" not in response.text
        assert requests == []


async def test_snapshot_and_health_normalize_real_schema(socket_server):
    backend, _, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.get("/v1/snapshot")
        assert response.status_code == 200
        snapshot = response.json()
        assert snapshot["herdrVersion"] == "0.9.3"
        assert snapshot["workspaces"] == [{"id": "w1", "label": "Project", "cwd": "/repo/subdir"}]
        assert snapshot["tabs"][1] == {"id": "w1:t2", "workspaceId": "w1", "label": "Agent"}
        assert snapshot["panes"][1] == {"id": "w1:p2", "workspaceId": "w1", "tabId": "w1:t2", "title": "Claude Code", "cwd": "/repo/subdir", "agent": "claude", "status": "blocked"}
        health = await client.get("/v1/health")
        assert health.json() == {"service": "herdr-android-bridge", "protocol": 1, "herdrVersion": "0.9.3"}


async def test_text_and_keys_are_sent_atomically_to_explicit_target(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/w1:p2/input", json={"text": "printf '%s' '$HOME'", "keys": ["enter"]})
        assert response.status_code == 200
        assert response.json() == {"ok": True}
    assert len(requests) == 1
    assert requests[0]["method"] == "pane.send_input"
    assert requests[0]["params"] == {"pane_id": "w1:p2", "text": "printf '%s' '$HOME'", "keys": ["enter"]}


@pytest.mark.parametrize("body", [{}, {"text": "", "keys": []}, {"text": "x" * 16385}, {"text": "😀" * 10000}, {"text": "a\x00b"}, {"keys": ["enter\n"]}, {"keys": ["enter"] * 33}, {"keys": [7]}, {"text": "hello", "pane_id": "w1:p2"}])
async def test_invalid_input_never_reaches_herdr(socket_server, body):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/w1:p1/input", json=body)
        assert response.status_code in (413, 422)
        assert response.json()["detail"]
    assert requests == []


async def test_request_body_limit_and_no_credential_echo(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/w1:p1/input", content=b"x" * 65537)
        assert response.status_code == 413
        malformed = await client.post("/v1/workspaces", json={"cwd": TOKEN, "label": "x"})
        assert malformed.status_code == 422
        assert TOKEN not in malformed.text
    assert requests == []


async def test_closed_target_maps_conflict_and_does_not_retry(socket_server):
    backend, requests, results, _ = socket_server
    results["pane.send_input"] = {"error": {"code": "pane_not_found", "message": f"secret {TOKEN}"}}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/w1:p2/input", json={"keys": ["enter"]})
        assert response.status_code == 409
        assert TOKEN not in response.text
    assert len(requests) == 1


async def test_unknown_key_is_server_validated_without_partial_send(socket_server):
    backend, requests, results, _ = socket_server
    results["pane.send_input"] = {"error": {"code": "invalid_params", "message": "unsupported key"}}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/w1:p1/input", json={"text": "must not submit", "keys": ["unsupported"]})
        assert response.status_code == 400
    assert len(requests) == 1
    assert requests[0]["method"] == "pane.send_input"


@pytest.mark.parametrize("option", ["drop", "wrong_id"])
async def test_uncertain_write_never_retried(socket_server, option):
    backend, requests, _, options = socket_server
    options[option] = True
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/w1:p1/input", json={"text": "one submission", "keys": ["enter"]})
        assert response.status_code == 503
        assert "retry" in response.json()["detail"]
    assert len(requests) == 1


async def test_invalid_target_never_uses_focused_pane(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/panes/current/input", json={"text": "oops"})
        assert response.status_code == 400
    assert requests == []


async def test_workspace_creation_preserves_user_focus(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.post("/v1/workspaces", json={"cwd": "/srv/project", "label": "Phone"})
        assert response.status_code == 201
        assert response.json() == {"id": "w2"}
    assert requests[0]["params"] == {"cwd": "/srv/project", "label": "Phone", "focus": False}


async def test_missing_daemon_reports_unavailable(tmp_path):
    from herdr_bridge.herdr import HerdrClient
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(HerdrClient(tmp_path / "absent.sock"), TOKEN)), base_url="http://localhost", headers=AUTH) as client:
        response = await client.get("/v1/health")
        assert response.status_code == 503
