from __future__ import annotations

import hashlib
import json
import multiprocessing
import os
from pathlib import Path
import stat
import uuid

import pytest

from herdr_bridge.pairing import MAX_REGISTRY_BYTES, PairingError, PairingStore


class Clock:
    def __init__(self):
        self.now = 1_800_000_000

    def __call__(self):
        return self.now


def make_device(store, name="Pixel 9"):
    code = store.begin_pairing("Development host")["code"]
    return store.exchange(code, name)


def _exchange_worker(path, code, ready, start, output):
    store = PairingStore(Path(path))
    ready.put(True)
    start.wait(10)
    try:
        response = store.exchange(code, "Concurrent phone")
        output.put((200, response["deviceId"], response["token"]))
    except PairingError as exc:
        output.put((exc.status, None, None))


def _auth_worker(path, token, revoked, output):
    store = PairingStore(Path(path))
    output.put(store.authorized(token))
    revoked.wait(10)
    output.put(store.authorized(token))


def test_device_token_persists_as_hash_only_with_private_files(tmp_path):
    path = tmp_path / "private" / "devices.json"
    store = PairingStore(path)
    pending = store.begin_pairing("Desktop")
    assert len(pending["code"]) == 43
    before = path.read_text()
    assert pending["code"] not in before
    assert hashlib.sha256(pending["code"].encode()).hexdigest() in before
    response = store.exchange(pending["code"], "My Android")
    assert set(response) == {"service", "protocol", "token", "deviceId", "serverName"}
    assert response["service"] == "herdr-android-bridge" and response["protocol"] == 1
    assert response["serverName"] == "Desktop"
    assert len(response["token"]) == 43
    assert response["token"] != pending["code"]
    assert uuid.UUID(response["deviceId"]).version == 4
    after = path.read_text()
    assert response["token"] not in after and pending["code"] not in after
    assert hashlib.sha256(response["token"].encode()).hexdigest() in after
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(store.lock_path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
    assert path.stat().st_size < MAX_REGISTRY_BYTES
    assert PairingStore(path).authorized(response["token"])
    assert not PairingStore(path).authorized("x" * 43)
    metadata = store.devices()
    assert set(metadata[0]) == {"deviceId", "deviceName", "serverName", "createdAt", "revokedAt"}
    assert metadata[0]["deviceName"] == "My Android"
    assert "Hash" not in json.dumps(metadata) and response["token"] not in json.dumps(metadata)


def test_used_challenge_cannot_be_replayed_and_revocation_persists(tmp_path):
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Host")
    result = store.exchange(pending["code"], "Phone")
    with pytest.raises(PairingError) as exc:
        PairingStore(store.path).exchange(pending["code"], "Second phone")
    assert exc.value.status == 401
    assert store.revoke(result["deviceId"])
    assert not PairingStore(store.path).authorized(result["token"])
    assert not store.revoke(result["deviceId"])
    assert not store.revoke(str(uuid.uuid4()))
    assert not store.revoke("current")
    assert store.devices()[0]["revokedAt"] is not None


def test_expiration_at_exact_deadline_is_rejected_and_pruned(tmp_path):
    clock = Clock()
    store = PairingStore(tmp_path / "devices.json", clock=clock)
    pending = store.begin_pairing("Host")
    assert pending["expiresAt"] == clock.now + 300
    clock.now += 299
    store.check_challenge(pending["code"])
    clock.now += 1
    with pytest.raises(PairingError) as exc:
        store.exchange(pending["code"], "Phone")
    assert exc.value.status == 401
    newer = store.begin_pairing("Host", ttl_seconds=1)
    registry = json.loads(store.path.read_text())
    assert len(registry["challenges"]) == 1
    clock.now += 1
    with pytest.raises(PairingError) as exc:
        store.check_challenge(newer["code"])
    assert exc.value.status == 401


@pytest.mark.parametrize("code", ["", "a", "A" * 42, "A" * 44, "!" * 43, "😀" * 43, None, 7])
def test_invalid_codes_fail_without_disclosing_submitted_value(tmp_path, code):
    store = PairingStore(tmp_path / "devices.json")
    with pytest.raises(PairingError) as exc:
        store.exchange(code, "Phone")
    assert exc.value.status == 401
    assert "😀" not in exc.value.detail and "!" not in exc.value.detail
    assert not store.authorized(code)


@pytest.mark.parametrize("name", ["", " " * 4, "x" * 101, "Phone\n", "Phone\x00", "Phone\x7f", "Phone\u202e", 7])
def test_invalid_names_do_not_consume_challenge(tmp_path, name):
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Host")
    with pytest.raises(PairingError) as exc:
        store.exchange(pending["code"], name)
    assert exc.value.status == 400
    assert store.exchange(pending["code"], "Valid phone")["serverName"] == "Host"


@pytest.mark.parametrize("ttl", [0, -1, 301, True, 1.5])
def test_pairing_lifetime_is_bounded(tmp_path, ttl):
    with pytest.raises(PairingError) as exc:
        PairingStore(tmp_path / "devices.json").begin_pairing("Host", ttl_seconds=ttl)
    assert exc.value.status == 400


@pytest.mark.parametrize("which", ["registry", "lock"])
def test_shared_files_are_rejected_and_device_auth_fails_closed(tmp_path, which):
    store = PairingStore(tmp_path / "devices.json")
    device = make_device(store)
    path = store.path if which == "registry" else store.lock_path
    path.chmod(0o644)
    assert not store.authorized(device["token"])
    with pytest.raises(PairingError) as exc:
        store.begin_pairing("Host")
    assert exc.value.status == 503


@pytest.mark.parametrize("which", ["registry", "lock"])
def test_symlinks_and_hardlinks_cannot_replace_private_files(tmp_path, which):
    store = PairingStore(tmp_path / "devices.json")
    device = make_device(store)
    path = store.path if which == "registry" else store.lock_path
    original = path.read_bytes()
    target = tmp_path / "target"
    path.rename(target)
    path.symlink_to(target)
    assert not store.authorized(device["token"])
    with pytest.raises(PairingError):
        store.begin_pairing("Host")
    assert target.read_bytes() == original
    path.unlink()
    os.link(target, path)
    assert not store.authorized(device["token"])
    with pytest.raises(PairingError):
        store.begin_pairing("Host")
    assert target.read_bytes() == original


@pytest.mark.parametrize("content", [b"not json", b'[]', b'{"version":1,"version":1,"challenges":[],"devices":[]}', b'x' * (MAX_REGISTRY_BYTES + 1)])
def test_corrupt_or_oversized_registry_is_not_reset(tmp_path, content):
    store = PairingStore(tmp_path / "devices.json")
    device = make_device(store)
    store.path.write_bytes(content)
    assert not store.authorized(device["token"])
    with pytest.raises(PairingError) as exc:
        store.begin_pairing("Host")
    assert exc.value.status == 503
    assert store.path.read_bytes() == content


def test_challenge_and_device_capacity_cannot_consume_a_code_on_failure(tmp_path, monkeypatch):
    import herdr_bridge.pairing as pairing
    monkeypatch.setattr(pairing, "MAX_CHALLENGES", 2)
    monkeypatch.setattr(pairing, "MAX_DEVICES", 1)
    store = PairingStore(tmp_path / "devices.json")
    first = store.begin_pairing("Host")
    second = store.begin_pairing("Host")
    with pytest.raises(PairingError):
        store.begin_pairing("Host")
    store.exchange(first["code"], "First phone")
    with pytest.raises(PairingError) as exc:
        store.exchange(second["code"], "Second phone")
    assert exc.value.status == 503
    store.check_challenge(second["code"])
    assert len(store.devices()) == 1


def test_failed_atomic_replacement_preserves_challenge_and_cleans_temporary_files(tmp_path, monkeypatch):
    import herdr_bridge.pairing as pairing
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Host")
    original = store.path.read_bytes()

    def unavailable_replace(_source, _destination):
        raise OSError("simulated disk failure")

    with monkeypatch.context() as isolated_patch:
        isolated_patch.setattr(pairing.os, "replace", unavailable_replace)
        with pytest.raises(PairingError) as exc:
            store.exchange(pending["code"], "Phone")
        assert exc.value.status == 503
    assert store.path.read_bytes() == original
    assert not list(tmp_path.glob(".devices.json.*"))
    assert store.devices() == []
    store.check_challenge(pending["code"])
    assert store.exchange(pending["code"], "Phone")["deviceId"]


def test_one_code_has_exactly_one_winner_across_processes(tmp_path):
    context = multiprocessing.get_context("spawn")
    store = PairingStore(tmp_path / "devices.json")
    pending = store.begin_pairing("Race host")
    ready, output, start = context.Queue(), context.Queue(), context.Event()
    workers = [context.Process(target=_exchange_worker, args=(str(store.path), pending["code"], ready, start, output)) for _ in range(4)]
    try:
        for worker in workers:
            worker.start()
        for _ in workers:
            assert ready.get(timeout=15)
        start.set()
        results = [output.get(timeout=15) for _ in workers]
        assert sorted(result[0] for result in results) == [200, 401, 401, 401]
        winner = next(result for result in results if result[0] == 200)
        assert PairingStore(store.path).authorized(winner[2])
        assert len(store.devices()) == 1
        assert store.devices()[0]["deviceId"] == winner[1]
    finally:
        start.set()
        for worker in workers:
            worker.join(timeout=5)
            if worker.is_alive():
                worker.kill()
                worker.join(timeout=5)
        ready.close()
        output.close()


def test_running_process_observes_revocation_without_restarting(tmp_path):
    context = multiprocessing.get_context("spawn")
    store = PairingStore(tmp_path / "devices.json")
    device = make_device(store)
    output, revoked = context.Queue(), context.Event()
    worker = context.Process(target=_auth_worker, args=(str(store.path), device["token"], revoked, output))
    worker.start()
    try:
        assert output.get(timeout=15) is True
        assert PairingStore(store.path).revoke(device["deviceId"])
        revoked.set()
        assert output.get(timeout=15) is False
    finally:
        revoked.set()
        worker.join(timeout=5)
        if worker.is_alive():
            worker.kill()
            worker.join(timeout=5)
        output.close()
