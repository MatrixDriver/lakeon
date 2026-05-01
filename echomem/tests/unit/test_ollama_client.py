import pytest
import httpx
from echomem.ollama_client import OllamaClient


@pytest.mark.asyncio
async def test_embed_returns_floats(httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        json={"embedding": [0.1, 0.2, 0.3]},
    )
    async with OllamaClient("http://localhost:11434") as c:
        v = await c.embed("hello", model="qwen3-embedding:0.6b")
    assert v == [0.1, 0.2, 0.3]


@pytest.mark.asyncio
async def test_embed_raises_on_5xx(httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        status_code=500,
        json={"error": "boom"},
    )
    async with OllamaClient("http://localhost:11434") as c:
        with pytest.raises(httpx.HTTPStatusError):
            await c.embed("hello", model="qwen3-embedding:0.6b")
