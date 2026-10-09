# Herdr Android companion protocol v1

The Android app is a native Kotlin/Jetpack Compose client. A small Python service runs as the same user as the existing Herdr daemon. Connect the phone and host through Tailscale before using the app. Herdr itself remains unmodified.

All `/v1` HTTP requests and WebSocket handshakes require `Authorization: Bearer <token>`, except `POST /v1/pair`, which authenticates a one-use invitation code in its JSON body. Accepted bearer credentials are the host's owner token or an active paired device token. Both grant control over the entire selected Herdr session. Use an HTTPS URL provided by Tailscale Serve; loopback HTTP is supported for local development. Never put credentials in URLs or logs.

## Pairing invitation and device credentials

Managed host setup runs one companion per selected Herdr socket and exposes its loopback listener through private Tailscale Serve. The host CLI creates a QR invitation locally, using Segno, and prints it as a QR code or complete JSON with `herdr-pocket pair --plain`. `pair --qr-file PATH` and `setup --qr-file PATH` also export the same invitation to an owner-only PNG, preserving the chosen directory's permissions. It retains the five-minute lifetime and one-use behavior. The payload is a flat JSON object with exactly these fields:

```json
{"type":"herdr-pocket-pair","version":1,"url":"https://host.tailnet.ts.net:8443/","name":"host / dev","code":"<one-use-code>","expiresAt":1791542400}
```

`code` above is a placeholder. Real codes encode 32 random bytes as 43 canonical, unpadded base64url characters. `expiresAt` is an integer Unix timestamp in seconds; the normal invitation lifetime is 300 seconds. `name` is a nonblank server display name of 1–100 Unicode code points without C0 or DEL controls. `url` is the companion's base address without credentials, an API path, a query, or a fragment. Android applies the same HTTPS/private development address rules used by manual profiles.

Android accepts at most 4,096 UTF-8 bytes for an invitation, rejects duplicate or unknown fields, nested values, incorrect types, and unsupported type/version values. Scanning or pasting only decodes and validates; it does not contact or pair with the server. The user reviews the host name/address and presses **Pair & connect** before the exchange. A phone-clock expiry indication is advisory; the host clock decides whether the invitation is valid.

`POST /v1/pair` receives:

```json
{"code":"<one-use-code>","deviceName":"My Android phone"}
```

This request has no owner bearer credential. Its body rejects unknown fields and incorrect types. `deviceName` is a nonblank string of 1–100 code points without C0 or DEL controls; `code` is a string of at most 128 characters. An incorrect, unknown, expired, or consumed code value receives the same `401` explanation, without echoing the supplied value. Missing fields and other body validation failures return `422`, and body/upload limits below still apply.

The companion first validates the code, then verifies a live Herdr snapshot. An unavailable or incompatible Herdr session returns `503` without consuming the invitation. It then atomically consumes the still-valid code and issues a fresh device credential. Concurrent exchanges can produce at most one successful pairing. The `200` response is:

```json
{"service":"herdr-android-bridge","protocol":1,"token":"<device-token>","deviceId":"<uuid>","serverName":"host / dev"}
```

The real token is another independently generated 32-byte, 43-character canonical base64url value. `deviceId` is a UUID, and `serverName` comes from the host-created invitation. The token is returned once and saved encrypted on the phone. Pairing saves the issued device credential, leaving the invitation and owner credential out of that profile. The client validates the response envelope, never follows redirects, and never automatically retries pairing. If the response is lost after code consumption, generate a new invitation and review the host's device list.

The host stores the registry as an owner-only regular JSON file, normally `~/.config/herdr-android/instances/INSTANCE/devices.json` (respecting `XDG_CONFIG_HOME`). File access rejects symlinks and unsafe ownership/permissions. Locked atomic updates store SHA-256 hashes of invitation codes and device tokens, with display names, timestamps, IDs, and revocation metadata. Plain codes/tokens are not persisted in that registry. The registry is bounded to 1 MiB, 64 pending invitations, and 4,096 device records. Expired invitations are removed when generating a new one. The separate owner token remains available for local service management and existing advanced manual profiles.

`herdr-pocket devices --session NAME` lists device metadata without credentials or hashes. `herdr-pocket revoke-device DEVICE_ID --session NAME` marks a device revoked; future HTTP requests and terminal handshakes reject its token. Active paired WebSockets recheck authorization once per second and close with policy code `1008` after revocation, cancelling their observer. Other device credentials and the owner token remain valid. Removing a profile from Android does not revoke its host-side device credential. Pairing or revocation itself sends no input to Herdr.

## HTTP endpoints

- `POST /v1/pair`: the one-use exchange above. Pairing returns `503` if a directly embedded service was created without a pairing registry.
- `GET /v1/health`: `{ "service": "herdr-android-bridge", "protocol": 1, "herdrVersion": "0.9.3", "companionVersion": "0.5.1", "pairing": true, "capabilities": ["terminal-input", "terminal-history"] }`. The Boolean indicates whether a pairing registry is configured. A configured Codex reader additionally advertises `codex-chat` and `codex-chat-working-input`. This reads a live Herdr snapshot and returns `503` if the daemon is unavailable; it is not merely a check that the HTTP process is running.
- `GET /v1/snapshot`: `{ "herdrVersion": "0.9.3", "workspaces": [...], "tabs": [...], "panes": [...] }`.
- A workspace is `{ "id": "w1", "label": "project", "cwd": "/path/to/project" }`. Herdr's workspace object has no directory field, so the bridge derives this from a pane in its active tab, falling back to another pane in the workspace. Unknown directories are empty strings.
- A tab is `{ "id": "w1:t1", "workspaceId": "w1", "label": "main" }`.
- A pane is `{ "id": "w1:p1", "workspaceId": "w1", "tabId": "w1:t1", "title": "Claude Code", "cwd": "/path/to/project", "agent": "claude", "status": "working" }`. `agent` may be null; status is `idle`, `working`, `blocked`, `done`, or `unknown`. Pane directories prefer Herdr's `foreground_cwd`, then `cwd`, then an empty string.
- `POST /v1/panes/{paneId}/input`: `{ "text": "hello", "keys": ["enter"] }`; text and keys are optional individually, but at least one must be nonempty. Sends literal input to the explicitly selected pane, using Herdr's atomic input API. Returns `{ "ok": true }`. Do not silently retry writes.
- `POST /v1/panes/{paneId}/terminal-input`: exactly `{ "text": "typed characters" }`, returning `{ "ok": true }`. Direct keyboard input uses Herdr's public `pane.send_text` on that explicit pane. Unlike reviewed paste, it sends literal control characters without bracketed-paste wrapping. Requires `terminal-input` capability.
- `GET /v1/panes/{paneId}/scrollback`: `{ "paneId": "w1:p1", "text": "recent output\n", "truncated": false }`. Reads real recent output through the fixed public `pane.read` request below. Requires `terminal-history` capability.
- `POST /v1/workspaces`: `{ "cwd": "/path/to/project", "label": "project" }`; returns `201` and `{ "id": "w2" }`. Creates a workspace with a shell using Herdr's public API and `focus: false`. Users can launch any agent from its terminal.
- Errors are non-2xx JSON `{ "detail": "Readable explanation" }`. `401` means a rejected token or pairing code, `400` means an invalid target/key/directory or terminal geometry, `422` means an invalid request body, `413` means the body is too large, `408` means the body upload timed out, `503` means Herdr is unavailable, an incompatible response, unavailable pairing storage, or unconfirmed prompt delivery after dispatch, and `409` indicates a missing or conflicting target or another rejected operation. Errors do not echo credentials, submitted text, or raw CLI diagnostics.

## Command limits and target selection

HTTP request bodies are limited to 65,536 bytes, including JSON encoding, and must finish arriving within 10 seconds. Input text is limited to 16,384 Unicode code points and 32,768 UTF-8 bytes, with no NUL characters. A command accepts at most 32 keys, each 1–32 ASCII characters with no whitespace. Herdr validates the complete logical key list before writing any text or keys. The Android toolbar uses `enter`, `esc`, `tab`, `up`, `down`, `left`, `right`, and `ctrl+c`, verified against Herdr 0.9.3.

Direct `terminal-input` has the same text/body bounds, accepts only a nonempty text string, and permits NUL and other terminal controls, including Ctrl+Space. It accepts no keys, arbitrary methods, or parameters. It dispatches `pane.send_text` exactly once and requires an `ok` acknowledgment. Failed acknowledgment or transport failure returns controlled `503`; delivery is uncertain and the user must inspect the terminal before continuing.

Workspace directories must contain 1–4,096 characters, contain no NUL, and begin with `/` or `~/`, or equal `~`. Labels must contain 1–128 characters, include non-whitespace text, and contain no characters below U+0020. Request bodies reject unknown fields and incorrect JSON types.

Pane IDs are explicit workspace-qualified IDs such as `w1:p1`. The bridge does not accept `current`, agent names, or omitted targets. It sends one newline-delimited Unix socket request with `method: "pane.send_input"` and `params: { "pane_id": "w1:p1", "text": "hello", "keys": ["enter"] }`; empty text/key fields are omitted. Transport failures never retry writes. Refresh and inspect the pane before repeating an input whose outcome is uncertain.

The bridge uses the same selected socket for snapshot reads, commands, and observer subprocesses. Socket overrides never fall back to a different session. The Unix socket must be owned by the service user. Per-request Unix socket communication is bounded to 10 seconds and 2 MiB per response line.

## Terminal WebSocket

`GET /v1/terminals/{paneId}/stream?cols=80&rows=30`, upgraded to a WebSocket with the same Authorization header. The bridge launches the installed `herdr terminal session observe <paneId> --cols <cols> --rows <rows>` and forwards JSON frames without modifying them. This uses a read-only observer: the desktop retains terminal geometry and controller ownership. Phone input uses the HTTP endpoint above.

The installed Herdr 0.9.3 CLI emits one JSON object per stdout line. The bridge forwards each validated object as a WebSocket text message, preserving its JSON fields. The actual frame shape is:

```json
{"bytes":"SGVsbG8=","encoding":"ansi","full":true,"height":30,"seq":1,"type":"terminal.frame","width":80}
```

`bytes` is base64-encoded ANSI terminal output. `full: true` represents a full viewport; later frames can use `full: false` and must be applied in order to the existing terminal state. `width` and `height` describe the projected observer viewport, and `seq` is a nonnegative integer sequence number. A real terminal exit emits:

```json
{"reason":"terminal term_65d5ad2c1ea6e1 exited","type":"terminal.closed"}
```

Normal closure forwards `terminal.closed`, then closes with WebSocket code `1000`. Invalid or unexpected observer output closes with `1011`. The stream accepts no input messages; attempting to send application data closes with `1008`. Use the HTTP input endpoint for all commands.

Requested geometry defaults to 80 columns and 30 rows, bounded to 20–300 columns and 5–150 rows. Herdr's observed frames use the requested geometry. The bridge's defensive frame validator permits raw positive dimensions up to 1,000 in either direction, while the Android client accepts the narrower request bounds. Each raw CLI frame is limited to 2 MiB, requires a valid base64 payload, and must match one of the two envelopes above.

Before upgrading, invalid tokens, unavailable Herdr, invalid geometry, and stale panes return the corresponding HTTP error. Supported uvicorn installations use HTTP WebSocket denial responses; ASGI servers without that extension reject the connection with policy code `1008` instead. The service never accepts an unauthenticated stream.

Observers do not own input or resize the desktop terminal. Disconnecting cancels and reaps the observation process without terminating the pane, including when the ASGI endpoint is cancelled during shutdown. Each observer receives a two-second termination grace period before a kill/reap fallback.

## Recent output history

History makes one read-only `pane.read` call with explicit `pane_id`, `source: "recent_unwrapped"`, `format: "text"`, `lines: 1000`, `strip_ansi: true`, and `intent: "interactive"`. The client cannot choose a method, source, format, path, or focused-pane fallback. The bridge requires matching pane/source/format in the result and a Boolean truncation flag. Returned text retains at most the latest 1,000 lines and 65,536 UTF-8 bytes without cutting a Unicode character; further bounding sets `truncated: true`. Invalid responses return controlled errors without exposing their contents.

Android validates the envelope, exact pane ID, Unicode, and both bounds. Output history is independently loaded wrapped text with selection, literal search, previous/next match, refresh, and explicit copy. It reads once when opened or resumed, never polls, and clears text/search when closed or backgrounded. Pane/profile/session changes cancel or reject stale reads. This bounded Herdr history is separate from the observer viewport.

## Snapshot and connection errors

Before rendering lists, Android requires nonblank, unique IDs in each workspace/tab/pane collection and valid workspace/tab references. A malformed or inconsistent snapshot produces a controlled connection error instead of an uncaught Compose list-key failure. JSON is limited to 2 MiB and 64 levels of nesting. Snapshot parsing runs off the main UI thread. Old health responses without capabilities remain compatible.

Client retirement rejects new requests immediately. Socket cancellation and TLS pool eviction run on independent IO workers, including pairing replacement and ViewModel shutdown. Cleanup does not depend on the cancelled UI scope and never disables Android's main-thread network guard.

If an uncaught app error occurs, Android still terminates the process normally. A best-effort private diagnostic records only app version, Android SDK, exception/cause class names, and at most 20 sanitized stack frames, bounded to 8 KiB. Exception messages, request URLs, invitation codes, tokens, and user input are excluded. A valid previous record offers explicit Copy/Delete on the server screen; nothing is uploaded automatically. This helps investigate device-specific crashes that cannot be reproduced with synthetic server responses.

## Native terminal interaction

Direct typing uses an Android input connection rather than the emulator's reply callback. IME composition stays local until committed; Enter/navigation flush text before the logical key, while Escape and Ctrl+C discard unfinished composition. Known navigation/modifier keys use `/input` so Herdr encodes the running terminal's actual keyboard mode. Observer frames describe rendered cells and do not expose every remote PTY mode. Clipboard and IME paste require review: a confirmed single line is inserted without Enter through `/input`, preserving Herdr's bracketed-paste handling; multiline text opens Compose. Control-bearing paste is rejected. Emulator-generated device-status replies and clipboard sequences never become remote input.

The native input FIFO holds at most 128 requests and 128 KiB including in-flight input, coalescing adjacent raw text up to 4 KiB. Each request captures profile generation, pane, terminal session, and observer attempt. Stale UI callbacks/input connections cannot enqueue to a new target. Switching panes/profiles, backgrounding, or losing the observer discards pending input; uncertain delivery pauses typing until the user reviews it and chooses Resume typing. Writes are never replayed. Direct keystrokes and special keys do not clear a Compose draft; only confirmed Compose submission advances its acknowledgment.

Pinch zoom, panning, Fit width, themes, and focus mode affect the phone viewport without resizing the desktop terminal. Selection has Copy/Cancel controls inside the phone viewport. Appearance is saved locally. Snippets are saved only by explicit user action and inserted into Compose for review. Optional command history holds at most 20 acknowledged Compose commands in memory, preserving exact text up to 8,192 characters; longer commands are omitted, never truncated for reuse. Live keystrokes are not recorded as command history. Compose drafts stay in memory outside saved-instance state.

An older companion without `terminal-input` retains Compose/logical-key controls. Without `terminal-history`, Output history is disabled. Host upgrades enable these features without changing the protocol version or existing device credentials. Managed setup records the owning hostname and places a matching `ConditionHost` in Linux user units so another machine sharing the same home cannot start that unit or silently replace its setup. Pairing commands must run on that owning host.

## Android behavior

### Native saved Codex conversation

A configured Codex reader adds `codex-chat` and `codex-chat-working-input` to health capabilities. The latter advertises support for receiving Codex messages while working. Older bridges can omit the capabilities field. Codex panes can expose optional `conversation: {"provider":"codex","available":true,"reason":null}` metadata in the snapshot. An unavailable descriptor includes a controlled reason. Availability means the pane has a recognized saved-session reference and the bridge has resolved a reader executable; the conversation request can still fail if the history or configured Codex home is unavailable.

The bridge accepts only Herdr's official canonical reference: pane `agent:"codex"`, `agent_session.source:"herdr:codex"`, `agent_session.agent:"codex"`, `kind:"id"`, and a canonical UUID value. It never finds a conversation by directory, display title, or most recent file. Missing references fall back to Terminal. The Android client supplies pane IDs and opaque binding/cursor values, never native thread IDs, Codex home paths, or transcript paths. Native references remain internal to the bridge.

`GET /v1/panes/{paneId}/conversation` returns:

```json
{"binding":"<opaque>","revision":"<opaque>","historyEpoch":"<opaque>","messages":[{"id":"<opaque>","role":"user","text":"Hello","phase":null},{"id":"<opaque>","role":"assistant","text":"Ready","phase":"final_answer"}],"olderCursor":null,"unchanged":false,"canSend":true,"status":"idle","warning":null}
```

Binding, revision, history epoch, and message IDs are 43-character opaque strings. A binding identifies the selected socket instance, pane, terminal generation, native Codex session, and configured Codex home. It changes when that identity changes or the bridge restarts. A message ID includes its turn/item identity; repeated identical message text remains separate. Phase is `commentary`, `final_answer`, or null. Messages contain literal user text parts and assistant text; attachments, attachment paths, hooks, tool arguments/output, reasoning, injected raw model context, and instructions are omitted. Text is rendered natively; it is not scraped from ANSI terminal output.

Optional message `timestamp` is an integer Unix timestamp in seconds, accompanied by `timestampSource:"turn_start"` or `"turn_end"`. Codex 0.162 exposes saved turn times rather than exact per-message times: user/commentary messages use the known turn start; final answers use the known completion, falling back to a known start. Invalid or unavailable optional times are omitted. Android shows localized date dividers and explicitly labels turn start/finish times using the phone's time zone and 12/24-hour setting. It never substitutes the current clock or thread creation time for missing history dates. Older companions remain compatible with the updated app.

The first page contains up to 50 latest messages in chronological order. Pass `cursor=<olderCursor>` to load preceding messages. Cursors are signed, shorter than 2,048 characters, and tied to both binding and revision. Malformed or stale cursors return `409`; refresh the latest page before rebasing pagination. Pass `revision=<lastRevision>` for a latest-page poll. If unchanged, the response has `unchanged:true` and `messages:[]` while still returning current binding, epoch, status, send readiness, warning, and the latest page's older cursor. Cursor requests always return `unchanged:false`.

`historyEpoch` stays stable across append-only updates. Removal, rollback, rewriting earlier messages, a read error, or cache recreation rotates it. Android keeps previously loaded pages during ordinary appends, resets them when the epoch changes, and clears the active conversation and draft when binding changes. Drafts and delivery uncertainty are retained in memory under the exact profile, pane, and binding, and restored only after an authoritative read confirms that same binding again. This store holds at most 16 entries and 512 KiB; eviction or app process death loses the retained drafts. Poll only the selected foreground chat, once per second; do not replay submissions during reconnects. The bridge shares a cached read per binding, refreshes no more than once per second, retains at most 32 bindings, and bounds total serialized normalized cache data to 64 MiB. Eviction invalidates retained history on the next read through a new epoch.

The configured local Codex executable starts with `app-server --listen stdio://`, using the selected Codex home and saved command search path. The adapter permits only `initialize`, `initialized`, and `thread/read` with `includeTurns:true`. It never starts, resumes, forks, subscribes to, interrupts, or sends a turn to a Codex agent. Saved Legacy and Paginated histories are normalized by Codex itself; Paginated threads require their existing projection database in the same Codex home. Initialization can maintain Codex's local metadata/database. This is a saved-history view: completed persisted message updates appear when available; token streaming and inline approval handling are not provided. Herdr remains the authority for the running pane's status because the reader's own runtime status describes its separate process.

Each saved-history RPC, including shared-channel wait and initialization when needed, has a 10-second deadline and a 32 MiB JSON response-line limit. Failures return controlled `503` explanations without raw diagnostics or paths. Pages are limited to 1 MiB including JSON encoding, so large messages reduce page size. Individual message text retains up to 256 KiB UTF-8; larger text carries a visible truncation marker and a page warning. C0/DEL terminal controls are removed from displayed text except tab/newline. Reader stderr is discarded, and shutdown or a failed/cancelled read terminates and reaps the sidecar, with the existing two-second termination grace period. There is no direct JSONL parser fallback; Terminal remains available.

`POST /v1/panes/{paneId}/messages` accepts exactly `{"binding":"<currentBinding>","text":"First line\nSecond line"}` and returns `{"ok":true,"warning":null}` after submission is confirmed. Text must be nonblank, at most 16,384 code points and 32,768 UTF-8 bytes, and contain no C0/DEL controls except newline, carriage return, and tab. Existing 64 KiB body and ten-second upload limits apply. Before sending, the bridge requires a successful conversation read, the same binding, canonical Codex metadata, `launch_pending` false or omitted, and status `idle`, `done`, or negotiated `working`. A present `interactive_ready` must be `true`; explicit false or malformed readiness is rejected. Stock Herdr 0.9.3 omits this managed-only field for ordinary manually launched Codex panes, which can send when the other checks pass. Agent `name` is a display label, including after a rename, and does not determine whether readiness is required. Otherwise the bridge returns `409` without input. Optional `canSendReason` explains disabled submission. Android also disables Send while loading, sending, disconnected, in an error state, or awaiting review of uncertain delivery. Blocked chats offer Terminal for interactive input; working chats use a quiet static caption.

Android 0.5.1 sends `X-Herdr-Chat-Working-Input: 1` on both conversation GETs and message POSTs. The companion permits working-state input only when that exact header value is present; without it, chat history remains readable and idle/done input remains available, while working pages return `canSend:false`. This preserves older phones whose conversation validators do not accept working-state send readiness. The capability is informative: Android still trusts a valid `canSend:true` from a compatible server that omits it. An updated phone on an older host displays an upgrade hint when working input is unavailable and the capability is absent.

Submission calls Herdr's public `agent.prompt` exactly once with explicit pane target and literal multiline text. It never falls back to shell input or a Codex app-server turn. Herdr checks that a known agent still owns the foreground runtime and queues text followed by its normal submission boundary. For Codex this inserts the text and presses Enter, which [updates the active turn while it is working](https://learn.chatgpt.com/docs/cli/reference#interactive-shortcuts); it does not promise Tab-style queuing for the next turn. Herdr 0.9.3 has no expected-thread/terminal parameter, so bridge preflight checking is not atomic with desktop activity. A post-submission binding check can return `ok:true` with a warning to inspect Terminal when the target changed or could not be checked. Transport failures and post-dispatch queue or PTY errors return `503` because some input may already have reached the agent. Android marks delivery as uncertain, never automatically retries, and never presents a draft as a confirmed persisted message. Inspect the conversation or Terminal before sending again.

Conversation reads and sends use the existing owner/device bearer policy and selected-session scope. Revoked devices cannot make further chat requests. No extra filesystem or arbitrary-thread browsing endpoint is exposed. Field allowlisting excludes internal metadata; message prose is still the user's authenticated conversation and is not automatically scrubbed for secrets.

Persist server profiles locally, with device/advanced manual tokens encrypted by Android Keystore; disable app backup. QR scanning uses a native CameraX preview and offline ZXing decoder. Request camera permission only when scanning is chosen; offer complete-JSON paste when scanning is unavailable or permission is denied. Do not save or upload camera images. Invitation payloads and pending pairing requests remain in memory, outside saved-instance state, logs, and generated string representations. Require explicit host review and confirmation. Cancel an in-flight pairing request when dismissed or when the app leaves the foreground.

**Choose QR screenshot** opens Android's system photo picker, with its document-picker fallback. It requests neither camera nor broad media/storage access. Only the selected image is read locally; no URI grant, image, or invitation is persisted or uploaded. Input is bounded to 20 MiB encoded and 32,768 pixels per original dimension, then downsampled to at most 2,048 pixels per decoded dimension. RGB decoding handles rotated screenshots, inverted codes, and surrounding interface content. Cancellation returns to pairing without a connection; unreadable, invalid, or expired codes show actionable messages. A decoded invitation enters the same host-review and explicit-confirmation flow as scanning and paste.

On Android 17/API 37 and later, request `android.permission.ACCESS_LOCAL_NETWORK` when the user chooses to connect or pair. Do not start HTTP requests or observers before it is granted. If it is denied or later revoked, stop network work and show an actionable permission error. The app uses the user's existing Tailscale connection; it does not manage the VPN or receive Tailscale credentials.

HTTPS is required except loopback and numeric Tailscale addresses (100.64.0.0/10 and fd7a:115c:a1e0::/48) explicitly identified as private development connections. Bearer-authenticated commands and observers never fall back to unauthenticated requests. Refresh snapshots after reconnecting and while the dashboard is open. Reconnect terminal observers with bounded backoff; display connection state and errors. Cancel observers when their screen leaves the foreground. Never replay a command on reconnect.

Current scope: QR/manual server connection settings, workspace and tab navigation, agent status, native saved Codex chat with older history and explicit message submission, native ANSI terminal rendering, direct native typing, reviewed Compose/paste, extra keys, themes/zoom/focus, reusable snippets, bounded Output history, pane switching, and session search. There is no WebView or PWA wrapper. Background push notifications and SSH key authentication are future work.
