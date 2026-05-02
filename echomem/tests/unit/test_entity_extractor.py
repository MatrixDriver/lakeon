import json
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.workers.entity_extractor import EntityExtractorWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _seed(driver, mid="01HXEX0000000000000000000", text="Jacky works on echomem."):
    now = int(time.time() * 1000)
    m = Memory(id=mid, agent_id="cc", source_kind="explicit", source_ref=None,
               text=text, meta=None, created_at=now, updated_at=now, deleted_at=None,
               embedding=[0.0] * 1024)
    driver.upsert_memory(m)
    return m


@pytest.mark.asyncio
async def test_extracts_high_confidence_triple_into_main_table(tmp_path, httpx_mock, driver):
    m = _seed(driver)
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": json.dumps({
            "triples": [
                {"subject": "Jacky", "predicate": "works_on", "object": "echomem", "confidence": 0.9}
            ]
        })},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = EntityExtractorWorker(driver, ol, model="gemma4:e4b", confidence_threshold=0.7)
        await worker.handle(m.id)

    sub = driver.query_subgraph(seed_id=worker._entity_id("Jacky"), hops=1)
    assert any(e.name == "Jacky" for e in sub.nodes)
    assert any(e.name == "echomem" for e in sub.nodes)
    pending = driver.list_pending_triples()
    assert len(pending) == 0


@pytest.mark.asyncio
async def test_low_confidence_routes_to_pending(tmp_path, httpx_mock, driver):
    m = _seed(driver, mid="01HXEX0000000000000000001", text="Maybe X relates to Y somehow.")
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": json.dumps({
            "triples": [
                {"subject": "X", "predicate": "relates_to", "object": "Y", "confidence": 0.4}
            ]
        })},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = EntityExtractorWorker(driver, ol, model="gemma4:e4b")
        await worker.handle(m.id)

    pending = driver.list_pending_triples()
    assert len(pending) == 1
    assert pending[0]["subject_text"] == "X"
    # main triple table empty
    sub = driver.query_subgraph(seed_id="ent:x", hops=1)
    assert len(sub.edges) == 0


@pytest.mark.asyncio
async def test_malformed_llm_response_does_not_crash(tmp_path, httpx_mock, driver):
    m = _seed(driver, mid="01HXEX0000000000000000002")
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": "not valid json at all"},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = EntityExtractorWorker(driver, ol, model="gemma4:e4b")
        # should not raise
        await worker.handle(m.id)
    assert driver.list_pending_triples() == []
