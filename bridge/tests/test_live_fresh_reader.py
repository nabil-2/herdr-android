"""Actual read-only Codex channel survives a synthetic unmaterialized thread."""

from __future__ import annotations

import os
import shutil
import uuid

import pytest

from herdr_bridge.codex import CodexHistoryNotPersisted, CodexReader, normalize_thread
from .test_live_codex_chat import history


pytestmark = pytest.mark.skipif(os.environ.get("CODEX_INTEGRATION") != "1",
                                reason="Opt-in temporary Codex home; never calls a model")


async def test_missing_fresh_thread_polls_keep_the_same_read_only_sidecar_until_history_materializes(tmp_path):
    executable = os.environ.get("CODEX_BIN") or shutil.which("codex")
    assert executable, "Install Codex or set CODEX_BIN"
    home = tmp_path / "codex"
    home.mkdir()
    (home / "config.toml").write_text("check_for_update_on_startup = false\n[analytics]\nenabled = false\n")
    thread = str(uuid.uuid4())
    reader = CodexReader(executable, home)
    try:
        with pytest.raises(CodexHistoryNotPersisted):
            await reader.read(thread)
        process = reader.process
        assert process is not None and process.returncode is None
        with pytest.raises(CodexHistoryNotPersisted):
            await reader.read(thread)
        assert reader.process is process and process.returncode is None
        history(home, thread, "legacy")
        messages, _ = normalize_thread(await reader.read(thread), thread)
        assert [message["role"] for message in messages] == ["user", "assistant"]
        assert all("messageTimestamp" in message for message in messages)
        assert reader.process is process and process.returncode is None
    finally:
        await reader.close()
    assert process.returncode is not None
