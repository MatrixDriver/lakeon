"""Tests for stuck_task_query — now mocks LakeonAdminClient (REST)."""
import json
from unittest.mock import MagicMock

from dbay_sre_mcp.tools.stuck_task_query import stuck_task_query_impl


def _fake_admin(response: dict):
    c = MagicMock()
    c.stuck_task_query = lambda *, threshold_minutes=10, type="": response
    return c


def test_no_stuck_tasks():
    admin = _fake_admin({"count": 0, "tasks": []})
    out = json.loads(stuck_task_query_impl(_admin=admin))
    assert out["count"] == 0
    assert out["tasks"] == []


def test_stuck_tasks_passthrough():
    admin = _fake_admin({
        "count": 2, "threshold_minutes": 10,
        "tasks": [
            {"task_id": "t1", "task_type": "WIKI_UPDATE", "source": "wiki_run_logs",
             "status": "in_progress", "age_sec": 700},
            {"task_id": "t2", "task_type": "FUSE_BACKFILL", "source": "agentfs_jobs",
             "status": "in_progress", "age_sec": 800},
        ],
    })
    out = json.loads(stuck_task_query_impl(threshold_minutes=10, _admin=admin))
    assert out["count"] == 2
    assert out["tasks"][0]["task_type"] == "WIKI_UPDATE"


def test_type_filter_passed_to_admin():
    captured = {}
    admin = MagicMock()

    def fake_stq(*, threshold_minutes, type):
        captured["threshold_minutes"] = threshold_minutes
        captured["type"] = type
        return {"count": 0, "tasks": []}

    admin.stuck_task_query = fake_stq
    stuck_task_query_impl(threshold_minutes=5, type="WIKI_UPDATE", _admin=admin)
    assert captured["type"] == "WIKI_UPDATE"
    assert captured["threshold_minutes"] == 5


def test_warnings_field_preserved():
    admin = _fake_admin({
        "count": 0, "tasks": [],
        "warnings": ["table kb_processing_tasks does not exist; skipped"],
    })
    out = json.loads(stuck_task_query_impl(_admin=admin))
    assert "warnings" in out
    assert "kb_processing_tasks" in out["warnings"][0]
