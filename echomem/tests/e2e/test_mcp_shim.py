import json
import io
import pytest
from echomem.mcp_shim.shim import handle_message


@pytest.mark.asyncio
async def test_initialize_returns_capabilities():
    msg = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}
    out = await handle_message(msg, base_url="http://t")
    assert out["jsonrpc"] == "2.0"
    assert out["id"] == 1
    assert "capabilities" in out["result"]
    assert "tools" in out["result"]["capabilities"]


@pytest.mark.asyncio
async def test_tools_list_returns_5_memory_tools():
    msg = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
    out = await handle_message(msg, base_url="http://t")
    names = {t["name"] for t in out["result"]["tools"]}
    assert {
        "memory_ingest",
        "memory_recall",
        "memory_list",
        "memory_get",
        "memory_delete",
    }.issubset(names)


import asyncio
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.mark.asyncio
async def test_tools_call_memory_ingest_via_real_daemon(tmp_path, httpx_mock, monkeypatch):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        json={"embedding": [0.0] * 1024},
        is_reusable=True,
        is_optional=True,
    )
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)

    # mount the FastAPI app on httpx via ASGITransport
    transport = ASGITransport(app=app)

    async with app.router.lifespan_context(app):
        # monkey-patch httpx.AsyncClient inside shim module to use ASGI transport
        import httpx
        original_async_client = httpx.AsyncClient

        def _AsyncClient(*args, **kwargs):
            kwargs["transport"] = transport
            return original_async_client(*args, **kwargs)

        monkeypatch.setattr(httpx, "AsyncClient", _AsyncClient)

        msg = {
            "jsonrpc": "2.0",
            "id": 10,
            "method": "tools/call",
            "params": {
                "name": "memory_ingest",
                "arguments": {"text": "hello via shim", "agent_id": "cc"},
            },
        }
        out = await handle_message(msg, base_url="http://t")

    assert out["jsonrpc"] == "2.0"
    assert "result" in out
    payload = json.loads(out["result"]["content"][0]["text"])
    assert "id" in payload
    assert payload["agent_id"] == "cc"
