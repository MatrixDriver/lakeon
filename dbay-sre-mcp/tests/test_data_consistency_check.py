"""Tests for data_consistency_check tool — now mocks LakeonAdminClient (REST)."""
import json
from unittest.mock import MagicMock

from dbay_sre_mcp.tools.data_consistency_check import (
    AVAILABLE_RULES,
    data_consistency_check_impl,
)


def _fake_admin(response: dict):
    c = MagicMock()
    c.data_consistency_check = lambda *, rule, threshold_minutes=10: response
    return c


def test_lists_available_rules():
    """AVAILABLE_RULES still exposes the 4 rule names statically."""
    assert {"kb_implies_db_id", "enqueued_implies_drained",
            "db_ready_implies_pod_running", "schema_seeded"} <= set(AVAILABLE_RULES)


def test_passes_through_admin_response():
    admin = _fake_admin({
        "ok": False, "count": 2,
        "violations": [{"kb_id": "kb_a"}, {"kb_id": "kb_b"}],
        "rule": "kb_implies_db_id",
    })
    out = json.loads(data_consistency_check_impl(
        rule="kb_implies_db_id", _admin=admin,
    ))
    assert out["ok"] is False
    assert out["count"] == 2
    assert out["violations"][0]["kb_id"] == "kb_a"


def test_passes_threshold_minutes_to_admin():
    captured = {}
    admin = MagicMock()

    def fake_dcc(*, rule, threshold_minutes):
        captured["rule"] = rule
        captured["threshold_minutes"] = threshold_minutes
        return {"ok": True, "count": 0, "violations": []}

    admin.data_consistency_check = fake_dcc
    data_consistency_check_impl(
        rule="enqueued_implies_drained", threshold_minutes=5, _admin=admin,
    )
    assert captured["threshold_minutes"] == 5


def test_list_dispatch_via_admin():
    """__list__ is delegated to admin endpoint (not local short-circuit)."""
    admin = _fake_admin({"rules": AVAILABLE_RULES,
                         "details": {r: "desc" for r in AVAILABLE_RULES}})
    out = json.loads(data_consistency_check_impl(rule="__list__", _admin=admin))
    assert "rules" in out
    assert set(out["rules"]) >= set(AVAILABLE_RULES)
