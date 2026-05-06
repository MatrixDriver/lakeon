import time
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": "ok"}, is_reusable=True, is_optional=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_add_url_writes_blob_and_returns_sha(client, httpx_mock):
    httpx_mock.add_response(method="GET", url="https://example.com/post",
                            headers={"content-type": "text/html"},
                            content=b"<html><body><article><p>Body text</p></article></body></html>")
    r = await client.post("/context/add_url", json={"url": "https://example.com/post"})
    assert r.status_code == 200
    body = r.json()
    assert "sha256" in body and len(body["sha256"]) == 64
    assert body["mime"].startswith("text/html")
    assert body["byte_size"] > 0
