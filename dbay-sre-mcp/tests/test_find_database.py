import json
from unittest.mock import MagicMock

import pytest

from dbay_sre_mcp.tools.find_database import find_database_impl


def _fake_admin(databases: list[dict]):
    """Build a fake LakeonAdminClient that returns the given databases."""
    c = MagicMock()
    by_id = {d["id"]: d for d in databases}
    c.get_database = lambda *, db_id: by_id.get(db_id)
    c.list_databases = lambda *, name_contains=None, tenant_id=None: [
        d for d in databases if (name_contains is None or name_contains in d["name"])
    ]
    return c


def test_find_by_id_returns_full_record():
    admin = _fake_admin([
        {"id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
         "status": "READY", "compute_host": "pod-1", "created_at": "2026-04-01T00:00:00Z"},
    ])
    out = json.loads(find_database_impl(name=None, db_id="db_xyz", _admin=admin))
    assert out["found"] is True
    assert out["database"]["id"] == "db_xyz"
    assert out["database"]["name"] == "tcph-bench"
    assert out["database"]["tenant_id"] == "t_abc"


def test_find_by_name_exact_match():
    admin = _fake_admin([
        {"id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
         "status": "READY", "compute_host": "pod-1"},
        {"id": "db_other", "name": "tcph-other", "tenant_id": "t_abc",
         "status": "READY"},
    ])
    out = json.loads(find_database_impl(name="tcph-bench", db_id=None, _admin=admin))
    assert out["found"] is True
    assert out["database"]["id"] == "db_xyz"


def test_find_by_name_multiple_matches_returns_disambiguation():
    admin = _fake_admin([
        {"id": "db_a", "name": "perf-test-a", "tenant_id": "t_1"},
        {"id": "db_b", "name": "perf-test-b", "tenant_id": "t_2"},
    ])
    out = json.loads(find_database_impl(name="perf-test", db_id=None, _admin=admin))
    assert out["found"] is True
    assert out["multiple"] is True
    assert len(out["matches"]) == 2
    assert {m["id"] for m in out["matches"]} == {"db_a", "db_b"}


def test_find_by_name_no_match():
    admin = _fake_admin([])
    out = json.loads(find_database_impl(name="nonexistent", db_id=None, _admin=admin))
    assert out["found"] is False
    assert "nonexistent" in out["message"]


def test_must_provide_name_or_id():
    admin = _fake_admin([])
    with pytest.raises(ValueError, match="provide.*name.*db_id"):
        find_database_impl(name=None, db_id=None, _admin=admin)
