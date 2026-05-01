import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.mark.asyncio
async def test_health_returns_200_and_version(tmp_path, httpx_mock):
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert "version" in body
    assert body["embedding_dim"] == 1024
