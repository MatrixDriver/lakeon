import re
import pytest
from typer.testing import CliRunner
from echomem.cli import app


def _setup(monkeypatch, tmp_path):
    monkeypatch.setenv("HOME", str(tmp_path))
    runner = CliRunner()
    runner.invoke(app, ["init"])
    return runner


def test_mem_ingest(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(
        method="POST",
        url=re.compile(r"http://127\.0\.0\.1:\d+/memory/ingest"),
        json={"id": "01HXCLI00000000000000000A", "agent_id": "cc", "created_at": 1},
    )
    result = runner.invoke(app, ["mem", "ingest", "hello", "--agent", "cc"])
    assert result.exit_code == 0
    assert "01HXCLI" in result.output


def test_mem_recall(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(
        method="POST",
        url=re.compile(r"http://127\.0\.0\.1:\d+/memory/recall"),
        json={
            "hits": [
                {
                    "id": "01HXCLI00000000000000000B",
                    "text": "hello world",
                    "score": 0.9,
                    "source_kind": "explicit",
                    "source_ref": None,
                    "meta": None,
                }
            ]
        },
    )
    result = runner.invoke(app, ["mem", "recall", "hello"])
    assert result.exit_code == 0
    assert "hello world" in result.output


def test_mem_list(tmp_path, monkeypatch, httpx_mock):
    runner = _setup(monkeypatch, tmp_path)
    httpx_mock.add_response(
        method="GET",
        url=re.compile(r"http://127\.0\.0\.1:\d+/memory/list.*"),
        json={"items": []},
    )
    result = runner.invoke(app, ["mem", "list"])
    assert result.exit_code == 0
