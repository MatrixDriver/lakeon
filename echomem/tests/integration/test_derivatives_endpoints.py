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
                            json={"response": "summary"}, is_reusable=True, is_optional=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_timeline_endpoint(client):
    r = await client.post("/memory/ingest", json={"text": "morning task", "agent_id": "cc"})
    mid = r.json()["id"]
    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    r2 = await client.get("/derivatives/timeline?agent=cc")
    assert r2.status_code == 200
    body = r2.json()
    assert "events" in body and len(body["events"]) >= 1
    assert mid in body["events"][0]["member_memory_ids"]


@pytest.mark.asyncio
async def test_tree_endpoint(client):
    r = await client.post("/memory/ingest", json={"text": "the quick brown fox", "agent_id": "cc"})
    mid = r.json()["id"]
    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    r2 = await client.get(f"/derivatives/tree?source_kind=memory&source_ref={mid}")
    assert r2.status_code == 200
    levels = {s["level"] for s in r2.json()["levels"]}
    assert 2 in levels  # at minimum L2 always present


@pytest.mark.asyncio
async def test_graph_endpoint_empty_when_no_entities(client):
    r2 = await client.get("/derivatives/graph?seed=ent:nonexistent")
    assert r2.status_code == 200
    body = r2.json()
    assert body["nodes"] == []
    assert body["edges"] == []


@pytest.mark.asyncio
async def test_skills_endpoint_empty_when_none_imported(client):
    r2 = await client.get("/derivatives/skills?ctx=write+a+test+first")
    assert r2.status_code == 200
    assert r2.json()["skills"] == []
