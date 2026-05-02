import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.workers.reflector import ReflectorWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


@pytest.mark.asyncio
async def test_reflect_once_returns_stats(driver):
    worker = ReflectorWorker(driver)
    stats = await worker.reflect_once()
    assert "considered" in stats
    assert stats["status"] == "noop"


@pytest.mark.asyncio
async def test_reflect_runs_without_memories(driver):
    worker = ReflectorWorker(driver)
    # should not raise on empty store
    stats = await worker.reflect_once()
    assert stats["considered"] == 0
