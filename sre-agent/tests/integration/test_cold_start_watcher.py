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
