from __future__ import annotations

import os
import stat

import pytest

from herdr_bridge.config import discover_socket, initialize_token, read_token


def test_token_generated_once_with_private_permissions(tmp_path):
    path = tmp_path / "private" / "token"
    token = initialize_token(path)
    assert len(token) >= 32
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
    assert initialize_token(path) == token
    assert read_token(path) == token


def test_unsafe_token_files_and_symlinks_rejected(tmp_path):
    path = tmp_path / "token"
    initialize_token(path)
    path.chmod(0o644)
    with pytest.raises(ValueError, match="0600"):
        read_token(path)
    path.chmod(0o600)
    link = tmp_path / "link"
    link.symlink_to(path)
    with pytest.raises(OSError):
        initialize_token(link)
    with pytest.raises(OSError):
        read_token(link)


def test_explicit_missing_socket_never_falls_back(monkeypatch, tmp_path):
    monkeypatch.setenv("HERDR_SOCKET", str(tmp_path / "absent.sock"))
    monkeypatch.setenv("HERDR_SOCKET_PATH", "/someone/else/herdr.sock")
    assert discover_socket() == tmp_path / "absent.sock"


def test_per_user_config_and_named_sessions(monkeypatch, tmp_path):
    for key in ("HERDR_SOCKET", "HERDR_SOCKET_PATH", "HERDR_CONFIG_PATH", "HERDR_SESSION"):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    assert discover_socket() == tmp_path / "herdr" / "herdr.sock"
    monkeypatch.setenv("HERDR_SESSION", "test-one")
    assert discover_socket() == tmp_path / "herdr" / "sessions" / "test-one" / "herdr.sock"
    monkeypatch.setenv("HERDR_SESSION", "../../someone")
    with pytest.raises(ValueError):
        discover_socket()
