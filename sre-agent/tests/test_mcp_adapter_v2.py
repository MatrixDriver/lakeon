"""Tests for the 7 new SREMCPAdapter methods added after dbay-sre-mcp 0.2.0."""
import json
from unittest.mock import patch

import pytest


def test_find_database(monkeypatch):
    import main
    def fake(name="", db_id=""):
        return json.dumps({"found": True, "database": {"id": "db_xyz", "name": "tcph-bench"}})
    monkeypatch.setattr("dbay_sre_mcp.server.find_database", fake, raising=False)
    out = main.SREMCPAdapter().find_database(name="tcph-bench")
    assert out["found"] is True
    assert out["database"]["id"] == "db_xyz"


def test_find_tenant(monkeypatch):
    import main
    def fake(name="", tenant_id="", include_databases=True):
        return json.dumps({"found": True, "tenant": {"id": "t_abc"}, "databases": []})
    monkeypatch.setattr("dbay_sre_mcp.server.find_tenant", fake, raising=False)
    out = main.SREMCPAdapter().find_tenant(tenant_id="t_abc")
    assert out["found"] is True


def test_database_status(monkeypatch):
    import main
    def fake(name_or_id):
        return json.dumps({"found": True, "database": {"id": "d"}, "cold_start_1h": {"p95_ms": 2100}, "recent_events_1h": []})
    monkeypatch.setattr("dbay_sre_mcp.server.database_status", fake, raising=False)
    out = main.SREMCPAdapter().database_status(name_or_id="tcph-bench")
    assert out["cold_start_1h"]["p95_ms"] == 2100


def test_data_consistency_check(monkeypatch):
    import main
    def fake(rule, threshold_minutes=10):
        return json.dumps({"ok": False, "count": 2, "violations": [{"kb_id": "kb_x"}]})
    monkeypatch.setattr("dbay_sre_mcp.server.data_consistency_check", fake, raising=False)
    out = main.SREMCPAdapter().data_consistency_check(rule="kb_implies_db_id")
    assert out["count"] == 2


def test_stuck_task_query(monkeypatch):
    import main
    def fake(threshold_minutes=10, type=""):
        return json.dumps({"count": 1, "tasks": [{"task_id": "t_42", "source": "wiki_run_logs"}]})
    monkeypatch.setattr("dbay_sre_mcp.server.stuck_task_query", fake, raising=False)
    out = main.SREMCPAdapter().stuck_task_query(threshold_minutes=5)
    assert out["count"] == 1


def test_pod_create_failures(monkeypatch):
    import main
    def fake(since="1h"):
        return json.dumps({"count": 3, "by_category": {"InvalidName": 3}, "failures": []})
    monkeypatch.setattr("dbay_sre_mcp.server.pod_create_failures", fake, raising=False)
    out = main.SREMCPAdapter().pod_create_failures(since="30m")
    assert out["by_category"]["InvalidName"] == 3


def test_multi_tenant_blast_radius(monkeypatch):
    import main
    def fake(window="15m", min_tenant_count=3):
        return json.dumps({"count": 1, "incidents": [{"component": "agentfs", "distinct_tenant_count": 5}]})
    monkeypatch.setattr("dbay_sre_mcp.server.multi_tenant_blast_radius", fake, raising=False)
    out = main.SREMCPAdapter().multi_tenant_blast_radius(window="10m")
    assert out["incidents"][0]["distinct_tenant_count"] == 5
