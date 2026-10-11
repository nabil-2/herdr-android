from __future__ import annotations

import asyncio
from dataclasses import replace
import json
import os
from pathlib import Path
import threading

import pytest

from herdr_bridge.codex_identity import DaemonRuntime
from herdr_bridge.codex_selection_store import CodexSelectionStore, MAX_STORE_BYTES, selection_fingerprint

OWNER = "a" * 64
OTHER_OWNER = "b" * 64
FINGERPRINT = "c" * 64
DIGEST = "d" * 64
THREAD = "12345678-90ab-7cde-8fab-0123456789ab"
OTHER_THREAD = "22345678-90ab-7cde-8fab-0123456789ab"
BOOT = "32345678-90ab-7cde-8fab-0123456789ab"


def runtime():
    return DaemonRuntime("w1:p2", "terminal_fixture", 101, 1001, 2001, 2002, 102, 3001,
                         Path("/tmp/fixture/daemon.sock"), 11, 12, Path("/tmp/fixture/home"), 13, 14)


def test_only_reconnecting_peer_endpoint_inodes_are_excluded_from_fingerprint():
    original = runtime()
    assert selection_fingerprint(original, boot_id=BOOT) == selection_fingerprint(
        replace(original, frontend_socket=9001, daemon_socket=9002), boot_id=BOOT)


@pytest.mark.parametrize("changes", [
    {"pane_id": "w1:p3"}, {"terminal_id": "replacement-terminal"}, {"frontend_pid": 201},
    {"frontend_start": 2001}, {"daemon_pid": 202}, {"daemon_start": 4001},
    {"socket_path": Path("/tmp/other.sock")}, {"socket_device": 31}, {"socket_inode": 32},
    {"home": Path("/tmp/other-home")}, {"herdr_device": 33}, {"herdr_inode": 34},
])
def test_each_process_terminal_and_host_replacement_rotates_fingerprint(changes):
    original = runtime()
    assert selection_fingerprint(original, boot_id=BOOT) != selection_fingerprint(replace(original, **changes), boot_id=BOOT)


def test_reboot_rotates_fingerprint_and_missing_proof_does_not_generate_one(monkeypatch):
    assert selection_fingerprint(runtime(), boot_id=BOOT) != selection_fingerprint(runtime(), boot_id=OTHER_THREAD)
    assert selection_fingerprint(runtime(), boot_id="not-a-boot-id") is None
    assert selection_fingerprint(replace(runtime(), frontend_pid=True), boot_id=BOOT) is None
    assert selection_fingerprint(replace(runtime(), socket_inode=False), boot_id=BOOT) is None
    assert selection_fingerprint(replace(runtime(), home=Path("relative")), boot_id=BOOT) is None
    monkeypatch.setattr("builtins.open", lambda *_args, **_kwargs: (_ for _ in ()).throw(FileNotFoundError()))
    assert selection_fingerprint(runtime()) is None


async def test_explicit_choice_survives_restart_only_for_matching_owner_pane_and_runtime(tmp_path):
    root = tmp_path / "private" / "codex-selections"
    assert await CodexSelectionStore(root).remember(OWNER, "w1:p2", FINGERPRINT, THREAD, DIGEST)
    restarted = CodexSelectionStore(root)
    assert await restarted.load(OWNER, "w1:p2", FINGERPRINT) == (THREAD, DIGEST)
    assert await restarted.load(OTHER_OWNER, "w1:p2", FINGERPRINT) is None
    assert await restarted.load(OWNER, "w1:p3", FINGERPRINT) is None
    assert await restarted.load(OWNER, "w1:p2", "e" * 64) is None
    assert root.stat().st_mode & 0o777 == 0o700
    ledger = root / "selections.json"
    assert ledger.stat().st_mode & 0o777 == 0o600 and ledger.stat().st_uid == os.getuid()
    stored = json.loads(ledger.read_text())
    assert set(stored["records"][0]) == {"ownerHash", "paneId", "fingerprint", "threadId", "bindingDigest"}
    assert "/tmp" not in ledger.read_text() and "Chosen conversation" not in ledger.read_text()


async def test_remember_and_forget_are_scoped_without_removing_another_device_or_pane(tmp_path):
    store = CodexSelectionStore(tmp_path / "selections")
    assert await store.remember(OWNER, "w1:p2", FINGERPRINT, THREAD, DIGEST)
    assert await store.remember(OTHER_OWNER, "w1:p2", FINGERPRINT, OTHER_THREAD, DIGEST)
    assert await store.remember(OWNER, "w1:p3", FINGERPRINT, OTHER_THREAD, DIGEST)
    assert await store.remember(OWNER, "w1:p2", "e" * 64, OTHER_THREAD, "f" * 64)
    assert await store.load(OWNER, "w1:p2", FINGERPRINT) is None
    assert await store.load(OWNER, "w1:p2", "e" * 64) == (OTHER_THREAD, "f" * 64)
    await store.forget(OWNER, "w1:p2")
    assert await store.load(OWNER, "w1:p2", "e" * 64) is None
    assert await store.load(OTHER_OWNER, "w1:p2", FINGERPRINT) == (OTHER_THREAD, DIGEST)
    assert await store.load(OWNER, "w1:p3", FINGERPRINT) == (OTHER_THREAD, DIGEST)


async def test_store_is_bounded_to_the_latest_32_explicit_scopes(tmp_path):
    store = CodexSelectionStore(tmp_path / "selections")
    for number in range(1, 35):
        assert await store.remember(OWNER, f"w1:p{number}", FINGERPRINT, THREAD, DIGEST)
    assert await store.load(OWNER, "w1:p1", FINGERPRINT) is None
    assert await store.load(OWNER, "w1:p2", FINGERPRINT) is None
    assert await store.load(OWNER, "w1:p3", FINGERPRINT) == (THREAD, DIGEST)
    ledger = store.root / "selections.json"
    assert len(json.loads(ledger.read_text())["records"]) == 32
    assert ledger.stat().st_size <= MAX_STORE_BYTES


async def test_concurrent_store_instances_do_not_lose_each_others_explicit_choices(tmp_path):
    root = tmp_path / "selections"
    left, right = CodexSelectionStore(root), CodexSelectionStore(root)
    results = await asyncio.gather(*(store.remember(OWNER, f"w1:p{number}", FINGERPRINT, THREAD, DIGEST)
                                    for number, store in enumerate([left, right] * 8, start=1)))
    assert all(results)
    assert len(json.loads((root / "selections.json").read_text())["records"]) == 16


async def test_cancelled_disk_write_finishes_before_a_new_selection_can_replace_it(tmp_path, monkeypatch):
    store = CodexSelectionStore(tmp_path / "selections")
    operation = store._operation
    started, release = threading.Event(), threading.Event()

    def delayed(*arguments):
        if arguments[0] == "remember" and arguments[4] == THREAD:
            started.set()
            assert release.wait(2)
        return operation(*arguments)

    monkeypatch.setattr(store, "_operation", delayed)
    pending = asyncio.create_task(store.remember(OWNER, "w1:p2", FINGERPRINT, THREAD, DIGEST))
    assert await asyncio.to_thread(started.wait, 2)
    pending.cancel()
    await asyncio.sleep(0)
    assert not pending.done()
    release.set()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert await store.remember(OWNER, "w1:p2", FINGERPRINT, OTHER_THREAD, DIGEST)
    assert await store.load(OWNER, "w1:p2", FINGERPRINT) == (OTHER_THREAD, DIGEST)


@pytest.mark.parametrize("kind", ["symlink", "hardlink", "directory", "fifo", "public", "oversized", "malformed", "duplicate", "duplicateKey", "nested", "versionBool"])
async def test_unsafe_or_unrecognized_ledgers_are_not_read_or_overwritten(tmp_path, kind):
    root = tmp_path / "selections"
    store = CodexSelectionStore(root)
    assert await store.remember(OWNER, "w1:p2", FINGERPRINT, THREAD, DIGEST)
    ledger = root / "selections.json"
    if kind == "symlink":
        outside = tmp_path / "outside"
        ledger.rename(outside)
        ledger.symlink_to(outside)
    elif kind == "hardlink":
        os.link(ledger, tmp_path / "outside")
    elif kind == "directory":
        ledger.unlink()
        ledger.mkdir()
    elif kind == "fifo":
        ledger.unlink()
        os.mkfifo(ledger, 0o600)
    elif kind == "public":
        ledger.chmod(0o644)
    elif kind == "oversized":
        ledger.write_bytes(b"x" * (MAX_STORE_BYTES + 1))
    elif kind == "malformed":
        ledger.write_text('{"version":1,"records":[{"threadId":"/private/path"}]}')
    elif kind == "duplicate":
        payload = json.loads(ledger.read_text())
        payload["records"].append(dict(payload["records"][0]))
        ledger.write_text(json.dumps(payload))
    elif kind == "duplicateKey":
        ledger.write_text('{"version":2,"version":1,"records":[]}')
    elif kind == "nested":
        ledger.write_text('[' * 1500 + ']' * 1500)
    else:
        payload = json.loads(ledger.read_text())
        payload["version"] = True
        ledger.write_text(json.dumps(payload))
    special = kind in {"directory", "fifo"}
    original = None if special else ledger.read_bytes()
    assert await store.load(OWNER, "w1:p2", FINGERPRINT) is None
    assert not await store.remember(OWNER, "w1:p2", FINGERPRINT, OTHER_THREAD, DIGEST)
    await store.forget(OWNER, "w1:p2")
    assert ledger.exists() if special else ledger.read_bytes() == original


@pytest.mark.parametrize("kind", ["ancestorSymlink", "rootSymlink", "publicDirectory", "lockSymlink"])
async def test_unsafe_directories_or_locks_cannot_redirect_private_writes(tmp_path, kind):
    outside = tmp_path / "outside"
    outside.mkdir(mode=0o700)
    marker = outside / "marker"
    marker.write_text("untouched")
    if kind == "ancestorSymlink":
        alias = tmp_path / "alias"
        alias.symlink_to(outside)
        root = alias / "selections"
    elif kind == "rootSymlink":
        root = tmp_path / "selections"
        root.symlink_to(outside)
    elif kind == "publicDirectory":
        root = tmp_path / "selections"
        root.mkdir(mode=0o755)
    else:
        root = tmp_path / "selections"
        root.mkdir(mode=0o700)
        (root / ".lock").symlink_to(marker)
    store = CodexSelectionStore(root)
    assert not await store.remember(OWNER, "w1:p2", FINGERPRINT, THREAD, DIGEST)
    assert await store.load(OWNER, "w1:p2", FINGERPRINT) is None
    assert marker.read_text() == "untouched"
    assert sorted(path.name for path in outside.iterdir()) == ["marker"]


async def test_atomic_replace_failure_preserves_previous_choice_and_removes_temporary_file(tmp_path, monkeypatch):
    root = tmp_path / "selections"
    store = CodexSelectionStore(root)
    assert await store.remember(OWNER, "w1:p2", FINGERPRINT, THREAD, DIGEST)
    original = (root / "selections.json").read_bytes()
    monkeypatch.setattr(os, "replace", lambda *_args, **_kwargs: (_ for _ in ()).throw(OSError("synthetic atomic failure")))
    assert not await store.remember(OWNER, "w1:p2", FINGERPRINT, OTHER_THREAD, DIGEST)
    assert (root / "selections.json").read_bytes() == original
    assert sorted(path.name for path in root.iterdir()) == [".lock", "selections.json"]


@pytest.mark.parametrize("changes", [{"owner": "raw-bearer-token"}, {"pane_id": "../pane"},
                                      {"fingerprint": "prefix"}, {"thread_id": "/private/path"},
                                      {"binding_digest": "legacy-binding"}])
async def test_invalid_identifiers_never_create_storage(tmp_path, changes):
    root = tmp_path / "selections"
    arguments = dict(owner=OWNER, pane_id="w1:p2", fingerprint=FINGERPRINT, thread_id=THREAD, binding_digest=DIGEST)
    arguments.update(changes)
    assert not await CodexSelectionStore(root).remember(**arguments)
    assert not root.exists()
