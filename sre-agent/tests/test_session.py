from pathlib import Path

import pytest

from agent_session_log.evidence import Blob
from agent_session_log.session import Branch, Session
from agent_session_log.store import FilesystemStore


def test_session_new_creates_manifest_and_empty_events(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(
        store=store,
        type="incident",
        trigger={"source": "cron/test", "alert": "cold start 8200ms"},
        tags=["component:compute"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )
    assert s.id.startswith("sess_")
    assert s.status == "open"
    manifest = store.read_manifest(s.id)
    assert manifest.type == "incident"
    assert manifest.status == "open"


def test_session_append_turn_and_conclude(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    t0 = s.append_turn(type="thought", content="let me look at the logs")
    t1 = s.append_turn(type="tool_call", tool="log_search", args={"since": "5m"})
    t2 = s.append_turn(type="tool_result", ref_turn=t1, truncated=False)
    assert (t0, t1, t2) == (0, 1, 2)
    s.conclude("root cause: pageserver re-attach")
    s.close()
    assert s.status == "closed"
    m = store.read_manifest(s.id)
    assert m.status == "closed"
    assert m.closed_at is not None
    assert store.read_conclusion(s.id).startswith("root cause")


def test_session_branches(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    b_img = s.branch("h1-image-pull")
    b_ps = s.branch("h2-pageserver")
    b_img.append_turn(type="tool_call", tool="log_search", args={"component": "image"})
    b_ps.append_turn(type="tool_call", tool="log_search", args={"component": "pageserver"})
    s.resolve_branches(keep="h2-pageserver", discard=["h1-image-pull"], reason="metric X showed Y")
    events_main = store.read_events(s.id, "main")
    assert any(e.get("type") == "branch_resolve" for e in events_main)
    assert len(store.read_events(s.id, "h1-image-pull")) == 1
    assert len(store.read_events(s.id, "h2-pageserver")) == 1


def test_session_attach_evidence_to_turn(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    blob = s.attach_evidence(b"raw log dump", mime="text/plain", source="log_search")
    assert blob.sha256 is not None
    tid = s.append_turn(type="tool_result", ref_turn=0, evidence=[blob.sha256])
    events = store.read_events(s.id, "main")
    turn = next(e for e in events if e.get("turn") == tid)
    assert blob.sha256 in turn["evidence"]


def test_session_record_outcome(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    s.conclude("fix: X")
    s.close()
    s.record_outcome(did_work=True, notes="cold start p95 back to 2.1s")
    out = store.read_outcome(s.id)
    assert "did_work: true" in out.lower() or "did work: true" in out.lower()
    assert "2.1s" in out


def test_session_load_roundtrip(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s1 = Session.new(store=store, type="incident", trigger={"k": "v"}, tags=["t1"])
    s1.append_turn(type="thought", content="hello")
    sid = s1.id
    s2 = Session.load(store=store, session_id=sid)
    assert s2.id == sid
    assert s2.next_turn_id == 1  # we appended one turn


def test_session_resumes_branch_turn_counter(tmp_log_root: Path):
    """When loading a session with existing branch events, branch() resumes from the right turn id."""
    store = FilesystemStore(tmp_log_root)
    s1 = Session.new(store=store, type="incident", trigger={}, tags=[])
    b1 = s1.branch("h1")
    b1.append_turn(type="thought", content="a")
    b1.append_turn(type="thought", content="b")
    sid = s1.id

    s2 = Session.load(store=store, session_id=sid)
    b1_again = s2.branch("h1")
    tid = b1_again.append_turn(type="thought", content="c")
    assert tid == 2  # picks up after the existing two turns

    events = store.read_events(sid, "h1")
    turn_ids = [e["turn"] for e in events]
    assert turn_ids == [0, 1, 2]  # no duplicates


def test_session_append_after_close_raises(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    s.conclude("x")
    s.close()
    with pytest.raises(RuntimeError, match="closed"):
        s.append_turn(type="thought", content="late")


def test_session_double_close_raises(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    s.close()
    with pytest.raises(RuntimeError, match="already closed"):
        s.close()


def test_session_record_outcome_still_works_after_close(tmp_log_root: Path):
    """record_outcome must work post-close; that's its whole purpose (24h later)."""
    store = FilesystemStore(tmp_log_root)
    s = Session.new(store=store, type="incident", trigger={}, tags=[])
    s.conclude("x")
    s.close()
    s.record_outcome(did_work=True, notes="after close is fine")
    assert store.read_outcome(s.id) is not None
