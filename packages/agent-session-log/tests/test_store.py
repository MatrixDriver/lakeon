import json
from pathlib import Path

import pytest

from agent_session_log.evidence import Blob
from agent_session_log.store import FilesystemStore
from agent_session_log.types import SessionManifest


def make_manifest(sid: str = "sess_20260423T091230_a1b2c3") -> SessionManifest:
    return SessionManifest(
        id=sid,
        type="incident",
        created_at="2026-04-23T09:12:30Z",
        closed_at=None,
        status="open",
        trigger={"source": "cron/test", "context": {}},
        tags=["component:compute"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )


def test_session_dir_layout(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    d = store.session_dir(m.id)
    assert d.parent.name == "23"
    assert d.parent.parent.name == "04"
    assert d.parent.parent.parent.name == "2026"
    assert d.name == m.id


def test_write_read_manifest(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    loaded = store.read_manifest(m.id)
    assert loaded.id == m.id
    assert loaded.type == "incident"
    assert loaded.tags == ["component:compute"]


def test_append_and_read_events(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    store.append_event(m.id, "main", {"turn": 0, "type": "trigger", "t": "2026-04-23T09:12:30Z"})
    store.append_event(m.id, "main", {"turn": 1, "type": "thought", "content": "hi"})
    events = store.read_events(m.id, "main")
    assert len(events) == 2
    assert events[0]["type"] == "trigger"
    assert events[1]["content"] == "hi"


def test_write_blob_content_addressed(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    blob1 = store.write_blob(m.id, b"hello world", mime="text/plain", source="log")
    blob2 = store.write_blob(m.id, b"hello world", mime="text/plain", source="log")
    # Same content → same sha256, one file on disk
    assert blob1.sha256 == blob2.sha256
    ev_dir = store.session_dir(m.id) / "evidence" / "by-hash"
    files = list(ev_dir.glob(f"{blob1.sha256}*"))
    assert len(files) == 1


def test_read_blob(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    blob = store.write_blob(m.id, b"payload", mime="text/plain")
    raw = store.read_blob(m.id, blob.sha256)
    assert raw == b"payload"


def test_conclusion_versioning(tmp_log_root: Path):
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    store.write_conclusion(m.id, "root cause: X")
    store.write_conclusion(m.id, "root cause: Y (refined)")
    d = store.session_dir(m.id)
    assert (d / "conclusion.md").read_text().strip() == "root cause: Y (refined)"
    hist = list((d / "conclusion-history").glob("v*.md"))
    assert len(hist) == 1  # previous version preserved
    assert "root cause: X" in hist[0].read_text()


def test_events_jsonl_format(tmp_log_root: Path):
    """Events are line-delimited JSON — each line parses independently."""
    store = FilesystemStore(tmp_log_root)
    m = make_manifest()
    store.init_session(m)
    store.append_event(m.id, "main", {"turn": 0, "type": "trigger"})
    path = store.session_dir(m.id) / "events.jsonl"
    lines = path.read_text().strip().split("\n")
    for line in lines:
        json.loads(line)  # must not raise
