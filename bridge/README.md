# Herdr Android bridge

This companion runs as the same Unix user as Herdr. It provides authenticated HTTP commands and read-only ANSI terminal streams for the native Android app. It uses Herdr's existing public API; no Herdr fork is required.

Requires Linux/macOS, Python 3.11+, an installed Herdr 0.9.3 CLI, and a running Herdr session. The HTTP server listens on `127.0.0.1:8765` by default. Connect through Tailscale Serve HTTPS. Tailscale provides private network access; the bridge still authenticates every HTTP request and WebSocket handshake with its own token.

## Install and run

From the repository root:

```sh
python3 -m venv bridge/.venv
bridge/.venv/bin/python -m pip install -r bridge/requirements.lock
bridge/.venv/bin/python -m pip install --no-deps -e ./bridge
bridge/.venv/bin/herdr-android-bridge init-token
bridge/.venv/bin/herdr-android-bridge serve
```

`init-token` generates a token once and prints it for copying into the Android server profile. Running it again prints the same token. Treat that provisioning output as a secret. The token lives in `$XDG_CONFIG_HOME/herdr-android/token`, or `~/.config/herdr-android/token`, with mode `0600`. Existing shared or symlinked credential files are rejected. The server disables HTTP access logging and never puts credentials in a URL.

To select a named session or a custom socket explicitly:

```sh
HERDR_SOCKET="$HOME/.config/herdr/sessions/dev/herdr.sock" bridge/.venv/bin/herdr-android-bridge serve
```

`--socket /absolute/path/herdr.sock` overrides discovery. Otherwise, discovery uses `HERDR_SOCKET`, inherited `HERDR_SOCKET_PATH`, `HERDR_CONFIG_PATH`'s directory, or the per-user Herdr configuration directory and `HERDR_SESSION` (default `default`), in that order. Missing explicit sockets never fall back to another session. Both commands and observer processes use the selected socket.

If Herdr is outside `PATH`, add `--herdr-bin "$HOME/.local/bin/herdr"`. To use a different credential file, pass `--token-file /path/token` for both commands, or set `HERDR_ANDROID_TOKEN_FILE`.

For Tailscale Serve configuration and Android installation, see the repository README. Keep the bridge bound to loopback when using Serve. Multiple Herdr sessions can each run a bridge on a different port with a different token.

## Behavior and limits

- `/v1/health` verifies a live Herdr snapshot, so unavailable daemons return `503`.
- Literal text and logical keys travel in one `pane.send_input` request addressed to an explicit pane ID. No write is retried automatically. If a connection breaks during a write, refresh and inspect the pane before trying again.
- Workspace creation sets `focus: false` and starts Herdr's normal shell. Workspace `cwd` in snapshots is derived from a pane in the active tab, falling back to another pane in the workspace, because Herdr's workspace API does not contain a working directory.
- Terminal WebSockets spawn `herdr terminal session observe`, which preserves desktop controller ownership and geometry. The service forwards validated JSON frames unchanged. Closing a phone connection cancels and reaps its observer process; it does not kill the underlying pane.
- All HTTP bodies are limited to 64 KiB, including JSON encoding, with a ten-second upload deadline. Text is limited to 16,384 Unicode code points and 32 KiB UTF-8 with no NUL; at most 32 logical keys of up to 32 ASCII characters without whitespace are accepted per command. Terminal requests allow 20–300 columns and 5–150 rows. Unknown keys are rejected atomically by Herdr. See [precise protocol limits](../docs/protocol.md) for workspace fields and frame validation.
- CLI errors and submitted text are not copied into HTTP errors. The bridge's token can control every pane in the selected session, so assign private Tailscale access accordingly.

Herdr 0.9.3 frame example:

```json
{"type":"terminal.frame","bytes":"SGVsbG8=","encoding":"ansi","full":true,"width":80,"height":30,"seq":1}
```

`bytes` contains base64 ANSI output, `full` indicates a full screen frame, and subsequent frames can be incremental. Terminal closure is `{"type":"terminal.closed","reason":"terminal … exited"}`.

## Tests

```sh
bridge/.venv/bin/python -m pip install -e './bridge[test]'
bridge/.venv/bin/python -m pytest bridge/tests
```

Tests use a simulated Unix socket daemon and real temporary observer processes, covering authentication, API normalization, atomic submission, rejected or uncertain writes, limits, stale targets, invalid terminal frames, and process cleanup after disconnect. They never connect to a user's existing Herdr daemon.

An optional live integration test starts a uniquely named Herdr session, creates its own shell, verifies snapshots, atomic input and ANSI observation, and stops only that session:

```sh
HERDR_INTEGRATION=1 HERDR_BIN="$HOME/.local/bin/herdr" bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py
```

## Live Android device fixture

For a real Android UI test without accessing any existing user pane, start the fixture from the repository root:

```sh
bridge/.venv/bin/python scripts/live_device_fixture.py
```

It starts a UUID-named Herdr session, creates its own workspace labelled `Android live test` in a temporary directory, writes `HERDR_ANDROID_LIVE_DEVICE_READY` in green, and runs the actual bridge on `127.0.0.1:18765`. It prints readiness JSON containing `url`, `workspaceId`, and `paneId`. Startup is bounded; a busy port fails before any session is created. Keep the fixture running for the device test, then press Ctrl+C or send SIGTERM. It shuts down the HTTP service and stops and reaps only its own Herdr session; the session's stopped metadata can remain in Herdr's configuration directory.

With an emulator or test device attached, run in another terminal:

```sh
adb reverse tcp:18765 tcp:18765
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.herdr.android.LiveBridgeDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_bridge_url=http://127.0.0.1:18765 \
  -Pandroid.testInstrumentationRunnerArguments.live_bridge_token=android-emulator-isolated-test-token-20261008
adb reverse --remove tcp:18765
```

The fixed fixture token is a public test value for this temporary loopback service. Never use it for a real bridge profile or expose this fixture through Tailscale Serve. Ordinary app tests skip the live test when its URL/token arguments are absent. Use `--herdr-bin /path/to/herdr` if the fixture cannot discover the installed CLI.
