import re
import pytest
from typer.testing import CliRunner
from echomem.cli import app


def test_status_when_no_daemon(tmp_path, monkeypatch, httpx_mock):
    monkeypatch.setenv("HOME", str(tmp_path))
    httpx_mock.add_exception(ConnectionError("refused"))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    result = runner.invoke(app, ["status"])
    assert result.exit_code == 0
    assert "down" in result.output.lower() or "not reachable" in result.output.lower()


def test_status_when_daemon_up(tmp_path, monkeypatch, httpx_mock):
    monkeypatch.setenv("HOME", str(tmp_path))
    httpx_mock.add_response(
        method="GET",
        url=re.compile(r"http://127\.0\.0\.1:\d+/health"),
        json={
            "status": "ok",
            "version": "0.1.0",
            "embedding_dim": 1024,
            "embedding_model": "qwen3-embedding:0.6b",
        },
    )
    runner = CliRunner()
    runner.invoke(app, ["init"])
    result = runner.invoke(app, ["status"])
    assert result.exit_code == 0
    assert "ok" in result.output.lower() or "up" in result.output.lower()
