"""Bounded newline JSON Unix socket client and read-only CLI observers."""

from __future__ import annotations

import asyncio
import base64
import binascii
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import stat
import uuid

from .runtime import external_environment


PANE_ID = re.compile(r"w[1-9][0-9]*:p[1-9][0-9]*\Z")
WORKSPACE_ID = re.compile(r"w[1-9][0-9]*\Z")
MAX_FRAME_BYTES = 2 * 1024 * 1024


class BridgeError(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


def validate_pane_id(pane_id: str) -> None:
    if len(pane_id) > 96 or not PANE_ID.fullmatch(pane_id):
        raise BridgeError(400, "Select a valid Herdr pane ID")


def validate_workspace_id(workspace_id: str) -> None:
    if len(workspace_id) > 96 or not WORKSPACE_ID.fullmatch(workspace_id):
        raise BridgeError(400, "Select a valid Herdr workspace ID")


@dataclass(frozen=True)
class CreatedTab:
    workspace_id: str
    tab_id: str
    pane_id: str
    terminal_id: str

    def public_result(self, warning: str | None = None) -> dict:
        return {"workspaceId": self.workspace_id, "tabId": self.tab_id, "paneId": self.pane_id, "warning": warning}


class HerdrClient:
    def __init__(self, socket_path: Path, executable: str = "herdr", timeout: float = 10):
        self.socket_path = socket_path
        self.executable = executable
        self.timeout = timeout

    async def call(self, method: str, params: dict) -> dict:
        """Exactly one request; mutation transport failures are never retried."""
        writer = None
        try:
            info = self.socket_path.stat()
            if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
                raise BridgeError(503, "Herdr socket must belong to the service user")
            async with asyncio.timeout(self.timeout):
                reader, writer = await asyncio.open_unix_connection(str(self.socket_path), limit=MAX_FRAME_BYTES)
                request_id = uuid.uuid4().hex
                request = {"id": request_id, "method": method, "params": params}
                writer.write(json.dumps(request, separators=(",", ":")).encode() + b"\n")
                await writer.drain()
                line = await reader.readline()
                if not line:
                    raise BridgeError(503, "Herdr disconnected; check state before retrying any input")
                response = json.loads(line)
                if not isinstance(response, dict) or response.get("id") != request_id:
                    raise BridgeError(503, "Herdr returned an incompatible response; check state before retrying any input")
                if "error" in response:
                    raise upstream_error(response["error"], prompt=method == "agent.prompt")
                result = response.get("result")
                if not isinstance(result, dict):
                    raise BridgeError(503, "Herdr returned an incompatible response; check state before retrying any input")
                return result
        except BridgeError:
            raise
        except (OSError, TimeoutError, ValueError, UnicodeError) as exc:
            raise BridgeError(503, "Herdr is unavailable; check state before retrying any input") from exc
        finally:
            if writer is not None:
                writer.close()
                try:
                    await writer.wait_closed()
                except OSError:
                    pass

    async def snapshot(self) -> dict:
        result = await self.call("session.snapshot", {})
        snapshot = result.get("snapshot")
        if not isinstance(snapshot, dict):
            raise BridgeError(503, "Herdr returned an incompatible snapshot")
        return normalize_snapshot(snapshot)

    async def agent(self, pane_id: str) -> dict:
        validate_pane_id(pane_id)
        result = await self.call("agent.get", {"target": pane_id})
        agent = result.get("agent")
        if not isinstance(agent, dict) or agent.get("pane_id") != pane_id:
            raise BridgeError(503, "Herdr returned incompatible agent metadata; use Terminal")
        return agent

    async def prompt(self, pane_id: str, text: str) -> None:
        validate_pane_id(pane_id)
        result = await self.call("agent.prompt", {"target": pane_id, "text": text})
        if result.get("type") != "agent_prompted":
            raise BridgeError(503, "Herdr could not confirm the message; inspect Terminal before retrying")

    async def send_input(self, pane_id: str, text: str, keys: list[str]) -> None:
        validate_pane_id(pane_id)
        # Both literal text and every key are validated atomically by Herdr.
        params: dict = {"pane_id": pane_id}
        if text:
            params["text"] = text
        if keys:
            params["keys"] = keys
        result = await self.call("pane.send_input", params)
        if result.get("type") != "ok":
            raise BridgeError(503, "Herdr could not confirm input; check state before retrying")

    async def create_workspace(self, cwd: str, label: str) -> str:
        result = await self.call("workspace.create", {"cwd": cwd, "label": label, "focus": False})
        workspace = result.get("workspace")
        if not isinstance(workspace, dict) or not isinstance(workspace.get("workspace_id"), str):
            raise BridgeError(503, "Herdr could not confirm workspace creation; refresh before retrying")
        return workspace["workspace_id"]

    async def create_tab(self, workspace_id: str, cwd: str | None, label: str, *, env: dict[str, str] | None = None) -> CreatedTab:
        validate_workspace_id(workspace_id)
        params = {"workspace_id": workspace_id, "label": label, "focus": False}
        if cwd is not None:
            params["cwd"] = str(Path(cwd).expanduser())
        if env:
            params["env"] = env
        result = await self.call("tab.create", params)
        tab, pane = result.get("tab"), result.get("root_pane")
        try:
            if result.get("type") != "tab_created" or not isinstance(tab, dict) or not isinstance(pane, dict):
                raise ValueError()
            tab_id, pane_id, terminal_id = tab["tab_id"], pane["pane_id"], pane["terminal_id"]
            if (tab.get("workspace_id") != workspace_id or pane.get("workspace_id") != workspace_id
                    or pane.get("tab_id") != tab_id or not isinstance(tab_id, str) or len(tab_id) > 96
                    or not re.fullmatch(re.escape(workspace_id) + r":t[1-9][0-9]*", tab_id)
                    or not isinstance(pane_id, str) or len(pane_id) > 96
                    or not re.fullmatch(re.escape(workspace_id) + r":p[1-9][0-9]*", pane_id)
                    or not isinstance(terminal_id, str) or not 1 <= len(terminal_id) <= 256):
                raise ValueError()
            return CreatedTab(workspace_id, tab_id, pane_id, terminal_id)
        except (KeyError, ValueError, TypeError) as exc:
            raise BridgeError(503, "Tab creation could not be confirmed; refresh the workspace before creating another") from exc

    async def start_codex(self, tab: CreatedTab) -> dict:
        # Only the pane returned by this tab.create operation is a launch target.
        # Herdr quotes its fixed executable/argument vector; no shell text is built here.
        await self._wait_created_shell(tab)
        name = "pocket-" + uuid.uuid4().hex[:24]
        result = await self.call("agent.start", {"name": name, "kind": "codex", "pane_id": tab.pane_id,
                                                  "args": ["--no-daemon"], "timeout_ms": 30000})
        agent = result.get("agent")
        if (result.get("type") != "agent_started" or not isinstance(agent, dict)
                or agent.get("workspace_id") != tab.workspace_id or agent.get("tab_id") != tab.tab_id
                or agent.get("pane_id") != tab.pane_id or agent.get("terminal_id") != tab.terminal_id
                or agent.get("name") != name or agent.get("agent") not in (None, "codex")):
            raise BridgeError(503, "Codex launch could not be confirmed; inspect the new terminal before creating another chat")
        return agent

    async def _wait_created_shell(self, tab: CreatedTab) -> None:
        # New tabs return before their shell has finished spawning. Observe its
        # exact terminal and foreground job before the one agent.start mutation.
        shells = {"sh", "bash", "dash", "zsh", "fish", "ksh", "mksh", "csh", "tcsh",
                  "elvish", "xonsh", "nu", "pwsh", "powershell", "cmd"}
        try:
            async with asyncio.timeout(3):
                while True:
                    pane = (await self.call("pane.get", {"pane_id": tab.pane_id})).get("pane")
                    if (not isinstance(pane, dict) or pane.get("pane_id") != tab.pane_id
                            or pane.get("workspace_id") != tab.workspace_id or pane.get("tab_id") != tab.tab_id
                            or pane.get("terminal_id") != tab.terminal_id):
                        raise BridgeError(503, "The new terminal changed before launch; inspect it before creating another chat")
                    info = (await self.call("pane.process_info", {"pane_id": tab.pane_id})).get("process_info")
                    if not isinstance(info, dict) or info.get("pane_id") != tab.pane_id:
                        raise BridgeError(503, "The new terminal's shell could not be verified; inspect it before creating another chat")
                    pid = info.get("shell_pid")
                    processes = info.get("foreground_processes")
                    if type(pid) is int and pid > 0 and info.get("foreground_process_group_id") == pid:
                        if not isinstance(processes, list) or not all(isinstance(process, dict) for process in processes):
                            raise BridgeError(503, "The new terminal returned incompatible process metadata")
                        if len(processes) == 1 and processes[0].get("pid") == pid:
                            name = processes[0].get("name")
                            if isinstance(name, str):
                                normalized = name.replace("\\", "/").rsplit("/", 1)[-1].lstrip("-").lower().removesuffix(".exe")
                                if normalized in shells:
                                    return
                    await asyncio.sleep(0.05)
        except TimeoutError as exc:
            raise BridgeError(503, "The new terminal's shell is still starting; inspect it before creating another chat") from exc

    async def send_terminal_input(self, pane_id: str, text: str) -> None:
        validate_pane_id(pane_id)
        # Herdr's send_input brackets text in paste mode. Direct keyboard input
        # must use this distinct public operation so control keys retain meaning.
        result = await self.call("pane.send_text", {"pane_id": pane_id, "text": text})
        if result.get("type") != "ok":
            raise BridgeError(503, "Input delivery was not confirmed; inspect the terminal before sending again")

    async def terminal_history(self, pane_id: str) -> dict:
        validate_pane_id(pane_id)
        result = await self.call("pane.read", {"pane_id": pane_id, "source": "recent_unwrapped",
                                              "format": "text", "lines": 1000, "strip_ansi": True, "intent": "interactive"})
        read = result.get("read")
        if (result.get("type") != "pane_read" or not isinstance(read, dict) or read.get("pane_id") != pane_id
                or read.get("source") != "recent_unwrapped" or read.get("format") != "text"
                or not isinstance(read.get("text"), str) or type(read.get("truncated")) is not bool):
            raise BridgeError(503, "Herdr returned incompatible terminal history; refresh before trying again")
        text = read["text"]
        truncated = read["truncated"]
        lines = text.splitlines(keepends=True)
        if len(lines) > 1000:
            text, truncated = "".join(lines[-1000:]), True
        try:
            encoded = text.encode("utf-8")
        except UnicodeError as exc:
            raise BridgeError(503, "Herdr returned invalid terminal history text") from exc
        if len(encoded) > 65536:
            text, truncated = encoded[-65536:].decode("utf-8", errors="ignore"), True
        return {"paneId": pane_id, "text": text, "truncated": truncated}

    async def observe(self, pane_id: str, cols: int, rows: int) -> asyncio.subprocess.Process:
        validate_pane_id(pane_id)
        # Check the selected target before spawning and always inherit this exact socket.
        snapshot = await self.snapshot()
        if not any(pane["id"] == pane_id for pane in snapshot["panes"]):
            raise BridgeError(409, "Pane is no longer available; refresh the workspace")
        env = external_environment()
        env["HERDR_SOCKET_PATH"] = str(self.socket_path)
        env.pop("HERDR_SOCKET", None)
        try:
            return await asyncio.create_subprocess_exec(
                self.executable, "terminal", "session", "observe", pane_id,
                "--cols", str(cols), "--rows", str(rows),
                env=env, stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
                limit=MAX_FRAME_BYTES,
            )
        except OSError as exc:
            raise BridgeError(503, "Herdr terminal observer could not start; check the installed CLI") from exc


def upstream_error(error: object, *, prompt: bool = False) -> BridgeError:
    # Upstream error text may contain user input: expose only controlled messages.
    code = error.get("code", "") if isinstance(error, dict) else ""
    if code in {"pane_not_found", "workspace_not_found", "tab_not_found", "target_not_found", "agent_not_found", "pane_not_running", "pane_input_disabled"}:
        return BridgeError(409, "Target is no longer available or cannot accept input; refresh the workspace")
    if code in {"invalid_params", "invalid_key", "invalid_keys", "invalid_request", "invalid_cwd", "cwd_not_found", "invalid_argument"}:
        return BridgeError(400, "Herdr rejected the input or working directory; check the values")
    if code in {"unknown_method", "method_not_found", "unsupported_method"}:
        return BridgeError(503, "Herdr does not support this operation; install a compatible version")
    if code == "pane_send_failed":
        return BridgeError(503, "Input delivery was not confirmed; inspect the terminal before sending again")
    if prompt:
        if code in {"agent_blocked", "agent_not_ready", "ambiguous_agent_target"}:
            return BridgeError(409, "The selected agent cannot accept a prompt; inspect Terminal before sending")
        if code == "empty_agent_prompt":
            return BridgeError(400, "Enter a nonempty agent message")
        # Queue/PTY failures can occur after part of the prompt reached the agent.
        # A conflict would incorrectly tell the phone that delivery was rejected.
        return BridgeError(503, "Message delivery was not confirmed; inspect the conversation or Terminal before sending again")
    return BridgeError(409, "Herdr rejected the operation; refresh the workspace before retrying")


def normalize_snapshot(snapshot: dict) -> dict:
    try:
        panes = []
        for pane in snapshot["panes"]:
            status = pane.get("agent_status", "unknown")
            panes.append({
                "id": pane["pane_id"], "workspaceId": pane["workspace_id"], "tabId": pane["tab_id"],
                "title": pane.get("label") or pane.get("title") or pane.get("terminal_title_stripped") or pane.get("display_agent") or pane.get("agent") or "Terminal",
                "cwd": pane.get("foreground_cwd") or pane.get("cwd") or "",
                "agent": pane.get("display_agent") or pane.get("agent"),
                "status": status if status in {"idle", "working", "blocked", "done", "unknown"} else "unknown",
            })
            if pane.get("agent") == "codex":
                available = canonical_codex_session(pane) is not None
                panes[-1]["conversation"] = {
                    "provider": "codex", "available": available,
                    "reason": None if available else "Codex has not reported a saved session; use Terminal",
                }
        workspaces = []
        for workspace in snapshot["workspaces"]:
            candidates = [pane for pane in panes if pane["workspaceId"] == workspace["workspace_id"]]
            candidates.sort(key=lambda pane: pane["tabId"] != workspace.get("active_tab_id"))
            workspaces.append({"id": workspace["workspace_id"], "label": workspace["label"], "cwd": next((pane["cwd"] for pane in candidates if pane["cwd"]), "")})
        tabs = [{"id": tab["tab_id"], "workspaceId": tab["workspace_id"], "label": tab["label"]} for tab in snapshot["tabs"]]
        return {"herdrVersion": snapshot["version"], "workspaces": workspaces, "tabs": tabs, "panes": panes}
    except (KeyError, TypeError, AttributeError) as exc:
        raise BridgeError(503, "Herdr returned an incompatible snapshot") from exc


def canonical_codex_session(agent: dict) -> str | None:
    """Use only the official integration's explicit native ID, never cwd/title."""
    session = agent.get("agent_session")
    if agent.get("agent") != "codex" or not isinstance(session, dict):
        return None
    if (session.get("source"), session.get("agent"), session.get("kind")) != ("herdr:codex", "codex", "id"):
        return None
    value = session.get("value")
    if not isinstance(value, str) or len(value) != 36:
        return None
    try:
        return value if str(uuid.UUID(value)) == value else None
    except ValueError:
        return None


def validate_terminal_frame(line: bytes) -> str:
    """Validate 0.9.3 CLI envelopes, then preserve their exact JSON text."""
    if len(line) > MAX_FRAME_BYTES:
        raise BridgeError(503, "Herdr terminal frame exceeded the size limit")
    try:
        raw = line.decode("utf-8")
        frame = json.loads(raw)
        if not isinstance(frame, dict):
            raise ValueError("object required")
        if frame.get("type") == "terminal.closed":
            if not isinstance(frame.get("reason"), str):
                raise ValueError("reason required")
        elif frame.get("type") == "terminal.frame":
            if frame.get("encoding") != "ansi" or not isinstance(frame.get("bytes"), str):
                raise ValueError("ANSI frame required")
            if not isinstance(frame.get("full"), bool):
                raise ValueError("full required")
            for name in ("width", "height", "seq"):
                if type(frame.get(name)) is not int or frame[name] < (0 if name == "seq" else 1):
                    raise ValueError("dimensions and sequence required")
            # The observer API bounds viewport geometry; permit host frame geometry as well.
            if frame["width"] > 1000 or frame["height"] > 1000:
                raise ValueError("frame dimensions too large")
            base64.b64decode(frame["bytes"], validate=True)
        else:
            raise ValueError("unexpected frame")
        return raw.rstrip("\r\n")
    except (ValueError, UnicodeError, binascii.Error) as exc:
        raise BridgeError(503, "Herdr returned an invalid terminal frame") from exc


async def stop_observer(process: asyncio.subprocess.Process) -> None:
    async def drain(stream: asyncio.StreamReader | None) -> None:
        if stream is not None:
            while await stream.read(65536):
                pass

    # Discard the remaining pipe contents as well as reaping the process. Leaving
    # unread streams behind can keep asyncio transports alive past shutdown.
    drains = [asyncio.create_task(drain(process.stdout)), asyncio.create_task(drain(process.stderr))]
    if process.returncode is None:
        try:
            process.terminate()
        except ProcessLookupError:
            pass
    try:
        await asyncio.wait_for(process.wait(), timeout=2)
    except TimeoutError:
        try:
            process.kill()
        except ProcessLookupError:
            pass
        await process.wait()
    finally:
        try:
            await asyncio.wait_for(asyncio.gather(*drains), timeout=2)
        except TimeoutError:
            for task in drains:
                task.cancel()
            await asyncio.gather(*drains, return_exceptions=True)
