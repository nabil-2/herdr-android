# Codex conversation identity

Codex 0.162 can run several terminals through one shared background app-server. That server inherits the environment of the terminal that started it. Herdr's original SessionStart helper uses the inherited `HERDR_PANE_ID`, so a hook for another frontend can update the wrong pane's session ID.

New Codex chats created by Herdr Pocket use `codex --no-daemon`. This keeps the session and hooks in their own terminal. Pocket also upgrades only its exact patch of Herdr's pinned official helper: on Linux, hooks whose nearest Codex ancestor is `app-server` are ignored. Version 0.7.1 additionally requires that ancestor to match the reported pane's actual foreground process group and controlling terminal, using a bounded read-only Herdr query and owned process/socket checks. Stale inherited pane variables cannot redirect a report into another tab. Custom helper contents and other hook registrations are preserved. The updated phone requires the Linux-only `codex-create-isolated` capability for new chats; existing macOS chat and terminal access remain available.

For an existing shared-daemon terminal, open Chat and choose the loaded conversation you want to control. Existing terminals and conversations continue running. The choice is explicit because Codex's public API does not report which thread each TUI is displaying; Pocket does not infer that association from a recently updated file or the daemon's inherited environment.

Choices contain primary interactive conversation IDs, saved names, creation times and runtime status. Message previews and subagent histories are excluded. A CLI can resume a conversation created in VS Code or the desktop app, retaining its original `source: vscode`, so both `cli` and `vscode` origins are eligible. The selected thread must be loaded, non-ephemeral, have its own root session ID, have no parent thread and advertise direct input support.

Each selection is scoped to the paired phone's credential and pane, exact terminal, Herdr socket inode, foreground Codex process and start time, daemon process and start time, owned socket inode, Codex home and Linux socket peer relationship. Choice tokens expire after five minutes and are accepted once. A companion restart or changed runtime requires choosing again. Listing scans at most 256 loaded IDs over eight pages and displays at most 32 eligible choices within an eight-second daemon deadline.

Selected chat reads use a separate connection to the existing daemon. A Send starts an idle turn with `turn/start` or steers a working turn with `turn/steer` and its exact `expectedTurnId`. Images use official `localImage` input. Existing frontend listeners remain attached and handle approval or tool questions in Terminal. Pocket never answers a server request automatically. A failed or missing acknowledgement is delivery uncertainty: Pocket does not retry the write, switch thread, start another runtime or fall back to terminal input.

The normal saved-history sidecar remains read-only. It permits only initialization and `thread/read`; explicit selection does not widen that allowlist.

Primary source references, pinned to Codex 0.162:

- [`--no-daemon` TUI option](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/tui/src/cli.rs#L82) and [embedded versus shared launch choice](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/tui/src/lib.rs#L1020).
- [Thread creation origin, root session and direct-input metadata](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/app-server-protocol/src/protocol/v2/thread_data.rs#L204).
- [Loaded thread listing](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/app-server-protocol/src/protocol/v2/thread.rs#L1641) and [metadata-only read](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/app-server-protocol/src/protocol/v2/thread.rs#L1687).
- [Unix control-socket WebSocket transport](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/app-server-transport/src/transport/unix_socket.rs#L169).
- [Exact active-turn steering precondition](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/app-server-protocol/src/protocol/v2/turn.rs#L308) and [existing frontend connection preservation](https://github.com/openai/codex/blob/rust-v0.162.0/codex-rs/app-server/src/thread_state.rs#L622).
