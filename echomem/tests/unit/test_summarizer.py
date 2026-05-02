import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.workers.summarizer import SummarizerWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _seed_memory(driver, text="A long original chunk of text covering many sentences."):
    now = int(time.time() * 1000)
    m = Memory(
        id="01HXSUM00000000000000000A", agent_id="cc", source_kind="explicit",
        source_ref=None, text=text, meta=None, created_at=now, updated_at=now,
        deleted_at=None, embedding=[0.0] * 1024,
    )
    driver.upsert_memory(m)
    return m


@pytest.mark.asyncio
async def test_summarizer_writes_three_levels(tmp_path, httpx_mock, driver):
    m = _seed_memory(driver)
    # gemma will be called 2 times (L0, L1); L2 is the original chunk (no LLM call)
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": "L0 short summary."},
    )
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": "L1 medium-length summary covering main points and details."},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = SummarizerWorker(driver, ol, model="gemma4:e4b")
        await worker.handle(m.id)

    tree = driver.query_tree(source_kind="memory", source_ref=m.id)
    levels = sorted(s.level for s in tree)
    assert levels == [0, 1, 2]


@pytest.mark.asyncio
async def test_summarizer_falls_back_when_llm_fails(tmp_path, httpx_mock, driver):
    m = _seed_memory(driver, text="A" * 500)
    # both calls error out
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/generate",
                            status_code=500, json={"error": "boom"}, is_reusable=True)
    async with OllamaClient("http://ol:11434") as ol:
        worker = SummarizerWorker(driver, ol, model="gemma4:e4b")
        await worker.handle(m.id)

    tree = driver.query_tree(source_kind="memory", source_ref=m.id)
    # L2 always present (original chunk, no LLM)
    assert any(s.level == 2 for s in tree)
    # L0 falls back to truncate-prefix (≤ 100 chars), so it IS present (no LLM dependency)
    l0 = [s for s in tree if s.level == 0]
    assert len(l0) == 1
    assert "fallback" in (l0[0].rationale or "").lower()
