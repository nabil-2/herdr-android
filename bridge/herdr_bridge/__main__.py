"""Packaged companion, local setup, and one-use phone pairing."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys
import json

import uvicorn

from .app import create_app
from .config import default_token_path, discover_socket, initialize_token, read_token
from .herdr import HerdrClient
from .pairing import PairingStore


def main() -> None:
    if sys.argv[1:] == ["--licenses"]:
        if getattr(sys, "frozen", False):
            path = Path(sys._MEIPASS) / "herdr_pocket_licenses/LICENSES.txt"
        else:
            path = Path(__file__).resolve().parents[2] / "THIRD_PARTY_NOTICES.md"
        print(path.read_text())
        return
    parser = argparse.ArgumentParser(description="Herdr Pocket: setup once, then scan the pairing code on your phone")
    parser.add_argument("command", choices=["setup", "pair", "devices", "revoke-device", "status", "init-token", "serve"])
    parser.add_argument("device_id", nargs="?", help="Device ID for revoke-device")
    parser.add_argument("--token-file", type=Path, default=Path(os.environ.get("HERDR_ANDROID_TOKEN_FILE", default_token_path())))
    parser.add_argument("--socket", type=Path, help="Exact Herdr Unix socket (or HERDR_SOCKET)")
    parser.add_argument("--herdr-bin", help="Installed Herdr CLI executable")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--session", help="Choose a running/configured named Herdr session")
    parser.add_argument("--config-dir", type=Path, help="Companion configuration directory")
    parser.add_argument("--registry-file", type=Path, help="Owner-only paired-device registry")
    parser.add_argument("--service-config", type=Path, help="Managed service setup.json (used internally)")
    parser.add_argument("--plain", action="store_true", help="Print pairing JSON instead of a QR code")
    parser.add_argument("--version", action="version", version="Herdr Pocket 0.2.0")
    args = parser.parse_args()
    try:
        if args.command in {"setup", "pair", "devices", "revoke-device", "status"}:
            from .host_setup import SetupError, configured_host, setup, show_pairing, wait_healthy
            root = args.config_dir.expanduser() if args.config_dir else None
            if args.command == "setup":
                setup(session=args.session, socket_path=args.socket, herdr_bin=args.herdr_bin, config_root=root)
                return
            config = configured_host(root, args.session)
            if args.command == "pair":
                show_pairing(config, plain=args.plain)
            elif args.command == "status":
                wait_healthy(config, timeout=3)
                print(f"Ready: {config['name']}\nSession: {config['session']}\nAddress: {config['url']}")
            else:
                devices = PairingStore(Path(config["registryFile"]))
                if args.command == "devices":
                    print(json.dumps(devices.devices(), indent=2))
                elif not args.device_id:
                    parser.error("revoke-device requires a device ID from 'herdr-pocket devices'")
                elif devices.revoke(args.device_id):
                    print("Phone access revoked.")
                else:
                    print("No active device matched that ID.")
            return
        if args.command == "init-token":
            # Deliberate, single-user provisioning output; never logged by the server.
            print(initialize_token(args.token_file.expanduser()))
            return
        token_path = args.token_file.expanduser()
        registry_path = args.registry_file.expanduser() if args.registry_file else token_path.parent / "devices.json"
        socket_path = args.socket.expanduser() if args.socket else discover_socket()
        herdr_bin, host, port = args.herdr_bin or "herdr", args.host, args.port
        if args.service_config:
            from .host_setup import read_config
            config = read_config(args.service_config.expanduser())
            token_path, registry_path = Path(config["tokenFile"]), Path(config["registryFile"])
            socket_path, herdr_bin, host, port = Path(config["socket"]), config["herdrBin"], config["host"], config["port"]
        token = read_token(token_path)
        if not 1 <= port <= 65535:
            raise ValueError("Port must be 1–65535")
        client = HerdrClient(socket_path, executable=herdr_bin)
        uvicorn.run(create_app(client, token, PairingStore(registry_path)), host=host, port=port, access_log=False)
    except (OSError, ValueError) as exc:
        # Do not print exceptions that could embed submitted credentials.
        if isinstance(exc, FileNotFoundError):
            print("Token file is missing. Run herdr-android-bridge init-token first.", file=sys.stderr)
        else:
            print("Could not start bridge. Check token permissions, socket configuration and port.", file=sys.stderr)
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        raise SystemExit(130) from None
    except Exception as exc:
        from .host_setup import SetupError
        from .herdr import BridgeError
        if isinstance(exc, SetupError):
            print(str(exc), file=sys.stderr)
        elif isinstance(exc, BridgeError):
            print(exc.detail, file=sys.stderr)
        else:
            print("Setup could not complete. Check Herdr, Tailscale and your user service permissions.", file=sys.stderr)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
