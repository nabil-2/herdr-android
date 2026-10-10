"""HTTP commands and read-only terminal streams for protocol v1."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, suppress
import hmac
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Annotated, Literal

import anyio
from fastapi import FastAPI, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from . import __version__
from .herdr import BridgeError, HerdrClient, stop_observer, validate_pane_id, validate_terminal_frame, validate_workspace_id
from .codex_integration import CodexIntegrationResult, ensure_codex_integration
from .pairing import PairingError, PairingStore, valid_name
from .codex import CodexReader, Conversations
from .codex_identity import DaemonConversations
from .attachments import ATTACHMENT_ID, MAX_ATTACHMENT_BYTES, AttachmentStore, DeliveryScope, valid_attachment_name

MAX_BODY_BYTES = 64 * 1024


def authorized(header: str | None, token: str, pairing_store: PairingStore | None = None) -> bool:
    if header is None:
        return False
    scheme, separator, supplied = header.partition(" ")
    if scheme.lower() != "bearer" or not separator or not supplied or len(supplied) > 4096:
        return False
    # Byte comparison accepts only ASCII credentials, without timing-dependent equality.
    try:
        if hmac.compare_digest(supplied.encode("ascii"), token.encode("ascii")):
            return True
    except UnicodeError:
        return False
    return pairing_store is not None and pairing_store.authorized(supplied)


class AuthAndBodyLimit:
    def __init__(self, app, token: str, pairing_store: PairingStore | None = None):
        self.app = app
        self.token = token
        self.pairing_store = pairing_store

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not (scope["path"] == "/v1" or scope["path"].startswith("/v1/")):
            await self.app(scope, receive, send)
            return
        headers = dict(scope["headers"])
        header = headers.get(b"authorization", b"").decode("latin1")
        # Only the exact exchange endpoint accepts a code instead of a bearer.
        # It still passes through the identical bounded-body reader below.
        pairing_request = scope["path"] == "/v1/pair" and scope["method"] == "POST"
        if not pairing_request and not await asyncio.to_thread(authorized, header, self.token, self.pairing_store):
            await JSONResponse({"detail": "Invalid or missing bearer token"}, 401, headers={"WWW-Authenticate": "Bearer"})(scope, receive, send)
            return
        if not pairing_request:
            supplied = header.partition(" ")[2]
            scope.setdefault("state", {})["attachment_owner"] = hashlib.sha256(supplied.encode("ascii")).hexdigest()
        attachment_upload = (scope["method"] == "POST" and re.fullmatch(
            r"/v1/panes/w[1-9][0-9]*:p[1-9][0-9]*/attachments", scope["path"]) is not None)
        if attachment_upload:
            try:
                raw_length = headers.get(b"content-length")
                if raw_length is not None and int(raw_length) > MAX_ATTACHMENT_BYTES:
                    await JSONResponse({"detail": "Attachments must fit within 16 MiB"}, 413)(scope, receive, send)
                    return
            except ValueError:
                await JSONResponse({"detail": "Invalid attachment length"}, 400)(scope, receive, send)
                return
            received = 0

            async def limited_receive():
                nonlocal received
                message = await receive()
                received += len(message.get("body", b""))
                if received > MAX_ATTACHMENT_BYTES:
                    raise BridgeError(413, "Attachments must fit within 16 MiB")
                return message

            # Only the exact upload endpoint streams its larger body. All JSON
            # commands retain the original 64 KiB limit and bounded replay.
            await self.app(scope, limited_receive, send)
            return
        body = bytearray()
        try:
            async with asyncio.timeout(10):
                while True:
                    message = await receive()
                    if message["type"] == "http.disconnect":
                        return
                    body.extend(message.get("body", b""))
                    if len(body) > MAX_BODY_BYTES:
                        await JSONResponse({"detail": "Request body exceeds 64 KiB"}, 413)(scope, receive, send)
                        return
                    if not message.get("more_body", False):
                        break
        except TimeoutError:
            await JSONResponse({"detail": "Request body timed out"}, 408)(scope, receive, send)
            return
        delivered = False

        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


class InputRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    text: str = Field(default="", max_length=16384)
    keys: list[Annotated[str, Field(min_length=1, max_length=32)]] = Field(default_factory=list, max_length=32)

    @field_validator("text")
    @classmethod
    def bounded_text(cls, value: str) -> str:
        if "\x00" in value or len(value.encode("utf-8")) > 32768:
            raise ValueError("Text must not contain NUL and must fit within 32 KiB")
        return value

    @field_validator("keys")
    @classmethod
    def printable_keys(cls, keys: list[str]) -> list[str]:
        if any(not key.isascii() or any(c.isspace() or ord(c) < 33 for c in key) for key in keys):
            raise ValueError("Keys must be printable logical key names")
        return keys

    @model_validator(mode="after")
    def nonempty(self):
        if not self.text and not self.keys:
            raise ValueError("Provide text or keys")
        return self


class CodexSelectionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: str = Field(min_length=36, max_length=36, pattern=r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    selectionToken: str = Field(min_length=43, max_length=43, pattern=r"^[A-Za-z0-9_-]{43}$")


class TerminalInputRequest(BaseModel):
    """Raw keyboard UTF-8, including control keys; never bracketed as pasted text."""
    model_config = ConfigDict(extra="forbid", strict=True)
    text: str = Field(min_length=1, max_length=16384)

    @field_validator("text")
    @classmethod
    def bounded_keyboard_text(cls, value: str) -> str:
        if len(value.encode("utf-8")) > 32768:
            raise ValueError("Terminal input must fit within 32 KiB of UTF-8")
        return value


class WorkspaceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    cwd: str = Field(min_length=1, max_length=4096)
    label: str = Field(min_length=1, max_length=128)

    @field_validator("cwd")
    @classmethod
    def absolute_path(cls, value: str) -> str:
        if "\x00" in value or not (value.startswith("/") or value.startswith("~/") or value == "~"):
            raise ValueError("Working directory must be an absolute or home-relative path")
        return value

    @field_validator("label")
    @classmethod
    def readable_label(cls, value: str) -> str:
        if not value.strip() or any(ord(c) < 32 for c in value):
            raise ValueError("Label must be nonempty text")
        return value


class TabRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    kind: Literal["terminal", "codex"]
    label: str = Field(min_length=1, max_length=128)
    cwd: str | None = Field(default=None, min_length=1, max_length=4096)

    @field_validator("cwd")
    @classmethod
    def scoped_directory(cls, value: str | None) -> str | None:
        if value is not None and (any(ord(c) < 32 or ord(c) == 127 for c in value)
                                  or not (value.startswith("/") or value.startswith("~/") or value == "~")):
            raise ValueError("Working directory must be an absolute or home-relative path without controls")
        return value

    @field_validator("label")
    @classmethod
    def readable_label(cls, value: str) -> str:
        if not value.strip() or any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError("Label must be nonempty text without controls")
        return value

class PairRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    # Incorrect code values receive the same 401 as used/expired codes. JSON
    # shape/type errors and excessive field lengths are invalid requests (422).
    code: str = Field(max_length=128)
    deviceName: str = Field(min_length=1, max_length=100)

    @field_validator("deviceName")
    @classmethod
    def readable_name(cls, value: str) -> str:
        if not valid_name(value):
            raise ValueError("Device name must be nonblank and contain no controls")
        return value


class MessageRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    binding: str = Field(pattern=r"^[A-Za-z0-9_-]{43}$")
    text: str = Field(default="", max_length=16384)
    attachmentIds: list[Annotated[str, Field(pattern=r"^[a-f0-9]{32}$")]] = Field(default_factory=list, max_length=8)

    @field_validator("text")
    @classmethod
    def literal_message(cls, value: str) -> str:
        if len(value.encode("utf-8")) > 32768 or any((ord(c) < 32 and c not in "\n\r\t") or ord(c) == 127 for c in value):
            raise ValueError("Message must be literal text within 32 KiB")
        return value

    @model_validator(mode="after")
    def nonempty(self):
        if (not self.text.strip() and not self.attachmentIds) or len(set(self.attachmentIds)) != len(self.attachmentIds):
            raise ValueError("Provide message text or distinct attachments")
        return self


def create_app(client: HerdrClient, token: str, pairing_store: PairingStore | None = None, *,
               codex_executable: str | None = None, codex_home: Path | None = None,
               codex_path: str | None = None, prepare_codex_integration: bool = False,
               attachment_dir: Path | None = None) -> FastAPI:
    if not 32 <= len(token) <= 4096 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("Use a generated bearer token of at least 32 ASCII characters")
    reader = CodexReader(codex_executable, codex_home, codex_path)
    conversations = Conversations(client, reader)
    conversations.daemon = DaemonConversations(client, reader.home) if reader.enabled else None
    attachments = AttachmentStore(attachment_dir)
    conversations.attachments = attachments

    async def prepare_integration() -> CodexIntegrationResult:
        try:
            result = await asyncio.to_thread(ensure_codex_integration, client.executable, reader.home)
        except Exception:
            result = CodexIntegrationResult(ready=False, advisory="Could not prepare Herdr's Codex integration.")
        app.state.codex_integration_result = result
        return result

    def integration_task() -> asyncio.Task:
        task = app.state.codex_integration_task
        # Each deliberate creation rechecks the installed hooks, while requests
        # arriving during preparation share that work. Never retry topology.
        if task is None or task.done():
            task = asyncio.create_task(prepare_integration())
            app.state.codex_integration_task = task
        return task

    async def require_integration() -> None:
        try:
            async with asyncio.timeout(8):
                result = await asyncio.shield(integration_task())
        except TimeoutError as exc:
            raise BridgeError(503, "Codex integration is still being prepared; wait a moment and try again") from exc
        if not result.ready:
            raise BridgeError(503, (result.advisory or "Codex integration is unavailable.")
                              + " Run herdr-pocket setup on the host before creating a Codex chat.")

    @asynccontextmanager
    async def lifespan(_app):
        if prepare_codex_integration and reader.enabled:
            integration_task()
        async def maintain_attachments():
            while True:
                with suppress(BridgeError, OSError):
                    await attachments.cleanup()
                await asyncio.sleep(60)
        attachment_maintenance = asyncio.create_task(maintain_attachments())
        try:
            yield
        finally:
            with anyio.CancelScope(shield=True):
                attachment_maintenance.cancel()
                await asyncio.gather(attachment_maintenance, return_exceptions=True)
                await reader.close()
                await attachments.close()
                task = app.state.codex_integration_task
                if task is not None:
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)

    app = FastAPI(title="Herdr Android Bridge", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.codex_reader = reader
    app.state.conversations = conversations
    app.state.attachments = attachments
    app.state.codex_integration_task = None
    app.state.codex_integration_result = None
    app.add_middleware(AuthAndBodyLimit, token=token, pairing_store=pairing_store)

    @app.exception_handler(BridgeError)
    async def bridge_error(_request, exc: BridgeError):
        return JSONResponse({"detail": exc.detail}, exc.status)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request, _exc):
        # Pydantic's default includes submitted text; keep error responses free of it.
        return JSONResponse({"detail": "Invalid request: check text, key names, directory, device name and size limits"}, 422)

    @app.post("/v1/pair")
    async def pair(request: PairRequest):
        if pairing_store is None:
            raise PairingError(503, "Pairing is not configured on this bridge; enable pairing on the host")
        await asyncio.to_thread(pairing_store.check_challenge, request.code)
        # A stopped or incompatible Herdr must not consume the one-time code.
        await client.snapshot()
        return await asyncio.to_thread(pairing_store.exchange, request.code, request.deviceName)

    @app.get("/v1/health")
    async def health():
        snapshot = await client.snapshot()
        result = {"service": "herdr-android-bridge", "protocol": 1, "companionVersion": __version__, "herdrVersion": snapshot["herdrVersion"], "pairing": pairing_store is not None}
        result["capabilities"] = ["terminal-input", "terminal-history", "workspace-tabs"]
        if reader.enabled:
            result["capabilities"].extend(["codex-chat", "codex-chat-working-input", "codex-chat-fresh-input", "codex-create"])
            if sys.platform.startswith("linux"):
                result["capabilities"].append("codex-create-isolated")
            result["capabilities"].extend(["codex-chat-attachments", "codex-chat-selection"])
        integration = app.state.codex_integration_result
        if integration is not None and integration.advisory:
            result["codexIntegrationAdvisory"] = integration.advisory
        return result

    @app.get("/v1/snapshot")
    async def snapshot(request: Request):
        snapshot = await client.snapshot()
        if not reader.enabled:
            for pane in snapshot["panes"]:
                if "conversation" in pane:
                    pane["conversation"] = {"provider": "codex", "available": False, "reason": "Codex conversation reader is not configured; use Terminal"}
        elif conversations.daemon is not None:
            await conversations.daemon.decorate_snapshot(snapshot, request.state.attachment_owner)
        return snapshot

    @app.get("/v1/panes/{pane_id}/codex-conversations")
    async def codex_conversations(pane_id: str, request: Request):
        validate_pane_id(pane_id)
        if conversations.daemon is None:
            raise BridgeError(503, "Codex conversation selection is unavailable; update the host companion")
        return await conversations.daemon.list(pane_id, request.state.attachment_owner)

    @app.post("/v1/panes/{pane_id}/codex-conversation")
    async def codex_conversation(pane_id: str, selection: CodexSelectionRequest, request: Request):
        validate_pane_id(pane_id)
        if conversations.daemon is None:
            raise BridgeError(503, "Codex conversation selection is unavailable; update the host companion")
        return await conversations.daemon.select(pane_id, request.state.attachment_owner, selection.id, selection.selectionToken)

    @app.get("/v1/panes/{pane_id}/conversation")
    async def conversation(pane_id: str, request: Request, cursor: Annotated[str | None, Query(max_length=2048)] = None,
                           revision: Annotated[str | None, Query(max_length=256)] = None):
        return await conversations.page(pane_id, cursor, revision,
                                        allow_working=request.headers.get("X-Herdr-Chat-Working-Input") == "1",
                                        allow_fresh=request.headers.get("X-Herdr-Chat-Fresh-Input") == "1",
                                        owner=request.state.attachment_owner)

    @app.post("/v1/panes/{pane_id}/messages")
    async def message(pane_id: str, request: MessageRequest, http_request: Request):
        return await conversations.send(pane_id, request.binding, request.text,
                                        allow_working=http_request.headers.get("X-Herdr-Chat-Working-Input") == "1",
                                        allow_fresh=http_request.headers.get("X-Herdr-Chat-Fresh-Input") == "1",
                                        attachment_ids=request.attachmentIds, attachments=attachments,
                                        attachment_owner=http_request.state.attachment_owner)

    async def attachment_scope(pane_id: str, request: Request) -> tuple[str, str, str]:
        binding = request.headers.get("X-Herdr-Chat-Binding", "")
        if len(binding) != 43 or not all(c.isascii() and (c.isalnum() or c in "_-") for c in binding):
            raise BridgeError(400, "Refresh the conversation before selecting an attachment")
        if not reader.enabled:
            raise BridgeError(503, "Codex conversation reader is not configured; use Terminal")
        agent, thread_id, current = await conversations.target(pane_id, owner=request.state.attachment_owner)
        if not hmac.compare_digest(binding, current):
            raise BridgeError(409, "Pane or conversation changed; refresh before selecting an attachment")
        cache = conversations.caches.get(binding)
        if cache is None or cache.error is not None:
            raise BridgeError(409, "Read the conversation successfully before selecting an attachment")
        await attachments.register(pane_id, binding, thread_id,
                                   conversations.attachment_runtime(pane_id, agent, thread_id))
        return binding, request.state.attachment_owner, thread_id

    @app.post("/v1/panes/{pane_id}/attachments", status_code=201)
    async def upload_attachment(pane_id: str, request: Request, kind: Literal["image", "file"],
                                name: Annotated[str, Query(min_length=1, max_length=160)]):
        if request.headers.get("content-type", "").partition(";")[0].strip().lower() != "application/octet-stream":
            raise BridgeError(415, "Upload attachment bytes as application/octet-stream")
        if not valid_attachment_name(name):
            raise BridgeError(400, "Choose an attachment with a valid basename")
        binding, owner, thread_id = await attachment_scope(pane_id, request)

        async def verify_scope():
            agent, current_thread, current = await conversations.target(pane_id, owner=owner)
            if not hmac.compare_digest(binding, current) or current_thread != thread_id:
                raise BridgeError(409, "Pane or conversation changed; refresh before selecting an attachment")
            cache = conversations.caches.get(binding)
            if cache is None or cache.error is not None:
                raise BridgeError(409, "Read the conversation successfully before selecting an attachment")
            return DeliveryScope(pane_id, current_thread,
                                 conversations.attachment_runtime(pane_id, agent, current_thread))

        return await attachments.upload(pane_id, binding, owner, name, kind, request.stream(),
                                        thread_id=thread_id, verify_scope=verify_scope)

    @app.delete("/v1/panes/{pane_id}/attachments/{attachment_id}")
    async def delete_attachment(pane_id: str, attachment_id: str, request: Request):
        if not ATTACHMENT_ID.fullmatch(attachment_id):
            raise BridgeError(400, "Select a valid attachment")
        binding, owner, _ = await attachment_scope(pane_id, request)
        await attachments.delete(attachment_id, pane_id, binding, owner)
        return {"ok": True}

    @app.post("/v1/panes/{pane_id}/attachments/acknowledge")
    async def acknowledge_attachment_input(pane_id: str, request: Request):
        # An explicit phone action after inspecting/clearing the Terminal composer.
        # Polling saved messages never clears an uncertain paste automatically.
        binding, owner, _ = await attachment_scope(pane_id, request)
        await attachments.confirmed(pane_id, binding, owner)
        return {"ok": True}

    @app.post("/v1/panes/{pane_id}/input")
    async def send_input(pane_id: str, request: InputRequest):
        await client.send_input(pane_id, request.text, request.keys)
        return {"ok": True}

    @app.post("/v1/panes/{pane_id}/terminal-input")
    async def terminal_input(pane_id: str, request: TerminalInputRequest):
        await client.send_terminal_input(pane_id, request.text)
        return {"ok": True}

    @app.get("/v1/panes/{pane_id}/scrollback")
    async def terminal_history(pane_id: str):
        return await client.terminal_history(pane_id)

    @app.post("/v1/workspaces", status_code=201)
    async def create_workspace(request: WorkspaceRequest):
        return {"id": await client.create_workspace(request.cwd, request.label)}

    @app.post("/v1/workspaces/{workspace_id}/tabs", status_code=201)
    async def create_tab(workspace_id: str, request: TabRequest):
        validate_workspace_id(workspace_id)
        selected = await client.snapshot()
        if not any(workspace["id"] == workspace_id for workspace in selected["workspaces"]):
            raise BridgeError(409, "Workspace is no longer available; refresh before creating a tab")
        if request.kind == "codex":
            if not reader.enabled:
                raise BridgeError(503, "Codex conversation support is not configured; run herdr-pocket setup on the host")
            await require_integration()
        launch_environment = None
        if request.kind == "codex":
            launch_environment = {"CODEX_HOME": str(reader.home)}
            if reader.search_path is not None:
                launch_environment["PATH"] = reader.search_path
        created = await client.create_tab(workspace_id, request.cwd, request.label, env=launch_environment)
        if (any(tab["id"] == created.tab_id for tab in selected["tabs"])
                or any(pane["id"] == created.pane_id for pane in selected["panes"])):
            raise BridgeError(503, "Tab creation returned an existing target; refresh the workspace before creating another")
        warning = None
        if request.kind == "codex":
            try:
                agent = await client.start_codex(created)
                conversations.register_created_codex(created, agent)
                warning = "Codex is starting. If it asks for login or permissions, open Terminal to continue."
            except BridgeError:
                warning = "The tab was created, but Codex launch could not be confirmed. Open Terminal and inspect it before creating another chat."
        return created.public_result(warning)

    @app.websocket("/v1/terminals/{pane_id}/stream")
    async def terminal(websocket: WebSocket, pane_id: str):
        async def deny(status: int, detail: str):
            if "websocket.http.response" in websocket.scope.get("extensions", {}):
                await websocket.send_denial_response(JSONResponse({"detail": detail}, status))
            else:
                await websocket.close(code=1008, reason=detail[:100])

        auth_header = websocket.headers.get("authorization")
        if not await asyncio.to_thread(authorized, auth_header, token, pairing_store):
            await deny(401, "Invalid or missing bearer token")
            return
        try:
            validate_pane_id(pane_id)
            try:
                cols = int(websocket.query_params.get("cols", "80"))
                rows = int(websocket.query_params.get("rows", "30"))
            except ValueError as exc:
                raise BridgeError(400, "Terminal dimensions must be integers") from exc
            if not 20 <= cols <= 300 or not 5 <= rows <= 150:
                raise BridgeError(400, "Terminal dimensions must be 20–300 columns and 5–150 rows")
            process = await client.observe(pane_id, cols, rows)
        except BridgeError as exc:
            await deny(exc.status, exc.detail)
            return

        tasks: list[asyncio.Task] = []
        try:
            await websocket.accept()

            async def forward():
                assert process.stdout is not None
                while line := await process.stdout.readline():
                    raw = validate_terminal_frame(line)
                    await websocket.send_text(raw)
                    if json.loads(raw)["type"] == "terminal.closed":
                        await websocket.close(code=1000)
                        return
                raise BridgeError(503, "Herdr terminal observer ended; reconnect to refresh output")

            async def watch_disconnect():
                while True:
                    message = await websocket.receive()
                    if message["type"] == "websocket.disconnect":
                        return
                    await websocket.close(code=1008, reason="Read-only stream; send input using the HTTP endpoint")
                    return

            async def drain_stderr():
                # Drain pipes without exposing CLI diagnostics or buffering them forever.
                assert process.stderr is not None
                while await process.stderr.read(4096):
                    pass

            tasks = [asyncio.create_task(forward()), asyncio.create_task(watch_disconnect())]
            watched = list(tasks)
            if pairing_store is not None and not authorized(auth_header, token):
                async def watch_device_access():
                    while True:
                        await asyncio.sleep(1)
                        if not await asyncio.to_thread(authorized, auth_header, token, pairing_store):
                            await websocket.close(code=1008, reason="Device access was revoked; pair again on the host")
                            return

                access_task = asyncio.create_task(watch_device_access())
                tasks.append(access_task)
                watched.append(access_task)
            tasks.append(asyncio.create_task(drain_stderr()))
            done, _pending = await asyncio.wait(watched, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        except (BridgeError, ValueError) as exc:
            detail = exc.detail if isinstance(exc, BridgeError) else "Herdr returned an invalid terminal frame"
            with suppress(WebSocketDisconnect, RuntimeError, OSError):
                await websocket.close(code=1011, reason=detail[:100])
        except (WebSocketDisconnect, OSError, RuntimeError):
            pass
        finally:
            # ASGI servers cancel the endpoint on disconnect/shutdown. Shield cleanup
            # so the terminate grace period and kill/reap fallback always complete.
            with anyio.CancelScope(shield=True):
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                await stop_observer(process)

    return app
