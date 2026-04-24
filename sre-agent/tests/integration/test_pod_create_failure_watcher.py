from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.pod_create_failure_watcher.watcher import PodCreateFailureWatcher


def _fake_mcp(response):
    m = MagicMock()
    m.pod_create_failures = lambda *, since="1h": response
    return m


def test_no_failures_opens_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = PodCreateFailureWatcher(log=log, mcp=_fake_mcp({"count": 0, "by_category": {}, "failures": []}))
    assert w.scan_once() == []


def test_opens_one_incident_per_category(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = PodCreateFailureWatcher(log=log, mcp=_fake_mcp({
        "count": 3,
        "by_category": {"InvalidName": 2, "CrashLoopBackOff": 1},
        "failures": [
            {"ts": "t1", "tenant_id": "t_a", "error": "InvalidName: foo", "category": "InvalidName"},
            {"ts": "t2", "tenant_id": "t_b", "error": "InvalidName: bar", "category": "InvalidName"},
            {"ts": "t3", "tenant_id": "t_c", "error": "CrashLoopBackOff", "category": "CrashLoopBackOff"},
        ],
    }))
    sids = w.scan_once()
    assert len(sids) == 2  # 2 categories → 2 incidents

    # Verify category tag + tenant list in conclusion
    for sid in sids:
        m = log.store.read_manifest(sid)
        cat = next((t for t in m.tags if t.startswith("category:")), None)
        assert cat is not None


def test_deduplicates_same_category_within_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    mcp = _fake_mcp({
        "count": 1, "by_category": {"InvalidName": 1},
        "failures": [{"ts": "t", "tenant_id": "t_a", "error": "InvalidName", "category": "InvalidName"}],
    })
    w = PodCreateFailureWatcher(log=log, mcp=mcp, dedupe_window_sec=600)
    first = w.scan_once()
    second = w.scan_once()  # same signal, within window
    assert len(first) == 1
    assert len(second) == 0


def test_reply_text_format(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = PodCreateFailureWatcher(log=log, mcp=_fake_mcp({
        "count": 2, "by_category": {"InvalidName": 2},
        "failures": [
            {"ts": "t1", "tenant_id": "t_a", "error": "InvalidName: a", "category": "InvalidName"},
            {"ts": "t2", "tenant_id": "t_b", "error": "InvalidName: b", "category": "InvalidName"},
        ],
    }))
    w.scan_once()
    report = w.build_feishu_report("InvalidName", 2, ["t_a", "t_b"])
    assert "InvalidName" in report
    assert "2" in report
    assert "t_a" in report
