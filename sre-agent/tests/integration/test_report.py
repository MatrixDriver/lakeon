"""Integration tests for cold-start-watcher report builder."""
from pathlib import Path

import pytest

from agent_session_log import LogStore
from skills.sre.cold_start_watcher.report import build_report


def test_build_report_from_closed_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(
        type="incident",
        trigger={"tenant_id": "t", "db_id": "d", "alert": "compute cold start 8234ms"},
        tags=["severity:medium"],
    )
    s.append_turn(type="branch_resolve", keep="pageserver-reattach",
                  discard=["image-pull-slow"], reason="metric X")
    s.conclude(
        "# Cold start 8234ms for t/d\n\n"
        "## Root cause (confidence 0.72)\n"
        "Pageserver re-attach gap for tenant t — 6.8s re-attach.\n\n"
        "## Suggested actions\n"
        "1. Manual PUT location_config\n"
        "2. File ticket for pageserver startup scan fix\n"
    )
    s.close()
    card = build_report(log, s.id)
    assert "8234" in card
    assert "pageserver" in card.lower()
    assert "Manual PUT location_config" in card
    assert "image-pull-slow" in card
