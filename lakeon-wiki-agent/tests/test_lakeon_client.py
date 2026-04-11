"""LakeonApiClient hits the right endpoints with the right headers."""
import pytest
from pytest_httpx import HTTPXMock

from app.clients.lakeon_api import LakeonApiClient


@pytest.mark.asyncio
async def test_list_pages_calls_correct_endpoint(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/list_pages",
        json=[{"title": "Auth", "filename": "auth.md", "summary": ""}],
    )
    client = LakeonApiClient("http://api:8080", "token")
    pages = await client.list_pages("t1", "kb1")
    assert len(pages) == 1
    assert pages[0]["title"] == "Auth"

    req = httpx_mock.get_request()
    assert req.headers["Authorization"] == "Bearer token"
    assert req.headers["Content-Type"] == "application/json"


@pytest.mark.asyncio
async def test_read_page_posts_title_in_body(httpx_mock: HTTPXMock):
    import json
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/read_page",
        json={"found": True, "title": "Auth", "content": "body"},
    )
    client = LakeonApiClient("http://api:8080", "token")
    r = await client.read_page("t1", "kb1", "Auth")
    assert r["found"] is True

    req = httpx_mock.get_request()
    body = json.loads(req.content)
    assert body == {"tenant_id": "t1", "kb_id": "kb1", "title": "Auth"}


@pytest.mark.asyncio
async def test_search_pages_includes_top_k(httpx_mock: HTTPXMock):
    import json
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/search_pages",
        json=[],
    )
    client = LakeonApiClient("http://api:8080", "token")
    await client.search_pages("t1", "kb1", "auth", top_k=3)

    req = httpx_mock.get_request()
    body = json.loads(req.content)
    assert body["top_k"] == 3


@pytest.mark.asyncio
async def test_get_schema_unwraps_schema_field(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/get_schema",
        json={"schema": "# Schema\n..."},
    )
    client = LakeonApiClient("http://api:8080", "token")
    s = await client.get_schema("t1", "kb1")
    assert s == "# Schema\n..."


@pytest.mark.asyncio
async def test_get_schema_returns_empty_when_missing(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/get_schema",
        json={},
    )
    client = LakeonApiClient("http://api:8080", "token")
    s = await client.get_schema("t1", "kb1")
    assert s == ""


@pytest.mark.asyncio
async def test_create_page_posts_body(httpx_mock: HTTPXMock):
    import json
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/create_page",
        json={"ok": True, "filename": "sharding.md"},
    )
    client = LakeonApiClient("http://api:8080", "token")
    r = await client.create_page("t1", "kb1", "Sharding", "body", ["tag1"])
    assert r["ok"] is True

    req = httpx_mock.get_request()
    body = json.loads(req.content)
    assert body["title"] == "Sharding"
    assert body["content"] == "body"
    assert body["tags"] == ["tag1"]


@pytest.mark.asyncio
async def test_update_page_posts_old_new(httpx_mock: HTTPXMock):
    import json
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/update_page",
        json={"ok": True},
    )
    client = LakeonApiClient("http://api:8080", "token")
    await client.update_page("t1", "kb1", "Auth", "foo", "bar")

    req = httpx_mock.get_request()
    body = json.loads(req.content)
    assert body["old_text"] == "foo"
    assert body["new_text"] == "bar"


@pytest.mark.asyncio
async def test_delete_page_posts_title(httpx_mock: HTTPXMock):
    import json
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/delete_page",
        json={"ok": True},
    )
    client = LakeonApiClient("http://api:8080", "token")
    await client.delete_page("t1", "kb1", "Old")

    req = httpx_mock.get_request()
    body = json.loads(req.content)
    assert body == {"tenant_id": "t1", "kb_id": "kb1", "title": "Old"}


@pytest.mark.asyncio
async def test_log_note_posts_message(httpx_mock: HTTPXMock):
    import json
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/tool/log_note",
        json={"ok": True},
    )
    client = LakeonApiClient("http://api:8080", "token")
    await client.log_note("t1", "kb1", "ingested doc X")

    req = httpx_mock.get_request()
    body = json.loads(req.content)
    assert body["message"] == "ingested doc X"


@pytest.mark.asyncio
async def test_write_runlog_posts_to_runlog_endpoint(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        method="POST",
        url="http://api:8080/api/v1/internal/wiki/runlog",
        status_code=202,
        json={},
    )
    client = LakeonApiClient("http://api:8080", "token")
    await client.write_runlog({
        "tenantId": "t1",
        "kbId": "kb1",
        "runId": "run_x",
        "runType": "ingest",
        "triggerDoc": "doc1.md",
        "pagesCreated": 2,
        "pagesUpdated": 5,
        "pagesDeleted": 0,
        "durationMs": 12345,
        "status": "success",
        "errorMessage": None,
        "toolCallsCount": 18,
        "tokenCount": 4200,
        "source": "queue",
    })

    req = httpx_mock.get_request()
    assert req.url.path == "/api/v1/internal/wiki/runlog"
