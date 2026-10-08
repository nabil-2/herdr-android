from __future__ import annotations

import json
import os
import sys

from herdr_bridge import host_setup, runtime


def test_frozen_linux_restores_previous_library_path_without_mutating_parent(monkeypatch):
    monkeypatch.setattr(runtime.sys, "frozen", True, raising=False)
    monkeypatch.setattr(runtime.sys, "platform", "linux")
    parent = {"LD_LIBRARY_PATH": "/bundle:/caller/libs", "LD_LIBRARY_PATH_ORIG": "/caller/libs", "HERDR_SOCKET_PATH": "/chosen/herdr.sock", "PATH": "/bin"}
    child = runtime.external_environment(parent)
    assert child["LD_LIBRARY_PATH"] == "/caller/libs"
    assert child["HERDR_SOCKET_PATH"] == "/chosen/herdr.sock" and child["PATH"] == "/bin"
    assert parent["LD_LIBRARY_PATH"] == "/bundle:/caller/libs"


def test_frozen_linux_clears_only_bundle_path_if_no_previous_path(monkeypatch):
    monkeypatch.setattr(runtime.sys, "frozen", True, raising=False)
    monkeypatch.setattr(runtime.sys, "platform", "linux")
    child = runtime.external_environment({"LD_LIBRARY_PATH": "/bundle", "OTHER": "kept"})
    assert "LD_LIBRARY_PATH" not in child and child["OTHER"] == "kept"
    # An explicitly empty original path is still a valid original value.
    child = runtime.external_environment({"LD_LIBRARY_PATH": "/bundle", "LD_LIBRARY_PATH_ORIG": ""})
    assert child["LD_LIBRARY_PATH"] == ""


def test_unfrozen_python_and_mac_keep_caller_library_paths(monkeypatch):
    parent = {"LD_LIBRARY_PATH": "/caller/libs", "DYLD_LIBRARY_PATH": "/mac/caller/libs"}
    monkeypatch.setattr(runtime.sys, "frozen", False, raising=False)
    assert runtime.external_environment(parent) == parent
    monkeypatch.setattr(runtime.sys, "frozen", True)
    monkeypatch.setattr(runtime.sys, "platform", "darwin")
    assert runtime.external_environment(parent) == parent


def test_actual_external_child_sees_original_environment_not_bundle(monkeypatch):
    monkeypatch.setattr(runtime.sys, "frozen", True, raising=False)
    monkeypatch.setattr(runtime.sys, "platform", "linux")
    monkeypatch.setenv("LD_LIBRARY_PATH", "/not-a-real-pyinstaller-bundle")
    monkeypatch.setenv("LD_LIBRARY_PATH_ORIG", "/original-host-library-search")
    result = host_setup.run([sys.executable, "-c", "import os,json; print(json.dumps({'libraryPath':os.environ.get('LD_LIBRARY_PATH')}))"])
    assert json.loads(result.stdout)["libraryPath"] == "/original-host-library-search"
    assert os.environ["LD_LIBRARY_PATH"] == "/not-a-real-pyinstaller-bundle"
