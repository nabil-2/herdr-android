# Herdr Android companion protocol v1

The Android app is a native Kotlin/Jetpack Compose client. A small Python service runs as the same user as the existing Herdr daemon. Connect the phone and host through Tailscale before using the app. Herdr itself remains unmodified.

All `/v1` HTTP requests and WebSocket handshakes require `Authorization: Bearer <token>`, except `POST /v1/pair`, which authenticates a one-use invitation code in its JSON body. Accepted bearer credentials are the host's owner token or an active paired device token. Both grant control over the entire selected Herdr session. Use an HTTPS URL provided by Tailscale Serve; loopback HTTP is supported for local development. Never put credentials in URLs or logs.

## Pairing invitation and device credentials

Managed host setup runs one companion per selected Herdr socket and exposes its loopback listener through private Tailscale Serve. The host CLI creates a QR invitation locally, using Segno, and prints it as a QR code or complete JSON with `herdr-pocket pair --plain`. The payload is a flat JSON object with exactly these fields:

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
- `GET /v1/health`: `{ "service": "herdr-android-bridge", "protocol": 1, "herdrVersion": "0.9.3", "pairing": true }`. The Boolean indicates whether a pairing registry is configured. This reads a live Herdr snapshot and returns `503` if the daemon is unavailable; it is not merely a check that the HTTP process is running.
- `GET /v1/snapshot`: `{ "herdrVersion": "0.9.3", "workspaces": [...], "tabs": [...], "panes": [...] }`.
- A workspace is `{ "id": "w1", "label": "project", "cwd": "/path/to/project" }`. Herdr's workspace object has no directory field, so the bridge derives this from a pane in its active tab, falling back to another pane in the workspace. Unknown directories are empty strings.
- A tab is `{ "id": "w1:t1", "workspaceId": "w1", "label": "main" }`.
- A pane is `{ "id": "w1:p1", "workspaceId": "w1", "tabId": "w1:t1", "title": "Claude Code", "cwd": "/path/to/project", "agent": "claude", "status": "working" }`. `agent` may be null; status is `idle`, `working`, `blocked`, `done`, or `unknown`. Pane directories prefer Herdr's `foreground_cwd`, then `cwd`, then an empty string.
- `POST /v1/panes/{paneId}/input`: `{ "text": "hello", "keys": ["enter"] }`; text and keys are optional individually, but at least one must be nonempty. Sends literal input to the explicitly selected pane, using Herdr's atomic input API. Returns `{ "ok": true }`. Do not silently retry writes.
- `POST /v1/workspaces`: `{ "cwd": "/path/to/project", "label": "project" }`; returns `201` and `{ "id": "w2" }`. Creates a workspace with a shell using Herdr's public API and `focus: false`. Users can launch any agent from its terminal.
- Errors are non-2xx JSON `{ "detail": "Readable explanation" }`. `401` means a rejected token or pairing code, `400` means an invalid target/key/directory or terminal geometry, `422` means an invalid request body, `413` means the body is too large, `408` means the body upload timed out, `503` means Herdr is unavailable, an incompatible response, or unavailable pairing storage, and `409` indicates a missing or conflicting target or another rejected operation. Errors do not echo credentials, submitted text, or raw CLI diagnostics.

## Command limits and target selection

HTTP request bodies are limited to 65,536 bytes, including JSON encoding, and must finish arriving within 10 seconds. Input text is limited to 16,384 Unicode code points and 32,768 UTF-8 bytes, with no NUL characters. A command accepts at most 32 keys, each 1–32 ASCII characters with no whitespace. Herdr validates the complete logical key list before writing any text or keys. The Android toolbar uses `enter`, `esc`, `tab`, `up`, `down`, `left`, `right`, and `ctrl+c`, verified against Herdr 0.9.3.

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

## Android behavior

Persist server profiles locally, with device/advanced manual tokens encrypted by Android Keystore; disable app backup. QR scanning uses a native CameraX preview and offline ZXing decoder. Request camera permission only when scanning is chosen; offer complete-JSON paste when scanning is unavailable or permission is denied. Do not save or upload camera images. Invitation payloads and pending pairing requests remain in memory, outside saved-instance state, logs, and generated string representations. Require explicit host review and confirmation. Cancel an in-flight pairing request when dismissed or when the app leaves the foreground.

On Android 17/API 37 and later, request `android.permission.ACCESS_LOCAL_NETWORK` when the user chooses to connect or pair. Do not start HTTP requests or observers before it is granted. If it is denied or later revoked, stop network work and show an actionable permission error. The app uses the user's existing Tailscale connection; it does not manage the VPN or receive Tailscale credentials.

HTTPS is required except loopback and numeric Tailscale addresses (100.64.0.0/10 and fd7a:115c:a1e0::/48) explicitly identified as private development connections. Bearer-authenticated commands and observers never fall back to unauthenticated requests. Refresh snapshots after reconnecting and while the dashboard is open. Reconnect terminal observers with bounded backoff; display connection state and errors. Cancel observers when their screen leaves the foreground. Never replay a command on reconnect.

Current scope: QR/manual server connection settings, workspace and tab navigation, agent status, native ANSI terminal rendering, a command composer with explicit send, and a special-key toolbar. There is no WebView or PWA wrapper. Background push notifications and SSH key authentication are future work.
