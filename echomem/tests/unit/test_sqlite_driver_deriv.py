import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import (
    Memory,
    Summary,
    Entity,
    Triple,
    Event,
    Skill,
)


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite", embedding_dim=1024)
    yield d
    d.close()


def _mem(driver, mid="01HXMEM00000000000000000A", text="hello", agent="cc"):
    now = int(time.time() * 1000)
    m = Memory(
        id=mid, agent_id=agent, source_kind="explicit", source_ref=None,
        text=text, meta=None, created_at=now, updated_at=now, deleted_at=None,
        embedding=[0.0] * 1024,
    )
    driver.upsert_memory(m)
    return m


def test_upsert_summary_and_query_tree(driver):
    m = _mem(driver)
    now = int(time.time() * 1000)
    l0 = Summary(id="01S0", source_kind="memory", source_ref=m.id, level=0, parent_id=None,
                 text="L0 short", token_estimate=10, created_at=now, rationale="fits ≤ 100t")
    l1 = Summary(id="01S1", source_kind="memory", source_ref=m.id, level=1, parent_id=l0.id,
                 text="L1 medium", token_estimate=50, created_at=now, rationale=None)
    driver.upsert_summary(l0)
    driver.upsert_summary(l1)
    tree = driver.query_tree(source_kind="memory", source_ref=m.id)
    assert {s.level for s in tree} == {0, 1}


def test_upsert_entity_and_triple(driver):
    m = _mem(driver)
    now = int(time.time() * 1000)
    e1 = Entity(id="ent:jacky", name="Jacky", kind="person", meta=None, first_seen_at=now, last_seen_at=now)
    e2 = Entity(id="ent:echomem", name="echomem", kind="project", meta=None, first_seen_at=now, last_seen_at=now)
    driver.upsert_entity(e1)
    driver.upsert_entity(e2)
    t = Triple(id="tr:1", subject_id=e1.id, predicate="works_on", object_id=e2.id,
               source_memory_id=m.id, confidence=0.95, created_at=now)
    driver.upsert_triple(t)

    sub = driver.query_subgraph(seed_id=e1.id, hops=1)
    assert len(sub.nodes) >= 2
    assert any(edge[2]["predicate"] == "works_on" for edge in sub.edges)


def test_pending_triple_isolated(driver):
    m = _mem(driver)
    now = int(time.time() * 1000)
    driver.upsert_pending_triple(
        id="tp:1", subject_text="?Jacky", predicate="maybe_likes", object_text="?cats",
        source_memory_id=m.id, confidence=0.4, created_at=now,
    )
    pending = driver.list_pending_triples()
    assert len(pending) == 1
    # main triple table 不受影响
    sub = driver.query_subgraph(seed_id="ent:nonexistent", hops=1)
    assert len(sub.nodes) == 0


def test_event_aggregation(driver):
    now = int(time.time() * 1000)
    ev = Event(
        id="ev:1", window_start=now - 60_000, window_end=now, agent_id="cc",
        title="dev session", summary="working on echomem",
        member_memory_ids=["01M1", "01M2"], created_at=now,
        rationale="topic similarity 0.8 + same window",
    )
    driver.upsert_event(ev)
    items = driver.query_timeline(start_ms=now - 120_000, end_ms=now + 1, agent_id="cc")
    assert len(items) == 1
    assert items[0].title == "dev session"


def test_skill_upsert_and_recall(driver):
    now = int(time.time() * 1000)
    sk = Skill(
        id="sk:tdd", name="TDD",
        trigger_pattern="when implementing a feature, write test first",
        trigger_emb=[1.0] + [0.0] * 1023,
        steps=["write test", "run fail", "implement", "run pass", "commit"],
        agent_scope="all", source="imported", observed_count=0, success_count=0,
        last_used_at=None, created_at=now, rationale=None,
    )
    driver.upsert_skill(sk)
    hits = driver.query_skills(query_emb=[1.0] + [0.0] * 1023, k=3)
    assert any(h.name == "TDD" for h in hits)
