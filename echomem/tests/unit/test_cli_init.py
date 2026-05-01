from typer.testing import CliRunner
from echomem.cli import app


def test_init_creates_data_dir_and_config(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    result = runner.invoke(app, ["init"])
    assert result.exit_code == 0, result.output
    cfg = tmp_path / ".echomem" / "config.toml"
    assert cfg.exists()
    assert (tmp_path / ".echomem" / "blobs").is_dir()
    assert (tmp_path / ".echomem" / "logs").is_dir()


def test_init_idempotent(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    result = runner.invoke(app, ["init"])
    assert result.exit_code == 0
    assert "already initialized" in result.output.lower()
