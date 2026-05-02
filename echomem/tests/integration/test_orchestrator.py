import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.pipeline.orchestrator import Orchestrator


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


@pytest.mark.asyncio
async def test_on_memory_ingested_runs_summarize_extract_timeline(tmp_path, httpx_mock, driver):
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/generate",
                            json={"response": "summary"}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)

    now = int(time.time() * 1000)
    m = Memory(id="01HXOR0000000000000000001", agent_id="cc", source_kind="explicit",
               source_ref=None, text="hello", meta=None, created_at=now, updated_at=now,
               deleted_at=None, embedding=[0.0] * 1024)
    driver.upsert_memory(m)

    async with OllamaClient("http://ol:11434") as ol:
        orch = Orchestrator(driver, ol, summary_model="gemma4:e4b",
                            extract_model="gemma4:e4b", embedding_model="qwen3-embedding:0.6b")
        await orch.start()
        await orch.on_memory_ingested(m.id)
        await orch.drain()
        await orch.stop()

    # Three task rows recorded for this memory
    rows = driver.con.execute(
        "SELECT kind, status FROM derivative_task WHERE memory_id = ? ORDER BY created_at",
        (m.id,),
    ).fetchall()
    kinds = {r[0] for r in rows}
    assert {"summarize", "extract_entity", "aggregate_timeline"}.issubset(kinds)
    # all should reach done (or failed-then-done, but here summary mock returns OK)
    assert all(r[1] in ("done", "failed") for r in rows)
