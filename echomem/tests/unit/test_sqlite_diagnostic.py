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
