"""Management tests use owned temporary files and fake services/network only."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import socket
from types import SimpleNamespace

import pytest

from herdr_bridge import host_manage as manage
from herdr_bridge import host_setup as setup

REMOVE_SERVE_ROUTE = manage.remove_serve_route
ACTIVE = manage.active


@pytest.fixture
def installation(tmp_path, monkeypatch):
    binary = tmp_path / "install/lib/herdr-pocket/herdr-pocket"
    binary.parent.mkdir(parents=True, mode=0o700)
    binary.write_bytes(b"old archive")
    binary.chmod(0o755)
    xdg = tmp_path / "xdg"
    units = xdg / "systemd/user"
    units.mkdir(parents=True)
    root = tmp_path / "pocket"
    monkeypatch.setenv("XDG_CONFIG_HOME", str(xdg))
    monkeypatch.setattr(manage, "install_binary", lambda: binary)
    monkeypatch.setattr(manage.platform, "system", lambda: "Linux")
    monkeypatch.setattr(manage.platform, "machine", lambda: "x86_64")
    monkeypatch.setattr(manage, "default_token_path", lambda: root / "token")
    calls = []
    monkeypatch.setattr(manage, "run", lambda args, **kwargs: calls.append(args) or SimpleNamespace(returncode=0, stdout="{}"))
    stopped = set()
    monkeypatch.setattr(manage, "active", lambda unit: unit[1] not in stopped)
    monkeypatch.setattr(manage, "stop", lambda unit: calls.append(["stop", unit[1]]) or stopped.add(unit[1]))
    monkeypatch.setattr(manage, "remove_serve_route", lambda config: calls.append(["route", config["instance"]]) or True)
    return binary, root, units, calls


def instance(binary, root, units, identifier="aaaaaaaaaaaa", *, legacy=False):
    directory = root / "instances" / identifier
    directory.mkdir(parents=True, mode=0o700)
    directory.chmod(0o700)
    path = directory / "setup.json"
    config = {"format": 1, "instance": identifier, "session": identifier, "name": "Fixture",
              "hostName": socket.gethostname(), "socket": str(directory / "herdr.sock"),
              "herdrBin": "/usr/bin/false", "tokenFile": str(directory / "token"),
              "registryFile": str(directory / "devices.json"), "host": "127.0.0.1", "port": 8765,
              "httpsPort": 8443, "url": "https://test.example.ts.net:8443/"}
    if legacy:
        del config["hostName"]
    setup.write_private(path, json.dumps(config).encode())
    setup.write_private(directory / "token", b"fixture-token-kept")
    setup.write_private(directory / "devices.json", b"fixture-device-registry-kept")
    content = setup.service_content([str(binary)], path)
    if legacy:
        content = "\n".join(line for line in content.splitlines() if not line.startswith("ConditionHost="))
    unit = units / f"herdr-pocket-{identifier}.service"
    unit.write_text(content)
    unit.chmod(0o600)
    return config, path, unit


def test_uninstall_keeps_binary_used_by_another_custom_config_root(installation):
    binary, root, units, calls = installation
    config, path, unit = instance(binary, root, units)
    other, other_path, other_unit = instance(binary, root.parent / "custom", units, "bbbbbbbbbbbb")
    manage.uninstall(config, config_root=root)
    assert not unit.exists() and other_unit.exists()
    assert binary.read_bytes() == b"old archive"
    assert path.exists() and other_path.exists()
    assert Path(config["tokenFile"]).read_bytes() == b"fixture-token-kept"
    assert Path(config["registryFile"]).read_bytes() == b"fixture-device-registry-kept"
    assert calls == [["stop", unit.name], ["systemctl", "--user", "disable", unit.name],
                     ["systemctl", "--user", "daemon-reload"], ["route", config["instance"]]]


def test_uninstall_last_instance_removes_only_owned_binary_and_matching_command_link(installation):
    binary, root, units, calls = installation
    config, path, unit = instance(binary, root, units)
    link = binary.parents[2] / "bin/herdr-pocket"
    link.parent.mkdir()
    link.symlink_to(binary)
    manage.uninstall(config, config_root=root)
    assert not binary.exists() and not link.is_symlink()
    assert path.exists() and Path(config["registryFile"]).exists()
    assert all("herdr" not in command[:1] and "reset" not in command for command in calls)


def test_purge_removes_only_selected_private_instance(installation):
    binary, root, units, calls = installation
    config, path, unit = instance(binary, root, units)
    _, other_path, _ = instance(binary, root, units, "bbbbbbbbbbbb")
    manage.uninstall(config, config_root=root, purge=True)
    assert not path.parent.exists()
    assert other_path.exists() and binary.exists()


@pytest.mark.parametrize("unsafe", ["symlink", "shared-directory", "external-credentials"])
def test_unsafe_purge_is_rejected_before_any_service_or_route_mutation(installation, unsafe):
    binary, root, units, calls = installation
    config, path, unit = instance(binary, root, units)
    if unsafe == "symlink":
        (path.parent / "outside").symlink_to(root.parent)
    elif unsafe == "shared-directory":
        path.parent.chmod(0o755)
    else:
        config["tokenFile"] = str(root.parent / "external-token")
        setup.write_private(path, json.dumps(config).encode())
    with pytest.raises(setup.SetupError):
        manage.uninstall(config, config_root=root, purge=True)
    assert calls == []
    assert path.exists() and unit.exists() and binary.read_bytes() == b"old archive"


def test_legacy_owned_service_without_host_affinity_remains_upgradable(installation):
    binary, root, units, _ = installation
    _, path, _ = instance(binary, root, units, legacy=True)
    original_run = manage.run
    manage.run = lambda args, **kwargs: SimpleNamespace(stdout=json.dumps({"Self": {"DNSName": "test.example.ts.net."}}))
    try:
        assert [entry[1] for entry in manage.instances(root, binary=binary)] == [path]
    finally:
        manage.run = original_run


def test_management_commands_are_serialized(installation):
    with manage.management_lock():
        with pytest.raises(setup.SetupError, match="Another companion"):
            with manage.management_lock():
                pytest.fail("A concurrent mutation acquired the same installation lock")


@pytest.mark.parametrize("state,reading", [("active", True), ("activating", True), ("deactivating", True), ("reloading", True), ("inactive", False), ("failed", False)])
def test_starting_and_stopping_services_remain_archive_readers(installation, monkeypatch, state, reading):
    monkeypatch.setattr(manage, "run", lambda args, **kwargs: SimpleNamespace(returncode=0, stdout=state + "\n"))
    assert ACTIVE((Path("fixture.service"), "fixture.service", [])) is reading


def test_uninstall_refuses_to_remove_a_service_that_remains_running(installation, monkeypatch):
    binary, root, units, calls = installation
    config, path, unit = instance(binary, root, units)
    monkeypatch.setattr(manage, "active", lambda _: True)
    with pytest.raises(setup.SetupError, match="could not be stopped safely"):
        manage.uninstall(config, config_root=root)
    assert unit.exists() and binary.exists() and path.exists()
    assert calls == [["stop", unit.name]]


@pytest.mark.parametrize("stage_tampered", [False, True])
def test_offline_update_checks_staged_bytes_before_executing_or_stopping_services(installation, monkeypatch, stage_tampered):
    binary, root, units, calls = installation
    original = root.parent / manage.platform_asset()
    original.write_bytes(b"downloaded new archive")
    checksum = hashlib.sha256(original.read_bytes()).hexdigest() if stage_tampered else "0" * 64
    (original.parent / "SHA256SUMS").write_text(checksum + "  " + original.name + "\n")
    monkeypatch.setattr(manage.sys, "frozen", True, raising=False)
    monkeypatch.setattr(manage, "binary_version", lambda _: pytest.fail("Unverified staged binary was executed"))
    if stage_tampered:
        monkeypatch.setattr(manage.shutil, "copyfile", lambda source, destination: Path(destination).write_bytes(b"changed during copy"))
    with pytest.raises(setup.SetupError, match="checksum"):
        manage.update(config_root=root, from_file=original)
    assert calls == [] and binary.read_bytes() == b"old archive"
    assert not list(binary.parent.glob(".pocket-update-*"))


def test_exec_failure_cleans_staged_updater_and_keeps_services(installation, monkeypatch):
    binary, root, units, calls = installation
    original = root.parent / manage.platform_asset()
    original.write_bytes(b"downloaded new archive")
    (original.parent / "SHA256SUMS").write_text(hashlib.sha256(original.read_bytes()).hexdigest() + "  " + original.name + "\n")
    monkeypatch.setattr(manage.sys, "frozen", True, raising=False)
    monkeypatch.setattr(manage, "binary_version", lambda path: "0.3.0" if path == binary else "0.4.0")
    monkeypatch.setattr(manage.os, "execve", lambda *args: (_ for _ in ()).throw(OSError("fixture execution refused")))
    with pytest.raises(setup.SetupError, match="No services were changed"):
        manage.update(config_root=root, from_file=original)
    assert calls == [] and binary.read_bytes() == b"old archive"
    assert not list(binary.parent.glob(".pocket-update-*"))


def test_github_update_selects_latest_published_prerelease_and_verifies_checksum(installation, monkeypatch, tmp_path):
    binary, root, units, _ = installation
    name = manage.platform_asset()
    package = b"verified new archive"
    manifest = (hashlib.sha256(package).hexdigest() + "  " + name + "\n").encode()
    releases = [{"draft": False, "published_at": "2026-10-01T12:00:00Z", "tag_name": "v0.3.0-preview", "assets": []},
                {"draft": True, "published_at": "2026-10-10T12:00:00Z", "assets": []},
                {"draft": False, "prerelease": True, "published_at": "2026-10-09T12:00:00Z", "tag_name": "v0.4.0-preview",
                 "assets": [{"name": name, "id": 123}, {"name": "SHA256SUMS", "id": 124}]}]
    calls = []
    monkeypatch.setattr(manage.shutil, "which", lambda _: "/fake/gh")
    monkeypatch.setattr(manage, "binary_version", lambda _: "0.4.0")

    def github(gh, endpoint, destination, **kwargs):
        calls.append(endpoint)
        content = package if endpoint.endswith("/123") else manifest if endpoint.endswith("/124") else json.dumps(releases).encode()
        destination.write_bytes(content)
        destination.chmod(0o600)

    monkeypatch.setattr(manage, "github_file", github)
    directory = tmp_path / "download"
    directory.mkdir(mode=0o700)
    candidate, version = manage.download_release(directory)
    assert candidate.read_bytes() == package and version == "0.4.0"
    assert calls == ["repos/nabil-2/herdr-android/releases?per_page=50", "repos/nabil-2/herdr-android/releases/assets/123", "repos/nabil-2/herdr-android/releases/assets/124"]


def test_github_authentication_failure_does_not_expose_credentials(installation, monkeypatch, tmp_path):
    secret = "fixture-private-github-token"
    monkeypatch.setattr(manage, "external_environment", lambda: {"GH_TOKEN": secret})

    def download(arguments, **kwargs):
        assert secret not in " ".join(arguments)
        assert kwargs["env"]["GH_TOKEN"] == secret
        return SimpleNamespace(returncode=1, stderr=secret.encode())

    monkeypatch.setattr(manage.subprocess, "run", download)
    with pytest.raises(setup.SetupError) as error:
        manage.github_file("/fake/gh", "repos/nabil-2/herdr-android/releases", tmp_path / "response")
    assert secret not in str(error.value)


@pytest.mark.parametrize("matching", [False, True])
def test_uninstall_serve_cleanup_preserves_other_handlers(installation, monkeypatch, matching):
    binary, root, units, _ = installation
    config, _, _ = instance(binary, root, units)
    calls = []
    serving = {"Web": {"test.example.ts.net:8443": {"Handlers": {
        "/": {"Proxy": "http://127.0.0.1:8765" if matching else "http://127.0.0.1:9999"},
        "/other": {"Proxy": "http://127.0.0.1:3000"}}}}}
    monkeypatch.setattr(manage.shutil, "which", lambda _: "/fake/tailscale")
    monkeypatch.setattr(manage, "run", lambda args, **kwargs: calls.append(args) or SimpleNamespace(stdout=json.dumps(serving)))
    assert REMOVE_SERVE_ROUTE(config) is matching
    assert calls == [["/fake/tailscale", "serve", "status", "--json"]] + (
        [["/fake/tailscale", "serve", "--bg", "--https=8443", "--set-path=/", "off"]] if matching else [])
    assert serving["Web"]["test.example.ts.net:8443"]["Handlers"]["/other"] == {"Proxy": "http://127.0.0.1:3000"}
