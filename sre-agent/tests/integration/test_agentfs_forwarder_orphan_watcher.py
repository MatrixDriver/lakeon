from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.agentfs_forwarder_orphan_watcher.watcher import AgentFSForwarderOrphanWatcher


def _fake_mcp(log_rows):
    m = MagicMock()
    m.log_search = lambda *, component="", keyword="", since="30m", limit=500, **_: log_rows
    return m


def _row(tid: str, level: str = "WARN") -> dict:
    return {
        "ts": f"t-{tid}",
        "level": level,
        "msg": f"forwarder: tenant {tid} not found",
    }


def test_no_warns_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = AgentFSForwarderOrphanWatcher(log=log, mcp=_fake_mcp([]))
    assert w.scan_once() == []


def test_below_threshold_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [_row("tn_aaa") for _ in range(3)]
    w = AgentFSForwarderOrphanWatcher(log=log, mcp=_fake_mcp(rows), occurrence_threshold=5)
    assert w.scan_once() == []


def test_orphan_tenant_opens_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [_row("tn_orphan") for _ in range(7)]
    w = AgentFSForwarderOrphanWatcher(log=log, mcp=_fake_mcp(rows), occurrence_threshold=5)
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "component:lakeon-api" in m.tags
    assert "logger:AgentFSEventForwarder" in m.tags
    assert m.trigger["tenant_id"] == "tn_orphan"
    assert m.trigger["warn_count"] == 7
    assert m.trigger["signal_id"] == "agentfs_forwarder_orphan:tn_orphan"


def test_multiple_orphans_one_incident_each(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = []
    for tid in ("tn_a", "tn_b", "tn_c"):
        rows.extend(_row(tid) for _ in range(6))
    rows.extend(_row("tn_quiet") for _ in range(2))  # below threshold, ignored
    w = AgentFSForwarderOrphanWatcher(log=log, mcp=_fake_mcp(rows), occurrence_threshold=5)
    sids = w.scan_once()
    assert len(sids) == 3


def test_dedupe_within_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [_row("tn_x") for _ in range(7)]
    w = AgentFSForwarderOrphanWatcher(
        log=log, mcp=_fake_mcp(rows), occurrence_threshold=5, dedupe_window_sec=600,
    )
    first = w.scan_once()
    second = w.scan_once()
    assert len(first) == 1
    assert len(second) == 0


def test_ignores_non_warn_lines(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [_row("tn_x", level="INFO") for _ in range(7)]  # INFO, not WARN
    w = AgentFSForwarderOrphanWatcher(log=log, mcp=_fake_mcp(rows), occurrence_threshold=5)
    assert w.scan_once() == []


def test_ignores_unrelated_forwarder_lines(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [
        {"ts": "t1", "level": "WARN", "msg": "forwarder started for tn_xxx"},
        {"ts": "t2", "level": "WARN", "msg": "some other forwarder thing"},
    ]
    w = AgentFSForwarderOrphanWatcher(log=log, mcp=_fake_mcp(rows), occurrence_threshold=1)
    assert w.scan_once() == []
