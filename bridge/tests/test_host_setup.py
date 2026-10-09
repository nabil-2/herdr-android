from __future__ import annotations

import json
import os
import platform
import shutil
import struct
import subprocess
import zlib
from pathlib import Path
from types import SimpleNamespace

import pytest

from herdr_bridge import host_setup as setup
from herdr_bridge import __version__
from herdr_bridge.config import initialize_token, read_token
from herdr_bridge.pairing import PairingError, PairingStore


def config_for(root: Path) -> dict:
    return {"format": 1, "instance": "abcdef012345", "session": "default", "name": "Test host",
            "socket": str(root / "herdr.sock"), "herdrBin": str(root / "herdr"),
            "tokenFile": str(root / "token"), "registryFile": str(root / "devices.json"),
            "host": "127.0.0.1", "port": 8765, "httpsPort": 8443, "url": "https://test.example.ts.net:8443/"}


def test_url_policy_matches_native_client_private_network_boundary():
    for address in ["https://host.ts.net:8443", "http://127.0.0.1:8765/", "http://100.64.0.1:8765", "http://[fd7a:115c:a1e0::1]:8765"]:
        assert setup.validate_url(address).endswith("/")
    for address in ["http://192.168.1.1", "http://100.128.0.1", "http://example.com", "https://user:secret@example.com", "https://host/v1", "https://host/?token=secret", "https://host/#secret", "https://host:99999"]:
        with pytest.raises(setup.SetupError):
            setup.validate_url(address)


def test_setup_file_cannot_be_shared_or_redirected_to_another_file(tmp_path):
    root = tmp_path / "config"
    path = root / "setup.json"
    value = config_for(root)
    setup.write_private(path, json.dumps(value).encode())
    assert setup.read_config(path) == value
    assert path.stat().st_mode & 0o777 == 0o600
    path.chmod(0o644)
    with pytest.raises(setup.SetupError):
        setup.read_config(path)
    path.chmod(0o600)
    link = root / "link.json"
    link.symlink_to(path)
    with pytest.raises(setup.SetupError):
        setup.write_private(link, b"replacement")
    with pytest.raises(setup.SetupError):
        setup.read_config(link)
    assert setup.read_config(path) == value


def test_invalid_service_binding_and_format_are_rejected(tmp_path):
    path = tmp_path / "setup.json"
    value = config_for(tmp_path)
    for change in [{"host": "0.0.0.0"}, {"port": True}, {"port": 0}, {"socket": "relative"}, {"format": 2}, {"name": "injected\nname"}, {"hostName": "!other"}, {"hostName": "host\nConditionHost=*"}, {"hostName": 4}]:
        setup.write_private(path, json.dumps(value | change).encode())
        with pytest.raises(setup.SetupError):
            setup.read_config(path)


def test_serve_keeps_existing_background_foreground_and_funnel_ports():
    domain = "test.example.ts.net"
    occupied = {"TCP": {"443": {"HTTPS": True}, "8443": {"HTTPS": True}},
                "Web": {domain + ":8443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:3000"}}}},
                "Foreground": {"other": {"TCP": {"8444": {"HTTPS": True}}}}}
    before = json.dumps(occupied, sort_keys=True)
    assert setup.serve_port(occupied, domain, "http://127.0.0.1:8765") == 8445
    assert json.dumps(occupied, sort_keys=True) == before
    own = {"TCP": {"8443": {"HTTPS": True}}, "Web": {domain + ":8443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8765"}}}}}
    assert setup.serve_port(own, domain, "http://127.0.0.1:8765", 8443) == 8443
    own["AllowFunnel"] = {domain + ":8443": True}
    assert setup.serve_port(own, domain, "http://127.0.0.1:8765", 8443) == 8444


def test_service_arguments_are_literal_and_credentials_are_not_in_command(tmp_path):
    path = tmp_path / 'folder with spaces/$HOME%"quote/setup.json'
    value = setup.service_content(["/path with spaces/python", "-m", "herdr_bridge"], path)
    assert setup.MANAGED in value
    assert "--service-config" in value
    assert "$$HOME%%" in value
    assert '\\"quote' in value
    assert "--token" not in value
    assert "Restart=on-failure" in value
    assert "WantedBy=default.target" in value
    assert f"ConditionHost={setup.socket.gethostname()}" in value
    with pytest.raises(setup.SetupError):
        setup.systemd_quote("bad\nargument")


def test_shared_home_pairing_identifies_owning_machine_before_reading_credentials(tmp_path, monkeypatch):
    monkeypatch.setattr(setup.socket, "gethostname", lambda: "other-machine.example")
    monkeypatch.setattr(setup, "read_token", lambda _: pytest.fail("Wrong-host health must not read a token"))
    config = config_for(tmp_path) | {"hostName": "pocket-server.example"}
    with pytest.raises(setup.SetupError, match="companion runs on pocket-server.example") as failure:
        setup.wait_healthy(config)
    assert "ssh pocket-server.example" in str(failure.value)
    assert "setup again" not in str(failure.value)


def test_managed_unit_never_uses_host_globs_or_control_characters(tmp_path, monkeypatch):
    for hostname in ("!other.example", "*.example", "host\nConditionHost=*", "-oProxyCommand=bad"):
        monkeypatch.setattr(setup.socket, "gethostname", lambda: hostname)
        with pytest.raises(setup.SetupError, match="unsupported host name"):
            setup.service_content(["/usr/bin/pocket"], tmp_path / "setup.json")


@pytest.mark.skipif(platform.system() != "Linux" or not shutil.which("systemd-analyze"), reason="Requires systemd's unit parser")
def test_systemd_parser_resolves_executable_dollar_sign_without_doubling(tmp_path):
    executable = tmp_path / "program dollar$literal%percent"
    executable.symlink_to(shutil.which("true"))
    unit = tmp_path / "pocket-quoting-review.service"
    unit.write_text(setup.service_content([str(executable)], tmp_path / "$HOME-config.json"))
    # Verify only parses this temporary unit; it never starts a service.
    result = subprocess.run(["systemd-analyze", "verify", "--man=no", str(unit)], text=True, capture_output=True, timeout=10)
    assert result.returncode == 0, result.stderr
    assert "$$HOME-config.json" in unit.read_text()


def test_relative_frozen_install_root_produces_absolute_binary_and_command_link(tmp_path, monkeypatch):
    source = tmp_path / "downloaded-pocket"
    source.write_bytes(b"standalone-companion-test")
    source.chmod(0o755)
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(setup.sys, "frozen", True, raising=False)
    monkeypatch.setattr(setup.sys, "executable", str(source))
    monkeypatch.setenv("HERDR_POCKET_INSTALL_ROOT", "relative install")
    command = setup.command_prefix()
    installed = tmp_path / "relative install/lib/herdr-pocket/herdr-pocket"
    link = tmp_path / "relative install/bin/herdr-pocket"
    assert command == [str(installed)]
    assert installed.read_bytes() == source.read_bytes()
    assert installed.stat().st_mode & 0o777 == 0o755
    assert link.is_symlink() and link.resolve() == installed


def test_noninteractive_session_ambiguity_never_guesses(monkeypatch):
    monkeypatch.setattr(setup.sys.stdin, "isatty", lambda: False)
    with pytest.raises(setup.SetupError, match="--session"):
        setup.choose([{"name": "default"}, {"name": "work"}], "name", "sessions")


def test_pairing_prints_one_use_challenge_but_never_owner_token(tmp_path, monkeypatch, capsys):
    value = config_for(tmp_path)
    token = initialize_token(Path(value["tokenFile"]))
    monkeypatch.setattr(setup, "wait_healthy", lambda *args, **kwargs: None)
    payload = setup.show_pairing(value, plain=True)
    printed = capsys.readouterr().out
    assert token not in printed
    assert json.loads(printed) == payload
    assert payload["type"] == "herdr-pocket-pair"
    assert payload["version"] == 1
    device = PairingStore(Path(value["registryFile"])).exchange(payload["code"], "Test phone")
    assert device["token"] not in printed
    assert device["serverName"] == "Test host"


def png_pixels(path: Path) -> tuple[int, int, list[list[int]]]:
    """Read Segno's lossless grayscale PNG without introducing image dependencies."""
    image = path.read_bytes()
    assert image.startswith(b"\x89PNG\r\n\x1a\n")
    position, compressed = 8, []
    while position < len(image):
        length = struct.unpack("!I", image[position:position + 4])[0]
        kind = image[position + 4:position + 8]
        content = image[position + 8:position + 8 + length]
        crc = struct.unpack("!I", image[position + 8 + length:position + 12 + length])[0]
        assert crc == zlib.crc32(kind + content)
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack("!IIBBBBB", content)
            assert (depth, color, compression, filtering, interlace) == (1, 0, 0, 0, 0)
        elif kind == b"IDAT":
            compressed.append(content)
        position += 12 + length
    stride = (width + 7) // 8
    raw = zlib.decompress(b"".join(compressed))
    assert len(raw) == height * (stride + 1)
    previous = bytes(stride)
    pixels = []
    for index in range(height):
        start = index * (stride + 1)
        filtering = raw[start]
        assert filtering in {0, 2}  # Segno emits unfiltered or prior-row differences.
        row = raw[start + 1:start + stride + 1]
        if filtering == 2:
            row = bytes((current + prior) & 255 for current, prior in zip(row, previous))
        previous = row
        pixels.append([1 - ((row[x // 8] >> (7 - x % 8)) & 1) for x in range(width)])
    return width, height, pixels


def test_pairing_png_exports_exact_plain_payload_and_keeps_directory_permissions(tmp_path, monkeypatch, capsys):
    import segno
    config = config_for(tmp_path / "config")
    owner_token = initialize_token(Path(config["tokenFile"]))
    directory = tmp_path / "Pictures"
    directory.mkdir(mode=0o755)
    directory.chmod(0o755)
    path = directory / "pairing.png"
    path.write_bytes(b"old image")
    path.chmod(0o644)
    monkeypatch.setattr(setup, "wait_healthy", lambda *args, **kwargs: None)
    payload = setup.show_pairing(config, plain=True, qr_file=path)
    printed = capsys.readouterr()
    assert json.loads(printed.out) == payload
    assert "expires in 5 minutes" in printed.err
    assert owner_token not in printed.out + printed.err
    assert path.stat().st_mode & 0o777 == 0o600
    assert directory.stat().st_mode & 0o777 == 0o755
    assert {item.name for item in directory.iterdir()} == {"pairing.png"}
    canonical = json.dumps(payload, separators=(",", ":"), ensure_ascii=True)
    qr = segno.make(canonical, error="M", micro=False)
    width, height, pixels = png_pixels(path)
    expected = [list(row) for row in qr.matrix_iter(scale=8, border=4)]
    assert width == height == len(expected)
    assert pixels == expected  # Every quiet-zone and QR pixel encodes the same invitation.
    registry = PairingStore(Path(config["registryFile"]))
    registry.exchange(payload["code"], "Gallery phone")
    with pytest.raises(PairingError, match="invalid or expired"):
        registry.exchange(payload["code"], "Second phone")


@pytest.mark.parametrize("unsafe", ["symlink", "hardlink", "directory", "parent_symlink", "shared_parent", "different_owner"])
def test_pairing_png_refuses_unsafe_destinations(tmp_path, monkeypatch, unsafe):
    config = config_for(tmp_path / "config")
    monkeypatch.setattr(setup, "wait_healthy", lambda *args, **kwargs: None)
    destination = tmp_path / "images" / "pairing.png"
    destination.parent.mkdir(mode=0o700)
    original = tmp_path / "untouched"
    original.write_bytes(b"original")
    if unsafe == "symlink":
        destination.symlink_to(original)
    elif unsafe == "hardlink":
        os.link(original, destination)
    elif unsafe == "directory":
        destination.mkdir()
    elif unsafe == "parent_symlink":
        destination.parent.rmdir()
        destination.parent.symlink_to(tmp_path, target_is_directory=True)
    elif unsafe == "shared_parent":
        destination.parent.chmod(0o777)
    else:
        destination.write_bytes(b"different owner")
        old_lstat = Path.lstat
        def nonowned_lstat(path):
            info = old_lstat(path)
            if path == destination:
                return SimpleNamespace(st_mode=info.st_mode, st_uid=os.getuid() + 1, st_nlink=1)
            return info
        monkeypatch.setattr(Path, "lstat", nonowned_lstat)
    with pytest.raises(setup.SetupError, match="unsafe setup file|Export directory"):
        setup.show_pairing(config, plain=True, qr_file=destination)
    assert original.read_bytes() == b"original"


def test_pairing_png_keeps_normal_terminal_qr_output(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(setup, "wait_healthy", lambda *args, **kwargs: None)
    image = tmp_path / "images" / "pairing.png"
    setup.show_pairing(config_for(tmp_path / "config"), qr_file=image)
    printed = capsys.readouterr()
    assert "Scan pairing code" in printed.out
    assert "Pairing QR image saved" in printed.out
    assert "expires in 5 minutes" in printed.out
    assert printed.err == ""
    assert image.stat().st_mode & 0o777 == 0o600


def test_setup_reads_selected_session_and_installs_only_its_companion(tmp_path, monkeypatch, capsys):
    calls = []
    selected_socket = tmp_path / "named-herdr.sock"
    executable = str(tmp_path / "herdr")
    monkeypatch.setattr(setup, "find_herdr", lambda _: executable)
    monkeypatch.setattr(setup, "select_session", lambda *args: ("work", selected_socket))
    monkeypatch.setattr(setup, "tailscale_state", lambda: ("tailscale", "test.example.ts.net"))
    monkeypatch.setattr(setup, "free_port", lambda: 8768)
    monkeypatch.setattr(setup, "command_prefix", lambda: ["/installed/herdr-pocket"])
    monkeypatch.setattr(setup, "codex_settings", lambda *args: {})
    monkeypatch.setattr(setup, "ensure_codex_integration", lambda binary, home:
                        calls.append(("integration", (binary, home))) or
                        SimpleNamespace(ready=True, changed=False, advisory=None))
    monkeypatch.setattr(setup, "wait_healthy", lambda *args, **kwargs: None)
    monkeypatch.setattr(setup, "show_pairing", lambda value, **kwargs: calls.append(("pair", value)))
    monkeypatch.setattr(setup, "start_service", lambda *args: calls.append(("service", args)))
    def fake_run(args, **kwargs):
        calls.append(("command", args))
        return SimpleNamespace(returncode=0, stdout=json.dumps({"TCP": {"8443": {"HTTPS": True}}}))
    monkeypatch.setattr(setup, "run", fake_run)
    setup.setup(config_root=tmp_path / "pocket")
    path = next((tmp_path / "pocket/instances").glob("*/setup.json"))
    value = setup.read_config(path)
    assert value["socket"] == str(selected_socket)
    assert value["port"] == 8768
    assert value["httpsPort"] == 8444
    assert value["url"] == "https://test.example.ts.net:8444/"
    assert read_token(Path(value["tokenFile"])) not in capsys.readouterr().out
    commands = [args for kind, args in calls if kind == "command"]
    assert commands[-1] == ["tailscale", "serve", "--bg", "--https=8444", "http://127.0.0.1:8768"]
    assert all("reset" not in args and "stop" not in args and "pane" not in args for args in commands)
    # Upgrade the same instance: retain paired phones and custom Codex settings.
    custom_codex = tmp_path / "custom-codex"
    custom_codex.write_text("#!/bin/sh\nexit 0\n")
    custom_codex.chmod(0o700)
    custom_home = str(tmp_path / "custom-home")
    value.update(codexBin=str(custom_codex), codexHome=custom_home, codexPath="/usr/bin:/bin")
    setup.write_private(path, json.dumps(value).encode())
    owner = read_token(Path(value["tokenFile"]))
    registry = PairingStore(Path(value["registryFile"]))
    invitation = registry.begin_pairing("Existing host")
    device = registry.exchange(invitation["code"], "Existing phone")
    monkeypatch.delenv("CODEX_HOME", raising=False)
    setup.setup(config_root=tmp_path / "pocket")
    upgraded = setup.read_config(path)
    assert upgraded["codexBin"] == str(custom_codex)
    assert upgraded["codexHome"] == custom_home
    assert upgraded["codexPath"] == "/usr/bin:/bin"
    assert ("integration", (executable, Path(custom_home))) in calls
    assert upgraded["port"] == value["port"] and upgraded["httpsPort"] == value["httpsPort"]
    assert read_token(Path(upgraded["tokenFile"])) == owner
    assert registry.authorized(device["token"])


def test_missing_tailscale_fails_before_installing_services(monkeypatch):
    monkeypatch.setattr(setup.shutil, "which", lambda _: None)
    with pytest.raises(setup.SetupError, match="Install and connect Tailscale"):
        setup.tailscale_state()


@pytest.mark.parametrize("hostname", ["current-host.example", "host\nsecret-host-detail", "host\x1b[31m"])
def test_unreachable_tailscale_explains_machine_local_service_without_raw_details(monkeypatch, hostname):
    monkeypatch.setattr(setup.shutil, "which", lambda _: "/installed/tailscale")
    monkeypatch.setattr(setup.socket, "gethostname", lambda: hostname)
    calls = []

    def failed_status(args):
        calls.append(args)
        raise setup.SetupError("private subprocess detail: https://private.example/?token=secret")

    monkeypatch.setattr(setup, "run", failed_status)
    with pytest.raises(setup.SetupError) as failure:
        setup.tailscale_state()
    message = str(failure.value)
    assert "Run 'tailscale status' on this computer" in message
    assert "A shared home directory shares files, not a running Tailscale service" in message
    assert "Start and connect Tailscale on this computer before running setup" in message
    assert "private" not in message and "secret" not in message
    assert "\n" not in message and "\x1b" not in message
    assert f"on {hostname}." in message if hostname == "current-host.example" else "on this computer." in message
    assert failure.value.__suppress_context__ is True
    assert calls == [["/installed/tailscale", "status", "--json"]]


def test_reachable_tailscale_still_requires_user_to_connect(monkeypatch):
    monkeypatch.setattr(setup.shutil, "which", lambda _: "/installed/tailscale")
    monkeypatch.setattr(setup, "run", lambda _: SimpleNamespace(stdout=json.dumps(
        {"BackendState": "NeedsLogin", "Self": {"Online": False, "DNSName": "test.example.ts.net."}})))
    with pytest.raises(setup.SetupError, match="Connect Tailscale on this computer before running setup"):
        setup.tailscale_state()


@pytest.fixture
def local_health_listener():
    """An owned HTTP listener representing an old daemon on the preserved port."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading

    health = {"service": "herdr-android-bridge", "protocol": 1, "pairing": True}
    requests = []

    class Health(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps(health).encode())

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Health)
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
    thread.start()
    try:
        yield health, server.server_port, requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


@pytest.mark.parametrize("reported_version", [None, "0.3.0", "untrusted-response-value", 4])
def test_health_refuses_missing_or_mismatched_running_companion_version(tmp_path, local_health_listener, reported_version):
    health, port, requests = local_health_listener
    if reported_version is not None:
        health["companionVersion"] = reported_version
    config = config_for(tmp_path)
    owner = initialize_token(Path(config["tokenFile"]))
    config["port"] = port
    with pytest.raises(setup.SetupError, match="Run the matching installer again") as error:
        setup.wait_healthy(config, timeout=0)
    assert requests == ["/v1/health"]
    assert owner not in str(error.value)
    assert "untrusted-response-value" not in str(error.value)


def test_health_accepts_current_companion_without_requiring_new_config_fields(tmp_path, local_health_listener):
    health, port, requests = local_health_listener
    health["companionVersion"] = __version__
    config = config_for(tmp_path)  # Existing format-1 configurations remain valid.
    initialize_token(Path(config["tokenFile"]))
    config["port"] = port
    setup.wait_healthy(config, timeout=0)
    assert requests == ["/v1/health"]


@pytest.mark.parametrize("expected,allow,accepted", [("0.3.1", False, False), ("0.3.1", True, True), ("0.4.0", True, False)])
def test_missing_version_is_accepted_only_for_explicit_legacy_rollback(tmp_path, local_health_listener, expected, allow, accepted):
    _, port, _ = local_health_listener
    config = config_for(tmp_path)
    initialize_token(Path(config["tokenFile"]))
    config["port"] = port
    if accepted:
        setup.wait_healthy(config, timeout=0, expected_version=expected, allow_legacy_version=allow)
    else:
        with pytest.raises(setup.SetupError, match="does not match"):
            setup.wait_healthy(config, timeout=0, expected_version=expected, allow_legacy_version=allow)


def test_companion_version_matches_the_declared_package_version():
    import tomllib
    project = tomllib.loads((Path(__file__).parents[1] / "pyproject.toml").read_text())
    assert __version__ == project["project"]["version"]


def test_health_does_not_follow_redirects_or_leak_bearer(tmp_path, monkeypatch):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import threading
    requests = []
    class Destination(BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.headers.get("Authorization"))
            self.send_response(200)
            self.end_headers()
        def log_message(self, *args):
            pass
    destination = ThreadingHTTPServer(("127.0.0.1", 0), Destination)
    class Redirect(Destination):
        def do_GET(self):
            self.send_response(302)
            self.send_header("Location", f"http://127.0.0.1:{destination.server_port}/stolen")
            self.end_headers()
    server = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
    threads = [threading.Thread(target=item.serve_forever, daemon=True) for item in (destination, server)]
    for thread in threads:
        thread.start()
    try:
        value = config_for(tmp_path)
        initialize_token(Path(value["tokenFile"]))
        value["port"] = server.server_port
        with pytest.raises(setup.SetupError):
            setup.wait_healthy(value, timeout=0.1)
        assert requests == []
    finally:
        for item in (destination, server):
            item.shutdown()
            item.server_close()
        for thread in threads:
            thread.join(timeout=2)


def test_explicit_herdr_name_is_resolved_in_path_before_current_directory(tmp_path, monkeypatch):
    directory = tmp_path / "bin"
    directory.mkdir()
    executable = directory / "herdr"
    executable.write_text("#!/bin/sh\nexit 0\n")
    executable.chmod(0o700)
    workspace = tmp_path / "unrelated"
    workspace.mkdir()
    monkeypatch.chdir(workspace)
    monkeypatch.setenv("PATH", str(directory))
    assert setup.find_herdr("herdr") == str(executable)
    assert setup.find_herdr(str(executable)) == str(executable)


def test_codex_discovery_keeps_runtime_path_and_custom_home_for_background_service(tmp_path, monkeypatch):
    directory = tmp_path / "node runtime/bin"
    directory.mkdir(parents=True)
    executable = directory / "codex"
    executable.write_text("#!/usr/bin/env node\n")
    executable.chmod(0o700)
    home = tmp_path / "custom codex"
    monkeypatch.setenv("PATH", str(directory) + os.pathsep + "." + os.pathsep + "/usr/bin")
    monkeypatch.setenv("CODEX_HOME", str(home))
    result = setup.codex_settings()
    assert result == {"codexBin": str(executable), "codexHome": str(home),
                      "codexPath": str(directory) + os.pathsep + "/usr/bin"}
    path = tmp_path / "setup.json"
    setup.write_private(path, json.dumps(config_for(tmp_path) | result).encode())
    assert setup.read_config(path)["codexPath"] == result["codexPath"]


def test_missing_codex_does_not_require_it_for_shell_hosts(tmp_path, monkeypatch):
    monkeypatch.setenv("PATH", str(tmp_path))
    monkeypatch.setattr(setup.Path, "home", classmethod(lambda cls: tmp_path))
    assert setup.codex_settings() == {}
    with pytest.raises(setup.SetupError, match="specified Codex"):
        setup.codex_settings("missing-codex")


def test_codex_service_configuration_rejects_relative_or_injected_paths(tmp_path):
    path = tmp_path / "setup.json"
    for change in [{"codexBin": "codex"}, {"codexHome": "relative"}, {"codexBin": "/bin/codex\n"},
                   {"codexPath": "/bin:."}, {"codexPath": "/bin::/usr/bin"}, {"codexPath": "/bin\n/usr/bin"},
                   {"codexPath": True}, {"codexHome": []}]:
        setup.write_private(path, json.dumps(config_for(tmp_path) | change).encode())
        with pytest.raises(setup.SetupError):
            setup.read_config(path)


@pytest.mark.parametrize("failure", ["timeout", "denied"])
def test_optional_lingering_failure_does_not_abort_installed_service(tmp_path, monkeypatch, capsys, failure):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    monkeypatch.setattr(setup.platform, "system", lambda: "Linux")
    monkeypatch.setattr(setup.shutil, "which", lambda name: "/usr/bin/" + name)
    calls = []

    def fake_run(args, **kwargs):
        calls.append(args)
        if Path(args[0]).name == "loginctl":
            if failure == "timeout":
                raise setup.SetupError("Optional command timed out")
            return SimpleNamespace(returncode=1, stdout="")
        return SimpleNamespace(returncode=0, stdout="")

    monkeypatch.setattr(setup, "run", fake_run)
    unit = setup.start_service(["/installed/herdr-pocket"], tmp_path / "setup.json", "abcdef012345")
    assert unit == "herdr-pocket-abcdef012345.service"
    assert any(args[1:] == ["--user", "restart", unit] for args in calls)
    assert "Background service installed" in capsys.readouterr().out
    assert (tmp_path / "systemd/user" / unit).exists()


def test_standard_mac_tailscale_app_can_supply_cli_when_not_in_path(tmp_path, monkeypatch):
    executable = tmp_path / "Tailscale.app/Contents/MacOS/Tailscale"
    executable.parent.mkdir(parents=True)
    executable.write_text("#!/bin/sh\nexit 0\n")
    executable.chmod(0o700)
    monkeypatch.setattr(setup, "MAC_TAILSCALE_APP", executable)
    monkeypatch.setattr(setup.platform, "system", lambda: "Darwin")
    monkeypatch.setattr(setup.shutil, "which", lambda _: None)
    calls = []

    def fake_run(args, **kwargs):
        calls.append(args)
        return SimpleNamespace(returncode=0, stdout=json.dumps({"BackendState": "Running", "Self": {"Online": True, "DNSName": "mac.example.ts.net."}}))

    monkeypatch.setattr(setup, "run", fake_run)
    assert setup.tailscale_state() == (str(executable), "mac.example.ts.net")
    assert calls == [[str(executable), "status", "--json"]]


def test_mac_tailscale_script_forces_cli_without_changing_caller_environment(tmp_path, monkeypatch):
    import sys
    executable = tmp_path / "Tailscale"
    executable.write_text(f"#!{sys.executable}\nimport os\nprint(os.environ.get('TAILSCALE_BE_CLI', 'missing'))\n")
    executable.chmod(0o700)
    monkeypatch.setattr(setup.platform, "system", lambda: "Darwin")
    monkeypatch.delenv("TAILSCALE_BE_CLI", raising=False)
    result = setup.run([str(executable), "status", "--json"])
    assert result.stdout.strip() == "1"
    assert "TAILSCALE_BE_CLI" not in os.environ
