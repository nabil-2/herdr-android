"""Environment for installed system commands launched by the companion."""

from __future__ import annotations

from collections.abc import Mapping
import os
import sys


def external_environment(environment: Mapping[str, str] | None = None) -> dict[str, str]:
    """Keep external programs out of a frozen Linux bundle's library directory.

    PyInstaller prepends its bundle to LD_LIBRARY_PATH and preserves a previous
    value in LD_LIBRARY_PATH_ORIG. Restore that value for external programs only;
    leave the companion process and its Python extension imports untouched.
    PyInstaller does not make this bootloader change on macOS, so preserve that
    platform's caller environment.
    """
    child = dict(os.environ if environment is None else environment)
    if getattr(sys, "frozen", False) and sys.platform.startswith("linux"):
        if "LD_LIBRARY_PATH_ORIG" in child:
            child["LD_LIBRARY_PATH"] = child["LD_LIBRARY_PATH_ORIG"]
        else:
            child.pop("LD_LIBRARY_PATH", None)
    return child
