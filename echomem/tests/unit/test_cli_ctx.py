import re
import pytest
from typer.testing import CliRunner
from echomem.cli import app


def _setup(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    return runner


def test_ctx_add_url(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="POST", url=re.compile(r"http://127\.0\.0\.1:\d+/context/add_url"),
                            json={"sha256": "a" * 64, "mime": "text/html",
                                  "byte_size": 100, "origin_url": "https://x", "path": None,
                                  "created_at": 1})
    result = runner.invoke(app, ["ctx", "add-url", "https://x"])
    assert result.exit_code == 0
    assert "a" * 8 in result.output


def test_ctx_ls(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="GET",
                            url=re.compile(r"http://127\.0\.0\.1:\d+/context/ls.*"),
                            json={"items": [{"path": "a/x.md", "sha256": "b" * 64,
                                              "mime": "text/markdown", "byte_size": 5,
                                              "origin_url": None, "created_at": 1}]})
    result = runner.invoke(app, ["ctx", "ls", "--prefix", "a/"])
    assert result.exit_code == 0
    assert "a/x.md" in result.output


def test_ctx_read_by_path(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="GET",
                            url=re.compile(r"http://127\.0\.0\.1:\d+/context/read.*"),
                            json={"sha256": "c" * 64, "path": "a.md", "mime": "text/plain",
                                  "byte_size": 5, "content": "hello"})
    result = runner.invoke(app, ["ctx", "read", "a.md"])
    assert result.exit_code == 0
    assert "hello" in result.output


def test_ctx_mv(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(method="POST",
                            url=re.compile(r"http://127\.0\.0\.1:\d+/context/mv"),
                            json={"old": "a.md", "new": "b.md", "moved": True})
    result = runner.invoke(app, ["ctx", "mv", "a.md", "b.md"])
    assert result.exit_code == 0
    assert "b.md" in result.output
