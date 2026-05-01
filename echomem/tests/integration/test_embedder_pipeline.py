import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.workers.embedder import EmbedderWorker


@pytest.mark.asyncio
async def test_embedder_writes_vec(tmp_path, httpx_mock):
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/embeddings",
        json={"embedding": [0.0] * 1024},
    )
    driver = SQLiteDriver(tmp_path / "db.sqlite")
    now = int(time.time() * 1000)
    m = Memory(
        id="01HXEMBED0000000000000000",
        agent_id="cc",
        source_kind="explicit",
        source_ref=None,
        text="hi",
        meta=None,
        created_at=now,
        updated_at=now,
        embedding=None,
    )
    driver.upsert_memory(m)

    async with OllamaClient("http://ol:11434") as ol:
        worker = EmbedderWorker(driver, ol, model="qwen3-embedding:0.6b")
        await worker.handle(m.id, m.text)

    rows = driver.con.execute(
        "SELECT memory_id FROM memory_vec WHERE memory_id = ?", (m.id,)
    ).fetchall()
    assert len(rows) == 1
    driver.close()
