#!/usr/bin/env python3
"""Exercise a frozen host companion against an isolated, read-only mock daemon.

Run on Linux or macOS: python scripts/smoke_host_package.py /path/to/BINARY
No installed Herdr, Tailscale, service manager, or third-party HTTP client is used.
"""

from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager, suppress
import http.client
import json
import os
from pathlib import Path
import secrets
import shlex
import signal
import socket
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import urlencode


ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bridge"))
from herdr_bridge.pairing import PairingStore  # noqa: E402
from herdr_bridge import __version__  # noqa: E402


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
CHAT_THREAD = "00000000-0000-4000-8000-000000000003"
# Our generated 2x2 blue pixels exercise the frozen codecs, without requiring
# Pillow in the external smoke runner or introducing user-provided image data.
IMAGE_FIXTURES = {
    "png": "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mNkYPjPwMDAxMDAwMDAAAALHwEDmlnfSQAAAABJRU5ErkJggg==",
    "jpeg": "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAACAAIDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAABQf/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCOALiMf//Z",
    "webp": "UklGRjoAAABXRUJQVlA4IC4AAACQAQCdASoCAAIAAUAmJaACdLoAA5gA/vtV4/+lwf/S4P/pcH/pcH8bss4bpAAA",
}


@contextmanager
def mock_daemon(path: Path, *, chat: bool = False):
    methods: list[str] = []
    snapshot = json.loads(json.dumps(SNAPSHOT))
    if chat:
        snapshot["panes"][0].update(agent="codex", terminal_id="term_package_smoke",
            agent_session={"source": "herdr:codex", "agent": "codex", "kind": "id", "value": CHAT_THREAD})

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
                    response["result"] = {"type": "session_snapshot", "snapshot": snapshot}
                elif chat and method == "agent.get" and request.get("params") == {"target": "w1:p1"}:
                    response["result"] = {"type": "agent", "agent": snapshot["panes"][0] | {"interactive_ready": True}}
                elif chat and method == "pane.process_info" and request.get("params") == {"pane_id": "w1:p1"}:
                    response["result"] = {"type": "process_info", "process_info": {
                        "pane_id": "w1:p1", "foreground_processes": []}}
                elif method == "pane.send_text" and request.get("params") == {"pane_id": "w1:p1", "text": "mock keyboard\u0003\r"}:
                    response["result"] = {"type": "ok"}
                elif method == "pane.read" and request.get("params") == {"pane_id": "w1:p1", "source": "recent_unwrapped", "format": "text", "lines": 1000, "strip_ansi": True, "intent": "interactive"}:
                    response["result"] = {"type": "pane_read", "read": {"pane_id": "w1:p1", "source": "recent_unwrapped", "format": "text", "text": "Packaged terminal history\n", "truncated": False}}
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


def attachment_request(port: int, path: str, token: str, binding: str, *, data: bytes | None = None) -> tuple[int, dict]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    headers = {"Authorization": "Bearer " + token, "X-Herdr-Chat-Binding": binding,
               "Accept": "application/json", "Content-Type": "application/octet-stream"}
    try:
        connection.request("POST" if data is not None else "DELETE", path, body=data, headers=headers)
        response = connection.getresponse()
        raw = response.read(65537)
        check(len(raw) <= 65536, "Attachment request returned an oversized response")
        value = json.loads(raw)
        check(isinstance(value, dict), "Attachment request returned an invalid response")
        return response.status, value
    finally:
        connection.close()


def wait_ready(process: subprocess.Popen, port: int, token: str) -> dict:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        check(process.poll() is None, "The packaged companion exited before becoming ready")
        try:
            status, health = http_json(port, "/v1/health", token=token)
            if status == 200 and health.get("service") == "herdr-android-bridge" and health.get("protocol") == 1 and health.get("pairing") is True and health.get("companionVersion") == __version__:
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


def mock_codex(root: Path) -> tuple[Path, Path]:
    """Real child process exercises frozen spawning/framing without any model call."""
    home = root / "codex-home"
    home.mkdir(mode=0o700)
    source = root / "mock-codex.py"
    source.write_text('''import json,os,sys
from pathlib import Path
home=Path(os.environ["CODEX_HOME"])
for line in sys.stdin:
    request=json.loads(line)
    method=request.get("method")
    with (home / "methods.jsonl").open("a") as log:
        log.write(json.dumps(method)+"\\n")
    if method=="initialized":
        continue
    if method=="initialize":
        result={"userAgent":"package-smoke"}
    elif method=="thread/read":
        result={"thread":{"id":request["params"]["threadId"],"turns":[{"id":"turn-1","items":[
            {"type":"userMessage","id":"user-1","content":[{"type":"text","text":"A clean chat"}]},
            {"type":"commandExecution","id":"tool-1","command":"HIDDEN_TOOL_COMMAND","aggregatedOutput":"HIDDEN_TOOL_OUTPUT"},
            {"type":"agentMessage","id":"reply-1","text":"**Ready from the packaged reader.**","phase":"final_answer"}
        ]}]}}
    else:
        raise RuntimeError("Reader attempted an ownership operation")
    print(json.dumps({"id":request["id"],"result":result}),flush=True)
''')
    executable = root / "mock-codex"
    executable.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(source)) + ' "$@"\n')
    executable.chmod(0o700)
    return executable, home


def exercise(binary: Path) -> None:
    check("Herdr Pocket " in command(binary, "--version"), "The packaged binary has an unexpected version output")
    licenses = command(binary, "--licenses").lower()
    check(all(name in licenses for name in ("python", "segno", "pyinstaller", "pillow")), "The packaged license bundle is incomplete")
    # Darwin's default TMPDIR can be too long for a Unix socket pathname.
    with tempfile.TemporaryDirectory(prefix="pocket-smoke-", dir="/tmp") as temporary:
        root = Path(temporary).resolve()
        daemon_socket = root / "mock.sock"
        token_file, registry_file = root / "token", root / "devices.json"
        owner_token = secrets.token_urlsafe(32)
        write_private(token_file, (owner_token + "\n").encode())
        seen_secrets = [owner_token]
        store = PairingStore(registry_file)
        port = unused_port()
        config_root = root / "config"
        config_file = config_root / "instances/abcdef012345/setup.json"
        codex_bin, codex_home = mock_codex(root)
        config = {
            "format": 1, "instance": "abcdef012345", "session": "default", "name": "Package smoke",
            "socket": str(daemon_socket), "herdrBin": str(binary),
            "tokenFile": str(token_file), "registryFile": str(registry_file),
            "host": "127.0.0.1", "port": port, "httpsPort": 8443, "url": f"http://127.0.0.1:{port}/",
            "codexBin": str(codex_bin), "codexHome": str(codex_home), "codexPath": os.defpath,
        }
        write_private(config_file, json.dumps(config).encode())
        log_file = root / "companion.log"
        with mock_daemon(daemon_socket, chat=True) as methods, log_file.open("wb") as log:
            environment = dict(os.environ)
            environment["XDG_CACHE_HOME"] = str(root / "cache")
            process = subprocess.Popen([
                str(binary), "serve", "--service-config", str(config_file),
            ], stdout=log, stderr=log, start_new_session=True, env=environment)
            try:
                health = wait_ready(process, port, owner_token)
                check(health.get("herdrVersion") == "0.9.3", "The packaged bridge did not normalize the mock daemon version")
                check("codex-chat" in health.get("capabilities", []), "The packaged Codex reader was not discovered")
                check("codex-chat-working-input" in health.get("capabilities", []), "The packaged companion does not advertise working chat updates")
                check("codex-chat-attachments" in health.get("capabilities", []), "The packaged companion does not advertise chat attachments")
                check("terminal-input" in health.get("capabilities", []), "The packaged keyboard route was not advertised")
                check("terminal-history" in health.get("capabilities", []), "The packaged terminal history route was not advertised")
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
                check(snapshot["panes"][0].get("conversation", {}).get("available") is True,
                      "The packaged snapshot omitted a supported chat pane")
                status, keyboard = http_json(port, "/v1/panes/w1:p1/terminal-input", token=device_token, body={"text": "mock keyboard\u0003\r"})
                check(status == 200 and keyboard == {"ok": True}, "The packaged keyboard route changed controls or its explicit target")
                status, output = http_json(port, "/v1/panes/w1:p1/scrollback", token=device_token)
                check(status == 200 and output == {"paneId": "w1:p1", "text": "Packaged terminal history\n", "truncated": False},
                      "The packaged terminal history route changed text or its target")
                status, conversation = http_json(port, "/v1/panes/w1:p1/conversation", token=device_token)
                check(status == 200 and [m["text"] for m in conversation["messages"]] ==
                      ["A clean chat", "**Ready from the packaged reader.**"],
                      "The packaged reader did not produce clean conversation messages")
                check(conversation.get("canSend") is True and "HIDDEN_TOOL" not in json.dumps(conversation),
                      "The packaged chat exposed tools or rejected an omitted false launch flag")
                reader_methods = [json.loads(line) for line in (codex_home / "methods.jsonl").read_text().splitlines()]
                check(reader_methods == ["initialize", "initialized", "thread/read"],
                      "The packaged reader attempted additional Codex operations")
                binding = conversation["binding"]
                for extension, encoded in IMAGE_FIXTURES.items():
                    data = base64.b64decode(encoded, validate=True)
                    name = "owned screenshot." + extension
                    path = "/v1/panes/w1:p1/attachments?" + urlencode({"kind": "image", "name": name})
                    status, receipt = attachment_request(port, path, device_token, binding, data=data)
                    check(status == 201 and receipt.get("name") == name and receipt.get("kind") == "image"
                          and receipt.get("mimeType") == "image/" + extension and receipt.get("sizeBytes") == len(data)
                          and isinstance(receipt.get("id"), str) and len(receipt["id"]) == 32,
                          "A frozen image codec or scoped attachment upload failed")
                    check(set(receipt) == {"id", "name", "kind", "mimeType", "sizeBytes"},
                          "Attachment receipt exposed an unexpected field")
                    status, deleted = attachment_request(port, "/v1/panes/w1:p1/attachments/" + receipt["id"], device_token, binding)
                    check(status == 200 and deleted == {"ok": True}, "A frozen image stage could not be removed")
                data = b"Owned generic file fixture\n"
                path = "/v1/panes/w1:p1/attachments?" + urlencode({"kind": "file", "name": "notes.txt"})
                status, receipt = attachment_request(port, path, device_token, binding, data=data)
                check(status == 201 and receipt.get("kind") == "file" and receipt.get("sizeBytes") == len(data),
                      "A frozen generic file could not be staged")
                status, deleted = attachment_request(port, "/v1/panes/w1:p1/attachments/" + receipt["id"], device_token, binding)
                check(status == 200 and deleted == {"ok": True}, "A frozen generic file stage could not be removed")
                status, _ = http_json(port, "/v1/pair", body={"code": invitation["code"], "deviceName": "Replay test phone"})
                check(status == 401, "A used invitation could pair a second phone")

                devices = json.loads(command(binary, "devices", "--config-dir", str(config_root)))
                check(any(item["deviceId"] == device_id for item in devices), "The packaged devices command missed the paired phone")
                command(binary, "revoke-device", device_id, "--config-dir", str(config_root))
                status, _ = http_json(port, "/v1/snapshot", token=device_token)
                check(status == 401 and not store.authorized(device_token), "Revoked device credentials still authorized requests")
                status, _ = http_json(port, "/v1/panes/w1:p1/conversation", token=device_token)
                check(status == 401, "Revoked device credentials still authorized chat history")

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
                expected_methods = {"session.snapshot", "agent.get", "pane.send_text", "pane.read"}
                # Local-daemon attribution uses Linux process/socket metadata.
                # macOS retains the canonical history flow without that probe.
                if sys.platform.startswith("linux"):
                    expected_methods.add("pane.process_info")
                check(methods and set(methods) == expected_methods and methods.count("pane.send_text") == 1 and methods.count("pane.read") == 1,
                      "The smoke test dispatched unexpected operations to its synthetic daemon")
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
    print("Packaged host smoke passed: authenticated API, clean Codex chat, PNG/JPEG/WebP and file uploads, single-use pairing, revocation, frozen QR helpers, and bundled licenses.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
