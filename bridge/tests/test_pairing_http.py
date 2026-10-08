from __future__ import annotations

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.pairing import PairingStore
from .conftest import AUTH, TOKEN


async def test_code_exchange_needs_no_bearer_and_device_token_authorizes_rest(socket_server, tmp_path):
    backend, requests, _, _ = socket_server
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Workstation")
    app = create_app(backend, TOKEN, store)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as phone:
        response = await phone.post("/v1/pair", json={"code": pending["code"], "deviceName": "Pixel"})
        assert response.status_code == 200
        result = response.json()
        assert set(result) == {"service", "protocol", "token", "deviceId", "serverName"}
        assert result["serverName"] == "Workstation" and result["token"] != TOKEN
        device_headers = {"Authorization": f"Bearer {result['token']}"}
        assert (await phone.get("/v1/health", headers=device_headers)).json()["pairing"] is True
        assert (await phone.get("/v1/snapshot", headers=device_headers)).status_code == 200
        assert (await phone.post("/v1/panes/w1:p1/input", headers=device_headers, json={"text": "explicit", "keys": ["enter"]})).json() == {"ok": True}
        assert (await phone.post("/v1/workspaces", headers=device_headers, json={"cwd": "/repo", "label": "Phone"})).status_code == 201
        assert any(request["method"] == "pane.send_input" for request in requests)
        with_owner = await phone.get("/v1/health", headers=AUTH)
        assert with_owner.status_code == 200
        repeated = await phone.post("/v1/pair", json={"code": pending["code"], "deviceName": "Other"})
        assert repeated.status_code == 401
        assert result["token"] not in repeated.text and pending["code"] not in repeated.text
    # Persistence works without sharing a Python PairingStore instance.
    restarted_app = create_app(backend, TOKEN, PairingStore(store.path))
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=restarted_app), base_url="http://localhost", headers=device_headers) as phone:
        assert (await phone.get("/v1/snapshot")).status_code == 200


async def test_offline_herdr_does_not_burn_valid_code(socket_server, tmp_path):
    backend, requests, _, options = socket_server
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Host")
    options["drop"] = True
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, store)), base_url="http://localhost") as phone:
        response = await phone.post("/v1/pair", json={"code": pending["code"], "deviceName": "Phone"})
        assert response.status_code == 503
        assert store.devices() == []
        store.check_challenge(pending["code"])
        options["drop"] = False
        paired = await phone.post("/v1/pair", json={"code": pending["code"], "deviceName": "Phone"})
        assert paired.status_code == 200
        assert len(store.devices()) == 1
    assert len(requests) == 2


async def test_expired_challenge_returns_same_unauthorized_error_before_daemon_read(socket_server, tmp_path):
    backend, requests, _, _ = socket_server
    now = [1_800_000_000]
    store = PairingStore(tmp_path / "devices.json", clock=lambda: now[0])
    pending = store.begin_pairing("Host")
    now[0] = pending["expiresAt"]
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, store)), base_url="http://localhost") as phone:
        expired = await phone.post("/v1/pair", json={"code": pending["code"], "deviceName": "Phone"})
        incorrect = await phone.post("/v1/pair", json={"code": "wrong", "deviceName": "Phone"})
        assert expired.status_code == incorrect.status_code == 401
        assert expired.json() == incorrect.json()
    assert requests == []


@pytest.mark.parametrize("code", ["", "short", "!" * 43, "😀" * 43, "A" * 43])
async def test_bad_code_has_sanitized_401_and_never_touches_herdr(socket_server, tmp_path, code):
    backend, requests, _, _ = socket_server
    store = PairingStore(tmp_path / "devices.json")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, store)), base_url="http://localhost") as phone:
        response = await phone.post("/v1/pair", json={"code": code, "deviceName": "Phone"})
        assert response.status_code == 401
        assert response.json()["detail"]
        assert "😀" not in response.text and "short" not in response.text
    assert requests == []


@pytest.mark.parametrize("body", [{}, {"code": 7, "deviceName": "Phone"}, {"code": "A" * 43, "deviceName": 7}, {"code": "A" * 43, "deviceName": "x" * 101}, {"code": "A" * 43, "deviceName": "Phone\n"}, {"code": "A" * 43, "deviceName": " "}, {"code": "A" * 43, "deviceName": "Phone", "token": TOKEN}])
async def test_malformed_pairing_requests_do_not_echo_input(socket_server, tmp_path, body):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, PairingStore(tmp_path / "devices.json"))), base_url="http://localhost") as phone:
        response = await phone.post("/v1/pair", json=body)
        assert response.status_code == 422
        assert TOKEN not in response.text and "A" * 43 not in response.text
    assert requests == []


async def test_unauthenticated_pairing_still_has_body_limit(socket_server, tmp_path):
    backend, requests, _, _ = socket_server
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Host")
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, store)), base_url="http://localhost") as phone:
        response = await phone.post("/v1/pair", content=b"x" * 65537)
        assert response.status_code == 413

        async def streamed():
            yield b"x" * 32768
            yield b"y" * 32769

        response = await phone.post("/v1/pair", content=streamed())
        assert response.status_code == 413
        store.check_challenge(pending["code"])
    assert requests == []


@pytest.mark.parametrize("method,path", [("GET", "/v1/pair"), ("POST", "/v1/pair/"), ("POST", "/v1/pairing"), ("GET", "/v1/snapshot"), ("POST", "/v1/workspaces")])
async def test_auth_exception_is_only_exact_pairing_post(socket_server, tmp_path, method, path):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, PairingStore(tmp_path / "devices.json"))), base_url="http://localhost") as phone:
        response = await phone.request(method, path, json={"code": "A" * 43, "deviceName": "Phone"})
        assert response.status_code == 401
    assert requests == []


async def test_no_pairing_store_is_safe_and_advertised_as_disabled(socket_server):
    backend, requests, _, _ = socket_server
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN)), base_url="http://localhost") as phone:
        response = await phone.post("/v1/pair", json={"code": "A" * 43, "deviceName": "Phone"})
        assert response.status_code == 503
        assert requests == []
        health = await phone.get("/v1/health", headers=AUTH)
        assert health.json()["pairing"] is False


async def test_revoked_device_is_denied_and_legacy_owner_keeps_access(socket_server, tmp_path):
    backend, requests, _, _ = socket_server
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Host")
    response = store.exchange(pending["code"], "Phone")
    headers = {"Authorization": f"Bearer {response['token']}"}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=create_app(backend, TOKEN, store)), base_url="http://localhost") as phone:
        assert (await phone.get("/v1/snapshot", headers=headers)).status_code == 200
        assert PairingStore(store.path).revoke(response["deviceId"])
        count = len(requests)
        assert (await phone.get("/v1/snapshot", headers=headers)).status_code == 401
        assert (await phone.post("/v1/panes/w1:p1/input", headers=headers, json={"keys": ["enter"]})).status_code == 401
        assert len(requests) == count
        assert (await phone.get("/v1/health", headers=AUTH)).status_code == 200
        store.path.write_bytes(b"corrupt registry")
        assert (await phone.get("/v1/health", headers=headers)).status_code == 401
        assert (await phone.get("/v1/health", headers=AUTH)).status_code == 200
