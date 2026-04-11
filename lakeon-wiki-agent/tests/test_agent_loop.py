"""Core agent loop — state machine behavior."""
import json
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.agent.loop import AgentRunner, RunRequest, new_run_id


def _llm_response(content=None, tool_calls=None, finish_reason=None):
    if finish_reason is None:
        finish_reason = "tool_calls" if tool_calls else "stop"
    return {
        "message": {"role": "assistant", "content": content, "tool_calls": tool_calls},
        "finish_reason": finish_reason,
        "usage": {"prompt": 10, "completion": 5, "total": 15},
    }


def _tc(name: str, args: dict, call_id: str = "c1") -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(args)},
    }


def _mock_api():
    api = MagicMock()
    api.get_schema = AsyncMock(return_value="# Schema")
    api.list_pages = AsyncMock(return_value=[])
    api.read_page = AsyncMock(return_value={"found": False})
    api.search_pages = AsyncMock(return_value=[])
    api.read_source = AsyncMock(return_value={"found": True, "content": "source"})
    api.create_page = AsyncMock(return_value={"ok": True, "filename": "sharding.md"})
    api.update_page = AsyncMock(return_value={"ok": True})
    api.append_page = AsyncMock(return_value={"ok": True})
    api.delete_page = AsyncMock(return_value={"ok": True})
    api.log_note = AsyncMock(return_value={"ok": True})
    api.write_runlog = AsyncMock()
    return api


@pytest.mark.asyncio
async def test_ingest_reads_source_then_creates_page_then_done():
    llm = MagicMock()
    llm.chat = AsyncMock(
        side_effect=[
            _llm_response(tool_calls=[_tc("get_schema", {})]),
            _llm_response(tool_calls=[_tc("list_pages", {})]),
            _llm_response(tool_calls=[_tc("read_source", {"document_id": "doc1"})]),
            _llm_response(
                tool_calls=[_tc("create_page", {"title": "Sharding", "content": "body"})]
            ),
            _llm_response(tool_calls=[_tc("done", {"summary": "created 1 page"})]),
        ]
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=10)
    result = await runner.run_ingest(
        RunRequest(
            tenant_id="t1",
            kb_id="kb1",
            run_id="run_x",
            source="test",
            document_id="doc1",
        )
    )

    assert result["status"] == "success"
    assert result["pages_created"] == 1
    assert result["pages_updated"] == 0
    assert result["summary"] == "created 1 page"
    assert result["tool_calls_count"] == 5  # get_schema, list, read_source, create, done
    assert result["token_count"] == 75  # 5 calls × 15 tokens
    api.create_page.assert_awaited_once_with("t1", "kb1", "Sharding", "body", [])
    api.write_runlog.assert_awaited_once()


@pytest.mark.asyncio
async def test_update_path_increments_updated_only():
    llm = MagicMock()
    llm.chat = AsyncMock(
        side_effect=[
            _llm_response(tool_calls=[_tc("get_schema", {})]),
            _llm_response(
                tool_calls=[
                    _tc("update_page", {"title": "Auth", "old_text": "x", "new_text": "y"})
                ]
            ),
            _llm_response(tool_calls=[_tc("done", {"summary": "updated auth"})]),
        ]
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=10)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_y", source="test", document_id="d1")
    )

    assert result["pages_created"] == 0
    assert result["pages_updated"] == 1
    assert result["pages_deleted"] == 0


@pytest.mark.asyncio
async def test_max_rounds_exceeded_reports_status():
    llm = MagicMock()
    # Return list_pages forever — agent never calls done()
    llm.chat = AsyncMock(
        return_value=_llm_response(tool_calls=[_tc("list_pages", {})])
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=3)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_z", source="test", document_id="d1")
    )

    assert result["status"] == "max_rounds_exceeded"
    assert result["error"] is not None
    assert "max" in result["error"].lower() or "round" in result["error"].lower()


@pytest.mark.asyncio
async def test_ingest_refuses_delete_page_without_crashing():
    llm = MagicMock()
    llm.chat = AsyncMock(
        side_effect=[
            _llm_response(tool_calls=[_tc("delete_page", {"title": "Old"})]),
            _llm_response(content="ok, I'll skip that", tool_calls=None),
        ]
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_a", source="test", document_id="d1")
    )

    # delete_page should have been refused at dispatch level, NOT sent to lakeon-api
    api.delete_page.assert_not_awaited()
    assert result["pages_deleted"] == 0
    # The LLM then gave a plain content response which we accept as implicit done
    assert result["status"] == "success"


@pytest.mark.asyncio
async def test_plain_content_response_is_implicit_done():
    llm = MagicMock()
    llm.chat = AsyncMock(
        return_value=_llm_response(content="all done, nothing to update", tool_calls=None)
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_b", source="test", document_id="d1")
    )

    assert result["status"] == "success"
    assert result["summary"] == "all done, nothing to update"


@pytest.mark.asyncio
async def test_tool_exception_is_captured_as_tool_result_not_crash():
    llm = MagicMock()
    llm.chat = AsyncMock(
        side_effect=[
            _llm_response(tool_calls=[_tc("list_pages", {})]),
            _llm_response(tool_calls=[_tc("done", {"summary": "recovered"})]),
        ]
    )
    api = _mock_api()
    api.list_pages = AsyncMock(side_effect=ConnectionError("api down"))

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_c", source="test", document_id="d1")
    )

    assert result["status"] == "success"
    assert result["summary"] == "recovered"


@pytest.mark.asyncio
async def test_curate_allows_delete_page():
    llm = MagicMock()
    llm.chat = AsyncMock(
        side_effect=[
            _llm_response(tool_calls=[_tc("delete_page", {"title": "Redundant"})]),
            _llm_response(tool_calls=[_tc("done", {"summary": "deleted 1"})]),
        ]
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_curate(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_d", source="manual")
    )

    api.delete_page.assert_awaited_once_with("t1", "kb1", "Redundant")
    assert result["pages_deleted"] == 1
    assert result["status"] == "success"


@pytest.mark.asyncio
async def test_llm_max_tokens_is_treated_as_error_not_success():
    llm = MagicMock()
    llm.chat = AsyncMock(
        return_value=_llm_response(
            content="truncated partial answer", tool_calls=None, finish_reason="length"
        )
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_len", source="test", document_id="d1")
    )

    assert result["status"] == "error"
    assert "max_tokens" in result["error"]


@pytest.mark.asyncio
async def test_done_with_sibling_tool_calls_runs_both():
    llm = MagicMock()
    llm.chat = AsyncMock(
        return_value=_llm_response(
            tool_calls=[
                _tc("create_page", {"title": "Auth", "content": "body"}, call_id="c1"),
                _tc("done", {"summary": "created + done in one response"}, call_id="c2"),
            ]
        )
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=5)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_dc", source="test", document_id="d1")
    )

    # Both calls should be executed even though they're in the same response
    api.create_page.assert_awaited_once_with("t1", "kb1", "Auth", "body", [])
    assert result["pages_created"] == 1
    assert result["status"] == "success"
    assert result["summary"] == "created + done in one response"


@pytest.mark.asyncio
async def test_counts_are_unique_titles_not_events():
    llm = MagicMock()
    llm.chat = AsyncMock(
        side_effect=[
            _llm_response(
                tool_calls=[
                    _tc("update_page", {"title": "Auth", "old_text": "x", "new_text": "y"}, call_id="c1")
                ]
            ),
            _llm_response(
                tool_calls=[
                    _tc("update_page", {"title": "Auth", "old_text": "y", "new_text": "z"}, call_id="c2")
                ]
            ),
            _llm_response(
                tool_calls=[
                    _tc("update_page", {"title": "Sharding", "old_text": "a", "new_text": "b"}, call_id="c3")
                ]
            ),
            _llm_response(tool_calls=[_tc("done", {"summary": "ok"})]),
        ]
    )
    api = _mock_api()

    runner = AgentRunner(llm=llm, api=api, max_rounds=10)
    result = await runner.run_ingest(
        RunRequest(tenant_id="t1", kb_id="kb1", run_id="run_u", source="test", document_id="d1")
    )

    # Auth updated twice, Sharding once → 2 unique pages, not 3 events
    assert result["pages_updated"] == 2


def test_new_run_id_is_unique():
    a = new_run_id()
    b = new_run_id()
    assert a != b
    assert a.startswith("run_")
