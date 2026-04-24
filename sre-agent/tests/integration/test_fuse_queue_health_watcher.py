from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.fuse_queue_health_watcher.watcher import FuseQueueHealthWatcher


def _fake_mcp(log_rows):
    m = MagicMock()
    m.log_search = lambda *, component="", keyword="", since="15m", limit=200, **_: log_rows
    return m


def test_no_stuck_blobs_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = FuseQueueHealthWatcher(log=log, mcp=_fake_mcp([
        {"ts": "t1", "msg": "retry blob_id=b1 attempt=1"},
        {"ts": "t2", "msg": "retry blob_id=b1 attempt=2"},
    ]), retry_threshold=5)
    assert w.scan_once() == []


def test_stuck_blob_opens_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [
        {"ts": f"t{i}", "msg": f"retry blob_id=b_stuck attempt={i}", "tenant_id": "t_a"}
        for i in range(1, 8)  # 7 retries
    ]
    w = FuseQueueHealthWatcher(log=log, mcp=_fake_mcp(rows), retry_threshold=5)
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "component:dbay-fuse" in m.tags
    assert "b_stuck" in str(m.trigger)


def test_deduplicates_same_blob_within_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    rows = [{"ts": f"t{i}", "msg": f"retry blob_id=b_x attempt={i}", "tenant_id": "t_a"}
            for i in range(1, 8)]
    w = FuseQueueHealthWatcher(log=log, mcp=_fake_mcp(rows), retry_threshold=5, dedupe_window_sec=600)
    first = w.scan_once()
    second = w.scan_once()
    assert len(first) == 1
    assert len(second) == 0
