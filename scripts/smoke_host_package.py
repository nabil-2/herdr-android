#!/usr/bin/env python3
"""Exercise a frozen host companion against an isolated, read-only mock daemon.

Run on Linux or macOS: python scripts/smoke_host_package.py /path/to/BINARY
No installed Herdr, Tailscale, service manager, or third-party HTTP client is used.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager, suppress
import http.client
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import time


ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bridge"))
from herdr_bridge.pairing import PairingStore  # noqa: E402


class SmokeFailure(Exception):
    """Diagnostics must never interpolate request/response credential bodies."""


def check(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeFailure(message)


SNAPSHOT = {
    "version": "0.9.3", "protocol": 22,
    "workspaces": [{"workspace_id": "w1", "label": "Package smoke", "active_tab_id": "w1:t1"}],
    "tabs": [{"tab_id": "w1:t1", "workspace_id": "w1", "label": "Shell"}],
    "panes": [{"pane_id": "w1:p1", "workspace_id": "w1", "tab_id": "w1:t1", "cwd": "/mock/project", "agent_status": "idle"}],
    "agents": [], "layouts": [],
}


@contextmanager
def mock_daemon(path: Path):
    methods: list[str] = []

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            self.request.settimeout(3)
            try:
                line = self.rfile.readline(65537)
                if len(line) > 65536:
                    return
                request = json.loads(line)
                method = request.get("method")
                methods.append(method)
                response = {"id": request["id"]}
                if method == "session.snapshot" and request.get("params") == {}:
                    response["result"] = {"type": "session_snapshot", "snapshot": SNAPSHOT}
                else:
                    response["error"] = {"code": "unknown_method"}
                self.wfile.write(json.dumps(response).encode() + b"\n")
            except (OSError, ValueError, KeyError):
                return

    class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
        daemon_threads = True
        block_on_close = False

    server = Server(str(path), Handler)
    path.chmod(0o600)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    try:
        yield methods
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=3)


def unused_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def http_json(port: int, path: str, *, token: str | None = None, body: dict | None = None) -> tuple[int, dict]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    try:
        connection.request("POST" if body is not None else "GET", path,
                           body=json.dumps(body).encode() if body is not None else None, headers=headers)
        response = connection.getresponse()
        raw = response.read(1024 * 1024 + 1)
        check(len(raw) <= 1024 * 1024, "The companion returned an oversized response")
        value = json.loads(raw)
        check(isinstance(value, dict), "The companion returned a non-object response")
        return response.status, value
    finally:
        connection.close()


def wait_ready(process: subprocess.Popen, port: int, token: str) -> dict:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        check(process.poll() is None, "The packaged companion exited before becoming ready")
        try:
            status, health = http_json(port, "/v1/health", token=token)
            if status == 200 and health.get("service") == "herdr-android-bridge" and health.get("protocol") == 1 and health.get("pairing") is True:
                return health
        except (OSError, ValueError, http.client.HTTPException):
            pass
        time.sleep(0.1)
    raise SmokeFailure("The packaged companion did not become ready within 30 seconds")


def stop_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    # PyInstaller one-file binaries have a bootloader parent and runtime child.
    # Only the new process group created by this test receives cleanup signals.
    with suppress(ProcessLookupError):
        os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def command(binary: Path, *arguments: str) -> str:
    result = subprocess.run([str(binary), *arguments], capture_output=True, text=True, timeout=30)
    check(result.returncode == 0, f"The packaged {arguments[0]} command failed")
    return result.stdout


def write_private(path: Path, contents: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as file:
        file.write(contents)


def exercise(binary: Path) -> None:
    check("Herdr Pocket " in command(binary, "--version"), "The packaged binary has an unexpected version output")
    licenses = command(binary, "--licenses").lower()
    check(all(name in licenses for name in ("python", "segno", "pyinstaller")), "The packaged license bundle is incomplete")
    # Darwin's default TMPDIR can be too long for a Unix socket pathname.
    with tempfile.TemporaryDirectory(prefix="pocket-smoke-", dir="/tmp") as temporary:
        root = Path(temporary)
        daemon_socket = root / "mock.sock"
        token_file, registry_file = root / "token", root / "devices.json"
        owner_token = secrets.token_urlsafe(32)
        write_private(token_file, (owner_token + "\n").encode())
        seen_secrets = [owner_token]
        store = PairingStore(registry_file)
        port = unused_port()
        config_root = root / "config"
        config_file = config_root / "instances/abcdef012345/setup.json"
        config = {
            "format": 1, "instance": "abcdef012345", "session": "default", "name": "Package smoke",
            "socket": str(daemon_socket), "herdrBin": str(binary),
            "tokenFile": str(token_file), "registryFile": str(registry_file),
            "host": "127.0.0.1", "port": port, "httpsPort": 8443, "url": f"http://127.0.0.1:{port}/",
        }
        write_private(config_file, json.dumps(config).encode())
        log_file = root / "companion.log"
        with mock_daemon(daemon_socket) as methods, log_file.open("wb") as log:
            process = subprocess.Popen([
                str(binary), "serve", "--socket", str(daemon_socket), "--token-file", str(token_file),
                "--registry-file", str(registry_file), "--port", str(port),
            ], stdout=log, stderr=log, start_new_session=True)
            try:
                health = wait_ready(process, port, owner_token)
                check(health.get("herdrVersion") == "0.9.3", "The packaged bridge did not normalize the mock daemon version")
                status, _ = http_json(port, "/v1/health")
                check(status == 401, "Anonymous health access was not rejected")

                invitation = store.begin_pairing("Package smoke")
                seen_secrets.append(invitation["code"])
                status, paired = http_json(port, "/v1/pair", body={"code": invitation["code"], "deviceName": "Package test phone"})
                check(status == 200 and paired.get("service") == "herdr-android-bridge" and paired.get("protocol") == 1,
                      "The packaged pairing endpoint rejected a valid invitation")
                device_token, device_id = paired["token"], paired["deviceId"]
                seen_secrets.append(device_token)
                check(len(device_token) == 43 and store.authorized(device_token), "Pairing did not persist a device credential")

                status, snapshot = http_json(port, "/v1/snapshot", token=device_token)
                check(status == 200 and snapshot["workspaces"][0]["id"] == "w1" and snapshot["panes"][0]["id"] == "w1:p1",
                      "A paired phone could not read the normalized snapshot")
                status, _ = http_json(port, "/v1/pair", body={"code": invitation["code"], "deviceName": "Replay test phone"})
                check(status == 401, "A used invitation could pair a second phone")

                devices = json.loads(command(binary, "devices", "--config-dir", str(config_root)))
                check(any(item["deviceId"] == device_id for item in devices), "The packaged devices command missed the paired phone")
                command(binary, "revoke-device", device_id, "--config-dir", str(config_root))
                status, _ = http_json(port, "/v1/snapshot", token=device_token)
                check(status == 401 and not store.authorized(device_token), "Revoked device credentials still authorized requests")

                plain = command(binary, "pair", "--plain", "--config-dir", str(config_root))
                qr = json.loads(plain)
                check(qr.get("type") == "herdr-pocket-pair" and qr.get("version") == 1 and qr.get("url") == config["url"],
                      "The packaged plain pairing command returned an invalid QR payload")
                seen_secrets.append(qr["code"])
                check(owner_token not in plain and device_token not in plain, "The plain pairing output exposed an existing credential")
                status, paired_again = http_json(port, "/v1/pair", body={"code": qr["code"], "deviceName": "Frozen QR helper phone"})
                check(status == 200, "A frozen helper's pairing code could not be redeemed by the packaged server")
                seen_secrets.append(paired_again["token"])
                status, _ = http_json(port, "/v1/snapshot", token=paired_again["token"])
                check(status == 200, "The frozen QR helper's paired credential could not read state")

                terminal_qr = command(binary, "pair", "--config-dir", str(config_root))
                check("Scan pairing code" in terminal_qr and any(char in terminal_qr for char in "▀▄█"),
                      "The frozen helper did not render a terminal QR code")
                check(all(secret not in terminal_qr for secret in seen_secrets), "Terminal QR output exposed existing plaintext credentials")
                check(methods and set(methods) == {"session.snapshot"}, "The smoke test attempted to mutate a Herdr session")
                check(stat.S_IMODE(token_file.stat().st_mode) == 0o600 and stat.S_IMODE(registry_file.stat().st_mode) == 0o600,
                      "The smoke test's credential files are not owner-only")
                registry_text = registry_file.read_text()
                check(all(secret not in registry_text for secret in seen_secrets), "The registry stored raw pairing credentials")
            finally:
                stop_process(process)
        check(all(secret not in log_file.read_text(errors="replace") for secret in seen_secrets), "The packaged companion logged a credential")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    args = parser.parse_args()
    binary = args.binary.expanduser().resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK):
        parser.error("BINARY must be an executable packaged host companion")
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    try:
        exercise(binary)
    except KeyboardInterrupt:
        return 130
    except Exception as error:
        message = str(error) if isinstance(error, SmokeFailure) else f"Unexpected {type(error).__name__}"
        print("Packaged host smoke test failed: " + message, file=sys.stderr)
        return 1
    print("Packaged host smoke passed: authenticated API, single-use pairing, revocation, frozen QR helpers, and bundled licenses.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
