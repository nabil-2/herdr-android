# Preview 0.7.2 validation

Validated on 11 October 2026. This release restores reads for Codex history backends that reject `list_turns`, recovers oversized histories through Codex's read-only paging API, retries transient phone chat failures, and remembers explicit conversation choices for verified shared runtimes. The [0.7.1 report](validation-0.7.1.md) records conversation isolation and terminal focus handling.

## Recovery and conversation identity

A read-only check on 005 reproduced a loaded primary conversation whose shared daemon returned RPC error `-32601`, `list_turns is not supported yet`. Its saved history remained readable through a separate Codex reader. The companion now falls back only for that exact typed error and reads the same canonical conversation ID. Delivery remains bound to the live daemon; history recovery never resumes a thread, sends a prompt, selects another conversation or forwards input to another terminal.

The phone retries visible transient read failures with bounded backoff, including 404, 409, network failures and server errors. Cached messages, draft text, canonical identity and delivery uncertainty remain intact. Sending is disabled while the read is recovering; 401/403 failures stop retries. A verified recovery closes a chooser opened automatically for that pane. A manually opened chooser stays open. Late chooser responses cannot undo the successful recovery, and a changed canonical ID still requires an explicit switch.

Shared Codex frontends do not expose an authoritative public mapping from the terminal being displayed to a conversation UUID. Their loaded-conversation list belongs to the common daemon. New phone-created isolated tabs retain automatic attribution. Existing shared tabs require an explicit initial choice; 0.7.1's memory-only choices cannot be recovered after updating the companion. Version 0.7.2 remembers new choices per paired credential in a private bounded store, then proves the same boot, frontend, daemon, terminal, socket and Codex home before restoring them. A remembered choice is not a claim that a terminal's later `/new` or `/resume` switch is followed automatically.

Transient peer-proof failures no longer fall through to a potentially stale inherited hook UUID. A slow disk restore cannot overwrite a newer explicit choice. The original verified runtime digest remains part of attachment scope, preserving durable delivery uncertainty across socket reconnects and companion restarts even when the opaque page binding rotates. Replaced processes, sockets, terminals, boot identity or credentials cannot inherit another choice.

## Bounded large-history reads

Ordinary histories retain the existing single full read below the 32 MiB transport limit. Only a typed local size-limit violation enables bounded `thread/turns/list` reads for that exact conversation. Full pages start at five turns and shrink when necessary. Metadata, IDs and cursors are checked; hidden reasoning and command items are discarded before caching visible messages.

If a single turn itself exceeds the limit, Codex's official summary projection is used for that turn and a visible warning explains that intermediate messages may be missing. This projection retains the first user message and last agent message. It does not promise complete commentary or intermediate steering within that oversized turn. History reads remain bounded by turn count, visible bytes and deadlines; ordinary RPC, permission and timeout errors do not silently switch formats.

Two actual Codex 0.162 reader tests use owned synthetic hidden reasoning payloads rather than user histories. A multi-turn history over 34 MiB recovers all six visible messages, including commentary, through full turn pages. A single turn over 32 MiB recovers through the official summary with the explicit warning. Both repeat reads preserve visible identities and use only initialization and history methods. These cases exercise the transport limit with valid projected hidden reasoning events; they do not claim to test a specific command event schema.

## Results

| Check | Result | Coverage |
| --- | --- | --- |
| Android JVM | 188 passed | Retry/send transitions, preserved identity and uncertainty, existing math, attachment and input guards |
| Android lint | 0 errors, 22 warnings, 2 hints | App and tests |
| Bridge offline | 674 passed | Typed recovery, bounded paging, private remembered choices, race ordering, process replacement, credential isolation and durable uncertainty |
| Actual Codex/Herdr integrations | 18 passed | Two new oversized-history cases plus existing hook, isolation, launch, history and attachment checks |
| Android 13 device | 112 passed | 109 ordinary cases and three owned live companion cases |
| Frozen Linux package | Passed | Version, QR/pairing, authentication, clean chat, image codecs, generic uploads and licenses |
| Host package CI | Passed | Linux x86_64, macOS arm64 and macOS x86_64 builds and frozen smoke |
| Android/bridge CI | Passed | JVM tests, lint, APK build and offline bridge tests |
| Installed 005 companion | Passed | Checksummed update, unchanged pairing/configuration/helper and active private routes |
| Normal Android update | Passed | Files/PackageInstaller 0.7.1→0.7.2, retained private app data, exact installed APK and successful launch |

These are **992 distinct automated passing cases**, plus artifact, package, installed-host and delivery checks. The ordinary device suite passed in 223.560 seconds; three live Android cases passed in 14.054 seconds. The focused recovery class passed eight cases in 33.349 seconds and is already included in the ordinary total. All 18 opt-in host runtime cases passed in 69.38 seconds; the offline run skips those cases. No device crash diagnostic was produced.

[Android/bridge CI](https://github.com/nabil-2/herdr-android/actions/runs/38130905930) and [all host package CI jobs](https://github.com/nabil-2/herdr-android/actions/runs/38130905951) validate production commit `d2a0f3e69af01840d2cdc42067c2e3feebdad2a7`.

All written test conversations, files, images and drafts are synthetic. Runtime tests use owned UUID Herdr sessions and temporary Codex homes, without model prompts or input into existing user agents. Actual user message bodies are not test fixtures or diagnostic output. Owned test sessions, private pairing files, port forwarding and the emulator were cleaned up.

## Artifact and installed host

- Pixel 7 emulator, Android 13/API 33, x86_64, hardware virtualization.
- JDK 17, Kotlin 2.4.20, Gradle 9.6.0, Android Gradle Plugin 9.4.0; compile/target SDK 37.0, build tools 36.0.0; minimum API 26.
- APK version code 11, name 0.7.2. All 16 native libraries and 20 bundled math fonts remain byte-identical to 0.7.1. The production APK excludes the synthetic test provider.
- Linux package: portable Python 3.11.17, PyInstaller 6.22.3. Local frozen smoke and all platform package CI jobs passed.
- Installed 005 companion: 0.7.2, with isolated-creation capability. Setup, device registry, owner token, Codex configuration, compatibility helper and original helper backup remain byte-identical. Tailscale and private APK services remain active with the existing HTTPS routes.
- Read-only checks with the new source successfully read all three loaded primary conversations, including the exact unsupported-history case. The two nonempty histories returned their latest 50 messages with older pages; the third saved thread is currently empty. This does not establish which terminal is displaying which shared conversation. Diagnostic choices were confined to a temporary in-memory manager and were never persisted or posted to the installed server.

APK: **32,198,999 bytes**, signature, unique ZIP entries, CRC and 16 KiB alignment verified. A normal Android Files/PackageInstaller 0.7.1→0.7.2 update confirmed **App installed**, preserved an app-private synthetic marker, installed the exact APK below and launched successfully. Private Tailscale HTTPS delivery passed normal CA/hostname validation, HTTP 200, APK MIME, full byte count and exact hash.

APK SHA-256:

```text
deb94dab65ee33eca4c2df6b8cfb9a6a3b240ffbcc7f9e9f5692720fbca78c11
```

The signing certificate is unchanged: `ad045612a56a05953afb2162ac0b277a8a579dd8e70269caa18db37c26125c9c`.

Linux companion SHA-256:

```text
ce354e5b6eaf48ce70894335f23375a13afb73b6343bdec8569e9670a50b3c92
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
