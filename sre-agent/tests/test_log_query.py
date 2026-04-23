import time
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
