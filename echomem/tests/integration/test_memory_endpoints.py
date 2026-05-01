import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/embeddings",
        json={"embedding": [0.0] * 1024},
        is_reusable=True,
        is_optional=True,
    )
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_ingest_returns_id_and_persists(client):
    r = await client.post(
        "/memory/ingest",
        json={"text": "hello world", "agent_id": "cc"},
    )
    assert r.status_code == 200
    body = r.json()
    assert "id" in body and len(body["id"]) == 26
    assert body["agent_id"] == "cc"


@pytest.mark.asyncio
async def test_ingest_validates_required_fields(client):
    r = await client.post("/memory/ingest", json={"agent_id": "cc"})
    assert r.status_code == 422
