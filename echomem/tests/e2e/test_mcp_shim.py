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
