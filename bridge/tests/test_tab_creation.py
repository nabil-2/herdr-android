"""Creation operates once on returned panes in an isolated synthetic Herdr API."""

from __future__ import annotations

import asyncio
from copy import deepcopy
import json
from pathlib import Path
import re
import uuid

import httpx
import pytest

from herdr_bridge.app import create_app
from herdr_bridge.codex_integration import CodexIntegrationResult
from herdr_bridge.herdr import HerdrClient
from .conftest import AUTH, SNAPSHOT, TOKEN


@pytest.fixture
async def creation_host(tmp_path, monkeypatch):
    path = tmp_path / "creation.sock"
    requests = []
    order = []
    thread = str(uuid.uuid4())
    tab_result = {
        "type": "tab_created",
        "tab": {"workspace_id": "w1", "tab_id": "w1:t3", "label": "Mobile"},
        "root_pane": {"workspace_id": "w1", "tab_id": "w1:t3", "pane_id": "w1:p3", "terminal_id": "fixture-new-terminal"},
    }
    pane_result = {"type": "pane_info", "pane": deepcopy(tab_result["root_pane"])}
    process_result = {"type": "pane_process_info", "process_info": {
        "pane_id": "w1:p3", "shell_pid": 42, "foreground_process_group_id": 42,
        "foreground_processes": [{"pid": 42, "name": "bash"}],
    }}
    options = {"tab_result": tab_result, "start_change": None, "drop": None,
               "integration": CodexIntegrationResult(ready=True), "pane_result": pane_result,
               "process_result": process_result, "shell_delay": 0,
               "process_sequence": [], "process_observations": []}

    def ensure(*_args):
        order.append("integration")
        return options["integration"]

    monkeypatch.setattr("herdr_bridge.app.ensure_codex_integration", ensure)

    async def reply(reader, writer):
        try:
            request = json.loads(await reader.readline())
            requests.append(request)
            method = request["method"]
            order.append(method)
            if options["drop"] == method:
                return
            if method == "session.snapshot":
                result = {"type": "session_snapshot", "snapshot": deepcopy(SNAPSHOT)}
            elif method == "tab.create":
                result = options["tab_result"]
            elif method == "pane.get":
                assert request["params"] == {"pane_id": "w1:p3"}
                result = options["pane_result"]
            elif method == "pane.process_info":
                assert request["params"] == {"pane_id": "w1:p3"}
                result = deepcopy(options["process_result"])
                if options["process_sequence"]:
                    result["process_info"] = deepcopy(options["process_sequence"].pop(0))
                if options["shell_delay"] > 0:
                    options["shell_delay"] -= 1
                    result["process_info"]["foreground_processes"] = []
                options["process_observations"].append(deepcopy(result["process_info"]))
            elif method == "agent.start":
                agent = {"workspace_id": "w1", "tab_id": "w1:t3", "pane_id": "w1:p3",
                         "terminal_id": "fixture-new-terminal", "name": request["params"]["name"],
                         "agent": "codex", "agent_status": "idle", "interactive_ready": True,
                         "agent_session": {"source": "herdr:codex", "agent": "codex", "kind": "id", "value": thread}}
                if options["start_change"] is not None:
                    agent.update(options["start_change"])
                result = {"type": "agent_started", "agent": agent}
            else:
                pytest.fail("Unexpected fixture operation: " + method)
            envelope = {"id": request["id"]}
            envelope.update(result if "error" in result else {"result": result})
            writer.write(json.dumps(envelope).encode() + b"\n")
            await writer.drain()
        finally:
            writer.close()
            await writer.wait_closed()

    server = await asyncio.start_unix_server(reply, path=path)
    async with server:
        yield HerdrClient(path, timeout=1), requests, options, order, tmp_path


async def post(host, payload: dict, *, workspace="w1", auth=AUTH, codex_enabled=True):
    backend, _, _, _, home = host
    app = create_app(backend, TOKEN, codex_executable="/bin/sh" if codex_enabled else None, codex_home=home)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture", headers=auth) as phone:
        response = await phone.post(f"/v1/workspaces/{workspace}/tabs", json=payload)
    return response, app


async def test_terminal_creation_preserves_literal_name_path_and_never_starts_an_agent(creation_host):
    _, requests, _, order, _ = creation_host
    name = "Mobile 雪 $(this-is-literal)"
    directory = "/tmp/a project; literal"
    response, _ = await post(creation_host, {"kind": "terminal", "label": name, "cwd": directory})
    assert response.status_code == 201
    assert response.json() == {"workspaceId": "w1", "tabId": "w1:t3", "paneId": "w1:p3", "warning": None}
    assert [request["method"] for request in requests] == ["session.snapshot", "tab.create"]
    assert requests[-1]["params"] == {"workspace_id": "w1", "label": name, "focus": False, "cwd": directory}
    assert "integration" not in order


async def test_codex_creation_prepares_integration_and_starts_only_the_new_root_once(creation_host):
    _, requests, _, order, _ = creation_host
    response, _ = await post(creation_host, {"kind": "codex", "label": "New conversation", "cwd": "/tmp/project"})
    assert response.status_code == 201
    assert response.json()["paneId"] == "w1:p3"
    assert "Terminal" in response.json()["warning"]
    assert order[:3] == ["session.snapshot", "integration", "tab.create"]
    assert order[-1] == "agent.start" and order.count("agent.start") == 1
    assert set(order[3:-1]) == {"pane.get", "pane.process_info"}
    start = requests[-1]["params"]
    assert start["kind"] == "codex" and start["pane_id"] == "w1:p3" and start["args"] == ["--no-daemon"]
    assert re.fullmatch(r"pocket-[0-9a-f]{24}", start["name"])
    assert start["timeout_ms"] == 30000
    assert all(request["method"] not in ("pane.send_input", "pane.send_text", "agent.prompt", "pane.close") for request in requests)
    assert "terminal_id" not in response.text and "agent_session" not in response.text


async def test_terminal_directory_can_inherit_workspace_without_serializing_null(creation_host):
    _, requests, _, _, _ = creation_host
    response, _ = await post(creation_host, {"kind": "terminal", "label": "Inherited directory"})
    assert response.status_code == 201
    assert "cwd" not in requests[-1]["params"]


@pytest.mark.parametrize("workspace,status", [("w0", 400), ("w1:t1", 400), ("w9", 409)])
async def test_bad_or_removed_workspace_never_creates_topology(creation_host, workspace, status):
    _, requests, _, order, _ = creation_host
    response, _ = await post(creation_host, {"kind": "codex", "label": "Scoped"}, workspace=workspace)
    assert response.status_code == status
    assert all(request["method"] == "session.snapshot" for request in requests)
    assert "integration" not in order


@pytest.mark.parametrize("change", [{"kind": "shell"}, {"label": ""}, {"label": "  "},
                                      {"label": "line\nfeed"}, {"cwd": "relative"},
                                      {"cwd": "/tmp\x00"}, {"cwd": "/tmp\n"},
                                      {"env": {"CODEX_HOME": "/tmp/client-controlled"}},
                                      {"args": ["--resume", "client-controlled"]},
                                      {"extra": "forbidden"}])
async def test_invalid_creation_body_is_rejected_without_side_effects_or_reflection(creation_host, change):
    _, requests, _, order, _ = creation_host
    response, _ = await post(creation_host, {"kind": "codex", "label": "PRIVATE_LABEL_123"} | change)
    assert response.status_code == 422
    assert requests == [] and order == []
    assert "PRIVATE_LABEL_123" not in response.text


async def test_authentication_precedes_creation_and_hook_preparation(creation_host):
    _, requests, _, order, _ = creation_host
    response, _ = await post(creation_host, {"kind": "codex", "label": "Unauthorized"}, auth={})
    assert response.status_code == 401
    assert requests == [] and order == []


@pytest.mark.parametrize("failure", ["unconfigured", "integration"])
async def test_unavailable_codex_support_fails_before_creating_a_tab(creation_host, failure):
    _, requests, options, _, _ = creation_host
    options["integration"] = CodexIntegrationResult(ready=False, advisory="Fixture hook unavailable")
    response, _ = await post(creation_host, {"kind": "codex", "label": "No launch"}, codex_enabled=failure != "unconfigured")
    assert response.status_code == 503
    assert [request["method"] for request in requests] == ["session.snapshot"]


@pytest.mark.parametrize("malformation", ["workspace", "pane_workspace", "pane_tab", "tab_id", "pane_id", "terminal_id", "type", "missing_root",
                                         "existing_tab", "existing_pane", "existing_tab_and_pane"])
async def test_unconfirmed_tab_identity_cannot_become_an_agent_launch_target(creation_host, malformation):
    _, requests, options, _, _ = creation_host
    result = options["tab_result"]
    if malformation == "workspace": result["tab"]["workspace_id"] = "w2"
    if malformation == "pane_workspace": result["root_pane"]["workspace_id"] = "w2"
    if malformation == "pane_tab": result["root_pane"]["tab_id"] = "w1:t1"
    if malformation == "tab_id": result["tab"]["tab_id"] = "w1:t0"
    if malformation == "pane_id": result["root_pane"]["pane_id"] = "w2:p1"
    if malformation == "terminal_id": result["root_pane"]["terminal_id"] = ""
    if malformation == "type": result["type"] = "ok"
    if malformation == "missing_root": result.pop("root_pane")
    if malformation in ("existing_tab", "existing_tab_and_pane"):
        result["tab"]["tab_id"] = result["root_pane"]["tab_id"] = "w1:t1"
    if malformation in ("existing_pane", "existing_tab_and_pane"):
        result["root_pane"]["pane_id"] = "w1:p1"
    response, _ = await post(creation_host, {"kind": "codex", "label": "Unknown target"})
    assert response.status_code == 503
    assert [request["method"] for request in requests].count("tab.create") == 1
    assert not any(request["method"] == "agent.start" for request in requests)


@pytest.mark.parametrize("drop", ["tab.create", "pane.get", "pane.process_info", "agent.start"])
async def test_lost_creation_or_launch_acknowledgement_is_never_retried_or_closed(creation_host, drop):
    _, requests, options, _, _ = creation_host
    options["drop"] = drop
    response, _ = await post(creation_host, {"kind": "codex", "label": "Uncertain outcome"})
    assert response.status_code == (503 if drop == "tab.create" else 201)
    assert [request["method"] for request in requests].count(drop) == 1
    assert not any(request["method"] in ("pane.close", "tab.close", "agent.prompt") for request in requests)
    if drop == "agent.start":
        assert response.json()["paneId"] == "w1:p3"
        assert "could not be confirmed" in response.json()["warning"]


async def test_shell_initialization_polls_only_owned_metadata_before_the_one_launch(creation_host):
    _, requests, options, _, _ = creation_host
    options["shell_delay"] = 1
    response, _ = await post(creation_host, {"kind": "codex", "label": "Wait for shell"})
    assert response.status_code == 201
    methods = [request["method"] for request in requests]
    assert methods[:2] == ["session.snapshot", "tab.create"] and methods[-1] == "agent.start"
    assert set(methods[2:-1]) == {"pane.get", "pane.process_info"}
    assert len(options["process_observations"]) > 2
    assert methods.count("tab.create") == methods.count("agent.start") == 1


async def test_transient_sole_shell_then_startup_child_does_not_launch_until_foreground_settles(creation_host):
    _, requests, options, _, _ = creation_host
    ready = deepcopy(options["process_result"]["process_info"])
    busy = deepcopy(ready)
    busy["foreground_processes"].append({"pid": 43, "name": "startup-helper"})
    options["process_sequence"] = [ready, busy, ready]
    response, _ = await post(creation_host, {"kind": "codex", "label": "Wait through shell startup"})
    assert response.status_code == 201 and "Codex is starting" in response.json()["warning"]
    observed = options["process_observations"]
    assert observed[:3] == [ready, busy, ready] and len(observed) > 3
    assert observed[-1] == ready
    assert [request["method"] for request in requests].count("agent.start") == 1
    assert all(request["method"] in {"session.snapshot", "tab.create", "pane.get", "pane.process_info", "agent.start"}
               for request in requests)


@pytest.mark.parametrize("malformation", ["terminal", "pane", "workspace", "tab", "process_target", "process_list", "no_shell"])
async def test_changed_or_unverified_shell_keeps_the_created_tab_without_launching_codex(creation_host, malformation):
    _, requests, options, _, _ = creation_host
    pane = options["pane_result"]["pane"]
    process = options["process_result"]["process_info"]
    if malformation == "terminal": pane["terminal_id"] = "replacement-terminal"
    if malformation == "pane": pane["pane_id"] = "w1:p1"
    if malformation == "workspace": pane["workspace_id"] = "w2"
    if malformation == "tab": pane["tab_id"] = "w1:t1"
    if malformation == "process_target": process["pane_id"] = "w1:p1"
    if malformation == "process_list": process["foreground_processes"] = {"pid": 42, "name": "bash"}
    if malformation == "no_shell": process["foreground_processes"] = []
    response, _ = await post(creation_host, {"kind": "codex", "label": "Inspect new terminal"})
    assert response.status_code == 201
    assert response.json()["paneId"] == "w1:p3"
    assert "could not be confirmed" in response.json()["warning"]
    methods = [request["method"] for request in requests]
    assert methods.count("tab.create") == 1
    assert "agent.start" not in methods
    assert not any(method in ("pane.close", "tab.close", "pane.send_input", "pane.send_text") for method in methods)


@pytest.mark.parametrize("change", [{"workspace_id": "w2"}, {"tab_id": "w1:t1"}, {"pane_id": "w1:p1"},
                                      {"terminal_id": "old-terminal"}, {"name": "different"}, {"agent": "claude"}])
async def test_mismatched_launch_receipt_keeps_created_tab_with_a_controlled_warning(creation_host, change):
    _, requests, options, _, _ = creation_host
    options["start_change"] = change
    response, _ = await post(creation_host, {"kind": "codex", "label": "Inspect launch"})
    assert response.status_code == 201
    assert response.json()["paneId"] == "w1:p3"
    assert "could not be confirmed" in response.json()["warning"]
    assert [request["method"] for request in requests].count("agent.start") == 1


@pytest.mark.parametrize("platform", ["linux", "darwin"])
async def test_capabilities_advertise_optional_codex_launch_support(creation_host, monkeypatch, platform):
    monkeypatch.setattr("herdr_bridge.app.sys.platform", platform)
    backend, _, _, _, home = creation_host
    for enabled in (False, True):
        app = create_app(backend, TOKEN, codex_executable="/bin/sh" if enabled else None, codex_home=home)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture", headers=AUTH) as phone:
            capabilities = (await phone.get("/v1/health")).json()["capabilities"]
        assert "workspace-tabs" in capabilities
        assert ("codex-create" in capabilities) is enabled
        assert ("codex-create-isolated" in capabilities) is (enabled and platform == "linux")
