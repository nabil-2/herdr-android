# Herdr Pocket

A native Android companion for the Herdr agents and terminals already running on your computer or server. Connect your host and phone through Tailscale, run the host installer, then scan its pairing code.

The app uses Kotlin, Jetpack Compose, and ConnectBot's native libvterm terminal component. A small authenticated companion runs beside your existing Herdr daemon. Herdr does not need to be forked or changed.

## What works

- Scan or paste a pairing invitation, review the host, and explicitly confirm pairing. Each phone receives its own revocable credential, encrypted locally with Android Keystore and excluded from backups.
- Save multiple server profiles and switch between them.
- Browse workspaces, tabs, agent states, and shell panes, with agents needing input grouped together. Status refreshes while the app is in the foreground.
- Open supported Codex panes in native chat: user messages and agent replies, selectable Markdown and code, older conversation history, and a multiline reply box. Commands, tool results, reasoning, and injected instructions stay out of the conversation.
- Open the terminal from chat to inspect activity or answer an approval prompt. The desktop keeps control of the same agent session.
- Watch live ANSI terminal output, including colors, cursor movement, and agent terminal interfaces.
- Send literal text with optional Enter, or use the Enter, Escape, Tab, arrow and Ctrl+C controls. Typing a draft does not send it. Failed sends retain the draft and are never replayed automatically.
- Create a workspace on the host, then launch an agent from its shell.
- Reconnect terminal observation after a temporary connection loss without taking the desktop's terminal controller or resizing its panes.

Requires Android 8.0+ (API 26), a running Herdr session on Linux or macOS, and Tailscale installed and signed into the same tailnet on both devices. The host companion runs as the Unix user who owns the Herdr session. The current integration targets Herdr 0.9.3.

<p>
  <img src="docs/screenshots/chat.png" width="240" alt="Native Codex conversation with selectable Markdown and code">
  <img src="docs/screenshots/pairing.png" width="240" alt="Herdr Pocket host review before confirming QR pairing">
  <img src="docs/screenshots/dashboard.png" width="240" alt="Herdr Pocket workspace dashboard from the live device test">
  <img src="docs/screenshots/terminal.png" width="240" alt="Native Herdr terminal displaying a command executed from the Android app">
</p>

## Install and pair

**1. Install the phone app.** Download the APK from the [releases page](https://github.com/nabil-2/herdr-android/releases) and open it on your phone. This preview is a debug-signed APK for sideloading; Android may ask you to allow installation from your browser or file manager. Keep Tailscale connected on the phone. The app uses that existing network connection and does not manage Tailscale or request its credentials.

**2. Run the host installer.** From the same release, download the asset matching your host:

| Host | Installer |
| --- | --- |
| Linux, Intel/AMD 64-bit | `herdr-pocket-setup-linux-x86_64.sh` |
| macOS, Apple Silicon | `herdr-pocket-setup-macos-arm64.sh` |
| macOS, Intel | `herdr-pocket-setup-macos-x86_64.sh` |

For example, open a host terminal in the download directory and run:

```sh
bash herdr-pocket-setup-linux-x86_64.sh
```

Use the matching filename on macOS. Run it as your normal user, without `sudo`. The installer includes its runtime, so Python and Git are not required. Herdr and Tailscale must already be installed and running. If you have several Herdr sessions, setup asks you to choose one.

Setup installs a background companion for that session, keeps its HTTP listener on loopback, and configures private [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve) HTTPS on an unused port starting at 8443. It preserves existing Serve endpoints. Tailscale may print a one-time HTTPS approval URL; follow that prompt. Your tailnet's access rules must allow the phone to reach the selected host and port.

Linux setup requires a working systemd user service manager. It attempts to enable user lingering so the companion can start at boot and stay running after logout; if your system denies that, an administrator must enable lingering for unattended access. macOS setup installs a LaunchAgent that starts at login and runs while that user is logged in. Herdr must remain running independently.

**3. Pair the phone.** Setup displays a QR code. In Herdr Pocket, tap **Scan pairing code**, allow camera access, review the host name and HTTPS address, then tap **Pair & connect**. The scanner decodes locally and does not save or upload camera images. On Android 17 and later, allow **Local network** access when connecting. If denied, enable it in Android's app settings before reconnecting.

If scanning is inconvenient, run the following on the host and paste the complete JSON into **Paste pairing code** in the app:

```sh
~/.local/bin/herdr-pocket pair --plain
```

Each code expires after five minutes and pairs one phone. It is exchanged for a separate device credential; you never need to copy the host's permanent owner token. If a code expires or has already been used, generate a new one. After connecting, choose a workspace, tab, and pane. Supported Codex sessions open as chat; shell panes and other agents open as terminals.

## Codex chat and upgrades

Install the APK and host installer from the same preview release. To upgrade an existing installation, open the new APK and rerun the matching host installer. Existing phone profiles, device credentials, and the selected host address are preserved; another pairing is unnecessary. Choose the same Herdr session if setup offers a session list.

Chat needs an installed Codex CLI and a session ID reported by Herdr's Codex integration. Setup discovers Codex automatically and saves its executable and home directory for the background service. Codex 0.162.0 is the tested version. An old bridge, missing session ID, unavailable history, or unsupported provider keeps a terminal fallback with an explanation. Existing agents are never restarted by setup. If a session predates Herdr's integration, enable that integration in Herdr before starting a new Codex session.

The phone reads saved conversation messages once per second while chat is visible. This is message refresh, not token-by-token streaming. Codex keeps running in its original pane. Send is enabled when Herdr confirms the agent is ready; a working or blocked agent can be controlled through **Terminal**. Approval menus and interactive questions show a visible attention banner with **Open terminal**. The chat composer never retries a submitted message automatically.

Advanced hosts can select a nonstandard installation with `herdr-pocket setup --codex-bin /absolute/path/to/codex --codex-home /absolute/path/to/codex-home`. The normal setup uses `CODEX_HOME`, or `~/.codex`. The read-only Codex app-server may maintain its local metadata databases; it does not start a new model turn or resume the running thread.

## Manage phone access

Generate another QR code, check the companion, list paired devices, or revoke one device using its ID from the list:

```sh
~/.local/bin/herdr-pocket pair
~/.local/bin/herdr-pocket status
~/.local/bin/herdr-pocket devices
~/.local/bin/herdr-pocket revoke-device DEVICE_ID
```

For another running Herdr session, run `~/.local/bin/herdr-pocket setup --session NAME`. Each session has its own companion, address, and device registry. Add `--session NAME` to `pair`, `status`, `devices`, and `revoke-device` when selecting among configured sessions. Revocation rejects subsequent requests and closes an active phone terminal stream.

Existing manual profiles remain supported through **Advanced: manual server setup**, or **Add server** beside existing profiles. See [host companion setup](bridge/README.md) for source installation, explicit sockets, and manual network configuration.

## Develop and test

Use Android Studio with an Android 37.0 SDK and JDK 17, or install the Android command-line tools and put the SDK path into an untracked `local.properties`:

```properties
sdk.dir=/absolute/path/to/android-sdk
```

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The Gradle wrapper verifies the distribution checksum. Native Android dependencies are pinned. The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

With an emulator or test phone attached:

```sh
./gradlew :app:connectedDebugAndroidTest
```

For host checks, install the [source environment](bridge/README.md#install-from-source), then run:

```sh
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py -q
CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
```

The live integration test creates and cleans up an isolated named Herdr session. It does not send commands to an existing user pane. The [Android device fixture](bridge/README.md#live-android-device-fixture) exercises the app against a real companion and Herdr shell, including host-generated QR decoding and pairing. GitHub Actions builds the APK, runs JVM/lint and host checks, and packages the supported host installers.

See [protocol](docs/protocol.md) for the app/service interface and [validation](docs/validation.md) for this preview's results. Physical camera scanning and macOS login-service behavior need testing on their respective hardware; emulator checks do not establish those results.

## Current limits

Status, chat refresh, and terminal observation run while the app is in the foreground; background push notifications are not implemented. Native chat supports Codex text conversations first; other agents, attachments, approval menus, and interactive questions use the selected terminal. Remote terminal scrollback and direct SSH connections are not implemented. The host, Tailscale, and Herdr daemon must remain online. Native Windows hosts are not supported because Herdr's terminal observer requires Linux or macOS.

## License

Apache License 2.0. This is an independent companion and is not an official Herdr application. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [third-party notices](THIRD_PARTY_NOTICES.md).
