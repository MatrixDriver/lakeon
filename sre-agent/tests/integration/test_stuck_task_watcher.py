from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.stuck_task_watcher.watcher import StuckTaskWatcher


def _fake_mcp(response):
    m = MagicMock()
    m.stuck_task_query = lambda *, threshold_minutes=10, type="": response
    return m


def test_no_stuck_tasks(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = StuckTaskWatcher(log=log, mcp=_fake_mcp({"count": 0, "tasks": []}))
    assert w.scan_once() == []


def test_stuck_tasks_open_one_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = StuckTaskWatcher(log=log, mcp=_fake_mcp({
        "count": 3,
        "tasks": [
            {"task_id": "t1", "task_type": "WIKI_UPDATE", "source": "wiki_run_logs", "age_sec": 700},
            {"task_id": "t2", "task_type": "WIKI_INGEST", "source": "wiki_run_logs", "age_sec": 900},
            {"task_id": "t3", "task_type": "FUSE_BACKFILL", "source": "agentfs_jobs", "age_sec": 1100},
        ],
    }))
    sids = w.scan_once()
    assert len(sids) == 1  # all grouped into one
    m = log.store.read_manifest(sids[0])
    assert m.trigger["count"] == 3


def test_dedupe_when_count_stable(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = StuckTaskWatcher(log=log, mcp=_fake_mcp({
        "count": 2,
        "tasks": [
            {"task_id": "t1", "task_type": "WIKI_UPDATE", "source": "wiki_run_logs", "age_sec": 700},
            {"task_id": "t2", "task_type": "FUSE_BACKFILL", "source": "agentfs_jobs", "age_sec": 700},
        ],
    }), dedupe_window_sec=600)
    first = w.scan_once()
    second = w.scan_once()
    assert len(first) == 1
    assert len(second) == 0
