# Preview 0.4.0 validation

Validated on 2026-10-09 with unmodified Herdr 0.9.3 and Codex 0.162.0. Live Herdr checks use UUID-named sessions and temporary workspaces. Codex checks use disposable provider homes with synthetic conversations. Existing user panes were never given input, restarted, or read for conversation contents.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM | 107 passed | Existing chat/pairing coverage; strict snapshot identities/references; native key encoding; bounded ordered input, Unicode fragments, stale target rejection; command memory; history bounds; redacted diagnostics |
| Android lint/build | Zero errors, 18 warnings | Minimum API 26, target API 37, native Kotlin/Compose; debug app and device test APKs |
| Bridge offline | 282 passed, 3 optional live cases skipped | Authentication, pairing, chat, raw terminal control characters, strict pane targets, bounded real history, hostname/version readiness, management downloads/checksums, multi-instance updates, rollback, safe uninstall and purge |
| Real Codex reader | 2 passed | Legacy/indexed saved histories and literal prompt routing without model turns |
| Stock Herdr | 1 passed | Owned shell/observer, raw Ctrl+U and Enter execution, discarded abandoned command, real recent output |
| Android device | 51 passed, 3 optional live cases skipped | JNI/Keystore/gallery/chat plus real native input connections, composition/key-row order, modifiers, stale connections, pinch/pan/copy, paste review, drafts/snippets, history search/copy/lifecycle, server search and full pairing transitions |
| Linux frozen package | Passed | Authenticated API, raw keys, real history envelope, Codex adapter, one-use pairing, revocation, QR helpers, version readiness and licenses |
| Frozen management roundtrip | Passed | Checksummed staged 0.3.1→0.4.0 upgrade, installed binary execution, uninstall keeping credentials, explicit purge; only temporary files and simulated Tailscale |
| APK packaging | Passed | Signature, same certificate, 16 KiB ZIP alignment and all eight 64-bit ELF libraries |

These are **443 distinct automated passing cases**, plus packaged-runtime checks. Optional device cases require their live fixture; the source tests have been updated for the new Compose drawer. Service operations in the management regression suite use temporary definitions and mocked service state; real macOS login behavior is not established by those checks.

The ordinary device suite completed all 54 cases successfully, with three live cases skipped without fixture arguments. The complete native workflow verifies that committing an IME word followed by the visible Enter button produces ordered requests to the same pane. Remote device-status queries produce no writes. Another case searches across workspaces and copies the selected non-Codex pane's output without commands or chat polling. Selection controls remain within the phone viewport; appearance changes preserve remote dimensions.

## Reported connection crash

The published 0.3.0 APK completed two full post-confirmation flows against synthetic v0.2/v0.3 companion responses: invitation exchange, encrypted profile persistence, authenticated health/snapshot, and a dashboard with multiple workspaces and mixed agent states. Normal upgrade responses did not reproduce a crash.

A separate duplicate-pane-ID fixture **did reproduce a post-connect crash in that published APK**, with Compose reporting an already-used list key. Version 0.4 rejects duplicate/blank IDs and inconsistent references before rendering, producing a controlled connection error while preserving the paired profile. The corresponding full app case passes. This demonstrates a concrete fixed crash condition; it does not prove the user's other server returned duplicate IDs.

Snapshot parsing runs off the UI thread and rejects excessive JSON nesting. Private bounded diagnostics retain exception class names and sanitized stack frames, excluding messages, URLs, tokens, and invitation codes. Sanitizer and actual owner-private file checks pass; Copy/Delete is explicit and nothing uploads automatically. No intentional fatal process crash is used to test recording.

## Environment and artifact

- JDK 17; Kotlin 2.4.20, Gradle 9.6.0, Android Gradle Plugin 9.4.0, Android SDK 37.0/build tools 36.0.0.
- Pixel 7 emulator, Android 16/API 36, x86_64, hardware virtualization.
- ConnectBot Terminal 0.3.11, CameraX 1.6.2, ZXing 3.5.4; native Compose without a WebView.
- Linux x86_64 package: glibc 2.34, portable Python 3.11.17, PyInstaller 6.22.3; no separate Python installation required.

APK: **34,678,807 bytes**, version code **5**, version name **0.4.0**. The signing certificate matches previous previews, so installation preserves phone profiles. SHA-256:

```text
065a5ed1e7d3b147a9bb346ab940984b0774bb2c906989a4e4f617b85f4841c1
```

[Native terminal](screenshots/terminal.png) · [Gallery pairing](screenshots/gallery.png) · [Codex chat](screenshots/chat.png)

The new terminal screenshot contains only a synthetic local fixture. Gallery import was also manually validated through the actual Android Photo Picker in 0.3.1; that path retains its permission-free local decoding/review flow. A camera preview startup/first-frame/Back check worked on this emulator. Physical camera/keyboard behavior and the reported other phone's exact crash remain unverified.

## Repeat

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_herdr.py -q
CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest bridge/tests/test_live_codex_chat.py -q
bridge/.venv/bin/python scripts/build_host.py
bridge/.venv/bin/python scripts/smoke_host_package.py artifacts/host/herdr-pocket-linux-x86_64
```

Run ordinary instrumentation without live arguments, or follow the [isolated Android fixture](../bridge/README.md#live-android-device-fixture) to include real one-use pairing and commands in an owned stock Herdr shell. Build first, start the fixture immediately before testing, and clean up its reverse mapping/process afterward. Invitations expire after five minutes.

GitHub [Android/bridge checks](https://github.com/nabil-2/herdr-android/actions/workflows/checks.yml) and [host packaging](https://github.com/nabil-2/herdr-android/actions/workflows/host-packages.yml) validate the source and package Linux/macOS targets. Platform license bundles and all release assets are covered by `SHA256SUMS`.

## Practical limits

Recent terminal history is bounded to 1,000 lines/64 KiB without older-page navigation. The live view projects Herdr's fixed observer grid; phone gestures do not resize the desktop. Tests establish implemented behavior, not measured parity with Termius on physical hardware. Native chat currently supports confirmed saved Codex sessions, with message refresh rather than token streaming. Inline approvals, background notifications, and direct SSH remain outside this preview.

Tailscale and Herdr run independently. On this Linux host, private userspace Tailscale HTTPS and the previous Pocket installation were already validated without sudo. The default Herdr session was later found stopped; a companion service can be active while health correctly reports unavailable Herdr. Start Herdr independently before setup/pairing. Existing Herdr state was not restarted for testing. Mac packages are exercised in CI; a physical macOS LaunchAgent/login scenario remains untested.
