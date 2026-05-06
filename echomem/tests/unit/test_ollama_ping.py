import pytest
from pytest_httpx import HTTPXMock

from echomem.ollama_client import OllamaClient


@pytest.mark.asyncio
async def test_ping_ok(httpx_mock: HTTPXMock):
    httpx_mock.add_response(url="http://localhost:11434/", text="Ollama is running")
    client = OllamaClient("http://localhost:11434")
    result = await client.ping()
    await client.aclose()
    assert result["status"] == "ok"
    assert result["latency_ms"] >= 0


@pytest.mark.asyncio
async def test_ping_unreachable():
    client = OllamaClient("http://127.0.0.1:1", timeout=1.0)
    result = await client.ping()
    await client.aclose()
    assert result["status"] in ("unreachable", "timeout")
    assert result["latency_ms"] is None
