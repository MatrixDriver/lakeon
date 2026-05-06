import time
from pathlib import Path

import pytest

from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory


@pytest.fixture
def driver(tmp_path: Path) -> SQLiteDriver:
    d = SQLiteDriver(tmp_path / "db.sqlite", embedding_dim=1024)
    yield d
    d.close()


def _mem(idx: int) -> Memory:
    now = int(time.time() * 1000)
    return Memory(
        id=f"m{idx:04d}", agent_id="t",
        source_kind="explicit", source_ref=None,
        text=f"hello {idx}", meta=None,
        created_at=now, updated_at=now, deleted_at=None,
        embedding=[0.1] * 1024,
    )


def test_count_memories_empty(driver):
    assert driver.count_memories() == 0


def test_count_memories_after_insert(driver):
    for i in range(5):
        driver.upsert_memory(_mem(i))
    assert driver.count_memories() == 5


def test_count_cognitions_zero_initially(driver):
    assert driver.count_cognitions() == 0


def test_count_entities_zero_initially(driver):
    assert driver.count_entities() == 0


def test_count_skills_zero_initially(driver):
    assert driver.count_skills() == 0


def test_worker_stats_empty(driver):
    stats = driver.worker_stats()
    assert "summarize" in stats and stats["summarize"]["queue_depth"] == 0
    assert stats["summarize"]["processed_total"] == 0
    assert stats["summarize"]["last_run_at"] is None


def test_worker_stats_after_inserts(driver):
    now = int(time.time() * 1000)
    driver.con.execute(
        "INSERT INTO derivative_task(id,kind,memory_id,status,attempts,created_at,updated_at) "
        "VALUES('t1','summarize','m0','pending',0,?,?)",
        (now, now),
    )
    driver.con.execute(
        "INSERT INTO derivative_task(id,kind,memory_id,status,attempts,created_at,updated_at) "
        "VALUES('t2','summarize','m1','done',1,?,?)",
        (now, now),
    )
    driver.con.commit()
    stats = driver.worker_stats()
    assert stats["summarize"]["queue_depth"] == 1
    assert stats["summarize"]["processed_total"] == 1
    assert stats["summarize"]["last_run_at"] == now


def test_list_dead_letters_empty(driver):
    assert driver.list_dead_letters(limit=10) == []


def test_list_dead_letters_after_insert(driver):
    now = int(time.time() * 1000)
    driver.con.execute(
        "INSERT INTO dead_letter(id,task_id,kind,memory_id,error,created_at) "
        "VALUES('d1','t1','summarize','m0','boom',?)",
        (now,),
    )
    driver.con.commit()
    items = driver.list_dead_letters(limit=10)
    assert len(items) == 1
    assert items[0]["worker"] == "summarize"
    assert items[0]["traceback"] == "boom"
