"""Unit tests for dbay-mcp wiki tools.

These tests mock `_api` (HTTP call) and `_resolve_kb_id` (config lookup) so
we can verify the tools without a running lakeon-api.
"""
import os

os.environ.setdefault("DBAY_API_KEY", "test-key")
os.environ.setdefault("DBAY_ENDPOINT", "http://localhost:8080")

from unittest.mock import patch

from dbay_mcp.server import (
    wiki_curate,
    wiki_get_schema,
    wiki_ingest,
    wiki_lint,
    wiki_list_pages,
    wiki_read_page,
    wiki_search_pages,
    wiki_task_status,
)


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_list_pages_empty(mock_api, _resolve):
    mock_api.return_value = []
    result = wiki_list_pages(None)
    assert "No wiki pages" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_list_pages_renders_titles(mock_api, _resolve):
    mock_api.return_value = [
        {"title": "Auth", "summary": "how auth works"},
        {"title": "Chunking", "summary": "chunk strategy"},
    ]
    result = wiki_list_pages(None)
    assert "Auth" in result
    assert "Chunking" in result
    assert "2 total" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_read_page_not_found(mock_api, _resolve):
    mock_api.return_value = {"found": False, "title": "Ghost"}
    result = wiki_read_page("Ghost", None)
    assert "not found" in result.lower()


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_read_page_returns_content(mock_api, _resolve):
    mock_api.return_value = {
        "found": True,
        "title": "Auth",
        "content": "# Auth\n\nThe auth subsystem uses JWT.",
    }
    result = wiki_read_page("Auth", None)
    assert "JWT" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_search_pages_renders_scores(mock_api, _resolve):
    mock_api.return_value = [
        {"title": "Auth", "score": 6, "summary": "auth details"},
        {"title": "Sharding", "score": 2, "summary": "unrelated"},
    ]
    result = wiki_search_pages("auth", None)
    assert "Auth" in result
    assert "score=6" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_search_pages_empty(mock_api, _resolve):
    mock_api.return_value = []
    result = wiki_search_pages("nothing", None)
    assert "No matches" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_get_schema_returns_content(mock_api, _resolve):
    mock_api.return_value = {
        "found": True,
        "title": "KB Schema",
        "content": "# KB Schema\nrules...",
    }
    result = wiki_get_schema(None)
    assert "KB Schema" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_get_schema_missing_returns_placeholder(mock_api, _resolve):
    mock_api.return_value = {"found": False}
    result = wiki_get_schema(None)
    assert "no schema page" in result.lower()


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_ingest_returns_task_id(mock_api, _resolve):
    mock_api.return_value = {
        "task_id": "task_x",
        "run_id": "run_y",
        "status": "accepted",
    }
    result = wiki_ingest("doc123", None)
    assert "task_x" in result
    assert "doc123" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_curate_returns_task_id(mock_api, _resolve):
    mock_api.return_value = {"task_id": "task_curate", "status": "accepted"}
    result = wiki_curate(None)
    assert "task_curate" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_lint_returns_task_id(mock_api, _resolve):
    mock_api.return_value = {"task_id": "task_lint", "status": "accepted"}
    result = wiki_lint(None)
    assert "task_lint" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_task_status_completed(mock_api, _resolve):
    mock_api.return_value = {
        "task_id": "t1",
        "status": "completed",
        "result": {
            "pages_created": 2,
            "pages_updated": 5,
            "pages_deleted": 0,
            "summary": "ingested doc X",
        },
    }
    result = wiki_task_status("t1", None)
    assert "completed" in result
    assert "2" in result
    assert "5" in result
    assert "ingested doc X" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_task_status_error(mock_api, _resolve):
    mock_api.return_value = {"status": "error", "error": "LLM timeout"}
    result = wiki_task_status("t1", None)
    assert "ERROR" in result
    assert "timeout" in result


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_task_status_not_found(mock_api, _resolve):
    mock_api.return_value = {"status": "not_found"}
    result = wiki_task_status("t1", None)
    assert "not found" in result.lower()


@patch("dbay_mcp.server._resolve_kb_id", return_value="kb1")
@patch("dbay_mcp.server._api")
def test_wiki_task_status_running(mock_api, _resolve):
    mock_api.return_value = {"status": "running"}
    result = wiki_task_status("t1", None)
    assert "still running" in result
