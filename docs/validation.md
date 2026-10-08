# Preview validation

Validated on 2026-10-08 against the installed, unmodified **Herdr 0.9.3**. The phone client and companion live tests used a newly created UUID-named Herdr session and its own temporary shell directory. No commands were sent to existing user panes.

## Environment

- Android app: Kotlin 2.4.20, Jetpack Compose, ConnectBot Terminal 0.3.11 and native libvterm; no WebView.
- Build: JDK 17, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Android SDK 37.0; minimum API 26, target API 37.
- Device checks: Pixel 7 emulator, Android 16 / API 36, x86_64, hardware virtualization.
- Bridge: Python 3.11 virtual environment installed from `bridge/requirements.lock`.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM suite | 23 passed | Bearer authentication, rejected credentials, redirects, frame parsing, input limits, explicit acknowledgements, uncertain writes, HTTP 503 follow-ups, and permission grant/denial |
| Android lint | Passed; zero errors | API compatibility, manifest/resources, Kotlin and Compose checks; remaining dependency/style warnings and intentional private-network HTTP configuration |
| Bridge offline suite | 49 passed | Simulated Unix daemon, atomic text/key submission, body limits, stale targets, token files, WebSocket authentication, validated ANSI frames, and observer process cleanup |
| Live bridge integration | 1 passed | Real stock Herdr snapshot, workspace creation, atomic input, toolbar key aliases, unknown-key rejection, and read-only ANSI observation |
| Android device suite with live fixture | 8 passed | Native JNI/libvterm, ANSI and fixed geometry; explicit send/Enter/key controls; draft retention; real Keystore round trip and profile binding; app connection and command delivery to real Herdr |

The ordinary bridge suite skips its optional live test. The ordinary device suite skips its optional live test unless fixture URL/token arguments are provided. The counts above include the separately enabled live runs.

The live app test creates a server profile, browses the workspace and pane, opens an authenticated terminal stream, and sends `printf 'PHONE_NATIVE_E2E_OK\n'` through the explicit Send control. The captured native terminal screen shows the resulting output. Fixture credentials are public temporary test values, and the test removes its own saved profile afterward.

[Workspace screenshot](screenshots/dashboard.png) · [Native terminal screenshot](screenshots/terminal.png)

The delivered APK is 31,242,922 bytes. APK signature verification and 16 KiB ZIP alignment checks passed; the packaged 64-bit native libraries also have 16 KiB-aligned ELF load segments. Its SHA-256 is:

```text
f629900f1196e4adb33e1f52d03a544d7dbe97f4d8cf87da432a6cd658c74ada
```

## Repeat the checks

From the repository root, with the Android SDK configured:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py -q
```

For the complete device run, follow the [isolated fixture instructions](../bridge/README.md#live-android-device-fixture), omitting the `class` filter to run all device tests. Stop the fixture afterward and remove its `adb reverse` mapping. Ordinary connected device checks use `./gradlew :app:connectedDebugAndroidTest`.

## Scope of validation

The live Android connection used `adb reverse` to the real loopback bridge, rather than a physical phone connected through a tailnet. Tailscale Serve setup is documented, but a physical phone/tailnet test remains to be done by the user. The Android 17 local-network permission flow has JVM grant/denial tests; the device run used Android 16. Minimum Android 8 compatibility was checked by lint and compilation, not by an Android 8 device run.

The preview APK is debug-signed for sideloading. It includes native libraries for arm64-v8a, armeabi-v7a, x86 and x86_64. The host companion and Herdr are not bundled into the APK. Background notifications, remote scrollback and direct SSH are outside this preview's implemented features.
