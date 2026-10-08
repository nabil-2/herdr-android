"""Installable CLI. Start on loopback and expose using Tailscale Serve."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys

import uvicorn

from .app import create_app
from .config import default_token_path, discover_socket, initialize_token, read_token
from .herdr import HerdrClient


def main() -> None:
    parser = argparse.ArgumentParser(description="Herdr Android companion service")
    parser.add_argument("command", choices=["init-token", "serve"])
    parser.add_argument("--token-file", type=Path, default=Path(os.environ.get("HERDR_ANDROID_TOKEN_FILE", default_token_path())))
    parser.add_argument("--socket", type=Path, help="Exact Herdr Unix socket (or HERDR_SOCKET)")
    parser.add_argument("--herdr-bin", default="herdr", help="Installed Herdr CLI executable")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    try:
        if args.command == "init-token":
            # Deliberate, single-user provisioning output; never logged by the server.
            print(initialize_token(args.token_file.expanduser()))
            return
        token = read_token(args.token_file.expanduser())
        socket_path = args.socket.expanduser() if args.socket else discover_socket()
        if not 1 <= args.port <= 65535:
            raise ValueError("Port must be 1–65535")
        client = HerdrClient(socket_path, executable=args.herdr_bin)
        uvicorn.run(create_app(client, token), host=args.host, port=args.port, access_log=False)
    except (OSError, ValueError) as exc:
        # Do not print exceptions that could embed submitted credentials.
        if isinstance(exc, FileNotFoundError):
            print("Token file is missing. Run herdr-android-bridge init-token first.", file=sys.stderr)
        else:
            print("Could not start bridge. Check token permissions, socket configuration and port.", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
