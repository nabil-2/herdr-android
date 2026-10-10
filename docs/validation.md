# Preview 0.7.1 validation

Validated on 10 October 2026. This release prevents a new Codex chat from replacing the displayed content of an existing chat. The [0.7.0 report](validation-0.7.0.md) records math, image/file attachments and existing shared-daemon conversation selection.

## Conversation isolation

An owned two-pane Herdr fixture reproduces the previous bug: the 0.7.0 helper accepts a new session report with a stale inherited pane variable, changing the older pane's reported conversation ID while the new pane remains unbound. This reproduces metadata misattribution; it does not demonstrate saved-history deletion or establish the precise trigger on a user's phone.

The Linux helper now proves that its nearest interactive Codex ancestor belongs to the exact reported pane's foreground process group and controlling terminal. It verifies owned process lifetimes, the Herdr socket and Linux peer credentials through a bounded read-only metadata request. Shared app-server and noninteractive ancestors are rejected. Custom hooks remain intact; only the exact recognized official compatibility patch is upgraded. New launches wait for the same sole foreground shell to stay stable for 200 milliseconds before starting one isolated Codex runtime.

Canonical conversation IDs accompany opaque page bindings. The phone preserves an already-open conversation and draft when the ID unexpectedly changes, pauses writes and requires an explicit switch. A binding rotation for the same canonical conversation does not replace its content or erase its draft. A bounded, memory-only history cache retains expected conversation identity across pane navigation. Explicit conversation selection clears that expectation deliberately. Attachment receipts from an obsolete binding are dropped; the companion retires only unconsumed stages belonging to the same credential and pane, preserving consumed and uncertain delivery records.

The terminal-inspection switch keeps unresolved delivery uncertainty with the original draft and never acknowledges another conversation's marker. The new target's authoritative server uncertainty still applies. Actual MainActivity regressions exercise new-chat creation followed by an older pane, explicit switching, same-ID binding rotation, old-companion creation rejection and inspected switching without a remote acknowledgement.

New phone-created Codex chats require the companion's Linux-only `codex-create-isolated` capability. Update older Linux hosts to 0.7.1. Existing macOS chat and terminal access remain supported; creation from this phone version is disabled until macOS has equivalent foreground ownership verification.

## Terminal focus

The first broad device run exposed an intermittent terminal teardown failure. Its earlier cause was native focus removal triggering a nested Compose measurement through lazy focus search; the later detach-lifecycle assertion was a consequence. The same primary exception appears in an archived 0.6 run, so it predates the conversation changes.

MainActivity enables Compose 1.12.1's existing experimental `isViewFocusFixEnabled` before creating the UI. The [official AndroidViewHolder implementation](https://android.googlesource.com/platform/frameworks/support/+/edd8bafb328feda384a1048242a66d77f12b6699/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/viewinterop/AndroidViewHolder.android.kt) clears focus before removing a focused native view. The new regression explicitly focuses the real terminal editor before six pane replacements and six returns to the lazy dashboard, rejects stale InputConnection commits and verifies no unintended terminal input. Retain this regression when upgrading Compose.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM | 186 passed | Canonical IDs, conversation guard/history cache, same-conversation reconnect, existing math/attachment/input guards |
| Android lint | 0 errors, 22 warnings, 2 hints | App and tests, including experimental focus opt-in |
| Bridge offline | 596 passed | Foreground hook ownership, canonical page IDs, launch stability, obsolete upload cleanup, retained protocol/authentication guards |
| Actual Codex/Herdr integrations | 16 passed | Owned two-pane misattribution reproduction and rejection, distinct launches, real hooks, histories and attachment input |
| Android 13 device | 104 passed | 101 ordinary cases plus three isolated live pairing/terminal cases |
| Frozen Linux package | Passed | Version/capabilities, QR/pairing, authentication, clean history, static image codecs, generic uploads and licenses |
| Host package CI | Passed | Linux x86_64, macOS arm64 and macOS x86_64 builds and frozen smoke |
| Android/bridge CI | Passed | Unit tests, lint, APK build and offline bridge tests |
| Installed 005 companion | Passed | Checksummed update, preserved pairing/configuration, guarded helper and original conversation metadata |
| Normal Android update | Passed | Files/PackageInstaller 0.7.0→0.7.1, retained private app data, exact installed APK and successful launch |

These are **902 distinct automated passing cases**, plus artifact, package, installed-host and delivery checks. The final 101-case ordinary device run passed in 194.712 seconds; the three live Android cases passed in 12.981 seconds. The focused terminal class, included in the full-suite total, passed six cases in 39.794 seconds. The final build produced no device crash diagnostic. All 16 opt-in runtime integrations passed separately; the offline run skips those integrations.

[Android/bridge CI](https://github.com/nabil-2/herdr-android/actions/runs/38031574617) and [all host package CI jobs](https://github.com/nabil-2/herdr-android/actions/runs/38031574638) passed at the final production commit.

All test conversations, images, files and draft text are synthetic. Real runtime tests use owned UUID Herdr sessions and temporary Codex homes, without model prompts or input into existing user agents. Existing user message bodies are not fixtures. Focused checks are included in the full-suite totals rather than counted twice.

## Artifact and installed host

- Pixel 7 emulator, Android 13/API 33, x86_64, hardware virtualization.
- JDK 17, Kotlin 2.4.20, Gradle 9.6.0, Android Gradle Plugin 9.4.0; compile/target SDK 37.0, build tools 36.0.0; minimum API 26.
- APK version code 10, name 0.7.1. All 16 native libraries and 20 bundled math fonts remain byte-identical to 0.7.0. The production APK excludes the synthetic test provider.
- Linux package: portable Python 3.11.17, PyInstaller 6.22.3. Frozen package and codec smoke passed. Linux and both macOS package builds passed in [host CI](https://github.com/nabil-2/herdr-android/actions/runs/38031574638).
- The installed 005 companion is 0.7.1 with the isolated-creation capability. Setup, device registry, owner token, Codex configuration and the original helper backup remain byte-identical. Only the recognized compatibility helper changed. Existing Tailscale routes and paired profiles are preserved.
- Metadata-only checks confirmed the original loaded primary conversations remain offered from both relevant 005 panes. The checked saved histories remain present and unarchived, with owner-private safety copies. No user conversation was automatically selected or restarted.

APK: **32,274,435 bytes**, signature, unique ZIP entries, CRC and 16 KiB alignment verified. The normal Android Files/PackageInstaller 0.7.0→0.7.1 update confirmed **App installed**, retained an app-private synthetic marker, installed the exact APK below and launched successfully. Private Tailscale HTTPS delivery passed normal CA/hostname validation, HTTP 200, APK MIME, full byte count and exact hash.

APK SHA-256:

```text
bc395eda156dc3cc8c4e0e7ef65eec8afb48ba5ff3f7924db17b3cf39dee9ac7
```

Production source is `dc756587195080bcde157c5e4b5dd7ef455382c7`. The host source is unchanged from `df1d06021c35aa63e1b5a727beaee0da23171b8b`; the final production change enables terminal focus handling. Later documentation records these results.

The signing certificate is unchanged: `ad045612a56a05953afb2162ac0b277a8a579dd8e70269caa18db37c26125c9c`.

Linux companion SHA-256:

```text
a455f459f795c78c7291d68e69d19f7762fd53cfc18e0608bb2bead996b40b29
```

## Repeat

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=dev.herdr.android.LiveBridgeDeviceTest
bridge/.venv/bin/python -m pytest bridge/tests -q
HERDR_INTEGRATION=1 CODEX_INTEGRATION=1 bridge/.venv/bin/python -m pytest \
  bridge/tests/test_live_*.py \
  bridge/tests/test_codex_integration.py::test_null_transcript_hook_replaces_only_owned_exact_codex_session -q
bridge/.venv/bin/python scripts/build_host.py
bridge/.venv/bin/python scripts/smoke_host_package.py artifacts/host/herdr-pocket-linux-x86_64
```

Runtime fixtures wait for exact metadata adoption after a hook report. Hook-report and mutation retries are not introduced. A dialog assertion is scoped to the dialog because the same explanatory text also appears in the dashboard. The initial broad device failure was investigated and fixed in production rather than hidden by a teardown delay.
