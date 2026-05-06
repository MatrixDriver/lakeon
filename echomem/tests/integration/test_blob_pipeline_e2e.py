import json
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": json.dumps({"triples": [
                                {"subject": "Echomem", "predicate": "ingests", "object": "URL",
                                 "confidence": 0.9}]})},
                            is_reusable=True, is_optional=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_add_url_runs_pipeline_and_writes_derivatives(client, httpx_mock):
    httpx_mock.add_response(
        method="GET", url="https://example.com/x",
        headers={"content-type": "text/html"},
        content=b"<html><body><article><p>Echomem ingests URLs and runs the pipeline.</p></article></body></html>",
    )
    r = await client.post("/context/add_url", json={"url": "https://example.com/x"})
    sha = r.json()["sha256"]

    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    # 1. tree exists for blob source
    r2 = await client.get(f"/context/read", params={"sha256": sha})
    assert r2.status_code == 200
    r3 = await client.get(f"/derivatives/tree?source_kind=blob&source_ref={sha}")
    assert r3.status_code == 200
    assert any(s["level"] == 2 for s in r3.json()["levels"])

    # 2. graph has Echomem → ingests → URL edge
    r4 = await client.get("/derivatives/graph?seed=ent:echomem&hops=2")
    edges = r4.json()["edges"]
    assert any(e["predicate"] == "ingests" for e in edges)
