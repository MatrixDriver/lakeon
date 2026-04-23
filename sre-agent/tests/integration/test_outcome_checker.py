"""Integration tests for outcome-checker skill."""
from pathlib import Path

import pytest

from agent_session_log import LogStore, SkillLedger
from skills.sre.outcome_checker.checker import OutcomeChecker


def _make_closed_incident(log: LogStore, ms: int, tenant: str, db: str, skill: str = "cold-start-watcher") -> str:
    s = log.new_session(
        type="incident",
        trigger={"tenant_id": tenant, "db_id": db, "alert": f"compute cold start {ms}ms",
                 "skill_version": "v0.1"},
        tags=["component:compute", f"skill:{skill}"],
    )
    s.conclude("fix: X")
    s.close()
    return s.id


class FakeMCP:
    def __init__(self, p95_map: dict[tuple[str, str], int]):
        self.p95 = p95_map

    def log_stats(self, since: str = "24h", **_):
        return {"cold_start_p95_by_db": {f"{t}/{d}": ms for (t, d), ms in self.p95.items()}}


def test_did_work_true_when_p95_improves(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    sid = _make_closed_incident(log, ms=8000, tenant="t", db="d")
    mcp = FakeMCP({("t", "d"): 2100})
    checker = OutcomeChecker(log=log, mcp=mcp, ledger=SkillLedger(tmp_log_root))

    checker.scan_once()

    out = log.store.read_outcome(sid)
    assert out is not None
    assert "did_work: true" in out.lower() or "did work: true" in out.lower()
    stats = SkillLedger(tmp_log_root).stats("cold-start-watcher")
    assert stats["did_work_count"] == 1


def test_did_work_false_when_no_improvement(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    sid = _make_closed_incident(log, ms=8000, tenant="t", db="d")
    mcp = FakeMCP({("t", "d"): 7900})  # still slow
    checker = OutcomeChecker(log=log, mcp=mcp, ledger=SkillLedger(tmp_log_root))

    checker.scan_once()

    out = log.store.read_outcome(sid)
    assert "did_work: false" in out.lower() or "did work: false" in out.lower()


def test_skips_already_checked(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    sid = _make_closed_incident(log, ms=8000, tenant="t", db="d")
    s = log.get_session(sid)
    s.record_outcome(did_work=True, notes="already")

    mcp = FakeMCP({("t", "d"): 5000})
    checker = OutcomeChecker(log=log, mcp=mcp, ledger=SkillLedger(tmp_log_root))

    # Should not overwrite existing outcome
    before = log.store.read_outcome(sid)
    checker.scan_once()
    after = log.store.read_outcome(sid)
    assert before == after
