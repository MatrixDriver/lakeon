import json
from unittest.mock import MagicMock

from dbay_sre_mcp.tools.pod_create_failures import pod_create_failures_impl


def _fake_admin(operations):
    c = MagicMock()
    c.get_operations = lambda *, component=None, since="1h": operations
    return c


def test_no_failures():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "SUCCESS"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["count"] == 0


def test_invalid_name_failures():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "FAILURE",
         "error": "InvalidName: must consist of lower case alphanumeric",
         "tenant_id": "t_abc", "db_id": "db_xyz"},
        {"ts": "2026-04-24T17:00:00Z", "type": "POD_CREATE", "outcome": "SUCCESS"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["count"] == 1
    assert "InvalidName" in out["failures"][0]["error"]
    assert out["failures"][0]["category"] == "InvalidName"


def test_crashloop_categorization():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "FAILURE",
         "error": "CrashLoopBackOff: container exited with code 1",
         "tenant_id": "t_abc"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["failures"][0]["category"] == "CrashLoopBackOff"


def test_unknown_failure_categorized_as_other():
    admin = _fake_admin([
        {"ts": "2026-04-24T18:00:00Z", "type": "POD_CREATE", "outcome": "FAILURE",
         "error": "weird new error nobody has seen", "tenant_id": "t"},
    ])
    out = json.loads(pod_create_failures_impl(since="1h", _admin=admin))
    assert out["failures"][0]["category"] == "Other"
