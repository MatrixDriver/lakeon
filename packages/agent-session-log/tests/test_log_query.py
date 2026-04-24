import pytest
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from agent_session_log.log import LogStore


def test_list_sessions_by_type(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s1 = log.new_session(type="incident", trigger={}, tags=["component:compute"])
    s1.conclude("c")
    s1.close()
    s2 = log.new_session(type="reading", trigger={}, tags=["source:web"])
    s2.close()
    incidents = log.list_sessions(type="incident")
    assert [x["id"] for x in incidents] == [s1.id]


def test_list_sessions_by_tags(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    a = log.new_session(type="incident", trigger={}, tags=["component:compute", "severity:high"])
    a.close()
    b = log.new_session(type="incident", trigger={}, tags=["component:pageserver"])
    b.close()
    matches = log.list_sessions(tags=["component:compute"])
    assert [x["id"] for x in matches] == [a.id]


def test_get_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={"x": 1}, tags=[])
    s.append_turn(type="thought", content="hi")
    s.conclude("ok")
    s.close()
    loaded = log.get_session(s.id)
    assert loaded.id == s.id


def test_search_text_in_conclusions(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    a = log.new_session(type="incident", trigger={}, tags=[])
    a.conclude("root cause: pageserver re-attach took 6.8s")
    a.close()
    b = log.new_session(type="incident", trigger={}, tags=[])
    b.conclude("root cause: image pull slow")
    b.close()
    hits = log.search_text("pageserver")
    assert [h["id"] for h in hits] == [a.id]


def test_replay_at_turn(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    for i in range(5):
        s.append_turn(type="thought", content=f"step {i}")
    s.conclude("done")
    s.close()
    snapshot = log.replay(s.id, at_turn=2)
    # snapshot returns list of turns up to and including turn 2
    assert [t["turn"] for t in snapshot] == [0, 1, 2]


def test_similar_raises_not_implemented(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    with pytest.raises(NotImplementedError, match="Phase 0b"):
        log.similar(s.id)


def test_list_sessions_since_filters_old(tmp_log_root):
    log = LogStore(tmp_log_root)
    old = log.new_session(type="reading", trigger={}, tags=[])
    old.close()
    m = log.store.read_manifest(old.id)
    m.created_at = (datetime.now(timezone.utc) - timedelta(days=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
    log.store.write_manifest(m)

    recent = log.new_session(type="reading", trigger={}, tags=[])
    recent.close()

    since_3d = log.list_sessions(type="reading", since="3d")
    assert [x["id"] for x in since_3d] == [recent.id]

    since_30d = log.list_sessions(type="reading", since="30d")
    assert {x["id"] for x in since_30d} == {old.id, recent.id}


def test_list_sessions_since_supports_seconds_minutes_hours(tmp_log_root):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    assert len(log.list_sessions(since="60s")) == 1
    assert len(log.list_sessions(since="5m")) == 1
    assert len(log.list_sessions(since="1h")) == 1
    assert len(log.list_sessions(since="1d")) == 1


def test_list_sessions_since_rejects_bad_format(tmp_log_root):
    log = LogStore(tmp_log_root)
    with pytest.raises(ValueError, match="since"):
        log.list_sessions(since="1 week")
    with pytest.raises(ValueError, match="since"):
        log.list_sessions(since="abc")


def test_list_sessions_since_none_returns_all(tmp_log_root):
    """Phase 0a SRE callers don't pass since — must keep working."""
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    assert len(log.list_sessions(type="incident")) == 1
    assert len(log.list_sessions(type="incident", since=None)) == 1
