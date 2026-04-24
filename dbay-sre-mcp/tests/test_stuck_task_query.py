import json
from unittest.mock import MagicMock, patch

import psycopg2

from dbay_sre_mcp.tools.stuck_task_query import stuck_task_query_impl


def _fake_pg_with_results(per_table: dict[str, list[tuple]]):
    cursor = MagicMock()

    def execute_side_effect(sql, params=None):
        # Detect which table this query targets
        for tbl in per_table:
            if tbl in sql:
                cursor._next = per_table[tbl]
                return
        cursor._next = []

    cursor.execute.side_effect = execute_side_effect
    cursor.fetchall.side_effect = lambda: cursor._next
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None
    return conn


def test_no_stuck_tasks():
    fake = _fake_pg_with_results({"wiki_run_logs": [], "agentfs_jobs": [], "kb_processing_tasks": []})
    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=fake):
        out = json.loads(stuck_task_query_impl(threshold_minutes=10))
    assert out["count"] == 0
    assert out["tasks"] == []


def test_stuck_wiki_task():
    fake = _fake_pg_with_results({
        "wiki_run_logs": [("task_42", "kb_abc", "WIKI_UPDATE", "in_progress",
                           "2026-04-24T10:00:00Z", 700)],
        "agentfs_jobs": [],
        "kb_processing_tasks": [],
    })
    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=fake):
        out = json.loads(stuck_task_query_impl(threshold_minutes=5))
    assert out["count"] == 1
    assert out["tasks"][0]["source"] == "wiki_run_logs"
    assert out["tasks"][0]["task_type"] == "WIKI_UPDATE"


def test_filter_by_type():
    fake = _fake_pg_with_results({
        "wiki_run_logs": [("t_a", "kb_a", "WIKI_UPDATE", "in_progress", "2026-04-24T10:00:00Z", 700)],
        "agentfs_jobs": [("t_b", None, "FUSE_BACKFILL", "in_progress", "2026-04-24T10:00:00Z", 700)],
        "kb_processing_tasks": [],
    })
    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=fake):
        out = json.loads(stuck_task_query_impl(threshold_minutes=5, type="WIKI_UPDATE"))
    assert out["count"] == 1
    assert out["tasks"][0]["task_type"] == "WIKI_UPDATE"


def test_table_missing_handled_gracefully():
    """If a table doesn't exist (DB schema variation), query returns 0 from that source."""
    cursor = MagicMock()
    def execute_side_effect(sql, params=None):
        if "kb_processing_tasks" in sql:
            raise psycopg2.errors.UndefinedTable("relation \"kb_processing_tasks\" does not exist")
        cursor._next = []
    cursor.execute.side_effect = execute_side_effect
    cursor.fetchall.side_effect = lambda: cursor._next
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None

    with patch("dbay_sre_mcp.tools.stuck_task_query.connect", return_value=conn):
        out = json.loads(stuck_task_query_impl(threshold_minutes=10))
    assert out["count"] == 0
    assert any("kb_processing_tasks" in w for w in out.get("warnings", []))
