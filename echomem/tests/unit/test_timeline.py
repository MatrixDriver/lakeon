import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.workers.timeline import TimelineWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _seed(driver, mid, agent, text, ts_ms, emb=None):
    m = Memory(
        id=mid, agent_id=agent, source_kind="explicit", source_ref=None,
        text=text, meta=None, created_at=ts_ms, updated_at=ts_ms, deleted_at=None,
        embedding=emb if emb is not None else [0.0] * 1024,
    )
    driver.upsert_memory(m)
    return m


def _emb(seed: int) -> list[float]:
    v = [0.0] * 1024
    v[seed] = 1.0
    return v


def test_two_close_similar_memories_become_one_event(driver):
    now = int(time.time() * 1000)
    m1 = _seed(driver, "01HXTL0000000000000000001", "cc", "fixing login", now,        _emb(0))
    m2 = _seed(driver, "01HXTL0000000000000000002", "cc", "still on login bug", now + 60_000, _emb(0))

    worker = TimelineWorker(driver)
    worker.handle(m1.id)
    worker.handle(m2.id)

    events = driver.query_timeline(start_ms=now - 1, end_ms=now + 120_000, agent_id="cc")
    assert len(events) == 1
    assert {m1.id, m2.id}.issubset(set(events[0].member_memory_ids))


def test_far_apart_in_time_become_two_events(driver):
    now = int(time.time() * 1000)
    m1 = _seed(driver, "01HXTL0000000000000000003", "cc", "task A", now,                _emb(0))
    m2 = _seed(driver, "01HXTL0000000000000000004", "cc", "task A again", now + 60 * 60_000, _emb(0))

    worker = TimelineWorker(driver)
    worker.handle(m1.id)
    worker.handle(m2.id)

    events = driver.query_timeline(start_ms=now - 1, end_ms=now + 120 * 60_000, agent_id="cc")
    assert len(events) == 2


def test_dissimilar_topic_becomes_new_event(driver):
    now = int(time.time() * 1000)
    m1 = _seed(driver, "01HXTL0000000000000000005", "cc", "auth refactor", now,         _emb(0))
    m2 = _seed(driver, "01HXTL0000000000000000006", "cc", "css tweaks",     now + 60_000, _emb(50))

    worker = TimelineWorker(driver)
    worker.handle(m1.id)
    worker.handle(m2.id)

    events = driver.query_timeline(start_ms=now - 1, end_ms=now + 120_000, agent_id="cc")
    assert len(events) == 2
