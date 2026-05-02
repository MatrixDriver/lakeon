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
    httpx_mock.add_response(
        method="POST",
        url="http://localhost:11434/api/generate",
        json={"response": "mock summary"},
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


@pytest.mark.asyncio
async def test_recall_after_ingest(client):
    await client.post(
        "/memory/ingest", json={"text": "alpha bravo", "agent_id": "cc"}
    )
    await client.post(
        "/memory/ingest", json={"text": "echo foxtrot", "agent_id": "openclaw"}
    )

    r = await client.post(
        "/memory/recall", json={"query": "alpha", "k": 5}
    )
    assert r.status_code == 200
    hits = r.json()["hits"]
    assert any(h["text"] == "alpha bravo" for h in hits)


@pytest.mark.asyncio
async def test_recall_filter_by_agent(client):
    await client.post(
        "/memory/ingest", json={"text": "secret cc", "agent_id": "cc"}
    )
    await client.post(
        "/memory/ingest", json={"text": "secret hermes", "agent_id": "hermes"}
    )

    r = await client.post(
        "/memory/recall", json={"query": "secret", "k": 5, "agent_id": "hermes"}
    )
    hits = r.json()["hits"]
    assert all(h["text"] != "secret cc" for h in hits)
    assert any(h["text"] == "secret hermes" for h in hits)


@pytest.mark.asyncio
async def test_list_recent_first(client):
    for txt in ["one", "two", "three"]:
        await client.post("/memory/ingest", json={"text": txt, "agent_id": "cc"})
    r = await client.get("/memory/list?limit=10")
    items = r.json()["items"]
    assert [i["text"] for i in items] == ["three", "two", "one"]


@pytest.mark.asyncio
async def test_get_returns_memory(client):
    r = await client.post("/memory/ingest", json={"text": "x", "agent_id": "cc"})
    mid = r.json()["id"]
    r2 = await client.get(f"/memory/{mid}")
    assert r2.status_code == 200
    assert r2.json()["text"] == "x"


@pytest.mark.asyncio
async def test_get_404_when_missing(client):
    r = await client.get("/memory/01HXMISSINGMISSINGMISSINGM")
    assert r.status_code == 404


@pytest.mark.asyncio
async def test_delete_then_get_404(client):
    r = await client.post("/memory/ingest", json={"text": "del", "agent_id": "cc"})
    mid = r.json()["id"]
    r2 = await client.delete(f"/memory/{mid}")
    assert r2.status_code == 200
    r3 = await client.get(f"/memory/{mid}")
    assert r3.status_code == 404


@pytest.mark.asyncio
async def test_ingest_triggers_pipeline(client):
    r = await client.post("/memory/ingest", json={"text": "hello pipeline", "agent_id": "cc"})
    assert r.status_code == 200
    mid = r.json()["id"]

    # Drain background pipeline (test harness exposes orchestrator on app.state)
    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    rows = app.state.driver.con.execute(
        "SELECT kind FROM derivative_task WHERE memory_id = ? ORDER BY kind", (mid,)
    ).fetchall()
    kinds = {r[0] for r in rows}
    assert {"aggregate_timeline", "extract_entity", "summarize"}.issubset(kinds)
