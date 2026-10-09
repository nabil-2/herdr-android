# Preview 0.5.1 validation

Validated on 9 October 2026. This release fixes the remaining disabled Send button while Codex is working, including manually renamed sessions. The Android 13 TLS fix and native chat layout from 0.5.0 are retained.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM | 119 passed | Working-state conversation readiness, shared Send guard, client retirement, timestamps/provenance, pagination and existing pairing/terminal/state tests |
| Android lint | 0 errors, 19 warnings | Debug app, tests and dependencies |
| Bridge offline | 318 passed | Working updates, named/unnamed manual-agent readiness, legacy-client negotiation, guarded submission, timestamps, pairing, authentication, terminal and host management |
| Actual Codex 0.162 | 4 passed | Isolated legacy/paginated saved histories, official turn times present/absent, append/rollback, single mocked Herdr submission; no model calls |
| Actual Herdr 0.9.3 | 3 passed | Owned terminal fixture plus exactly-once submissions to unnamed and manually renamed working synthetic Codex processes |
| Android 13 device | 64 passed | 61 ordinary cases plus three live fixtures; complete working-chat typing/Send/acknowledgement/follow-up flow, disabled duplicate input, five real Conscrypt TLS regressions, chat and terminal |
| Frozen Linux package | Passed | Authenticated API, Codex chat, single-use pairing/revocation, QR helpers and licenses |
| Linux/macOS CI packages | Passed | Linux x86_64, macOS arm64 and macOS x86_64 builds and packaged smoke checks |
| Installed 005 companion | Passed | Checksummed 0.5.0→0.5.1 update; authenticated health and working-input capability; configuration and paired-device data byte-identical |

These are **508 distinct automated passing cases**, plus packaged-runtime and installed-host checks. The offline bridge run skips seven optional real-runtime cases, run separately. Gradle passed all 61 ordinary Android cases with the live class excluded. The optional Android class reported `OK (3 tests)` in 13.64 seconds against an owned UUID fixture. No existing user agent received test input, and no user conversation was read for testing.

The first optional fixture run failed before exercising the app because an adb-created external directory was owned by the shell. The passing rerun supplied its QR in app-private storage and let the app create its screenshot directory. Ordinary Gradle device commands explicitly exclude the opt-in live class; see the repeat commands below.

## Reported failures

A real idle HTTPS socket on the API 33 emulator reproduces `NetworkOnMainThreadException` when the original synchronous `ConnectionPool.evictAll()` runs on Android's main thread. The fixed Herdr and pairing clients close those sockets from detached IO workers; the real `HerdrViewModel.pairServer` replacement path and in-flight cancellation pass with Android's network guard enabled. Retirement rejects new calls immediately, remains idempotent, and evicts connections returned after initial cleanup. Production certificate validation is unchanged. This establishes the supplied crash mechanism; it does not depend on reproducing the user's private server state.

The 0.5.0 fix addressed stock Herdr omitting its managed-only `interactive_ready` flag, but still rejected the `working` status in the companion, Android protocol parser, ViewModel and composer. All four now accept authoritative working-state readiness. The ViewModel and composer share the same submission guard. Renaming a manually launched agent only changes its display label; it does not create a managed launch or require a missing readiness field.

The complete Android activity test types a literal Unicode/multiline message into a working conversation, presses Send, holds the HTTP acknowledgement, checks that a second touch does not submit twice, releases the acknowledgement and sends another follow-up while the agent remains working. It verifies exact text, binding, authentication and negotiation headers. Two real isolated Herdr fixtures additionally prove that `agent.prompt` delivers the follow-up and submission boundary exactly once, including after a real `agent.rename`. These fixtures use fake Codex processes and synthetic history; no model calls are made.

Both conversation GETs and message POSTs opt in with `X-Herdr-Chat-Working-Input: 1`. Without it, the new companion retains the idle/done readiness understood by older phone versions, so upgrading the host does not break their history parser. The new phone explains when an older companion needs updating. Blocked, pending launch, explicit false/malformed readiness, stale binding, read errors and uncertain delivery remain guarded. Herdr rechecks the foreground runtime at submission; the app never retries automatically. Codex [Enter while working updates the current turn](https://learn.chatgpt.com/docs/cli/reference#interactive-shortcuts).

## Chat review

The [chat layout screenshot from 0.5.0](screenshots/chat.png) contains only synthetic fixture text: compact user bubbles, assistant text on the canvas, restrained Markdown headings, readable code, accessible copy controls and an anchored composer. The 0.5.1 device tests retain those checks. Working remains a static caption and now explains that Send updates the current task.

Codex 0.162 exposes saved turn start/completion times rather than exact item times. The app labels that provenance explicitly and respects the phone's time zone and 12/24-hour format. Histories without official turn times omit dates rather than borrowing file/item timestamps or the current clock. Both cases were verified against the actual app-server in isolated temporary Codex homes.

Design references: [official Android JetChat](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt), [Material typography](https://m3.material.io/styles/typography/applying-type), and [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

## Environment and artifact

- Pixel 7 emulator, Android 13/API 33, x86_64, hardware virtualization.
- JDK 17, Kotlin 2.4.20, Gradle 9.6.0, Android Gradle Plugin 9.4.0; compile/target SDK 37.0 and build tools 36.0.0; minimum Android API 26.
- Linux x86_64 package: glibc 2.34, portable Python 3.11.17, PyInstaller 6.22.3.
- APK: **34,770,762 bytes**, version code **7**, version name **0.5.1**; signature and 16 KiB ZIP alignment checks passed.

APK SHA-256:

```text
2b9c848b802210aaf1b3f029acdb3874a17957827d60340a6711d87c0680b1fa
```

The signing certificate matches earlier previews (`ad045612a56a05953afb2162ac0b277a8a579dd8e70269caa18db37c26125c9c`), allowing installation over the existing app. Release assets and platform license bundles are covered by `SHA256SUMS`. Phone profiles and host device credentials do not need to be recreated for this update.

Production payloads were built from `67fe73b23da5e20fa2034bdc09b18d6fef3ee6ec`. [Android/bridge CI](https://github.com/nabil-2/herdr-android/actions/runs/37915895987) and [host package CI](https://github.com/nabil-2/herdr-android/actions/runs/37915896024) passed for that source. Later edits only record these results and correct the live fixture invocation.

## Repeat

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=dev.herdr.android.LiveBridgeDeviceTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py bridge/tests/test_live_busy_codex.py -q
CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
bridge/.venv/bin/python scripts/build_host.py
bridge/.venv/bin/python scripts/smoke_host_package.py artifacts/host/herdr-pocket-linux-x86_64
```

Use the [owned live Android fixture](../bridge/README.md#live-android-device-fixture) for the three optional end-to-end cases. Start it immediately before testing and clean up its process and adb reverse mapping afterward; its one-use invitation expires after five minutes.

## Practical limits

Native chat supports saved Codex text conversations; tools/reasoning stay out of chat, and interactive approvals use Terminal. Completed persisted messages refresh once per second rather than streaming tokens. Old saved histories may lack official turn times. Background notifications and direct SSH are not included. The actual reported phone has not been tested directly, and physical camera/keyboard and macOS login-service behavior remain outside emulator/CI evidence. Herdr and Tailscale continue to run independently.
