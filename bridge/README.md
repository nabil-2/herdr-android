# Herdr Pocket host companion

This companion runs as the same Unix user as Herdr. It provides authenticated commands, read-only Codex conversation history, and ANSI terminal streams for the native Android app. It uses Herdr's existing public API; no Herdr fork is required.

For normal installation, use the self-contained release installer and QR pairing described in the [repository README](../README.md#install-and-pair). Python and Git are unnecessary for that path. This document covers managed service details, source development, and advanced manual configuration. The current integration targets Herdr 0.9.3 on Linux/macOS.

## Managed setup and pairing

The release installer installs `~/.local/lib/herdr-pocket/herdr-pocket` and a command at `~/.local/bin/herdr-pocket`. Setup checks that Herdr is running, selects its owned Unix socket, and creates one companion instance per session. If several sessions are available, choose one interactively or use:

```sh
~/.local/bin/herdr-pocket setup --session dev
```

Tailscale must be installed, online, and signed into the same tailnet as the phone. On macOS, setup also discovers the standard `/Applications/Tailscale.app` installation if the CLI is absent from `PATH`. Setup selects a free loopback HTTP port starting at 8765 and a private Tailscale Serve HTTPS port starting at 8443. Existing Serve endpoints are preserved, and setup will not reuse a public Funnel endpoint. Tailscale may display a one-time HTTPS approval URL.

Linux uses a systemd user service named `herdr-pocket-INSTANCE.service`. Setup requires a working `systemctl --user` manager and attempts to enable user lingering. If lingering is denied or unavailable, an administrator must enable it for service startup at boot and operation after logout. macOS uses `~/Library/LaunchAgents/dev.herdr.pocket.INSTANCE.plist`; the LaunchAgent starts at login and requires that user to remain logged in. Neither service starts or restarts Herdr itself.

Instance configuration is under `$XDG_CONFIG_HOME/herdr-android/instances/INSTANCE`, or `~/.config/herdr-android/instances/INSTANCE`. Each instance has `setup.json`, an owner credential in `token`, and a paired-device registry in `devices.json`. Configuration and credentials are owner-only regular files. The registry contains hashes of pairing codes and device credentials, not their original values.

Setup prints a five-minute, one-use QR invitation. The phone reviews its host name/address and explicitly confirms **Pair & connect** before exchanging it for a device credential. To generate another invitation or manage devices:

```sh
~/.local/bin/herdr-pocket pair --session dev
~/.local/bin/herdr-pocket pair --plain --session dev
~/.local/bin/herdr-pocket pair --qr-file ~/herdr-pocket-pairing.png --session dev
~/.local/bin/herdr-pocket status --session dev
~/.local/bin/herdr-pocket devices --session dev
~/.local/bin/herdr-pocket revoke-device DEVICE_ID --session dev
```

`pair --plain` prints the complete QR JSON for the app's paste alternative. Treat an unused invitation as a temporary access credential. A separate credential is issued for each successful pairing. `devices` lists metadata and IDs, never tokens; `revoke-device` rejects that phone's future requests and closes its active terminal stream. Other phones and the owner credential remain valid. Deleting a profile on a phone does not revoke its host-side credential.

`pair --qr-file PATH` also saves the invitation as an owner-only PNG. Open it on your computer or transfer it to your phone and choose **Choose QR screenshot** in the app. `setup --qr-file PATH` exports its initial invitation too. Combining `pair --plain --qr-file PATH` keeps JSON-only stdout. Export directories must be owned by you and must not allow other users to write; existing directory permissions are preserved. The PNG expires with its invitation after five minutes.

Omit `--session` when only one instance is configured. Run `setup --session NAME` for additional running Herdr sessions. Re-running setup for the same socket retains the instance's owner credential and device registry. `--socket /absolute/path/herdr.sock`, `--herdr-bin /path/to/herdr`, and `--config-dir /private/config/root` provide explicit setup overrides.

Setup automatically discovers the optional Codex CLI, its `CODEX_HOME` (default `~/.codex`), and the executable search path needed by user-installed runtimes. These are retained in owner-only service configuration. Advanced overrides are `--codex-bin /absolute/path/codex` and `--codex-home /absolute/path/home`; direct `serve` accepts them too. Codex is not bundled or required for terminal-only hosts. The tested version is Codex 0.162.0.

The bridge starts a local stdio Codex app-server only when reading a supported pane's saved conversation. The reader’s RPC allowlist permits initialization and `thread/read` only; it cannot create, resume, or control a model turn. Replies use Herdr's `agent.prompt` in the original pane. The reader may update Codex's local metadata databases. Paginated history needs the matching Codex home and its projected history database, not just copied JSONL files. Missing history or session attribution uses the terminal fallback.

Version 0.5.1 allows a verified Codex pane to receive updates while working through Herdr’s normal text-plus-Enter submission. This follows the [Codex CLI’s current-turn update behavior](https://learn.chatgpt.com/docs/cli/reference#interactive-shortcuts). Binding, canonical session, pending launch, and interactive readiness checks still apply; a renamed agent’s display label is not a managed-launch indicator. A configured reader advertises `codex-chat-working-input`, and clients opt in with `X-Herdr-Chat-Working-Input: 1` on both conversation GETs and message POSTs. Without the header, older phones retain history and idle/done submission. Updated phones explain when an older companion needs upgrading. Neither path retries uncertain writes. See the [chat protocol](../docs/protocol.md#native-saved-codex-conversation) for the exact readiness rules.

The packaged companion's full bundled license texts are available with `~/.local/bin/herdr-pocket --licenses` and in the release's `LICENSES.txt`.

## Managed updates and removal

`herdr-pocket update` downloads the latest published platform binary and checksum manifest from the private release repository using an authenticated GitHub CLI. For offline upgrades, use `herdr-pocket update --from-file /path/herdr-pocket-linux-x86_64` with that release's `SHA256SUMS` beside it. The staged binary is checksum-verified before execution. Previously active services are stopped before replacing the frozen archive, restarted afterward, and checked against the expected companion version. Settings and paired credentials stay in place. Source checkouts are updated through Git.

`herdr-pocket uninstall --session NAME` removes only the selected verified managed service and matching root Serve handler. It preserves other Serve paths/ports, Herdr, Tailscale, and other instances. Credentials/settings stay for reinstallation; `--purge` explicitly removes the selected private instance directory. Management refuses shared binaries used by an instance owned by another hostname.

Linux units include `ConditionHost` and setup records `hostName`. Run management and pairing on that machine. Re-run `herdr-pocket setup` to start an installed companion again; Herdr must already be running. The authenticated health response advertises `companionVersion` so an old listener cannot falsely satisfy readiness for a new installer.

## Install from source

Source development requires Python 3.11 or later, the installed Herdr CLI, a running Herdr session, and Tailscale for managed setup.

From the repository root:

```sh
python3 -m venv bridge/.venv
bridge/.venv/bin/python -m pip install -r bridge/requirements.lock
bridge/.venv/bin/python -m pip install --no-deps -e ./bridge
bridge/.venv/bin/herdr-pocket setup
```

The locked environment includes host test tools. Source setup uses the same services and pairing flow as the packaged installer, but its service runs this virtual environment's Python interpreter. Keep the repository and virtual environment at their installed paths. For later commands, use `bridge/.venv/bin/herdr-pocket pair`, `devices`, `revoke-device`, or `status`.

To build a self-contained installer on the target OS and architecture:

```sh
bridge/.venv/bin/python -m pip install -r bridge/requirements-build.lock
bridge/.venv/bin/python scripts/build_host.py
```

Outputs under `artifacts/host` include the executable, matching `.sh` installer, `LICENSES.txt`, and `SHA256SUMS`. The installer verifies its embedded binary checksum before executing it. Cross-platform releases are built on matching Linux/macOS runners; the packaging workflow does not establish physical macOS service validation.

## Advanced manual service and profiles

To run the HTTP server directly and provision an existing manual Android profile:

```sh
bridge/.venv/bin/herdr-android-bridge init-token
bridge/.venv/bin/herdr-android-bridge serve
```

This listens on `127.0.0.1:8765` by default. Keep the process running; closing it stops phone access without stopping Herdr's agents. `init-token` generates an owner token once and prints it for provisioning an advanced manual Android profile. Running it again prints the same token. Treat that output as a secret. The token grants control of the selected session and cannot be revoked through `revoke-device`.

The token lives in `$XDG_CONFIG_HOME/herdr-android/token`, or `~/.config/herdr-android/token`, with mode `0600`. Existing shared or symlinked credential files are rejected. Direct `serve` uses an owner-only `devices.json` registry beside the token unless `--registry-file` is specified. The managed `pair` command operates on setup instances; use managed setup for QR onboarding. HTTP access logging is disabled, and credentials are never put in URLs.

To select a named session or a custom socket explicitly:

```sh
bridge/.venv/bin/herdr-android-bridge serve --socket "$HOME/.config/herdr/sessions/dev/herdr.sock"
```

`--socket /absolute/path/herdr.sock` overrides discovery. Otherwise, discovery uses `HERDR_SOCKET`, inherited `HERDR_SOCKET_PATH`, `HERDR_CONFIG_PATH`'s directory, or the per-user Herdr configuration directory and `HERDR_SESSION` (default `default`), in that order. Missing explicit sockets never fall back to another session. Both commands and observer processes use the selected socket.

If Herdr is outside `PATH`, add `--herdr-bin "$HOME/.local/bin/herdr"`. To use a different credential file, pass `--token-file /path/token` for both commands, or set `HERDR_ANDROID_TOKEN_FILE`.

For manual HTTPS exposure, inspect `tailscale serve status` and choose an unused HTTPS port, then configure it, for example:

```sh
tailscale serve --bg --https=8443 http://127.0.0.1:8765
tailscale serve status
```

Enter that HTTPS address and the owner token in **Advanced: manual server setup**, or **Add server** beside existing profiles. Keep the listener on loopback when using Serve. Every command and terminal handshake still requires the companion's credential; Tailscale access alone is insufficient. Multiple manually configured services need separate ports and credentials.

For private-network development, the app accepts HTTP on loopback and numeric Tailscale addresses (`100.64.0.0/10` or `fd7a:115c:a1e0::/48`). To connect directly, bind `serve` to that specific address with `--host`, then use a URL such as `http://100.x.y.z:8765`. Arbitrary HTTP hosts, URL credentials, redirects, and untrusted TLS certificates are rejected.

## Behavior and limits

- `/v1/health` verifies a live Herdr snapshot, so unavailable daemons return `503`. Pairing also verifies Herdr before consuming an invitation.
- The one-use pairing exchange is the only endpoint without a bearer token. It validates the invitation, issues a distinct device token, and atomically consumes the code. Revoked device tokens are rejected; active paired streams check revocation once per second.
- Literal text and logical keys travel in one `pane.send_input` request addressed to an explicit pane ID. No write is retried automatically. If a connection breaks during a write, refresh and inspect the pane before trying again.
- Workspace creation sets `focus: false` and starts Herdr's normal shell. Workspace `cwd` in snapshots is derived from a pane in the active tab, falling back to another pane in the workspace, because Herdr's workspace API does not contain a working directory.
- Terminal WebSockets spawn `herdr terminal session observe`, which preserves desktop controller ownership and geometry. The service forwards validated JSON frames unchanged. Closing a phone connection cancels and reaps its observer process; it does not kill the underlying pane.
- All HTTP bodies are limited to 64 KiB, including JSON encoding, with a ten-second upload deadline. Text is limited to 16,384 Unicode code points and 32 KiB UTF-8 with no NUL; at most 32 logical keys of up to 32 ASCII characters without whitespace are accepted per command. Terminal requests allow 20–300 columns and 5–150 rows. Unknown keys are rejected atomically by Herdr. See [precise protocol limits](../docs/protocol.md) for workspace fields and frame validation.
- CLI errors, credentials, and submitted text are not copied into HTTP errors. Both owner and device tokens control every pane in the selected session, so assign private Tailscale access accordingly.

Herdr 0.9.3 frame example:

```json
{"type":"terminal.frame","bytes":"SGVsbG8=","encoding":"ansi","full":true,"width":80,"height":30,"seq":1}
```

`bytes` contains base64 ANSI output, `full` indicates a full screen frame, and subsequent frames can be incremental. Terminal closure is `{"type":"terminal.closed","reason":"terminal … exited"}`.

## Tests

```sh
bridge/.venv/bin/python -m pytest bridge/tests
```

Tests use simulated Unix socket daemons, temporary registries, and observer processes, covering authentication, one-use pairing, concurrent exchanges, revocation, normalization, atomic input, limits, stale targets, invalid frames, and cleanup. They never connect to a user's existing Herdr daemon. Service setup tests mock OS service managers and Tailscale; they do not establish live login-service behavior on macOS.

An optional live integration test starts a uniquely named Herdr session, creates its own shell, verifies snapshots, atomic input and ANSI observation, and stops only that session:

```sh
HERDR_INTEGRATION=1 HERDR_BIN="$HOME/.local/bin/herdr" bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py
```

## Live Android device fixture

Build the app and instrumentation APK before launching the fixture, because its invitation lasts five minutes:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

For a real Android UI test without accessing any existing user pane, start the fixture from the repository root in one terminal:

```sh
mkdir -p artifacts/live-device
bridge/.venv/bin/python scripts/live_device_fixture.py > artifacts/live-device/fixture.json
```

It starts a UUID-named Herdr session, creates its own workspace labelled `Android live test` in a temporary directory, writes `HERDR_ANDROID_LIVE_DEVICE_READY` in green, and runs the actual companion on `127.0.0.1:18765`. Its one-line readiness JSON contains `url`, `workspaceId`, `paneId`, the complete `pairingPayload` string, and the path `qrImage` to a host-generated QR PNG. Wait for that JSON before starting the device test. Startup is bounded; a busy port fails before any session is created. Keep the fixture running for the device test, then press Ctrl+C or send SIGTERM. It shuts down the companion and stops and reaps only its own Herdr session; stopped metadata can remain in Herdr's configuration directory.

With an emulator or test device attached, run in another terminal:

```sh
task_payload=$(bridge/.venv/bin/python -c 'import json; print(json.load(open("artifacts/live-device/fixture.json"))["pairingPayload"])')
task_qr=$(bridge/.venv/bin/python -c 'import json; print(json.load(open("artifacts/live-device/fixture.json"))["qrImage"])')
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:18765 tcp:18765
adb shell mkdir -p /sdcard/Android/data/dev.herdr.android/files/validation
adb push "$task_qr" /sdcard/Android/data/dev.herdr.android/files/validation/host-pairing.png
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.herdr.android.LiveBridgeDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.live_bridge_url=http://127.0.0.1:18765 \
  -Pandroid.testInstrumentationRunnerArguments.live_bridge_token=android-emulator-isolated-test-token-20261008 \
  "-Pandroid.testInstrumentationRunnerArguments.live_pairing_payload=$task_payload" \
  -Pandroid.testInstrumentationRunnerArguments.live_pairing_qr=/sdcard/Android/data/dev.herdr.android/files/validation/host-pairing.png
adb reverse --remove tcp:18765
```

The device test decodes the host QR image with the same native decoder, pastes the invitation, confirms pairing, opens a real pane, and verifies submitted shell input. Emulator image decoding does not test a physical camera. Restart the fixture for a fresh invitation before rerunning the one-use pairing test.

The fixed fixture token is a public test value for this temporary loopback service. Never use it for a real profile or expose the fixture through Tailscale Serve. When running ordinary device checks without a fixture, exclude the live class with `-Pandroid.testInstrumentationRunnerArguments.notClass=dev.herdr.android.LiveBridgeDeviceTest`. The current Gradle device driver can report JUnit assumption skips as failures; the live class requires the fixture arguments above. Use `--herdr-bin /path/to/herdr` if CLI discovery fails.
