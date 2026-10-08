"""Per-user configuration and credential storage; no token in URLs or logs."""

from __future__ import annotations

import os
from pathlib import Path
import secrets
import stat


def default_token_path() -> Path:
    root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return root / "herdr-android" / "token"


def discover_socket() -> Path:
    # Explicit selection wins, even if absent: never silently retarget a write.
    explicit = os.environ.get("HERDR_SOCKET") or os.environ.get("HERDR_SOCKET_PATH")
    if explicit:
        return Path(explicit).expanduser()
    config_path = os.environ.get("HERDR_CONFIG_PATH")
    if config_path:
        return Path(config_path).expanduser().parent / "herdr.sock"
    root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    directory = root / "herdr"
    session = os.environ.get("HERDR_SESSION", "default")
    if session != "default":
        if not session or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for c in session):
            raise ValueError("HERDR_SESSION must be a valid named session")
        directory = directory / "sessions" / session
    return directory / "herdr.sock"


def read_token(path: Path) -> str:
    """Refuse symlinks, shared files, and tokens with unsafe file permissions."""
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    fd = os.open(path, flags)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise ValueError("Token must be a regular file owned by the service user")
        if stat.S_IMODE(info.st_mode) & 0o077:
            raise ValueError("Token file must have mode 0600; run chmod 600 on it")
        token = os.read(fd, 4097).decode("ascii").strip()
        if not 32 <= len(token) <= 4096 or any(c.isspace() or ord(c) < 33 or ord(c) > 126 for c in token):
            raise ValueError("Token must contain 32–4096 printable non-whitespace ASCII characters")
        return token
    finally:
        os.close(fd)


def initialize_token(path: Path) -> str:
    """Generate once, exclusively, with 0600 permissions; preserve existing tokens."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    token = secrets.token_urlsafe(32)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    except FileExistsError:
        return read_token(path)
    try:
        os.write(fd, (token + "\n").encode("ascii"))
        os.fsync(fd)
    finally:
        os.close(fd)
    return token
