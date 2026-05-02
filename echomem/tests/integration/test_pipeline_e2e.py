import json
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.1] + [0.0] * 1023}, is_reusable=True)
    # gemma summarize replies; reused for L0/L1 calls
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": json.dumps({"triples": [
                                {"subject": "Jacky", "predicate": "ships",
                                 "object": "echomem", "confidence": 0.9}]})},
                            is_reusable=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_ingest_produces_summary_event_and_triple(client):
    r = await client.post("/memory/ingest",
                          json={"text": "Jacky ships echomem after a long sprint.",
                                "agent_id": "cc"})
    mid = r.json()["id"]

    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    # 1. timeline has 1 event with this memory id
    r1 = await client.get("/derivatives/timeline?agent=cc")
    assert any(mid in ev["member_memory_ids"] for ev in r1.json()["events"])

    # 2. tree has at least L2 (always) + maybe L0 (gemma returns the triple JSON as 'summary')
    r2 = await client.get(f"/derivatives/tree?source_kind=memory&source_ref={mid}")
    assert any(s["level"] == 2 for s in r2.json()["levels"])

    # 3. graph: Jacky → echomem
    r3 = await client.get("/derivatives/graph?seed=ent:jacky")
    edges = r3.json()["edges"]
    assert any(e["predicate"] == "ships" for e in edges)
