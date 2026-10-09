# Preview 0.3.0 validation

Validated on 2026-10-09 against installed, unmodified **Herdr 0.9.3** and **Codex 0.162.0**. Live Herdr tests create UUID-named sessions and temporary workspaces. Codex checks use temporary provider homes with synthetic conversations. No input was sent to existing user panes, and no user conversations or model calls were needed.

## Environment

- Native Android: Kotlin 2.4.20, Jetpack Compose, ConnectBot Terminal 0.3.11/libvterm, CameraX 1.6.2, ZXing Core 3.5.4, multiplatform Markdown renderer 0.41.0.
- Build: JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Android SDK 37.0 and build tools 36.0.0; minimum API 26, target API 37.
- Device: Pixel 7 emulator, Android 16/API 36, x86_64, hardware virtualization.
- Host: Linux x86_64, glibc 2.34, packaged Python 3.11.17 and PyInstaller 6.22.3. Packaged installation requires no separate Python runtime.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM suite | 67 passed | Pairing/authentication, input limits, strict conversation decoding, stable IDs, pagination/rollback, stale binding rejection, scoped drafts, canceled-send uncertainty, acknowledgment, safe links |
| Android lint | Zero errors, 16 warnings | API compatibility, manifest/resources, Kotlin/Compose; dependency/style warnings and intentional private-network HTTP support |
| Bridge offline suite | 195 passed | Existing pairing/terminal behavior plus Codex attribution, RPC allowlist, history normalization, bounded cache/reads, paging, rollback epochs, ready-state checks, single prompt dispatch, uncertainty, config discovery and upgrade preservation |
| Real Codex reader | 2 passed | Legacy and indexed Paginated histories, commentary/final messages, external updates, stable IDs, repeated text, rollback, multiline prompt routing, unchanged provider configuration |
| Live stock Herdr integration | 1 passed | Isolated snapshots, workspace creation, atomic input, logical keys, read-only ANSI observation |
| Android device cases | 23 passed across full and focused runs | Native JNI/libvterm, Keystore, pairing/QR/live command delivery, chat default routing, Markdown/code clipboard/tables, safe links, blocked/error/send states, earlier-page anchors, draft retention, terminal/back navigation, activity recreation, foreground polling |
| Frozen package smoke | Passed on all three hosts | Authenticated API, clean Codex conversation, frozen child-process environment, single-use pairing, revocation, QR CLI helpers, bundled licenses |
| Installer extraction | Passed | Embedded checksum and execution of the extracted companion |
| Linux installer and systemd service | Passed | Actual enabled/active user service, installed binary/link, live stock Herdr health/snapshot/ANSI stream, revocation closing the observer with 1008, removal of owned service/files |

The 288 distinct automated cases above passed. Optional live cases are skipped without their fixture arguments. Frozen-package and real installer/service checks are additional end-to-end checks.

The Codex checks launch the actual installed `codex app-server` in disposable homes. Legacy history uses synthetic saved events; Paginated history also has a source-compatible projection database, as required by Codex's saved-history reader. Only initialization and `thread/read` are sent to the provider. These checks do not start or resume a model conversation. A mock Herdr socket records the literal multiline prompt once; the separate stock Herdr test covers the real public input/observer APIs.

The phone tests include a real one-use pairing exchange with the isolated host bridge, host-generated QR decoding, and explicit shell command delivery. Native chat navigation uses a disposable loopback HTTP/WebSocket server with synthetic messages. It verifies that opening an identified Codex pane shows Chat, Terminal and backgrounding stop chat polling, Back restores Chat, and drafts survive navigation and activity recreation. A focused Markdown test additionally verifies native code copying and scrolling to a table cell.

The self-extracting Linux installer runs against its own Herdr session with an actual systemd user service manager. Only Tailscale and lingering CLI commands are simulated; no real tailnet settings change. Its binary serves authenticated requests after setup exits. Revocation rejects the paired phone and closes its active observer. Cleanup removes only the temporary unit, install directory, credentials, and source fixture, restoring prior directory permissions.

[Native Codex chat](screenshots/chat.png) · [Pairing review](screenshots/pairing.png) · [Workspace dashboard](screenshots/dashboard.png) · [Native terminal](screenshots/terminal.png)

The APK is **34,287,184 bytes**, has version code **3**, and uses the same signing certificate as previews 0.1.0 and 0.2.0. It can update those installations without removing profiles. Signature verification and 16 KiB ZIP alignment passed; all eight 64-bit native libraries have 16 KiB-aligned ELF load segments. APK SHA-256:

```text
33ebdde0e764b35235f8c4914f5846b262df3c9b3b048d5905ad790a2bf48ef6
```

## Repeat the checks

From the repository root, with the Android SDK configured:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py -q
CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
bridge/.venv/bin/python scripts/build_host.py
bridge/.venv/bin/python scripts/smoke_host_package.py artifacts/host/herdr-pocket-linux-x86_64
```

For device checks, follow the [isolated fixture instructions](../bridge/README.md#live-android-device-fixture), omitting the `class` filter. Build first and start the fixture immediately before testing because its invitation expires after five minutes. Stop it and remove its `adb reverse` mapping afterward. The optional Codex check requires an installed Codex 0.162.0 executable and uses only temporary histories.

[Android/bridge CI checks](https://github.com/nabil-2/herdr-android/actions/runs/37889298916) and [all three host package checks](https://github.com/nabil-2/herdr-android/actions/runs/37889298944) passed. Delivered Mac assets come from that successful packaging run. The delivered Linux assets were built and installer-tested locally on glibc 2.34 using the same source and portable runtime. Release `SHA256SUMS` covers the APK, all companion binaries/installers, and platform license bundles.

## Scope of validation

Chat currently supports confirmed Codex identities reported by Herdr. Missing integration metadata, other providers, old bridges, and unavailable history keep an explicit Terminal action. Saved messages refresh while Chat is visible; token streaming and inline approvals are outside this preview. Sends use Herdr's public `agent.prompt` with binding preflight. Herdr 0.9.3 does not offer an atomic expected-thread condition, so a simultaneous desktop session change can produce a warning; uncertain submissions require checking the conversation before resending. Drafts are bounded and memory-only, and process death or eviction loses them. See [the protocol](protocol.md#native-saved-codex-conversation) for exact bounds and behavior.

Device networking used loopback and `adb reverse`. A physical phone over a real Tailscale network, physical camera scanning, and macOS LaunchAgent/login behavior remain untested. QR decoding and permission/onboarding behavior have separate coverage. Android 17 local-network permission logic has JVM grant/denial checks; device tests used Android 16. Android 8 compatibility was checked through compilation and lint.

The APK is debug-signed for sideloading and contains arm64-v8a, armeabi-v7a, x86, and x86_64 native libraries. Herdr remains independently installed and running on the host; Codex chat uses the existing host installation and agent pane. Background notifications, terminal scrollback, and direct SSH are outside this preview.
