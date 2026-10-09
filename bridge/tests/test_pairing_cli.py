import pytest

from herdr_bridge import __main__ as cli
from herdr_bridge import host_setup


def test_pair_qr_file_argument_preserves_plain_and_session_selection(monkeypatch, tmp_path):
    image = tmp_path / "pairing.png"
    config = {"selected": "host"}
    calls = []
    monkeypatch.setattr(cli.sys, "argv", ["herdr-pocket", "pair", "--session", "work", "--plain", "--qr-file", str(image)])
    monkeypatch.setattr(host_setup, "configured_host", lambda root, session: config if session == "work" else None)
    monkeypatch.setattr(host_setup, "show_pairing", lambda value, **kwargs: calls.append((value, kwargs)))
    cli.main()
    assert calls == [(config, {"plain": True, "qr_file": image})]


def test_setup_qr_file_argument_reaches_pairing_without_discarding_other_options(monkeypatch, tmp_path):
    image, root = tmp_path / "pairing.png", tmp_path / "config"
    calls = []
    monkeypatch.setattr(cli.sys, "argv", ["herdr-pocket", "setup", "--config-dir", str(root), "--session", "work", "--qr-file", str(image)])
    monkeypatch.setattr(host_setup, "setup", lambda **kwargs: calls.append(kwargs))
    cli.main()
    assert len(calls) == 1
    assert calls[0]["session"] == "work"
    assert calls[0]["config_root"] == root
    assert calls[0]["qr_file"] == image
    assert calls[0]["plain"] is False


@pytest.mark.parametrize("command", ["serve", "devices", "status", "init-token", "revoke-device"])
def test_qr_file_is_rejected_for_nonpairing_commands(monkeypatch, command, tmp_path):
    monkeypatch.setattr(cli.sys, "argv", ["herdr-pocket", command, "--qr-file", str(tmp_path / "pairing.png")])
    with pytest.raises(SystemExit) as error:
        cli.main()
    assert error.value.code == 2
