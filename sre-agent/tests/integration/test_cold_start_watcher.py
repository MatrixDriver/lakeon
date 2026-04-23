"""Integration tests for cold-start-watcher skill."""
from pathlib import Path

import pytest

from agent_session_log import LogStore


@pytest.fixture
def mock_mcp():
    """Simulate dbay-sre-mcp.log_search returning fake lakeon-api log rows."""
    class Mock:
        calls = []
        responses = {}

        def log_search(self, **kwargs):
            self.calls.append(kwargs)
            return self.responses.get(kwargs.get("keyword", ""), [])

        def log_stats(self, **kwargs):
            return {"count_by_level": {"INFO": 100}}

    return Mock()


def test_watcher_opens_session_for_slow_start(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher

    mock_mcp.responses["compute started in"] = [
        {
            "ts": "2026-04-23T09:12:34Z",
            "component": "lakeon-api",
            "msg": "compute started in 8234ms for tenant=t_abc db=db_xyz",
            "tenant_id": "t_abc",
            "db_id": "db_xyz",
        }
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp, threshold_ms=5000)

    incidents = w.scan_once()

    assert len(incidents) == 1
    sid = incidents[0]
    sess = log.get_session(sid)
    m = log.store.read_manifest(sid)
    assert m.type == "incident"
    assert "component:compute" in m.tags
    assert "skill:cold-start-watcher" in m.tags
    assert m.trigger["alert"].startswith("compute cold start")


def test_watcher_ignores_fast_starts(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher

    mock_mcp.responses["compute started in"] = [
        {
            "ts": "...",
            "msg": "compute started in 1200ms for tenant=t db=d",
            "tenant_id": "t",
            "db_id": "d",
        },
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp, threshold_ms=5000)
    incidents = w.scan_once()
    assert incidents == []


def test_watcher_dedupes_same_pair_within_window(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher

    mock_mcp.responses["compute started in"] = [
        {
            "ts": "2026-04-23T09:12:34Z",
            "msg": "compute started in 6000ms for tenant=t db=d",
            "tenant_id": "t",
            "db_id": "d",
        },
        {
            "ts": "2026-04-23T09:14:10Z",
            "msg": "compute started in 7000ms for tenant=t db=d",
            "tenant_id": "t",
            "db_id": "d",
        },
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp, threshold_ms=5000, dedupe_window_sec=600)
    incidents = w.scan_once()
    assert len(incidents) == 1  # second one deduped


def test_diagnose_fills_conclusion_and_closes(tmp_log_root: Path, mock_mcp):
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.sre.cold_start_watcher.diagnose import diagnose
    from agent_session_log import LogStore

    class FakeLLM:
        calls = 0
        def complete(self, *, system, user, tools=None):
            FakeLLM.calls += 1
            if FakeLLM.calls == 1:
                return {"text": "I'll check pageserver-reattach first.",
                        "model": "deepseek-chat", "tokens_in": 100, "tokens_out": 50,
                        "cost_usd": 0.001}
            return {"text": "# Root cause (confidence 0.72)\n"
                            "pageserver-reattach gap observed for tenant t_abc.\n",
                    "model": "deepseek-chat", "tokens_in": 200, "tokens_out": 80,
                    "cost_usd": 0.002}

    mock_mcp.responses["compute started in"] = [
        {"ts": "2026-04-23T09:12:34Z",
         "msg": "compute started in 8234ms for tenant=t_abc db=db_xyz",
         "tenant_id": "t_abc", "db_id": "db_xyz"},
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp)
    sids = w.scan_once()
    assert len(sids) == 1
    session = log.get_session(sids[0])
    diagnose(session, llm=FakeLLM(), mcp=mock_mcp)

    # Session is closed with conclusion
    m = log.store.read_manifest(sids[0])
    assert m.status == "closed"
    concl = log.store.read_conclusion(sids[0])
    assert "pageserver" in concl.lower()
    # At least one branch was resolved
    decisions = (log.store.session_dir(sids[0]) / "branch-decisions.jsonl")
    assert decisions.exists() and decisions.read_text().strip()


def test_diagnose_closes_abandoned_on_llm_failure(tmp_log_root: Path, mock_mcp):
    from unittest.mock import patch
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.sre.cold_start_watcher.diagnose import diagnose
    from agent_session_log import LogStore

    class FailingLLM:
        def complete(self, **kwargs):
            raise RuntimeError("rate limited")

    mock_mcp.responses["compute started in"] = [
        {"msg": "compute started in 8000ms for tenant=t db=d", "tenant_id": "t", "db_id": "d"},
    ]
    log = LogStore(tmp_log_root)
    w = Watcher(log=log, mcp=mock_mcp)
    sid = w.scan_once()[0]
    session = log.get_session(sid)

    # Patch time.sleep so retry backoff doesn't slow down the test suite
    with patch("time.sleep"), pytest.raises(RuntimeError, match="rate limited"):
        diagnose(session, llm=FailingLLM(), mcp=mock_mcp)

    m = log.store.read_manifest(sid)
    assert m.status == "abandoned"
