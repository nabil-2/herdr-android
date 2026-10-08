#!/usr/bin/env python3
"""Real Herdr + bridge fixture for an Android emulator connected with adb reverse.

Run with bridge/.venv/bin/python scripts/live_device_fixture.py. This fixture's
fixed, ephemeral test token is intentionally public; never use it for real work.
It creates and operates on only a new, uniquely named Herdr session. stdout is a
single readiness JSON object. SIGTERM or Ctrl+C closes the bridge and that session.
"""

from __future__ import annotations

import argparse
import asyncio
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import sys
import tempfile
import uuid

import httpx
import uvicorn

from herdr_bridge.app import create_app
from herdr_bridge.herdr import HerdrClient, stop_observer


TEST_TOKEN = "android-emulator-isolated-test-token-20261008"
PORT = 18765
MARKER = "HERDR_ANDROID_LIVE_DEVICE_READY"


class FixtureServer(uvicorn.Server):
    @contextmanager
    def capture_signals(self):
        # The fixture owns SIGTERM/SIGINT so one signal cleans up both services.
        yield


async def run(executable: str) -> None:
    # Claim the fixed fixture port before creating any Herdr processes. This also
    # avoids a background uvicorn task raising SystemExit when the port is busy.
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        listener.bind(("127.0.0.1", PORT))
        listener.listen(128)
    except OSError:
        listener.close()
        raise
    session_name = "android-device-it-" + uuid.uuid4().hex[:12]
    env = dict(os.environ)
    for key in (
        "HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CLIENT_SOCKET_PATH", "HERDR_SESSION",
        "HERDR_CONFIG_PATH", "HERDR_PANE_ID", "HERDR_TAB_ID", "HERDR_WORKSPACE_ID",
    ):
        env.pop(key, None)
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stopped.set)

    herdr_process = None
    log_drain = None
    bridge = None
    bridge_task = None
    with tempfile.TemporaryDirectory(prefix="herdr-android-device-") as directory:
        try:
            herdr_process = await asyncio.create_subprocess_exec(
                executable, "--session", session_name, "server", env=env,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
            )
            assert herdr_process.stdout is not None
            async with asyncio.timeout(10):
                while True:
                    line = await herdr_process.stdout.readline()
                    if not line:
                        raise RuntimeError("Isolated Herdr session did not start")
                    if line.startswith(b"api socket: "):
                        socket_path = Path(line.decode().strip().removeprefix("api socket: "))
                        break

            async def discard_server_output():
                assert herdr_process is not None and herdr_process.stdout is not None
                while await herdr_process.stdout.read(65536):
                    pass

            log_drain = asyncio.create_task(discard_server_output())
            client = HerdrClient(socket_path, executable)
            workspace_id = await client.create_workspace(directory, "Android live test")
            snapshot = await client.snapshot()
            panes = [pane for pane in snapshot["panes"] if pane["workspaceId"] == workspace_id]
            if len(panes) != 1:
                raise RuntimeError("Isolated workspace did not expose one root pane")
            pane_id = panes[0]["id"]
            await client.send_input(
                pane_id,
                f"printf '\\033[32m{MARKER}\\033[0m\\n'",
                ["enter"],
            )

            bridge = FixtureServer(uvicorn.Config(
                create_app(client, TEST_TOKEN), host="127.0.0.1", port=PORT,
                log_level="error", access_log=False, timeout_graceful_shutdown=3,
            ))
            bridge_task = asyncio.create_task(bridge.serve(sockets=[listener]))
            url = f"http://127.0.0.1:{PORT}"
            async with httpx.AsyncClient(trust_env=False, timeout=1) as phone:
                async with asyncio.timeout(10):
                    while True:
                        if bridge_task.done():
                            bridge_task.result()
                            raise RuntimeError("Bridge stopped before becoming ready")
                        try:
                            response = await phone.get(url + "/v1/health", headers={"Authorization": f"Bearer {TEST_TOKEN}"})
                            if bridge.started and response.status_code == 200:
                                break
                        except httpx.HTTPError:
                            pass
                        await asyncio.sleep(0.1)
            # Readiness contains only the public fixture URL and explicit target IDs.
            print(json.dumps({"url": url, "workspaceId": workspace_id, "paneId": pane_id}), flush=True)
            await stopped.wait()
        finally:
            if bridge is not None:
                bridge.should_exit = True
            if bridge_task is not None:
                try:
                    await asyncio.wait_for(asyncio.shield(bridge_task), timeout=8)
                except TimeoutError:
                    if bridge is not None:
                        bridge.force_exit = True
                    bridge_task.cancel()
                    await asyncio.gather(bridge_task, return_exceptions=True)
                except (SystemExit, asyncio.CancelledError):
                    pass
            if herdr_process is not None:
                # The UUID belongs to this fixture; never run bare `herdr server stop`.
                stop = await asyncio.create_subprocess_exec(
                    executable, "--session", session_name, "session", "stop", session_name,
                    env=env, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
                )
                try:
                    await asyncio.wait_for(stop.wait(), timeout=5)
                except TimeoutError:
                    await stop_observer(stop)
                if log_drain is not None:
                    log_drain.cancel()
                    await asyncio.gather(log_drain, return_exceptions=True)
                await stop_observer(herdr_process)
            for sig in (signal.SIGTERM, signal.SIGINT):
                loop.remove_signal_handler(sig)
            listener.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--herdr-bin", default=os.environ.get("HERDR_BIN") or shutil.which("herdr") or str(Path.home() / ".local/bin/herdr"))
    args = parser.parse_args()
    try:
        asyncio.run(run(args.herdr_bin))
    except (OSError, RuntimeError, TimeoutError):
        print("Live fixture failed. Check the Herdr executable and whether port 18765 is free.", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
