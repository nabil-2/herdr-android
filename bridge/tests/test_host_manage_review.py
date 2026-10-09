"""Regression checks for archive readers across instances and failed rollback.

Every file belongs to tmp_path. Service state is mocked; these tests never start,
stop, inspect, or change an actual Herdr/Pocket/Tailscale service.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import socket
import subprocess

import pytest

from herdr_bridge import host_manage as manage
from herdr_bridge.host_setup import SetupError, service_content


@pytest.fixture
def installation(tmp_path, monkeypatch):
    binary = tmp_path / "installation/lib/herdr-pocket/herdr-pocket"
    binary.parent.mkdir(parents=True, mode=0o700)
    binary.write_bytes(b"old archive")
    binary.chmod(0o755)
    xdg = tmp_path / "xdg"
    units = xdg / "systemd/user"
    units.mkdir(parents=True, mode=0o700)
    config_root = xdg / "herdr-android"
    monkeypatch.setenv("XDG_CONFIG_HOME", str(xdg))
    monkeypatch.setattr(manage.platform, "system", lambda: "Linux")
    monkeypatch.setattr(manage, "install_binary", lambda: binary)
    monkeypatch.setattr(manage, "default_token_path", lambda: config_root / "token")
    return binary, config_root, units


def managed_instance(binary, config_root, units, instance, *, hostname=None):
    root = config_root / "instances" / instance
    root.mkdir(parents=True, mode=0o700)
    path = root / "setup.json"
    config = {
        "format": 1, "instance": instance, "session": instance,
        "socket": str(root / "herdr.sock"), "herdrBin": "/usr/bin/false",
        "tokenFile": str(root / "token"), "registryFile": str(root / "devices.json"),
        "host": "127.0.0.1", "hostName": hostname or socket.gethostname(),
        "port": 8765, "httpsPort": 8443, "url": "https://synthetic.example.ts.net:8443/",
        "name": "Owned lifecycle fixture",
    }
    path.write_text(json.dumps(config))
    path.chmod(0o600)
    unit_name = f"herdr-pocket-{instance}.service"
    unit_file = units / unit_name
    unit_file.write_text(service_content([str(binary)], path))
    unit_file.chmod(0o600)
    return config, path, (unit_file, unit_name, [])


def mock_services(monkeypatch, readers, events):
    def active(unit):
        return unit[1] in readers

    def stop(unit):
        events.append(("stop", unit[1]))
        readers.discard(unit[1])

    def start(unit):
        events.append(("start", unit[1]))
        readers.add(unit[1])

    monkeypatch.setattr(manage, "active", active)
    monkeypatch.setattr(manage, "stop", stop)
    monkeypatch.setattr(manage, "start", start)
    monkeypatch.setattr(manage, "binary_version", lambda path: "0.3.1" if path.read_bytes() == b"old archive" else "0.4.0")
    monkeypatch.setattr(manage, "wait_healthy", lambda *args, **kwargs: None)


def test_shared_binary_discovers_managed_services_in_another_configuration_root(installation):
    binary, first_root, units = installation
    first = managed_instance(binary, first_root, units, "aaaaaaaaaaaa")
    second = managed_instance(binary, first_root.parent / "custom-pocket", units, "bbbbbbbbbbbb")
    found = manage.instances(first_root, binary=binary)
    assert {entry[1] for entry in found} == {first[1], second[1]}
    assert {entry[2][1] for entry in found} == {first[2][1], second[2][1]}


def test_shared_binary_refuses_another_host_even_outside_selected_configuration_root(installation):
    binary, first_root, units = installation
    managed_instance(binary, first_root, units, "aaaaaaaaaaaa")
    managed_instance(binary, first_root.parent / "foreign-pocket", units, "bbbbbbbbbbbb", hostname="another-machine.example")
    with pytest.raises(SetupError):
        manage.instances(first_root, binary=binary)
    assert binary.read_bytes() == b"old archive"


def legacy_instance(binary, config_root, units):
    config, path, unit = managed_instance(binary, config_root, units, "aaaaaaaaaaaa")
    config.pop("hostName")
    path.write_text(json.dumps(config))
    content = unit[0].read_text()
    unit[0].write_text("\n".join(line for line in content.splitlines() if not line.startswith("ConditionHost=")) + "\n")
    return config, path, unit


def test_legacy_instance_accepts_verified_local_tailscale_identity(installation, monkeypatch):
    binary, config_root, units = installation
    _, path, _ = legacy_instance(binary, config_root, units)
    calls = []
    monkeypatch.setattr(manage.shutil, "which", lambda name: "/synthetic/tailscale" if name == "tailscale" else None)

    def tailscale_status(arguments):
        calls.append(arguments)
        return subprocess.CompletedProcess(arguments, 0, stdout=json.dumps({"Self": {"DNSName": "SYNTHETIC.EXAMPLE.TS.NET."}}))

    monkeypatch.setattr(manage, "run", tailscale_status)
    found = manage.instances(config_root, binary=binary)
    assert [entry[1] for entry in found] == [path]
    assert calls and all(command == ["/synthetic/tailscale", "status", "--json"] for command in calls)
    assert binary.read_bytes() == b"old archive"


def test_legacy_instance_rejects_foreign_tailnet_identity_before_stopping_readers(installation, monkeypatch):
    binary, config_root, units = installation
    _, _, unit = legacy_instance(binary, config_root, units)
    readers = {unit[1]}
    events = []
    mock_services(monkeypatch, readers, events)
    monkeypatch.setattr(manage.shutil, "which", lambda name: "/synthetic/tailscale" if name == "tailscale" else None)
    monkeypatch.setattr(manage, "run", lambda arguments: subprocess.CompletedProcess(arguments, 0, stdout=json.dumps({"Self": {"DNSName": "another-machine.example.ts.net."}})))

    with pytest.raises(SetupError, match="could not be verified against local Tailscale"):
        with manage.binary_upgrade(config_root):
            pytest.fail("A foreign legacy installation reached binary replacement")

    assert not events
    assert readers == {unit[1]}
    assert binary.read_bytes() == b"old archive"


def test_late_installer_failure_stops_new_archive_readers_before_restoring_binary(installation, monkeypatch):
    binary, config_root, units = installation
    old = managed_instance(binary, config_root, units, "aaaaaaaaaaaa")
    readers = {old[2][1]}
    events = []
    mock_services(monkeypatch, readers, events)
    actual_replace = os.replace

    def replace(source, destination):
        if Path(destination) == binary and Path(source).read_bytes() == b"old archive":
            assert not readers, "A new archive reader was still running when its binary was replaced"
            events.append(("restore", str(binary)))
        return actual_replace(source, destination)

    monkeypatch.setattr(manage.os, "replace", replace)
    new = None
    with pytest.raises(SetupError):
        with manage.binary_upgrade(config_root):
            binary.write_bytes(b"new archive")
            new = managed_instance(binary, config_root, units, "bbbbbbbbbbbb")
            readers.add(new[2][1])
            raise SetupError("Synthetic Tailscale failure after a new service started")

    assert binary.read_bytes() == b"old archive"
    assert readers == {old[2][1]}
    stop_new = events.index(("stop", new[2][1]))
    restored = events.index(("restore", str(binary)))
    assert stop_new < restored


def test_failed_reader_stop_keeps_new_archive_and_retains_a_recovery_backup(installation, monkeypatch):
    binary, config_root, units = installation
    old = managed_instance(binary, config_root, units, "aaaaaaaaaaaa")
    readers = {old[2][1]}
    events = []
    mock_services(monkeypatch, readers, events)
    normal_stop = manage.stop
    new_name = "herdr-pocket-bbbbbbbbbbbb.service"

    def cannot_stop_new(unit):
        if unit[1] == new_name:
            raise SetupError("Synthetic new reader cannot stop")
        normal_stop(unit)

    monkeypatch.setattr(manage, "stop", cannot_stop_new)
    with pytest.raises(SetupError):
        with manage.binary_upgrade(config_root):
            binary.write_bytes(b"new archive")
            new = managed_instance(binary, config_root, units, "bbbbbbbbbbbb")
            readers.add(new[2][1])
            raise SetupError("Synthetic installer failure")

    assert binary.read_bytes() == b"new archive"
    assert new_name in readers
    backups = [path for path in binary.parent.rglob("*") if path != binary and path.is_file() and path.read_bytes() == b"old archive"]
    assert backups, "The previous archive must remain available for recovery"
    assert all(path.stat().st_uid == os.getuid() for path in backups)
    assert all(path.parent.stat().st_mode & 0o077 == 0 for path in backups)
