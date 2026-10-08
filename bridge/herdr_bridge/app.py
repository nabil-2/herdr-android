"""HTTP commands and read-only terminal streams for protocol v1."""

from __future__ import annotations

import asyncio
from contextlib import suppress
import hmac
import json
from typing import Annotated

import anyio
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .herdr import BridgeError, HerdrClient, stop_observer, validate_pane_id, validate_terminal_frame

MAX_BODY_BYTES = 64 * 1024


def authorized(header: str | None, token: str) -> bool:
    if header is None:
        return False
    scheme, separator, supplied = header.partition(" ")
    if scheme.lower() != "bearer" or not separator or not supplied or len(supplied) > 4096:
        return False
    # Byte comparison accepts only ASCII credentials, without timing-dependent equality.
    try:
        return hmac.compare_digest(supplied.encode("ascii"), token.encode("ascii"))
    except UnicodeError:
        return False


class AuthAndBodyLimit:
    def __init__(self, app, token: str):
        self.app = app
        self.token = token

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not (scope["path"] == "/v1" or scope["path"].startswith("/v1/")):
            await self.app(scope, receive, send)
            return
        headers = dict(scope["headers"])
        header = headers.get(b"authorization", b"").decode("latin1")
        if not authorized(header, self.token):
            await JSONResponse({"detail": "Invalid or missing bearer token"}, 401, headers={"WWW-Authenticate": "Bearer"})(scope, receive, send)
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


def create_app(client: HerdrClient, token: str) -> FastAPI:
    if not 32 <= len(token) <= 4096 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("Use a generated bearer token of at least 32 ASCII characters")
    app = FastAPI(title="Herdr Android Bridge", docs_url=None, redoc_url=None, openapi_url=None)
    app.add_middleware(AuthAndBodyLimit, token=token)

    @app.exception_handler(BridgeError)
    async def bridge_error(_request, exc: BridgeError):
        return JSONResponse({"detail": exc.detail}, exc.status)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(_request, _exc):
        # Pydantic's default includes submitted text; keep error responses free of it.
        return JSONResponse({"detail": "Invalid request: check text, key names, directory and size limits"}, 422)

    @app.get("/v1/health")
    async def health():
        snapshot = await client.snapshot()
        return {"service": "herdr-android-bridge", "protocol": 1, "herdrVersion": snapshot["herdrVersion"]}

    @app.get("/v1/snapshot")
    async def snapshot():
        return await client.snapshot()

    @app.post("/v1/panes/{pane_id}/input")
    async def send_input(pane_id: str, request: InputRequest):
        await client.send_input(pane_id, request.text, request.keys)
        return {"ok": True}

    @app.post("/v1/workspaces", status_code=201)
    async def create_workspace(request: WorkspaceRequest):
        return {"id": await client.create_workspace(request.cwd, request.label)}

    @app.websocket("/v1/terminals/{pane_id}/stream")
    async def terminal(websocket: WebSocket, pane_id: str):
        async def deny(status: int, detail: str):
            if "websocket.http.response" in websocket.scope.get("extensions", {}):
                await websocket.send_denial_response(JSONResponse({"detail": detail}, status))
            else:
                await websocket.close(code=1008, reason=detail[:100])

        if not authorized(websocket.headers.get("authorization"), token):
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

            tasks = [asyncio.create_task(forward()), asyncio.create_task(watch_disconnect()), asyncio.create_task(drain_stderr())]
            done, _pending = await asyncio.wait(tasks[:2], return_when=asyncio.FIRST_COMPLETED)
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
