import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite", embedding_dim=1024)
    yield d
    d.close()


def _make_mem(text="hello world", emb=None, agent="cc", mid="01HXEXAMPLEMEMORY00001"):
    now = int(time.time() * 1000)
    return Memory(
        id=mid,
        agent_id=agent,
        source_kind="explicit",
        source_ref=None,
        text=text,
        meta={"tag": "smoke"},
        created_at=now,
        updated_at=now,
        deleted_at=None,
        embedding=emb or [0.0] * 1024,
    )


def test_upsert_then_get(driver):
    m = _make_mem()
    driver.upsert_memory(m)
    got = driver.get_memory(m.id)
    assert got is not None
    assert got.text == "hello world"
    assert got.meta == {"tag": "smoke"}
    assert got.agent_id == "cc"


def test_get_returns_none_when_missing(driver):
    assert driver.get_memory("01HXNOTEXISTNOTEXISTNOTEX") is None


def test_upsert_overwrites(driver):
    m = _make_mem(text="v1")
    driver.upsert_memory(m)
    m2 = _make_mem(text="v2")
    driver.upsert_memory(m2)
    got = driver.get_memory(m.id)
    assert got.text == "v2"


def test_upsert_skips_vec_when_embedding_none(driver):
    m = _make_mem()
    m.embedding = None
    driver.upsert_memory(m)
    got = driver.get_memory(m.id)
    assert got is not None
