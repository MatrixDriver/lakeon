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
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "kb_implies_db_id": {
                "ok": False, "count": 2,
                "violations": [{"kb_id": "kb_a"}, {"kb_id": "kb_b"}],
                "description": "KB ready but no db_id",
            },
        }),
        llm=_FakeLLM("## 根因假设 (0.6)\n疑似 @AfterCommit 时序 bug\n"),
    )
    sids = w.scan_once()
    assert len(sids) == 1
    m = log.store.read_manifest(sids[0])
    assert "rule:kb_implies_db_id" in m.tags
    assert "@AfterCommit" in (log.store.read_conclusion(sids[0]) or "")


def test_multiple_rules_violate_multiple_incidents(tmp_log_root):
    log = LogStore(tmp_log_root)
    w = DataConsistencyWatcher(
        log=log,
        mcp=_fake_mcp({
            "kb_implies_db_id": {"ok": False, "count": 1, "violations": [{"kb_id": "x"}],
                                 "description": "d1"},
            "enqueued_implies_drained": {"ok": False, "count": 3, "violations": [],
                                          "description": "d2"},
        }),
        llm=_FakeLLM("root cause guess"),
    )
    sids = w.scan_once()
    assert len(sids) == 2
