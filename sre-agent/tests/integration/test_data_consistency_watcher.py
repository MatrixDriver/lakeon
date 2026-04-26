from unittest.mock import MagicMock

from agent_session_log import LogStore
from skills.sre.data_consistency_watcher.watcher import DataConsistencyWatcher


class _FakeLLM:
    def __init__(self, text):
        self.text = text
        self.calls: list[dict] = []
    def complete(self, *, system, user, tools=None):
        self.calls.append({"system": system, "user": user})
        return {"text": self.text, "model": "deepseek-chat",
                "tokens_in": 100, "tokens_out": 50, "cost_usd": None}


def _fake_mcp(rule_results: dict[str, dict]):
    m = MagicMock()
    m.data_consistency_check = lambda *, rule, threshold_minutes=10: rule_results.get(rule, {"ok": True, "count": 0, "violations": []})
    return m


def test_all_rules_ok_no_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(log=log, mcp=_fake_mcp({}), llm=_FakeLLM("should not be called"))
    assert w.scan_once() == []


def test_one_rule_violates_opens_incident(tmp_log_root):
    log = LogStore(tmp_log_root)
    llm = _FakeLLM("## 根因假设 (0.5)\n候选原因待证实\n")
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "kb_implies_db_id": {
                "ok": False, "count": 2,
                "violations": [{"kb_id": "kb_a"}, {"kb_id": "kb_b"}],
                "description": "KB ready but no db_id",
                "severity": "WARN", "self_healable": False,
            },
        }),
        llm=llm,
    )
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "rule:kb_implies_db_id" in m.tags
    assert "severity:warn" in m.tags
    conclusion = log.store.read_conclusion(sids[0]) or ""
    assert "Severity" in conclusion and "WARN" in conclusion
    # Prompt no longer hard-codes @AfterCommit; verify it now mentions self_healable.
    assert "self_healable" in llm.calls[0]["user"].lower() or "self-healable" in llm.calls[0]["user"].lower()


def test_multiple_rules_violate_multiple_incidents(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "kb_implies_db_id": {"ok": False, "count": 1, "violations": [{"kb_id": "x"}],
                                 "description": "d1", "severity": "WARN",
                                 "self_healable": False},
            "enqueued_implies_drained": {"ok": False, "count": 3, "violations": [],
                                          "description": "d2", "severity": "WARN",
                                          "self_healable": False},
        }),
        llm=_FakeLLM("root cause guess"),
    )
    sids = w.scan_once()
    assert len(sids) == 2


def test_info_severity_is_suppressed(tmp_log_root):
    """INFO severity (e.g. self-healable transient) must not open an incident."""
    log = LogStore(tmp_log_root)
    llm = _FakeLLM("should not be called")
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "db_ready_implies_pod_running": {
                "ok": False, "count": 1,
                "violations": [{"db_id": "db_x", "age_sec": 480}],
                "description": "DB RUNNING but compute_host null",
                "severity": "INFO", "self_healable": True,
                "max_age_seconds": 480,
            },
        }),
        llm=llm,
    )
    sids = w.scan_once()
    assert sids == []
    assert llm.calls == []  # LLM not invoked, saving tokens & noise


def test_error_severity_opens_incident_with_error_tag(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "db_ready_implies_pod_running": {
                "ok": False, "count": 7,
                "violations": [{"db_id": f"db_{i}"} for i in range(7)],
                "description": "DB RUNNING but compute_host null",
                "severity": "ERROR", "self_healable": True,
                "max_age_seconds": 9000,
            },
        }),
        llm=_FakeLLM("blast radius hypothesis"),
    )
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "severity:error" in m.tags
