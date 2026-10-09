"""Local setup and pairing; never restart or send input to a Herdr session."""

from __future__ import annotations

import asyncio
import hashlib
import io
import ipaddress
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

from . import __version__
from .config import default_token_path, initialize_token, read_token
from .herdr import HerdrClient
from .pairing import PairingStore
from .runtime import external_environment

MANAGED = "Managed by Herdr Pocket"
SERVICE_PREFIX = "dev.herdr.pocket."
MAC_TAILSCALE_APP = Path("/Applications/Tailscale.app/Contents/MacOS/Tailscale")
HOST_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,252}\Z")


class SetupError(Exception):
    pass


def run(args: list[str], *, visible: bool = False, check: bool = True, timeout: int = 30):
    try:
        environment = external_environment()
        if platform.system() == "Darwin" and Path(args[0]).name.lower() == "tailscale":
            # The standard macOS app executable contains both the GUI and CLI.
            # Tailscale documents this flag for unambiguous invocation in scripts.
            environment["TAILSCALE_BE_CLI"] = "1"
        result = subprocess.run(args, capture_output=not visible, text=True, timeout=timeout, env=environment)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SetupError(f"Could not run {Path(args[0]).name}. Check that it is installed and available.") from error
    if check and result.returncode:
        raise SetupError(f"{Path(args[0]).name} could not complete setup. Check its permissions and status.")
    return result


def private_directory(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        raise SetupError("Setup directory must be owned by you and must not be a symbolic link.")
    path.chmod(0o700)


def write_private(path: Path, content: bytes, *, preserve_directory_permissions: bool = False) -> None:
    if preserve_directory_permissions:
        # An explicitly chosen export directory need not be a private setup
        # directory. Keep its permissions, while the exported file stays 0600.
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        info = path.parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
            raise SetupError("Export directory must be owned by you, must not be a symbolic link, and must not allow others to write.")
    else:
        private_directory(path.parent)
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
            raise SetupError("Refusing to replace an unsafe setup file.")
    fd, temporary = tempfile.mkstemp(prefix=".pocket-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_config(path: Path) -> dict:
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    try:
        fd = os.open(path, flags)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077 or info.st_nlink != 1:
                raise SetupError("Setup file must be an owner-only regular file.")
            data = os.read(fd, 16385)
            if len(data) > 16384:
                raise SetupError("Setup file is too large.")
            value = json.loads(data)
        finally:
            os.close(fd)
        if not isinstance(value, dict) or value.get("format") != 1:
            raise ValueError("format")
        for field in ("socket", "herdrBin", "tokenFile", "registryFile", "name", "url", "instance", "session"):
            if not isinstance(value.get(field), str) or not value[field] or any(ord(c) < 32 for c in value[field]):
                raise ValueError("field")
        if value.get("host") != "127.0.0.1" or type(value.get("port")) is not int or not 1 <= value["port"] <= 65535:
            raise ValueError("listen")
        if "hostName" in value and (not isinstance(value["hostName"], str) or not HOST_NAME.fullmatch(value["hostName"])):
            raise ValueError("host name")
        if not all(Path(value[key]).is_absolute() for key in ("socket", "herdrBin", "tokenFile", "registryFile")):
            raise ValueError("path")
        for field in ("codexBin", "codexHome"):
            candidate = value.get(field)
            if candidate is not None and (not isinstance(candidate, str) or not candidate or len(candidate) > 4096
                                          or any(ord(c) < 32 for c in candidate) or not Path(candidate).is_absolute()):
                raise ValueError("codex path")
        search_path = value.get("codexPath")
        if search_path is not None and (not isinstance(search_path, str) or len(search_path) > 8192
                                        or any(ord(c) < 32 for c in search_path)
                                        or any(not Path(part).is_absolute() for part in search_path.split(os.pathsep))):
            raise ValueError("codex search path")
        validate_url(value["url"])
        return value
    except (OSError, UnicodeError, ValueError, KeyError) as error:
        raise SetupError("Could not read the host setup. Run setup again.") from error


def validate_url(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ("", "/") or not parsed.hostname:
        raise SetupError("Use a server address without credentials or an API path.")
    try:
        port = parsed.port
        address = ipaddress.ip_address(parsed.hostname)
        private = address.is_loopback or address in ipaddress.ip_network("100.64.0.0/10") or address in ipaddress.ip_network("fd7a:115c:a1e0::/48")
    except ValueError:
        private = parsed.hostname == "localhost"
        try:
            port = parsed.port
        except ValueError as error:
            raise SetupError("Invalid server port.") from error
    if port is not None and not 1 <= port <= 65535:
        raise SetupError("Invalid server port.")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and private):
        raise SetupError("Use HTTPS, or a numeric Tailscale/loopback address for private HTTP.")
    return value.rstrip("/") + "/"


def find_herdr(explicit: str | None = None) -> str:
    candidate = explicit or shutil.which("herdr") or str(Path.home() / ".local/bin/herdr")
    candidate = str(Path(candidate).expanduser())
    executable = Path(shutil.which(candidate) or candidate).absolute()
    if not executable.is_file() or not os.access(executable, os.X_OK):
        raise SetupError("Install Herdr first: https://herdr.dev/docs/install/")
    return str(executable)


def codex_settings(explicit: str | None = None, home: Path | None = None) -> dict:
    """Discover the optional chat reader without starting Codex or requiring it."""
    search = os.environ.get("PATH", os.defpath)
    # Services often have a smaller PATH than the shell that installed Codex.
    # Keep only absolute entries: a background service must not execute from its cwd.
    search = os.pathsep.join(part for part in search.split(os.pathsep)
                             if part and Path(part).is_absolute() and not any(ord(c) < 32 for c in part))
    candidate = explicit or shutil.which("codex", path=search) or str(Path.home() / ".local/bin/codex")
    candidate = str(Path(candidate).expanduser())
    executable = Path(shutil.which(candidate, path=search) or candidate).absolute()
    if not executable.is_file() or not os.access(executable, os.X_OK):
        if explicit:
            raise SetupError("The specified Codex executable is unavailable. Check --codex-bin.")
        return {}
    selected_home = (home or Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))).expanduser().absolute()
    if any(ord(c) < 32 for c in str(executable) + str(selected_home)) or max(len(str(executable)), len(str(selected_home))) > 4096:
        raise SetupError("Codex paths must be absolute paths without control characters.")
    if len(search) > 8192:
        raise SetupError("The executable search path is too long for background setup.")
    return {"codexBin": str(executable), "codexHome": str(selected_home), "codexPath": search or os.defpath}


def choose(items: list[dict], field: str, description: str) -> dict:
    if not items:
        raise SetupError(f"No {description} found.")
    if len(items) == 1:
        return items[0]
    if not sys.stdin.isatty():
        raise SetupError("More than one Herdr session is available. Select one with --session NAME.")
    print("Choose a Herdr session:")
    for number, item in enumerate(items, 1):
        print(f"  {number}. {item[field]}")
    try:
        number = int(input("Session number: "))
        if not 1 <= number <= len(items):
            raise ValueError("selection")
    except (ValueError, EOFError) as error:
        raise SetupError("Select a listed session with --session NAME.") from error
    return items[number - 1]


def select_session(executable: str, name: str | None, explicit_socket: Path | None) -> tuple[str, Path]:
    if explicit_socket is not None:
        selected = explicit_socket.expanduser().absolute()
        session_name = name or (selected.parent.name if selected.parent.parent.name == "sessions" else "default")
    else:
        try:
            sessions = json.loads(run([executable, "session", "list", "--json"]).stdout)["sessions"]
            running = [item for item in sessions if item.get("running") is True]
            inherited = os.environ.get("HERDR_SOCKET") or os.environ.get("HERDR_SOCKET_PATH")
            if name:
                matching = [item for item in running if item.get("name") == name]
                if len(matching) != 1:
                    raise SetupError("The selected Herdr session is not running. Start it in Herdr first.")
                item = matching[0]
            elif inherited:
                matching = [item for item in running if item.get("socket_path") == str(Path(inherited).expanduser())]
                if len(matching) != 1:
                    raise SetupError("The inherited Herdr socket is not available; select --session explicitly.")
                item = matching[0]
            else:
                item = choose(running, "name", "running Herdr sessions")
            session_name, selected = item["name"], Path(item["socket_path"])
        except (ValueError, KeyError, TypeError) as error:
            raise SetupError("Herdr returned an incompatible session list.") from error
    if not selected.is_absolute() or any(ord(c) < 32 for c in str(selected)):
        raise SetupError("Herdr must provide an absolute socket path.")
    try:
        info = selected.stat()
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
            raise OSError("owner")
        asyncio.run(HerdrClient(selected, executable).snapshot())
    except Exception as error:
        raise SetupError("Could not read the selected Herdr session. Run setup as the user who owns it.") from error
    return session_name, selected


def tailscale_state() -> tuple[str, str]:
    executable = shutil.which("tailscale")
    if not executable and platform.system() == "Darwin" and MAC_TAILSCALE_APP.is_file() and os.access(MAC_TAILSCALE_APP, os.X_OK):
        executable = str(MAC_TAILSCALE_APP)
    if not executable:
        raise SetupError("Install and connect Tailscale on this computer and your phone first: https://tailscale.com/download")
    try:
        status = json.loads(run([executable, "status", "--json"]).stdout)
        own = status["Self"]
        dns_name = own["DNSName"].rstrip(".")
        if status.get("BackendState") != "Running" or not own.get("Online") or not dns_name.endswith(".ts.net"):
            raise ValueError("offline")
        if any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-" for c in dns_name):
            raise ValueError("name")
        return executable, dns_name
    except (ValueError, KeyError, TypeError) as error:
        raise SetupError("Connect Tailscale on this computer before running setup.") from error


def free_port(start: int = 8765) -> int:
    for port in range(start, start + 20):
        with socket.socket() as listener:
            try:
                listener.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise SetupError("No free local companion port was found.")


def serve_port(config: dict, dns_name: str, proxy: str, previous: int | None = None) -> int:
    web, tcp = config.get("Web", {}), config.get("TCP", {})
    if not isinstance(web, dict) or not isinstance(tcp, dict):
        raise SetupError("Could not read existing Tailscale Serve configuration.")
    occupied_foreground: set[str] = set()
    foreground = config.get("Foreground", {})
    if not isinstance(foreground, dict):
        raise SetupError("Could not read existing foreground Tailscale services.")
    for value in foreground.values():
        if not isinstance(value, dict) or not isinstance(value.get("TCP", {}), dict) or not isinstance(value.get("Web", {}), dict):
            raise SetupError("Could not read existing foreground Tailscale services.")
        occupied_foreground.update(value.get("TCP", {}).keys())
        occupied_foreground.update(key.rsplit(":", 1)[-1] for key in value.get("Web", {}))
    candidates = ([previous] if previous else []) + list(range(8443, 8463))
    for port in dict.fromkeys(candidates):
        if type(port) is not int or not 1 <= port <= 65535:
            continue
        if str(port) in occupied_foreground:
            continue
        endpoint = f"{dns_name}:{port}"
        existing = web.get(endpoint)
        if existing is not None:
            handlers = existing.get("Handlers", {})
            if handlers == {"/": {"Proxy": proxy}} and not config.get("AllowFunnel", {}).get(endpoint, False):
                return port
            continue
        if str(port) not in tcp and not any(key.rsplit(":", 1)[-1] == str(port) for key in web):
            return port
    raise SetupError("No unused Tailscale Serve port is available. Existing services were preserved.")


def command_prefix() -> list[str]:
    if not getattr(sys, "frozen", False):
        return [str(Path(sys.executable).absolute()), "-m", "herdr_bridge"]
    install_root = Path(os.environ.get("HERDR_POCKET_INSTALL_ROOT", Path.home() / ".local")).expanduser().absolute()
    directory = install_root / "lib/herdr-pocket"
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / "herdr-pocket"
    source = Path(sys.executable).absolute()
    if source != destination.absolute():
        if destination.is_symlink():
            raise SetupError("Refusing to replace a symbolic link at the companion binary path.")
        fd, temporary = tempfile.mkstemp(prefix=".herdr-pocket-", dir=directory)
        os.close(fd)
        try:
            shutil.copyfile(source, temporary)
            os.chmod(temporary, 0o755)
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    bin_directory = install_root / "bin"
    bin_directory.mkdir(parents=True, exist_ok=True)
    link = bin_directory / "herdr-pocket"
    if link.is_symlink() and link.resolve() == destination.resolve():
        pass
    elif link.exists() or link.is_symlink():
        raise SetupError("A different program already uses the herdr-pocket command name.")
    else:
        link.symlink_to(destination)
    return [str(destination)]


def systemd_quote(argument: str, *, executable: bool = False) -> str:
    if any(ord(c) < 32 for c in argument):
        raise SetupError("Service paths must not contain control characters.")
    escaped = argument.replace("\\", "\\\\").replace('"', '\\"').replace("%", "%%")
    # systemd expands environment variables in argv, but never in the executable
    # path. Doubling its dollar signs would select a different file.
    if not executable:
        escaped = escaped.replace("$", "$$")
    return '"' + escaped + '"'


def service_content(prefix: list[str], config_path: Path) -> str:
    arguments = prefix + ["serve", "--service-config", str(config_path)]
    command = " ".join(systemd_quote(value, executable=index == 0) for index, value in enumerate(arguments))
    hostname = socket.gethostname()
    if not HOST_NAME.fullmatch(hostname):
        raise SetupError("This machine has an unsupported host name.")
    return f"# {MANAGED}\n[Unit]\nDescription=Herdr Pocket companion\nConditionHost={hostname}\nAfter=network-online.target\n\n[Service]\nType=simple\nExecStart={command}\nRestart=on-failure\nRestartSec=3\nUMask=0077\n\n[Install]\nWantedBy=default.target\n"


def start_service(prefix: list[str], config_path: Path, instance: str) -> str:
    if not instance.isalnum():
        raise SetupError("Invalid service instance.")
    if platform.system() == "Linux":
        systemctl = shutil.which("systemctl")
        if not systemctl or run([systemctl, "--user", "show-environment"], check=False).returncode:
            raise SetupError("A systemd user session is required. Log in normally, then run setup again.")
        unit = f"herdr-pocket-{instance}.service"
        root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
        path = root / "systemd/user" / unit
        if path.exists() and MANAGED not in path.read_text():
            raise SetupError("A different service already uses this name.")
        write_private(path, service_content(prefix, config_path).encode())
        run([systemctl, "--user", "daemon-reload"])
        run([systemctl, "--user", "enable", unit])
        run([systemctl, "--user", "restart", unit])
        # Linger keeps the user service alive after SSH/logout and starts it on boot.
        loginctl = shutil.which("loginctl")
        if loginctl:
            try:
                result = run([loginctl, "enable-linger", str(os.getuid())], check=False, timeout=10)
                lingering_failed = result.returncode != 0
            except SetupError:
                lingering_failed = True
            if lingering_failed:
                print("Background service installed. To keep it running after logout, an administrator may need to enable user lingering.")
        return unit
    if platform.system() == "Darwin":
        label = SERVICE_PREFIX + instance
        path = Path.home() / "Library/LaunchAgents" / (label + ".plist")
        log_directory = config_path.parent / "logs"
        private_directory(log_directory)
        value = {"Label": label, "Comment": MANAGED, "ProgramArguments": prefix + ["serve", "--service-config", str(config_path)],
                 "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 3, "Umask": 0o077,
                 "StandardOutPath": str(log_directory / "service.log"), "StandardErrorPath": str(log_directory / "service.log")}
        if path.exists() and plistlib.loads(path.read_bytes()).get("Comment") != MANAGED:
            raise SetupError("A different launch agent already uses this name.")
        write_private(path, plistlib.dumps(value))
        domain = f"gui/{os.getuid()}"
        run(["launchctl", "bootout", domain + "/" + label], check=False)
        run(["launchctl", "bootstrap", domain, str(path)])
        return label
    raise SetupError("Automatic setup supports Linux and macOS.")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def ensure_current_host(config: dict) -> None:
    hostname = config.get("hostName")
    if hostname and hostname != socket.gethostname():
        raise SetupError(f"This companion runs on {hostname}, while this terminal is on {socket.gethostname()}. "
                         f"Run the command on {hostname}; for example: ssh {hostname} '~/.local/bin/herdr-pocket pair'")


def wait_healthy(config: dict, timeout: float = 20, *, expected_version: str = __version__, allow_legacy_version: bool = False) -> None:
    ensure_current_host(config)
    request = Request(f"http://127.0.0.1:{config['port']}/v1/health", headers={"Authorization": "Bearer " + read_token(Path(config["tokenFile"]))})
    opener = build_opener(ProxyHandler({}), NoRedirect())
    deadline = time.monotonic() + timeout
    version_mismatch = False
    while True:
        try:
            with opener.open(request, timeout=2) as response:
                health = json.loads(response.read(16385))
            if health.get("service") == "herdr-android-bridge" and health.get("protocol") == 1 and health.get("pairing") is True:
                # Explicit rollback to a known pre-0.4 binary may use its older
                # health schema; setup/status must always require a version.
                legacy = allow_legacy_version and tuple(map(int, expected_version.split(".")[:2])) < (0, 4)
                if health.get("companionVersion") == expected_version or (legacy and "companionVersion" not in health):
                    return
                version_mismatch = True
        except Exception:
            pass
        if time.monotonic() >= deadline:
            if version_mismatch:
                raise SetupError(f"The running companion does not match Herdr Pocket {expected_version}. "
                                 "Run the matching installer again and check the companion service for an older process using its port.")
            raise SetupError("The companion did not become ready. Check Herdr and the background service, then run setup again.")
        time.sleep(0.2)


def setup(*, session: str | None = None, socket_path: Path | None = None, herdr_bin: str | None = None,
          config_root: Path | None = None, codex_bin: str | None = None, codex_home: Path | None = None,
          plain: bool = False, qr_file: Path | None = None) -> None:
    executable = find_herdr(herdr_bin)
    name, selected_socket = select_session(executable, session, socket_path)
    tailscale, dns_name = tailscale_state()
    instance = hashlib.sha256(str(selected_socket).encode()).hexdigest()[:12]
    root = (config_root or default_token_path().parent) / "instances" / instance
    private_directory(root)
    config_path = root / "setup.json"
    previous = read_config(config_path) if config_path.exists() else None
    if previous:
        # Shared home directories can contain another machine's local service.
        # Never overwrite its credentials, service unit, or tailnet address.
        ensure_current_host(previous)
    chat = codex_settings(codex_bin, codex_home)
    if previous:
        # Preserve an advanced custom home when upgrading from an installed companion.
        if codex_home is None and "CODEX_HOME" not in os.environ and previous.get("codexHome"):
            chat["codexHome"] = previous["codexHome"]
        if codex_bin is None and previous.get("codexBin"):
            prior_executable = Path(previous["codexBin"])
            if prior_executable.is_file() and os.access(prior_executable, os.X_OK):
                chat["codexBin"] = previous["codexBin"]
                if previous.get("codexPath"):
                    chat["codexPath"] = previous["codexPath"]
        if not chat.get("codexBin") and previous.get("codexBin"):
            chat.update({key: previous[key] for key in ("codexBin", "codexHome", "codexPath") if key in previous})
    port = previous["port"] if previous else free_port()
    proxy = f"http://127.0.0.1:{port}"
    try:
        serving = json.loads(run([tailscale, "serve", "status", "--json"]).stdout)
    except (ValueError, TypeError) as error:
        raise SetupError("Could not read Tailscale Serve settings.") from error
    https_port = serve_port(serving, dns_name, proxy, previous.get("httpsPort") if previous else None)
    token_path = root / "token"
    initialize_token(token_path)  # The owner token is never printed by setup.
    config = {"format": 1, "instance": instance, "session": name, "socket": str(selected_socket), "herdrBin": executable,
              "tokenFile": str(token_path.absolute()), "registryFile": str((root / "devices.json").absolute()),
              "host": "127.0.0.1", "hostName": socket.gethostname(), "port": port, "httpsPort": https_port,
              "url": f"https://{dns_name}:{https_port}/", "name": (socket.gethostname() + (" / " + name if name != "default" else ""))[:100]}
    config.update(chat)
    print(f"Preparing phone access to Herdr session {name}…")
    # Stop all readers of the common frozen archive before atomically replacing
    # it. Old one-file processes can otherwise lazily read the new archive.
    from .host_manage import installer_upgrade
    with installer_upgrade(config_root):
        write_private(config_path, (json.dumps(config) + "\n").encode())
        start_service(command_prefix(), config_path.absolute(), instance)
        wait_healthy(config)
        print("Configuring private Tailscale access. Follow any one-time HTTPS consent shown below.")
        run([tailscale, "serve", "--bg", f"--https={https_port}", proxy], visible=True, timeout=120)
        print("\nThe companion is running in the background.")
        show_pairing(config, plain=plain, qr_file=qr_file)


def configured_host(config_root: Path | None = None, session: str | None = None) -> dict:
    root = (config_root or default_token_path().parent) / "instances"
    configs = [read_config(path) for path in sorted(root.glob("*/setup.json"))]
    if session:
        configs = [config for config in configs if config["session"] == session]
    return choose(configs, "session", "configured Herdr Pocket hosts; run setup first")


def show_pairing(config: dict, *, plain: bool = False, qr_file: Path | None = None) -> dict:
    wait_healthy(config, timeout=3)
    challenge = PairingStore(Path(config["registryFile"])).begin_pairing(config["name"])
    payload = {"type": "herdr-pocket-pair", "version": 1, "url": config["url"], "name": config["name"], **challenge}
    encoded = json.dumps(payload, separators=(",", ":"), ensure_ascii=True)
    qr = None
    if qr_file is not None or not plain:
        import segno
        qr = segno.make(encoded, error="M", micro=False)
    if qr_file is not None:
        destination = qr_file.expanduser().absolute()
        if any(ord(char) < 32 for char in str(destination)):
            raise SetupError("Choose a QR image path without control characters.")
        image = io.BytesIO()
        qr.save(image, kind="png", scale=8, border=4)
        write_private(destination, image.getvalue(), preserve_directory_permissions=True)
        # Keep --plain stdout suitable for piping into existing consumers.
        print(f"Pairing QR image saved to {destination}. It can pair one phone and expires in 5 minutes.",
              file=sys.stderr if plain else sys.stdout)
    if plain:
        print(encoded)
    else:
        print("\nOpen Herdr Pocket on your phone and tap Scan pairing code.\n")
        qr.terminal(compact=True, border=4)
        print("This code can pair one phone and expires in 5 minutes.")
        print("To pair another phone later: herdr-pocket pair")
        print("Use 'herdr-pocket pair --plain' if you need to paste the pairing code instead.")
        print("Use 'herdr-pocket pair --qr-file pairing.png' to open the code as an image or import it from your phone's gallery.")
    return payload
