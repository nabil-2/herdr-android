# Preview 0.2.0 validation

Validated on 2026-10-09 against the installed, unmodified **Herdr 0.9.3**. Live tests created UUID-named Herdr sessions and temporary shell directories. No commands were sent to existing user panes.

## Environment

- Native Android: Kotlin 2.4.20, Jetpack Compose, ConnectBot Terminal 0.3.11/libvterm, CameraX 1.6.2, ZXing Core 3.5.4.
- Build: JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Android SDK 37.0; minimum API 26, target API 37.
- Device: Pixel 7 emulator, Android 16/API 36, x86_64, hardware virtualization.
- Host: Linux x86_64, glibc 2.34, Python 3.11.17; packaged runtime built with PyInstaller 6.22.3. Normal packaged installation requires no separate Python runtime.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM suite | 37 passed | Strict QR parsing, redacted diagnostics, pairing authentication, no redirect/replay, cancellation, terminal frames, input limits, and permissions |
| Android lint | Zero errors, 14 warnings | API compatibility, manifest/resources, Kotlin/Compose; dependency/style warnings and intentional private-network HTTP support |
| Bridge offline suite | 133 passed | Pairing expiration, concurrent one-use exchanges, durable hashed credentials, revocation, body limits, socket routing, setup/service generation, Tailscale endpoint preservation, frozen external-command environments, terminal cleanup |
| Live bridge integration | 1 passed | Stock Herdr snapshots, workspace creation, atomic input, logical keys, and read-only ANSI observation |
| Android device suite with live fixture | 15 passed | Native JNI/libvterm, explicit terminal controls, Keystore persistence, pairing UI review/cancellation/denial, host-generated QR decoding, real pairing and command delivery |
| Frozen Linux package smoke | Passed | Self-contained authenticated HTTP server, phone pairing, one-use rejection, snapshot access, device revocation, packaged QR CLI helpers, private credential files, bundled licenses |
| Installer extraction | Passed | Embedded binary checksum and execution of the extracted packaged CLI |
| Linux installer and systemd service | Passed | Installed binary/link, real enabled and active user service, stock Herdr health/snapshots, packaged terminal WebSocket, device revocation closing the stream with 1008 |

The 186 automated tests above passed. Optional live cases are skipped in ordinary runs without their fixture arguments. The packaged smoke is an additional end-to-end check against an isolated mock Unix daemon and does not require installed Herdr or Tailscale.

The real Android pairing test reviews and confirms a one-use invitation, stores its device credential through Keystore, opens a live Herdr pane, and sends `printf 'PHONE_QR_PAIRING_E2E_OK\n'`. A separate read-only observer confirmed that marker in the isolated pane's output. Another device test decodes a Segno-generated host PNG using the same ZXing decoder as the CameraX scanner. The legacy manual-profile connection also passed. Tests remove their own saved profiles afterward.

The self-extracting installer was also run against that isolated Herdr session with a real systemd user service manager. Its installed executable served authenticated requests and native Herdr ANSI frames after the installer exited. The test revoked a paired device and verified HTTP rejection and active-stream closure. Only Tailscale and lingering CLI commands were simulated; no real tailnet settings were changed. Cleanup disabled and removed only the temporary companion unit and its files.

[Pairing review](screenshots/pairing.png) · [Workspace dashboard](screenshots/dashboard.png) · [Native terminal](screenshots/terminal.png)

The updated APK is **33,423,065 bytes**, uses the same signing certificate as preview 0.1.0, and can update that installation. APK signature verification and 16 KiB ZIP alignment checks passed; all eight packaged 64-bit native libraries have 16 KiB-aligned ELF load segments. APK SHA-256:

```text
495fdd294898254ef4ee425d5e5067093735ae1671ae4ba55987b4a0d33eb78e
```

## Repeat the checks

From the repository root, with the Android SDK configured:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py -q
bridge/.venv/bin/python scripts/build_host.py
bridge/.venv/bin/python scripts/smoke_host_package.py artifacts/host/herdr-pocket-linux-x86_64
```

For all device tests, follow the [isolated fixture instructions](../bridge/README.md#live-android-device-fixture), omitting the `class` filter. Build first, then start the fixture immediately before testing because its invitation expires after five minutes. Stop the fixture and remove its `adb reverse` mapping afterward.

GitHub Actions checks Android JVM/lint/APK and the offline bridge suite. The separate host packaging workflow builds and exercises self-contained companions on Linux x86_64, macOS Apple Silicon, and macOS Intel. [Android/bridge checks](https://github.com/nabil-2/herdr-android/actions/runs/37857944286) and [all three host package checks](https://github.com/nabil-2/herdr-android/actions/runs/37857944438) passed. The delivered Linux package was built with a portable Python 3.11.17 distribution and passed the real installer/service check on glibc 2.34; the Mac packages were downloaded from the successful CI run.

## Scope of validation

The live Android connection used `adb reverse` to a real loopback bridge. A physical phone over a real Tailscale network, physical camera scanning, and macOS LaunchAgent/login behavior remain untested. QR image decoding and permission/onboarding logic were tested separately. Tailscale configuration behavior is covered with simulated CLI responses; tests never modify the user's real Serve/Funnel endpoints. The Android 17 local-network permission flow has JVM grant/denial tests; device checks used Android 16. Android 8 compatibility was checked by lint and compilation.

The preview APK is debug-signed for sideloading. It includes native libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. Herdr itself is not bundled; it must remain running independently on the host. The host executable includes its Python runtime and license texts. Background notifications, remote scrollback, and direct SSH are outside the implemented preview.
