"""Private, bounded uploads scoped to a paired device and one conversation.

The caller never supplies a host path. Uploading does not type into any pane;
receipts become single-use before the first intentional composer mutation.
"""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from dataclasses import dataclass
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import re
import secrets
import stat
import shlex
import time
import uuid
from typing import AsyncIterator, Awaitable, Callable

from PIL import Image, UnidentifiedImageError

from .herdr import BridgeError

MAX_ATTACHMENT_BYTES = 16 * 1024 * 1024
MAX_SUBMISSION_BYTES = 32 * 1024 * 1024
MAX_ATTACHMENTS = 8
MAX_STORED_BYTES = 128 * 1024 * 1024
MAX_STORED_ITEMS = 64
MAX_DISPLAY_ITEMS = 4096
MAX_DISPLAY_BYTES = 8 * 1024 * 1024
MAX_PENDING_ITEMS = 64
MAX_IMAGE_PIXELS = 16_000_000
STAGED_TTL = 3600
SENT_TTL = 7 * 24 * 3600
ATTACHMENT_ID = re.compile(r"[a-f0-9]{32}\Z")
STAGED_FILE = re.compile(r"hp-([a-f0-9]{32})\.[a-z0-9]{1,12}\Z")
PENDING_FILE = re.compile(r"pending-[a-f0-9]{64}\.json\Z")
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
IMAGE_TYPES = {"PNG": ("image/png", ".png"), "JPEG": ("image/jpeg", ".jpg"), "WEBP": ("image/webp", ".webp")}


async def _disk(function, *args, close_cancelled_fd: bool = False):
    """Finish bounded filesystem work before its caller closes a borrowed FD.

    Cancelling to_thread does not stop the worker; a prematurely closed/reused
    directory descriptor could otherwise redirect a late file operation.
    """
    worker = asyncio.create_task(asyncio.to_thread(function, *args))
    try:
        return await asyncio.shield(worker)
    except asyncio.CancelledError:
        while not worker.done():
            try:
                await asyncio.shield(worker)
            except asyncio.CancelledError:
                continue
            except Exception:
                break
        try:
            result = worker.result()
        except Exception:
            pass
        else:
            if close_cancelled_fd:
                os.close(result)
        raise


def valid_attachment_name(value: str) -> bool:
    return (isinstance(value, str) and 1 <= len(value) <= 160 and bool(value.strip())
            and value not in (".", "..") and not any(c in "/\\" or ord(c) < 32 or ord(c) == 127 for c in value)
            and len(value.encode("utf-8")) <= 640)


def _image_type(data: bytes) -> tuple[str, str]:
    try:
        with Image.open(BytesIO(data), formats=list(IMAGE_TYPES)) as image:
            width, height = image.size
            if width <= 0 or height <= 0 or width * height > MAX_IMAGE_PIXELS:
                raise BridgeError(413, "Images must contain at most 16 million pixels")
            if getattr(image, "is_animated", False):
                raise BridgeError(415, "Choose a static PNG, JPEG or WebP image")
            kind = image.format
            image.verify()
        # verify() alone does not decode JPEG/WebP pixel data. Decode once under
        # the explicit pixel limit to reject truncated/corrupt files before input.
        with Image.open(BytesIO(data), formats=list(IMAGE_TYPES)) as image:
            image.load()
        return IMAGE_TYPES[kind]
    except BridgeError:
        raise
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError) as exc:
        raise BridgeError(415, "Choose a valid static PNG, JPEG or WebP image") from exc


@dataclass
class Attachment:
    id: str
    owner: str
    pane_id: str
    binding: str
    name: str
    kind: str
    mime_type: str
    size: int
    path: Path
    inode: tuple[int, int]
    digest: str
    expires_at: float
    thread_id: str | None = None
    consumed: bool = False

    def public(self) -> dict:
        return {"id": self.id, "name": self.name, "kind": self.kind,
                "mimeType": self.mime_type, "sizeBytes": self.size}


@dataclass(frozen=True)
class DeliveryScope:
    pane_id: str
    thread_id: str
    runtime_id: str

    def marker_name(self, owner: str) -> str:
        identity = [self.pane_id, self.thread_id, self.runtime_id, owner]
        return "pending-" + hashlib.sha256(json.dumps(identity, separators=(",", ":")).encode()).hexdigest() + ".json"


class AttachmentStore:
    def __init__(self, root: Path | None = None, *, clock=time.time):
        cache = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache"))
        self.root = (root or cache / "herdr-pocket" / "attachments").absolute()
        self.clock = clock
        self.receipts: dict[str, Attachment] = {}
        self.lock = asyncio.Lock()
        self.uncertain: set[tuple[str, str, str]] = set()
        self.scopes: OrderedDict[tuple[str, str], DeliveryScope] = OrderedDict()

    def _root_fd(self) -> int:
        # Refuse a symlink anywhere in the configured path. The final directory
        # is private; system-owned ancestors are permitted.
        current = Path(self.root.anchor)
        for part in self.root.parts[1:]:
            current /= part
            try:
                info = current.lstat()
            except FileNotFoundError:
                try:
                    current.mkdir(mode=0o700)
                except FileExistsError:
                    pass
                info = current.lstat()
            if not stat.S_ISDIR(info.st_mode):
                raise BridgeError(503, "Attachment staging directory is unsafe; check host setup")
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        info = os.fstat(fd)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
            os.close(fd)
            raise BridgeError(503, "Attachment staging directory must be private to the service user")
        return fd

    @staticmethod
    def _info(fd: int, name: str):
        info = os.stat(name, dir_fd=fd, follow_symlinks=False)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) & 0o077):
            raise BridgeError(503, "Attachment staging files must remain private regular files")
        return info

    def _metadata(self, fd: int, name: str, expires_at: float, receipt: Attachment | None = None) -> None:
        temporary = ".hp-meta-" + secrets.token_hex(16)
        out = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd)
        try:
            with os.fdopen(out, "wb") as stream:
                record = {"expiresAt": expires_at}
                if receipt is not None:
                    record.update({"paneId": receipt.pane_id, "threadId": receipt.thread_id,
                                   "name": receipt.name, "kind": receipt.kind, "submitted": receipt.consumed})
                stream.write(json.dumps(record, separators=(",", ":")).encode())
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, name + ".json", src_dir_fd=fd, dst_dir_fd=fd)
        finally:
            try:
                os.unlink(temporary, dir_fd=fd)
            except FileNotFoundError:
                pass

    def _read_record(self, fd: int, name: str) -> dict | None:
        try:
            if self._info(fd, name).st_size > 2048:
                return None
            source = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
            try:
                value = json.loads(os.read(source, 2049))
                return value if isinstance(value, dict) else None
            finally:
                os.close(source)
        except (FileNotFoundError, ValueError):
            return None

    def _pending_owners(self, fd: int, scope: DeliveryScope) -> set[str]:
        owners = set()
        names = [name for name in os.listdir(fd) if PENDING_FILE.fullmatch(name)]
        if len(names) > MAX_PENDING_ITEMS:
            raise BridgeError(503, "Attachment delivery records need host inspection before sending again")
        for name in names:
            record = self._read_record(fd, name)
            if not isinstance(record, dict):
                raise BridgeError(503, "Attachment delivery records need host inspection before sending again")
            owner = record.get("ownerHash")
            try:
                valid = (isinstance(owner, str) and SHA256.fullmatch(owner)
                         and isinstance(record.get("paneId"), str)
                         and re.fullmatch(r"w[1-9]\d*:p[1-9]\d*", record["paneId"])
                         and isinstance(record.get("runtimeId"), str) and SHA256.fullmatch(record["runtimeId"])
                         and str(uuid.UUID(record.get("threadId"))) == record["threadId"])
            except (ValueError, TypeError, AttributeError):
                valid = False
            if (not valid or DeliveryScope(record["paneId"], record["threadId"], record["runtimeId"]).marker_name(owner) != name):
                raise BridgeError(503, "Attachment delivery records need host inspection before sending again")
            if (record["paneId"] == scope.pane_id and record["threadId"] == scope.thread_id
                    and record["runtimeId"] == scope.runtime_id):
                owners.add(owner)
        return owners

    def _write_pending(self, fd: int, scope: DeliveryScope, owner: str) -> None:
        if not SHA256.fullmatch(owner):
            raise BridgeError(503, "Attachment credential scope could not be verified")
        name = scope.marker_name(owner)
        names = [item for item in os.listdir(fd) if PENDING_FILE.fullmatch(item)]
        if name not in names and len(names) >= MAX_PENDING_ITEMS:
            raise BridgeError(413, "Inspect and acknowledge earlier attachment delivery before sending more attachments")
        temporary = ".hp-pending-" + secrets.token_hex(16)
        out = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd)
        try:
            with os.fdopen(out, "wb") as stream:
                record = {"paneId": scope.pane_id, "threadId": scope.thread_id,
                          "runtimeId": scope.runtime_id, "ownerHash": owner}
                stream.write(json.dumps(record, separators=(",", ":")).encode())
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, name, src_dir_fd=fd, dst_dir_fd=fd)
            os.fsync(fd)
        finally:
            try:
                os.unlink(temporary, dir_fd=fd)
            except FileNotFoundError:
                pass

    async def register(self, pane_id: str, binding: str, thread_id: str, runtime_id: str) -> None:
        """Bind durable guards only to freshly verified conversation metadata."""
        try:
            valid = (re.fullmatch(r"w[1-9]\d*:p[1-9]\d*", pane_id) and SHA256.fullmatch(runtime_id)
                     and str(uuid.UUID(thread_id)) == thread_id)
        except (ValueError, TypeError, AttributeError):
            valid = False
        if not valid:
            raise BridgeError(503, "Attachment conversation scope could not be verified")
        scope = DeliveryScope(pane_id, thread_id, runtime_id)
        async with self.lock:
            key = pane_id, binding
            self.scopes[key] = scope
            self.scopes.move_to_end(key)
            while len(self.scopes) > 128:
                stale, _ = self.scopes.popitem(last=False)
                self.uncertain = {item for item in self.uncertain if item[:2] != stale}
            if not self.root.exists():
                return
            fd = await _disk(self._root_fd, close_cancelled_fd=True)
            try:
                owners = await _disk(self._pending_owners, fd, scope)
            except OSError as exc:
                raise BridgeError(503, "Attachment delivery records could not be verified; inspect host storage") from exc
            finally:
                os.close(fd)
            self.uncertain = {item for item in self.uncertain if item[:2] != key}
            self.uncertain.update((pane_id, binding, owner) for owner in owners)

    @staticmethod
    def _display_record(record: dict | None) -> bool:
        if (record is None or record.get("submitted") is not True
                or not isinstance(record.get("paneId"), str)
                or not re.fullmatch(r"w[1-9]\d*:p[1-9]\d*", record["paneId"])
                or not valid_attachment_name(record.get("name"))
                or record.get("kind") not in ("image", "file")):
            return False
        try:
            return str(uuid.UUID(record.get("threadId"))) == record["threadId"]
        except (ValueError, TypeError, AttributeError):
            return False

    def _sweep(self, fd: int) -> tuple[int, int]:
        total, count = 0, 0
        for name in os.listdir(fd):
            if not STAGED_FILE.fullmatch(name):
                continue
            info = self._info(fd, name)
            expires = info.st_mtime + STAGED_TTL
            record = self._read_record(fd, name + ".json")
            value = record.get("expiresAt") if record is not None else None
            if type(value) in (int, float) and 0 < value <= self.clock() + SENT_TTL + 60:
                expires = value
            if self.clock() >= expires:
                os.unlink(name, dir_fd=fd)
                if not self._display_record(record):
                    try:
                        os.unlink(name + ".json", dir_fd=fd)
                    except FileNotFoundError:
                        pass
                self.receipts.pop(STAGED_FILE.fullmatch(name)[1], None)
            else:
                count += 1
                total += info.st_size
        # Payload retention and history presentation have different lifetimes.
        # Retain only bounded, private display records after the seven-day
        # payload expiry; they grant no upload/send authority after restart.
        orphaned = []
        for name in os.listdir(fd):
            if not name.endswith(".json") or not STAGED_FILE.fullmatch(name[:-5]):
                continue
            try:
                self._info(fd, name[:-5])
                continue
            except FileNotFoundError:
                pass
            info = self._info(fd, name)
            if not self._display_record(self._read_record(fd, name)):
                os.unlink(name, dir_fd=fd)
                continue
            orphaned.append((info.st_mtime_ns, name, info.st_size))
        display_bytes = sum(item[2] for item in orphaned)
        display_count = len(orphaned)
        for _, name, size in sorted(orphaned):
            if display_count <= MAX_DISPLAY_ITEMS and display_bytes <= MAX_DISPLAY_BYTES:
                break
            os.unlink(name, dir_fd=fd)
            display_count -= 1
            display_bytes -= size
        return total, count

    def _retire_superseded(self, fd: int, pane_id: str, binding: str, owner: str) -> bool:
        """Retire only our unsubmitted stages after a live scope recheck.

        A pending delivery can outlive its in-memory binding. Keep its payloads
        even when the native client has moved to a different conversation.
        """
        removed = False
        pending: dict[DeliveryScope, set[str]] = {}
        for receipt in list(self.receipts.values()):
            if (receipt.owner != owner or receipt.pane_id != pane_id or receipt.binding == binding
                    or receipt.consumed or (pane_id, receipt.binding, owner) in self.uncertain):
                continue
            scope = self.scopes.get((pane_id, receipt.binding))
            if scope is None or receipt.thread_id != scope.thread_id:
                continue
            if scope not in pending:
                pending[scope] = self._pending_owners(fd, scope)
            if owner in pending[scope]:
                continue
            record = self._read_record(fd, receipt.path.name + ".json")
            if (record is None or record.get("submitted") is not False
                    or record.get("paneId") != pane_id or record.get("threadId") != scope.thread_id):
                continue
            info = self._info(fd, receipt.path.name)
            if (info.st_dev, info.st_ino) != receipt.inode or info.st_size != receipt.size:
                raise BridgeError(409, "Attachment changed on the host; inspect staged files")
            os.unlink(receipt.path.name, dir_fd=fd)
            os.unlink(receipt.path.name + ".json", dir_fd=fd)
            self.receipts.pop(receipt.id, None)
            removed = True
        return removed

    async def upload(self, pane_id: str, binding: str, owner: str, name: str, kind: str,
                     chunks: AsyncIterator[bytes], *, thread_id: str | None = None,
                     verify_scope: Callable[[], Awaitable[DeliveryScope]] | None = None) -> dict:
        if kind not in ("image", "file") or not valid_attachment_name(name):
            raise BridgeError(400, "Choose an attachment with a valid basename and kind")
        async with self.lock:
            fd = None
            path_name = None
            try:
                # Requests can wait behind a streamed upload. Recheck the live
                # target under this lock before granting stage cleanup authority;
                # a registered but now stale binding alone is insufficient.
                if verify_scope is not None:
                    current = await verify_scope()
                    if (current != self.scopes.get((pane_id, binding))
                            or current.pane_id != pane_id or current.thread_id != thread_id):
                        raise BridgeError(409, "Pane or conversation changed; refresh before selecting an attachment")
                fd = await _disk(self._root_fd, close_cancelled_fd=True)
                total, count = await _disk(self._sweep, fd)
                if verify_scope is not None and await _disk(self._retire_superseded, fd, pane_id, binding, owner):
                    total, count = await _disk(self._sweep, fd)
                if count >= MAX_STORED_ITEMS or total >= MAX_STORED_BYTES:
                    raise BridgeError(413, "Attachment storage is full; remove staged files or wait for expiry")
                if sum(r.owner == owner and not r.consumed for r in self.receipts.values()) >= MAX_ATTACHMENTS:
                    raise BridgeError(413, "Use at most eight staged attachments at a time")
                attachment_id = secrets.token_hex(16)
                extension = Path(name).suffix.lower()
                if not re.fullmatch(r"\.[a-z0-9]{1,12}", extension):
                    extension = ".bin"
                path_name = "hp-" + attachment_id + extension
                out = os.open(path_name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=fd)
                digest = hashlib.sha256()
                size = 0
                image_bytes = bytearray() if kind == "image" else None
                with os.fdopen(out, "wb") as stream:
                    async with asyncio.timeout(60):
                        async for chunk in chunks:
                            size += len(chunk)
                            if size > MAX_ATTACHMENT_BYTES or total + size > MAX_STORED_BYTES:
                                raise BridgeError(413, "Attachments must fit within 16 MiB and available host storage")
                            if chunk:
                                await _disk(stream.write, chunk)
                                digest.update(chunk)
                                if image_bytes is not None:
                                    image_bytes.extend(chunk)
                    if not size:
                        raise BridgeError(400, "Choose a nonempty attachment")
                    await _disk(stream.flush)
                    await _disk(os.fsync, stream.fileno())
                mime_type = "application/octet-stream"
                if image_bytes is not None:
                    mime_type, extension = await _disk(_image_type, bytes(image_bytes))
                    final_name = "hp-" + attachment_id + extension
                    os.rename(path_name, final_name, src_dir_fd=fd, dst_dir_fd=fd)
                    path_name = final_name
                expires = self.clock() + STAGED_TTL
                info = self._info(fd, path_name)
                receipt = Attachment(attachment_id, owner, pane_id, binding, name, kind, mime_type, size,
                                     self.root / path_name, (info.st_dev, info.st_ino), digest.hexdigest(), expires, thread_id)
                await _disk(self._metadata, fd, path_name, expires, receipt)
                self.receipts[attachment_id] = receipt
                path_name = None
                return receipt.public()
            except TimeoutError as exc:
                raise BridgeError(408, "Attachment upload timed out; select it again") from exc
            except OSError as exc:
                raise BridgeError(503, "Attachment could not be staged; check host storage") from exc
            finally:
                if fd is not None:
                    if path_name is not None:
                        for name_to_remove in (path_name, path_name + ".json"):
                            try:
                                os.unlink(name_to_remove, dir_fd=fd)
                            except FileNotFoundError:
                                pass
                    os.close(fd)

    def _scoped(self, attachment_id: str, pane_id: str, binding: str, owner: str) -> Attachment:
        receipt = self.receipts.get(attachment_id)
        if (receipt is None or receipt.owner != owner or receipt.pane_id != pane_id or receipt.binding != binding
                or self.clock() >= receipt.expires_at):
            raise BridgeError(409, "Attachment is no longer available for this device and conversation; select it again")
        if receipt.consumed:
            raise BridgeError(503, "These attachments were already submitted; inspect Terminal before sending again")
        return receipt

    def _verify(self, fd: int, receipt: Attachment) -> None:
        info = self._info(fd, receipt.path.name)
        if (info.st_dev, info.st_ino) != receipt.inode or info.st_size != receipt.size:
            raise BridgeError(409, "Attachment changed on the host; select it again")
        source = os.open(receipt.path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
        try:
            with os.fdopen(source, "rb") as stream:
                opened = os.fstat(stream.fileno())
                if ((opened.st_dev, opened.st_ino) != receipt.inode or opened.st_size != receipt.size
                        or not stat.S_ISREG(opened.st_mode) or opened.st_uid != os.getuid() or opened.st_nlink != 1):
                    raise BridgeError(409, "Attachment changed on the host; select it again")
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if digest != receipt.digest:
                raise BridgeError(409, "Attachment changed on the host; select it again")
        except OSError as exc:
            raise BridgeError(409, "Attachment is no longer available; select it again") from exc

    async def consume(self, ids: list[str], pane_id: str, binding: str, owner: str) -> list[Attachment]:
        if not 1 <= len(ids) <= MAX_ATTACHMENTS or len(set(ids)) != len(ids):
            raise BridgeError(400, "Select between one and eight distinct attachments")
        async with self.lock:
            scope = (pane_id, binding, owner)
            if scope in self.uncertain:
                raise BridgeError(503, "Attachment delivery was not confirmed; inspect Terminal and acknowledge it before sending again")
            receipts = [self._scoped(i, pane_id, binding, owner) for i in ids]
            if sum(r.size for r in receipts) > MAX_SUBMISSION_BYTES:
                raise BridgeError(413, "A message can contain at most 32 MiB of attachments")
            fd = await _disk(self._root_fd, close_cancelled_fd=True)
            try:
                for receipt in receipts:
                    await _disk(self._verify, fd, receipt)
                delivery_scope = self.scopes.get((pane_id, binding))
                if delivery_scope is not None:
                    # The guard reaches durable storage before any image paste
                    # or turn request. A new process/binding can recover it only
                    # after the same thread and runtime are verified again.
                    await _disk(self._write_pending, fd, delivery_scope, owner)
                # Consume all receipts before any input; cancellation cannot make
                # an attachment reusable after its path reached the composer.
                self.uncertain.add(scope)
                for receipt in receipts:
                    receipt.consumed = True
                    receipt.expires_at = self.clock() + SENT_TTL
                    await _disk(self._metadata, fd, receipt.path.name, receipt.expires_at, receipt)
            except OSError as exc:
                raise BridgeError(409, "Attachment is no longer available; select it again") from exc
            finally:
                os.close(fd)
            return receipts

    async def confirmed(self, pane_id: str, binding: str, owner: str) -> None:
        async with self.lock:
            scope = self.scopes.get((pane_id, binding))
            if scope is not None and self.root.exists():
                fd = await _disk(self._root_fd, close_cancelled_fd=True)
                try:
                    def remove():
                        try:
                            os.unlink(scope.marker_name(owner), dir_fd=fd)
                        except FileNotFoundError:
                            pass
                        os.fsync(fd)
                    await _disk(remove)
                except OSError as exc:
                    raise BridgeError(503, "Attachment acknowledgement could not be saved; inspect host storage") from exc
                finally:
                    os.close(fd)
            self.uncertain.discard((pane_id, binding, owner))

    def blocked(self, pane_id: str, binding: str) -> bool:
        return any(p == pane_id and b == binding for p, b, _ in self.uncertain)

    def _history_refs(self, pane_id: str, thread_id: str) -> list[tuple[str, str, str]]:
        refs = []
        if not self.root.exists():
            return refs
        fd = self._root_fd()
        try:
            for name in os.listdir(fd):
                if not name.endswith(".json") or not STAGED_FILE.fullmatch(name[:-5]):
                    continue
                record = self._read_record(fd, name)
                if (not self._display_record(record) or record["paneId"] != pane_id
                        or record["threadId"] != thread_id):
                    continue
                refs.append((str(self.root / name[:-5]), record["name"], record["kind"]))
        finally:
            os.close(fd)
        return refs

    async def present_history(self, messages: list[dict], pane_id: str, thread_id: str) -> None:
        try:
            refs = await _disk(self._history_refs, pane_id, thread_id)
        except (OSError, BridgeError):
            return
        # Only verified saved user messages are transformed; pending stages,
        # another thread/pane and arbitrary user-authored paths never supply text.
        for message in messages:
            if message.get("role") != "user":
                continue
            text = message["text"]
            for path, name, kind in refs:
                label = ("Image attachment: " if kind == "image" else "File attachment: ") + name
                quoted = shlex.quote(path)
                text = text.replace(label + "\nUploaded host file: " + quoted, label)
                for reference in sorted({quoted, path}, key=len, reverse=True):
                    pattern = r"(?<![a-zA-Z0-9_./-])" + re.escape(reference) + r"(?![a-zA-Z0-9_./-])"
                    text = re.sub(pattern, lambda _match: "[" + label + "]", text)
            message["text"] = text

    @staticmethod
    def prompt_text(text: str, receipts: list[Attachment]) -> str:
        parts = [text] if text.strip() else []
        for receipt in receipts:
            if receipt.kind == "image":
                parts.append("Image attachment: " + receipt.name)
            else:
                parts.append("File attachment: " + receipt.name + "\nUploaded host file: " + shlex.quote(str(receipt.path)))
        return "\n\n".join(parts)

    async def close(self) -> None:
        # Submitted references must remain readable for their seven-day lifetime.
        # Unsubmitted stages can be removed when this companion stops.
        async with self.lock:
            if not self.receipts:
                return
            fd = await _disk(self._root_fd, close_cancelled_fd=True)
            try:
                for receipt in self.receipts.values():
                    if not receipt.consumed:
                        for name in (receipt.path.name, receipt.path.name + ".json"):
                            try:
                                os.unlink(name, dir_fd=fd)
                            except FileNotFoundError:
                                pass
            finally:
                os.close(fd)

    async def cleanup(self) -> None:
        async with self.lock:
            if not self.root.exists():
                return
            fd = await _disk(self._root_fd, close_cancelled_fd=True)
            try:
                await _disk(self._sweep, fd)
            finally:
                os.close(fd)

    async def delete(self, attachment_id: str, pane_id: str, binding: str, owner: str) -> None:
        async with self.lock:
            receipt = self._scoped(attachment_id, pane_id, binding, owner)
            fd = await _disk(self._root_fd, close_cancelled_fd=True)
            try:
                self._info(fd, receipt.path.name)
                os.unlink(receipt.path.name, dir_fd=fd)
                try:
                    os.unlink(receipt.path.name + ".json", dir_fd=fd)
                except FileNotFoundError:
                    pass
                self.receipts.pop(attachment_id, None)
            finally:
                os.close(fd)
