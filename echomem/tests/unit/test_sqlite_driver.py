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


def _emb(seed: int, dim: int = 1024) -> list[float]:
    # 简单确定性 embedding：第 seed 维 = 1.0，其余 0.0；用于近邻测试
    v = [0.0] * dim
    v[seed % dim] = 1.0
    return v


def test_recall_by_vector_similarity(driver):
    for seed, txt in [(0, "alpha"), (1, "beta"), (2, "gamma")]:
        mid = f"01HXSEED{seed:018d}"
        m = Memory(
            id=mid,
            agent_id="cc",
            source_kind="explicit",
            source_ref=None,
            text=txt,
            meta=None,
            created_at=seed,
            updated_at=seed,
            deleted_at=None,
            embedding=_emb(seed),
        )
        driver.upsert_memory(m)

    hits = driver.recall(_emb(1), query_text="", k=2)
    assert len(hits) >= 1
    assert hits[0].text == "beta"


def test_recall_by_fts(driver):
    for i, txt in enumerate(["alpha bravo", "charlie delta", "echo foxtrot"]):
        m = Memory(
            id=f"01HXFTS{i:020d}",
            agent_id="cc",
            source_kind="explicit",
            source_ref=None,
            text=txt,
            meta=None,
            created_at=i,
            updated_at=i,
            deleted_at=None,
            embedding=_emb(i),
        )
        driver.upsert_memory(m)

    hits = driver.recall(_emb(99), query_text="bravo", k=3)
    assert any(h.text == "alpha bravo" for h in hits)


def test_recall_filter_by_agent(driver):
    for i, agent in enumerate(["cc", "openclaw", "hermes"]):
        m = Memory(
            id=f"01HXAGT{i:020d}",
            agent_id=agent,
            source_kind="explicit",
            source_ref=None,
            text=f"text {agent}",
            meta=None,
            created_at=i,
            updated_at=i,
            deleted_at=None,
            embedding=_emb(i),
        )
        driver.upsert_memory(m)

    hits = driver.recall(_emb(0), query_text="text", k=10, agent_id="cc")
    assert all(h.source_kind == "explicit" for h in hits)
    # 验证只命中 cc
    for h in hits:
        got = driver.get_memory(h.memory_id)
        assert got.agent_id == "cc"
