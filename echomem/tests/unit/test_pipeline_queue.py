import asyncio
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.pipeline.queue import WorkerPool, TaskKind


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


@pytest.mark.asyncio
async def test_enqueue_runs_handler_once(driver):
    seen: list[str] = []

    async def handler(memory_id: str):
        seen.append(memory_id)

    pool = WorkerPool(driver, handlers={TaskKind.SUMMARIZE: handler})
    await pool.start()
    await pool.enqueue(TaskKind.SUMMARIZE, memory_id="01HXM0000000000000000A")
    await pool.drain()
    await pool.stop()
    assert seen == ["01HXM0000000000000000A"]


@pytest.mark.asyncio
async def test_failing_handler_retries_then_dead_letters(driver):
    attempts: list[int] = []

    async def handler(memory_id: str):
        attempts.append(1)
        raise RuntimeError("nope")

    pool = WorkerPool(driver, handlers={TaskKind.SUMMARIZE: handler}, max_attempts=3,
                     retry_base_seconds=0)  # no real backoff in tests
    await pool.start()
    await pool.enqueue(TaskKind.SUMMARIZE, memory_id="01HXM0000000000000000B")
    await pool.drain()
    await pool.stop()

    assert len(attempts) == 3
    rows = driver.con.execute("SELECT count(*) FROM dead_letter").fetchone()
    assert rows[0] == 1


@pytest.mark.asyncio
async def test_unknown_kind_is_dead_lettered_immediately(driver):
    pool = WorkerPool(driver, handlers={})
    await pool.start()
    await pool.enqueue(TaskKind.SUMMARIZE, memory_id="01HXM0000000000000000C")
    await pool.drain()
    await pool.stop()

    rows = driver.con.execute("SELECT error FROM dead_letter").fetchone()
    assert rows is not None
    assert "unknown" in rows[0].lower() or "no handler" in rows[0].lower()
