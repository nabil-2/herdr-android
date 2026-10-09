# Preview 0.5.0 validation

Validated on 9 October 2026. This release fixes the reported Android 13 TLS shutdown crash and disabled Codex Send, and revises the native chat layout.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM | 118 passed | Client retirement, detached cleanup, late pooled socket return, conversation protocol, timestamps/provenance, pagination readiness, existing pairing/terminal/state tests |
| Android lint | 0 errors, 19 warnings | Debug app, tests and dependencies |
| Bridge offline | 308 passed | Stock Herdr manual-agent readiness, guarded submission, timestamps, pairing, authentication, terminal and host management |
| Actual Codex 0.162 | 4 passed | Isolated legacy/paginated saved histories, official turn times present/absent, append/rollback, single mocked Herdr submission; no model calls |
| Actual Herdr 0.9.3 | 1 passed | Owned isolated session only |
| Android 13 device | 62 passed | Full instrumentation, including five real Conscrypt TLS regressions, chat dates/layout/working state, native terminal and three live fixture cases |
| Frozen Linux package | Passed | Authenticated API, Codex chat, single-use pairing/revocation, QR helpers and licenses |
| Linux/macOS CI packages | Passed | Linux x86_64, macOS arm64 and macOS x86_64 builds and packaged smoke checks |
| Installed 005 companion | Passed | Checksummed 0.4.0→0.5.0 update; authenticated readiness; configuration and paired-device data byte-identical |

These are **493 distinct automated passing cases**, plus packaged-runtime and installed-host checks. The offline bridge run skips the five optional real-runtime cases; those were run separately. The final complete Android suite ran directly through AndroidJUnitRunner with an owned live fixture, and reported `OK (62 tests)` in 91 seconds. No existing user agent received test input.

The initial Gradle device run reported three missing-fixture JUnit assumptions as failures, plus an older empty-draft assertion that compared `AnnotatedString` presentation annotations. The assertion now checks the actual draft text. The complete fixture-equipped rerun passes. Ordinary Gradle device commands explicitly exclude the opt-in live class; see the repeat commands below.

## Reported failures

A real idle HTTPS socket on the API 33 emulator reproduces `NetworkOnMainThreadException` when the original synchronous `ConnectionPool.evictAll()` runs on Android's main thread. The fixed Herdr and pairing clients close those sockets from detached IO workers; the real `HerdrViewModel.pairServer` replacement path and in-flight cancellation pass with Android's network guard enabled. Retirement rejects new calls immediately, remains idempotent, and evicts connections returned after initial cleanup. Production certificate validation is unchanged. This establishes the supplied crash mechanism; it does not depend on reproducing the user's private server state.

Stock Herdr 0.9.3 omits its managed-only `interactive_ready` flag for ordinary manually launched Codex. Requiring that flag for every pane kept Send disabled indefinitely. Verified unnamed canonical Codex sessions now accept messages when idle/done; named managed sessions still require explicit readiness. Working, blocked, starting, malformed, stale, or uncertain states remain guarded, with a controlled explanation. Submission still uses Herdr's foreground-runtime validation and never retries automatically.

## Chat review

The [new screenshot](screenshots/chat.png) contains only synthetic fixture text. It was inspected after a full activity navigation test: compact user bubbles, assistant text on the canvas, restrained Markdown headings, readable code, accessible copy controls and an anchored composer. Working is a static caption without an animated banner.

Codex 0.162 exposes saved turn start/completion times rather than exact item times. The app labels that provenance explicitly and respects the phone's time zone and 12/24-hour format. Histories without official turn times omit dates rather than borrowing file/item timestamps or the current clock. Both cases were verified against the actual app-server in isolated temporary Codex homes.

Design references: [official Android JetChat](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt), [Material typography](https://m3.material.io/styles/typography/applying-type), and [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

## Environment and artifact

- Pixel 7 emulator, Android 13/API 33, x86_64, hardware virtualization.
- JDK 17, Kotlin 2.4.20, Gradle 9.6.0, Android Gradle Plugin 9.4.0; compile/target SDK 37.0 and build tools 36.0.0; minimum Android API 26.
- Linux x86_64 package: glibc 2.34, portable Python 3.11.17, PyInstaller 6.22.3.
- APK: **34,420,320 bytes**, version code **6**, version name **0.5.0**; signature and 16 KiB ZIP alignment checks passed.

APK SHA-256:

```text
d392bd0dc02d1fd646a908cf1dc33b960786267bbabb397fba7db1a34640e669
```

The signing certificate matches earlier previews (`ad045612a56a05953afb2162ac0b277a8a579dd8e70269caa18db37c26125c9c`), allowing installation over the existing app. Release assets and platform license bundles are covered by `SHA256SUMS`. Phone profiles and host device credentials do not need to be recreated for this update.

Production payloads were built from `486cb9f6182292c50e2c95dbe543a59d3ac789d4`. [Android/bridge CI](https://github.com/nabil-2/herdr-android/actions/runs/37908676899) and [host package CI](https://github.com/nabil-2/herdr-android/actions/runs/37908676928) passed for that source. Later edits only correct the device test assertion, update its invocation documentation, and record these results/screenshots.

## Repeat

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=dev.herdr.android.LiveBridgeDeviceTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py -q
CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
bridge/.venv/bin/python scripts/build_host.py
bridge/.venv/bin/python scripts/smoke_host_package.py artifacts/host/herdr-pocket-linux-x86_64
```

Use the [owned live Android fixture](../bridge/README.md#live-android-device-fixture) for the three optional end-to-end cases. Start it immediately before testing and clean up its process and adb reverse mapping afterward; its one-use invitation expires after five minutes.

## Practical limits

Native chat supports saved Codex text conversations; tools/reasoning stay out of chat, and interactive approvals use Terminal. Completed persisted messages refresh once per second rather than streaming tokens. Old saved histories may lack official turn times. Background notifications and direct SSH are not included. The actual reported phone has not been tested directly, and physical camera/keyboard and macOS login-service behavior remain outside emulator/CI evidence. Herdr and Tailscale continue to run independently.
