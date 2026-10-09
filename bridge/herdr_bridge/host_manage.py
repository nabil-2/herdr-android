"""Owned companion lifecycle only; never stop Herdr or the Tailscale daemon."""

from __future__ import annotations

from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import shlex
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit

from .config import default_token_path
from .host_setup import (MANAGED, SERVICE_PREFIX, SetupError, ensure_current_host, read_config,
                         run, systemd_quote, wait_healthy)
from .runtime import external_environment

REPOSITORY = "nabil-2/herdr-android"
MAX_BINARY_BYTES = 256 * 1024 * 1024


def install_binary() -> Path:
    root = Path(os.environ.get("HERDR_POCKET_INSTALL_ROOT", Path.home() / ".local")).expanduser().absolute()
    return root / "lib/herdr-pocket/herdr-pocket"


def safe_file(path: Path, *, executable: bool = False) -> None:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1 or info.st_mode & 0o022:
        raise SetupError("Companion management requires regular files owned by you, without shared write permissions.")
    if executable and not os.access(path, os.X_OK):
        raise SetupError("The companion binary is not executable.")


def safe_directory(path: Path) -> None:
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
        raise SetupError("The companion installation directory must be owned by you and must not permit shared writes.")


def config_directory(root: Path | None) -> Path:
    return (root or default_token_path().parent).expanduser().absolute()


def verify_legacy_host(config: dict) -> None:
    """Old shared-home configs have no host field; verify their tailnet identity."""
    if config.get("hostName"):
        ensure_current_host(config)
        return
    tailscale = shutil.which("tailscale")
    if tailscale:
        try:
            value = json.loads(run([tailscale, "status", "--json"]).stdout)
            dns = value.get("Self", {}).get("DNSName", "")
            if isinstance(dns, str) and dns.rstrip(".").lower() == urlsplit(config["url"]).hostname:
                return
        except (SetupError, ValueError, TypeError, AttributeError, KeyError):
            pass
    raise SetupError("This older companion has no machine identity and could not be verified against local Tailscale. Run the command on its server with Tailscale connected; no services were changed.")


def unit_info(config: dict, path: Path) -> tuple[Path, str, list[str]] | None:
    instance = config["instance"]
    if not instance.isalnum():
        raise SetupError("Invalid companion instance.")
    if platform.system() == "Linux":
        unit = f"herdr-pocket-{instance}.service"
        directory = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "systemd/user"
        filename = directory / unit
        if not filename.exists() and not filename.is_symlink():
            return None
        safe_file(filename)
        content = filename.read_text()
        suffix = ' "serve" "--service-config" ' + systemd_quote(str(path))
        commands = [line.removeprefix("ExecStart=") for line in content.splitlines() if line.startswith("ExecStart=")]
        if f"# {MANAGED}" not in content or len(commands) != 1 or not commands[0].endswith(suffix):
            raise SetupError("Refusing to manage a service that does not match this companion configuration.")
        executable = commands[0][:-len(suffix)]
        hosts = [line.removeprefix("ConditionHost=") for line in content.splitlines() if line.startswith("ConditionHost=")]
        # Releases before host affinity was added had no ConditionHost.
        # Validate it whenever present, retaining their upgrade compatibility.
        if hosts and hosts != [socket.gethostname()]:
            raise SetupError("This companion service belongs to another machine or has an unsafe host condition. Use a machine-local installation.")
        if not hosts:
            verify_legacy_host(config)
        return filename, unit, [executable]
    if platform.system() == "Darwin":
        label = SERVICE_PREFIX + instance
        filename = Path.home() / "Library/LaunchAgents" / (label + ".plist")
        if not filename.exists() and not filename.is_symlink():
            return None
        safe_file(filename)
        value = plistlib.loads(filename.read_bytes())
        arguments = value.get("ProgramArguments", [])
        if value.get("Comment") != MANAGED or value.get("Label") != label or len(arguments) < 4 or arguments[-3:] != ["serve", "--service-config", str(path)]:
            raise SetupError("Refusing to manage a launch agent that does not match this companion configuration.")
        verify_legacy_host(config)
        return filename, label, arguments[:-3]
    raise SetupError("Companion management supports Linux and macOS.")


def service_configs(binary: Path) -> set[Path]:
    """Discover every managed reader, including instances outside the selected root."""
    result = set()
    if platform.system() == "Linux":
        directory = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "systemd/user"
        for filename in sorted(directory.glob("*.service")):
            # Unrelated units are not ours to inspect through unsafe links.
            try:
                safe_file(filename)
            except (SetupError, OSError):
                if filename.name.startswith("herdr-pocket-"):
                    raise SetupError("A companion service definition is unsafe; no binaries were changed.")
                continue
            if filename.stat().st_size > 65536:
                if filename.name.startswith("herdr-pocket-"):
                    raise SetupError("A companion service definition is too large.")
                continue
            content = filename.read_text()
            for line in content.splitlines():
                if not line.startswith("ExecStart="):
                    continue
                command = line.removeprefix("ExecStart=")
                try:
                    arguments = shlex.split(command)
                    arguments = [arguments[0].replace("%%", "%")] + [value.replace("%%", "%").replace("$$", "$") for value in arguments[1:]]
                except (ValueError, IndexError) as error:
                    if str(binary) in command or systemd_quote(str(binary), executable=True) in command:
                        raise SetupError("A service using the companion binary could not be verified.") from error
                    continue
                if arguments[0].lstrip("-+!:@") != str(binary):
                    continue
                if arguments[:3] != [str(binary), "serve", "--service-config"] or len(arguments) != 4 or not Path(arguments[3]).is_absolute():
                    raise SetupError("A service using the companion binary could not be verified.")
                path = Path(arguments[3])
                config = read_config(path)
                unit = unit_info(config, path)
                if unit is None or unit[0] != filename:
                    raise SetupError("A service using the companion binary does not match its configuration.")
                result.add(path)
    elif platform.system() == "Darwin":
        for filename in sorted((Path.home() / "Library/LaunchAgents").glob("*.plist")):
            try:
                safe_file(filename)
            except (SetupError, OSError):
                if filename.name.startswith(SERVICE_PREFIX):
                    raise SetupError("A companion launch agent definition is unsafe.")
                continue
            if filename.stat().st_size > 65536:
                continue
            value = plistlib.loads(filename.read_bytes())
            arguments = value.get("ProgramArguments", [])
            if not arguments or arguments[0] != str(binary):
                continue
            if len(arguments) != 4 or arguments[1:3] != ["serve", "--service-config"] or not Path(arguments[3]).is_absolute():
                raise SetupError("A launch agent using the companion binary could not be verified.")
            path = Path(arguments[3])
            config = read_config(path)
            unit = unit_info(config, path)
            if unit is None or unit[0] != filename:
                raise SetupError("A launch agent using the companion binary does not match its configuration.")
            result.add(path)
    else:
        raise SetupError("Companion management supports Linux and macOS.")
    return result


def instances(root: Path | None = None, *, binary: Path | None = None) -> list[tuple[dict, Path, tuple]]:
    result = []
    paths = set((config_directory(root) / "instances").glob("*/setup.json"))
    if binary is not None:
        paths.update(service_configs(binary))
    for path in sorted(paths):
        config = read_config(path)
        unit = unit_info(config, path.absolute())
        if unit is None:
            continue
        if binary is not None:
            expected = [systemd_quote(str(binary), executable=True)] if platform.system() == "Linux" else [str(binary)]
            if unit[2] != expected:
                continue
            if config.get("hostName") and config["hostName"] != socket.gethostname():
                raise SetupError("This installation is shared with a companion on another machine. Use a machine-local installation to update it.")
        ensure_current_host(config)
        result.append((config, path.absolute(), unit))
    return result


def active(unit: tuple) -> bool:
    if platform.system() == "Linux":
        result = run(["systemctl", "--user", "show", "--property=ActiveState", "--value", unit[1]], check=False)
        return result.returncode == 0 and result.stdout.strip() in {"active", "activating", "reloading", "deactivating"}
    return run(["launchctl", "print", f"gui/{os.getuid()}/{unit[1]}"], check=False).returncode == 0


def stop(unit: tuple) -> None:
    if platform.system() == "Linux":
        run(["systemctl", "--user", "stop", unit[1]])
    else:
        run(["launchctl", "bootout", f"gui/{os.getuid()}/{unit[1]}"])


def start(unit: tuple) -> None:
    if platform.system() == "Linux":
        run(["systemctl", "--user", "start", unit[1]])
    else:
        run(["launchctl", "bootstrap", f"gui/{os.getuid()}", str(unit[0])])


def binary_version(binary: Path) -> str:
    safe_file(binary, executable=True)
    safe_directory(binary.parent)
    result = run([str(binary), "--version"])
    match = re.fullmatch(r"Herdr Pocket ([0-9]+\.[0-9]+\.[0-9]+(?:[A-Za-z0-9.+-]*))\s*", result.stdout)
    if not match:
        raise SetupError("The selected file is not a versioned Herdr Pocket companion.")
    return match[1]


@contextmanager
def binary_upgrade(root: Path | None = None):
    with management_lock():
        with _binary_upgrade(root):
            yield


@contextmanager
def management_lock():
    directory = install_binary().parent
    directory.mkdir(parents=True, exist_ok=True)
    safe_directory(directory)
    path = directory / ".management.lock"
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        safe_file(path)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise SetupError("Another companion installation, update or uninstall is running. Try again after it finishes.") from error
        yield
    finally:
        os.close(descriptor)


@contextmanager
def _binary_upgrade(root: Path | None = None):
    """Stop readers before replacement: frozen processes may lazily reopen their archive."""
    binary = install_binary()
    if not binary.exists():
        yield
        return
    safe_file(binary, executable=True)
    managed = instances(root, binary=binary)
    running = [entry for entry in managed if active(entry[2])]
    previous_version = binary_version(binary)
    directory = tempfile.mkdtemp(prefix=".pocket-backup-", dir=binary.parent)
    preserve_backup = False
    try:
        backup = Path(directory) / "herdr-pocket"
        shutil.copyfile(binary, backup)
        backup.chmod(0o755)
        stopped = []
        try:
            for entry in running:
                stop(entry[2])
                if active(entry[2]):
                    raise SetupError("A companion service could not be stopped safely.")
                stopped.append(entry)
            yield
            version = binary_version(binary)
            for config, _, unit in stopped:
                if not active(unit):
                    start(unit)
                wait_healthy(config, expected_version=version)
        except (Exception, KeyboardInterrupt) as error:
            # Retain recovery bytes if cancellation or an I/O failure also
            # interrupts rollback. Remove them only after a successful restore.
            preserve_backup = True
            rollback_failed = False
            # Setup can create a new service during the transaction. Stop all
            # current archive readers, rather than only the original set.
            try:
                readers = instances(root, binary=binary)
            except Exception:
                readers = []
                rollback_failed = True
            for entry in readers:
                try:
                    if active(entry[2]):
                        stop(entry[2])
                        if active(entry[2]):
                            rollback_failed = True
                except (SetupError, OSError):
                    rollback_failed = True
            if rollback_failed:
                preserve_backup = True
                raise SetupError(f"Update failed and a companion service could not be stopped safely. The installed binary was kept; the previous binary is preserved at {backup}. Stop the companion services before restoring it.") from error
            try:
                os.replace(backup, binary)
            except OSError as restore_error:
                raise SetupError(f"Update failed and the previous binary could not be restored. Its recovery copy is preserved at {backup}.") from restore_error
            preserve_backup = False
            for config, _, unit in running:
                try:
                    start(unit)
                    if not active(unit):
                        raise SetupError("The previous companion service did not restart.")
                    wait_healthy(config, expected_version=previous_version, allow_legacy_version=True)
                except (SetupError, OSError):
                    rollback_failed = True
            message = "Update failed; the previous companion binary was restored. "
            message += "Check the companion service; it could not restart." if rollback_failed else "Previously running companion services were restarted."
            raise SetupError(message) from error
    finally:
        if not preserve_backup:
            shutil.rmtree(directory)


@contextmanager
def installer_upgrade(root: Path | None = None):
    if getattr(sys, "frozen", False):
        if Path(sys.executable).absolute() != install_binary():
            with binary_upgrade(root):
                yield
        else:
            with management_lock():
                yield
    else:
        yield


def platform_asset() -> str:
    system = {"Linux": "linux", "Darwin": "macos"}.get(platform.system())
    architecture = {"x86_64": "x86_64", "AMD64": "x86_64", "arm64": "arm64", "aarch64": "arm64"}.get(platform.machine())
    if system is None or architecture is None:
        raise SetupError("No companion package is available for this platform.")
    return f"herdr-pocket-{system}-{architecture}"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_manifest(binary: Path, manifest: Path, name: str) -> None:
    safe_file(manifest)
    if manifest.stat().st_size > 65536:
        raise SetupError("The release checksum file is too large.")
    values = []
    for line in manifest.read_text(encoding="ascii").splitlines():
        match = re.fullmatch(r"([A-Fa-f0-9]{64})\s+\*?([^\r\n]+)", line)
        if match and match[2] == name:
            values.append(match[1].lower())
    if len(values) != 1 or digest(binary) != values[0]:
        raise SetupError("Release checksum verification failed. Download the binary and SHA256SUMS again.")


def github_file(gh: str, endpoint: str, destination: Path, *, binary: bool = False) -> None:
    arguments = [gh, "api", endpoint]
    if binary:
        arguments += ["-H", "Accept: application/octet-stream"]
    try:
        with destination.open("xb") as output:
            result = subprocess.run(arguments, stdout=output, stderr=subprocess.PIPE, timeout=120, env=external_environment())
        destination.chmod(0o600)
        if result.returncode or destination.stat().st_size > MAX_BINARY_BYTES:
            raise SetupError("Could not download the release. Sign in with 'gh auth login' using access to nabil-2/herdr-android, or use --from-file with the release binary and SHA256SUMS.")
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SetupError("Could not download the release. Check GitHub CLI authentication or use --from-file.") from error


def download_release(directory: Path) -> tuple[Path, str]:
    gh = shutil.which("gh")
    if not gh:
        raise SetupError("Updates from this private repository need GitHub CLI. Install gh and run 'gh auth login', or download the binary and SHA256SUMS and use --from-file.")
    metadata = directory / "releases.json"
    github_file(gh, f"repos/{REPOSITORY}/releases?per_page=50", metadata)
    if metadata.stat().st_size > 4 * 1024 * 1024:
        raise SetupError("GitHub returned oversized release metadata.")
    releases = json.loads(metadata.read_text())
    if not isinstance(releases, list):
        raise SetupError("GitHub returned invalid release metadata.")
    published = [release for release in releases if isinstance(release, dict) and not release.get("draft") and release.get("published_at")]
    if not published:
        raise SetupError("No published companion release is available.")
    release = max(published, key=lambda value: str(value["published_at"]))
    name = platform_asset()
    assets = {asset.get("name"): asset for asset in release.get("assets", []) if isinstance(asset, dict)}
    for filename in (name, "SHA256SUMS"):
        asset = assets.get(filename, {})
        identifier = asset.get("id")
        if type(identifier) is not int or identifier <= 0:
            raise SetupError("The latest release does not include a package for this platform.")
        github_file(gh, f"repos/{REPOSITORY}/releases/assets/{identifier}", directory / filename, binary=True)
    candidate = directory / name
    verify_manifest(candidate, directory / "SHA256SUMS", name)
    candidate.chmod(0o755)
    version = binary_version(candidate)
    tag = release.get("tag_name", "")
    if tag != f"v{version}" and not tag.startswith(f"v{version}-"):
        raise SetupError("The release binary version does not match its release tag.")
    return candidate, version


def apply_update(payload_path: Path) -> None:
    """Run from the staged new archive, which remains stable while the installed archive changes."""
    safe_file(payload_path)
    payload = json.loads(payload_path.read_text())
    source = Path(sys.executable).absolute()
    directory = payload_path.parent
    binary = install_binary()
    if not getattr(sys, "frozen", False) or source.parent != directory or directory.parent != binary.parent or not directory.name.startswith(".pocket-update-"):
        raise SetupError("Invalid staged companion update.")
    if digest(source) != payload.get("sha256"):
        raise SetupError("The staged companion changed before installation.")
    root = Path(payload["configRoot"])
    try:
        with binary_upgrade(root):
            # Keep this process's archive at its staged path until it exits.
            replacement = directory / "replacement"
            shutil.copyfile(source, replacement)
            replacement.chmod(0o755)
            os.replace(replacement, binary)
        print("Herdr Pocket updated. Configuration and paired phones were preserved.")
    finally:
        shutil.rmtree(directory)


def update(*, config_root: Path | None = None, from_file: Path | None = None) -> None:
    if not getattr(sys, "frozen", False):
        raise SetupError("Use the installed packaged herdr-pocket command to update; source checkouts are updated through Git.")
    binary = install_binary()
    safe_file(binary, executable=True)
    safe_directory(binary.parent)
    directory = Path(tempfile.mkdtemp(prefix=".pocket-update-", dir=binary.parent))
    handed_off = False
    try:
        if from_file is None:
            candidate, version = download_release(directory)
        else:
            original = from_file.expanduser().absolute()
            safe_file(original)
            if original.stat().st_size > MAX_BINARY_BYTES:
                raise SetupError("The companion package is too large.")
            name = platform_asset()
            verify_manifest(original, original.parent / "SHA256SUMS", name)
            candidate = directory / name
            shutil.copyfile(original, candidate)
            candidate.chmod(0o755)
            verify_manifest(candidate, original.parent / "SHA256SUMS", name)
            version = binary_version(candidate)
        current = binary_version(binary)
        version_numbers = lambda value: tuple(map(int, re.match(r"([0-9]+)\.([0-9]+)\.([0-9]+)", value).groups()))
        if version_numbers(version) < version_numbers(current):
            raise SetupError("The published package is older than this installation. No services were changed.")
        if digest(candidate) == digest(binary):
            print(f"Herdr Pocket {version} is already installed.")
            return
        # Validate all managed instances before replacing this running process.
        instances(config_root, binary=binary)
        payload = directory / "apply.json"
        payload.write_text(json.dumps({"configRoot": str(config_directory(config_root)), "sha256": digest(candidate)}))
        payload.chmod(0o600)
        handed_off = True
        try:
            os.execve(str(candidate), [str(candidate), "_apply-update", str(payload)], external_environment())
        except OSError as error:
            handed_off = False
            raise SetupError("Could not start the verified updater. No services were changed.") from error
    finally:
        if not handed_off:
            shutil.rmtree(directory)


def remove_serve_route(config: dict) -> bool:
    tailscale = shutil.which("tailscale")
    if not tailscale:
        return False
    try:
        serving = json.loads(run([tailscale, "serve", "status", "--json"]).stdout)
        endpoint = f"{urlsplit(config['url']).hostname}:{config['httpsPort']}"
        handler = serving.get("Web", {}).get(endpoint, {}).get("Handlers", {}).get("/")
        if handler != {"Proxy": f"http://127.0.0.1:{config['port']}"}:
            return False
        # Explicit '/' removes only this handler, preserving other paths/ports.
        run([tailscale, "serve", "--bg", f"--https={config['httpsPort']}", "--set-path=/", "off"])
        return True
    except (SetupError, ValueError, TypeError, AttributeError, KeyError):
        return False


def uninstall(config: dict, *, config_root: Path | None = None, purge: bool = False) -> None:
    with management_lock():
        _uninstall(config, config_root=config_root, purge=purge)


def _uninstall(config: dict, *, config_root: Path | None = None, purge: bool = False) -> None:
    ensure_current_host(config)
    root = config_directory(config_root) / "instances" / config["instance"]
    path = root / "setup.json"
    if read_config(path) != config:
        raise SetupError("The companion configuration changed; run uninstall again.")
    unit = unit_info(config, path)
    if unit is None:
        verify_legacy_host(config)
    binary = install_binary()
    # Validate all cross-root archive readers and purge paths before mutations.
    managed = instances(config_root, binary=binary)
    if purge:
        preflight_purge(root, config)
    others = [candidate for candidate in (config_directory(config_root) / "instances").glob("*/setup.json") if candidate != path]
    others += [entry[1] for entry in managed if entry[1] != path]
    if not others and (binary.exists() or binary.is_symlink()):
        safe_file(binary)
    if unit is not None:
        if active(unit):
            stop(unit)
            if active(unit):
                raise SetupError("The companion service could not be stopped safely. No service definition or binary was removed.")
        if platform.system() == "Linux":
            run(["systemctl", "--user", "disable", unit[1]])
        unit[0].unlink()
        if platform.system() == "Linux":
            run(["systemctl", "--user", "daemon-reload"])
    route_removed = remove_serve_route(config)
    if not others:
        if binary.exists() or binary.is_symlink():
            safe_file(binary)
            link = binary.parents[2] / "bin/herdr-pocket"
            if link.is_symlink() and link.resolve() == binary.resolve():
                link.unlink()
            binary.unlink()
    if purge:
        shutil.rmtree(root)
    print("Selected Pocket companion uninstalled. Herdr and Tailscale were left running.")
    print("Private configuration and paired phones were removed." if purge else "Configuration and paired phones were kept for reinstallation; use --purge to remove them.")
    if not route_removed:
        print("Tailscale Serve settings were preserved because this instance's route could not be verified or removed.")


def preflight_purge(root: Path, config: dict) -> None:
    # Remove only the selected private instance, never arbitrary configured paths.
    info = root.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise SetupError("Refusing to purge an unsafe companion directory.")
    if any(Path(config[key]).parent != root for key in ("tokenFile", "registryFile")):
        raise SetupError("Private credentials are outside this instance directory; move or remove them explicitly before using --purge.")
    for directory, directories, files in os.walk(root, followlinks=False):
        for name in directories + files:
            entry = Path(directory) / name
            if entry.is_symlink() or entry.lstat().st_uid != os.getuid():
                raise SetupError("Refusing to purge a companion directory containing unsafe files.")
