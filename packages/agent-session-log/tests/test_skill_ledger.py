"""Tests for SkillLedger."""
from pathlib import Path

from agent_session_log.skill_ledger import SkillLedger


def test_record_and_stats(tmp_log_root: Path):
    ledger = SkillLedger(tmp_log_root)
    ledger.record_invocation("cold-start-watcher", version="v0.1",
                             session_id="sess_1", triggered_at="2026-04-23T00:00:00Z")
    ledger.record_outcome("cold-start-watcher", session_id="sess_1", did_work=True)
    ledger.record_invocation("cold-start-watcher", version="v0.1",
                             session_id="sess_2", triggered_at="2026-04-23T00:05:00Z")
    ledger.record_outcome("cold-start-watcher", session_id="sess_2", did_work=False)

    stats = ledger.stats("cold-start-watcher")
    assert stats["total_invocations"] == 2
    assert stats["outcomes_filed"] == 2
    assert stats["did_work_count"] == 1
    assert stats["did_work_rate"] == 0.5


def test_stats_empty_skill(tmp_log_root: Path):
    ledger = SkillLedger(tmp_log_root)
    stats = ledger.stats("nonexistent")
    assert stats["total_invocations"] == 0
    assert stats["did_work_rate"] is None


def test_list_invocations(tmp_log_root: Path):
    ledger = SkillLedger(tmp_log_root)
    ledger.record_invocation("s", version="v0.1", session_id="sa", triggered_at="2026-04-23T00:00:00Z")
    ledger.record_invocation("s", version="v0.1", session_id="sb", triggered_at="2026-04-23T00:01:00Z")
    invs = ledger.list_invocations("s")
    assert [i["session_id"] for i in invs] == ["sa", "sb"]
