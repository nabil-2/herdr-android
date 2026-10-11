#!/usr/bin/env python3
"""Build a self-contained companion and a checksummed, one-command installer."""

from __future__ import annotations

import argparse
import base64
import hashlib
from importlib import metadata
from pathlib import Path
import platform
import subprocess
import sys
import sysconfig


ROOT = Path(__file__).resolve().parent.parent


def license_bundle() -> str:
    documents = ["Herdr Pocket packaged host companion — licenses\n"]
    for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
        documents.append(f"\n===== {name} =====\n" + (ROOT / name).read_text())
    python_license = next((path for path in (
        Path(sysconfig.get_path("stdlib")) / "LICENSE.txt",
        Path(sys.base_prefix) / "LICENSE.txt",
        ROOT / "scripts/licenses/Python-3.11.txt",
    ) if path.is_file()))
    documents.append("\n===== Python runtime =====\n" + python_license.read_text())
    for distribution in sorted(metadata.distributions(), key=lambda item: item.metadata["Name"].lower()):
        for path in distribution.files or []:
            if path.name.lower().startswith(("license", "copying", "copyright", "notice")) and path.suffix.lower() in ("", ".txt", ".md", ".rst"):
                source = distribution.locate_file(path)
                if source.is_file():
                    documents.append(f"\n===== {distribution.metadata['Name']} {distribution.version}: {path.name} =====\n" + source.read_text(errors="replace"))
    return "\n".join(documents)


def installer(binary: bytes, operating_system: str, architecture: str) -> str:
    digest = hashlib.sha256(binary).hexdigest()
    # Installation is performed by the companion itself, after prerequisite checks.
    # A failed setup leaves no shell-created persistent files behind.
    header = f'''#!/usr/bin/env bash
# Herdr Pocket 0.7.2. Self-contained runtime; no Python installation required.
set -euo pipefail
case "$(uname -s)" in
  {"Linux" if operating_system == "linux" else "Darwin"}) ;;
  *) echo "This installer is for {operating_system}. Download the matching host installer." >&2; exit 1 ;;
esac
case "$(uname -m)" in
  {"x86_64|amd64" if architecture == "x86_64" else "arm64|aarch64"}) ;;
  *) echo "This installer is for {architecture}. Download the matching host installer." >&2; exit 1 ;;
esac
task_tmp=$(mktemp -d "${{TMPDIR:-/tmp}}/herdr-pocket.XXXXXXXX")
trap 'rm -rf "$task_tmp"' EXIT
awk 'payload {{ print }} /^__HERDR_POCKET_BINARY__$/ {{ payload=1 }}' "$0" > "$task_tmp/binary.b64"
if [ "$(uname -s)" = Darwin ]; then
  base64 -D < "$task_tmp/binary.b64" > "$task_tmp/herdr-pocket"
  task_digest=$(shasum -a 256 "$task_tmp/herdr-pocket" | awk '{{print $1}}')
else
  base64 -d < "$task_tmp/binary.b64" > "$task_tmp/herdr-pocket"
  task_digest=$(sha256sum "$task_tmp/herdr-pocket" | awk '{{print $1}}')
fi
if [ "$task_digest" != "{digest}" ]; then
  echo "Installer checksum failed. Download the release asset again." >&2; exit 1
fi
chmod 700 "$task_tmp/herdr-pocket"
"$task_tmp/herdr-pocket" "${{@:-setup}}"
exit $?
__HERDR_POCKET_BINARY__
'''
    return header + base64.encodebytes(binary).decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/host")
    args = parser.parse_args()
    system = {"Linux": "linux", "Darwin": "macos"}.get(platform.system())
    architecture = {"x86_64": "x86_64", "AMD64": "x86_64", "arm64": "arm64", "aarch64": "arm64"}.get(platform.machine())
    if system is None or architecture is None:
        parser.error("Build on a supported Linux/macOS host architecture")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    licenses = output / "LICENSES.txt"
    licenses.write_text(license_bundle())
    name = f"herdr-pocket-{system}-{architecture}"
    subprocess.run([
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--onefile",
        "--name", name, "--distpath", str(output),
        "--workpath", str(ROOT / "build/host-package"),
        "--specpath", str(ROOT / "build/host-package"),
        "--paths", str(ROOT / "bridge"),
        "--collect-submodules", "uvicorn", "--collect-submodules", "anyio",
        "--collect-submodules", "websockets", "--collect-data", "herdr_bridge",
        "--add-data", str(licenses) + ":herdr_pocket_licenses",
        str(ROOT / "scripts/host_entry.py"),
    ], cwd=ROOT, check=True)
    binary = output / name
    subprocess.run([str(binary), "--version"], check=True)
    script = output / f"herdr-pocket-setup-{system}-{architecture}.sh"
    script.write_text(installer(binary.read_bytes(), system, architecture), encoding="ascii")
    script.chmod(0o755)
    subprocess.run(["bash", str(script), "--version"], check=True)
    checksums = "".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in (binary, script, licenses))
    (output / "SHA256SUMS").write_text(checksums)
    print(f"Built {script.name}")


if __name__ == "__main__":
    main()
