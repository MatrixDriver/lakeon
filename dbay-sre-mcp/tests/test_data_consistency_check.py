import json
from unittest.mock import MagicMock, patch

import pytest

from dbay_sre_mcp.tools.data_consistency_check import (
    AVAILABLE_RULES,
    data_consistency_check_impl,
)


def _fake_pg(rows: list[tuple]):
    """Build a fake psycopg2 connection that returns `rows` for any cursor.fetchall()."""
    cursor = MagicMock()
    cursor.fetchall.return_value = rows
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None
    return conn


def test_lists_available_rules():
    out = json.loads(data_consistency_check_impl(rule="__list__"))
    assert "rules" in out
    assert {"kb_implies_db_id", "enqueued_implies_drained",
            "db_ready_implies_pod_running", "schema_seeded"} <= set(out["rules"])


def test_unknown_rule_returns_helpful_error():
    out = json.loads(data_consistency_check_impl(rule="bogus_rule"))
    assert out["ok"] is False
    assert "unknown" in out["message"].lower()
    assert "available" in out["message"].lower()


def test_kb_implies_db_id_no_violations():
    fake = _fake_pg([])
    with patch("dbay_sre_mcp.tools.data_consistency_check.connect", return_value=fake):
        out = json.loads(data_consistency_check_impl(rule="kb_implies_db_id"))
    assert out["ok"] is True
    assert out["violations"] == []


def test_kb_implies_db_id_with_violations():
    fake = _fake_pg([
        ("kb_abc", "demo-kb", "t_xyz", None),
        ("kb_def", "test-kb", "t_xyz", None),
    ])
    with patch("dbay_sre_mcp.tools.data_consistency_check.connect", return_value=fake):
        out = json.loads(data_consistency_check_impl(rule="kb_implies_db_id"))
    assert out["ok"] is False
    assert out["count"] == 2
    assert out["violations"][0]["kb_id"] == "kb_abc"


def test_enqueued_implies_drained_finds_orphans():
    fake = _fake_pg([
        ("write_42", "kb_abc", "2026-04-24T10:00:00Z", 600),  # 10 min ago, undrained
    ])
    with patch("dbay_sre_mcp.tools.data_consistency_check.connect", return_value=fake):
        out = json.loads(data_consistency_check_impl(
            rule="enqueued_implies_drained", threshold_minutes=5,
        ))
    assert out["ok"] is False
    assert out["count"] == 1
