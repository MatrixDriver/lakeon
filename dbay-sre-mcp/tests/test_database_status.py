import json
from unittest.mock import MagicMock

import pytest

from dbay_sre_mcp.tools.database_status import database_status_impl


def _fake_admin(database, cold_start, operations):
    c = MagicMock()
    c.get_database = lambda *, db_id: database if database and database["id"] == db_id else None
    c.list_databases = lambda *, name_contains=None, tenant_id=None: (
        [database] if database and (not name_contains or name_contains in database["name"]) else []
    )
    c.get_compute_cold_start = lambda *, since, db_id=None: cold_start
    c.get_operations = lambda *, component=None, since="1h": operations
    return c


def test_status_for_existing_database():
    admin = _fake_admin(
        database={
            "id": "db_xyz", "name": "tcph-bench", "tenant_id": "t_abc",
            "status": "READY", "compute_host": "pod-tcph-1",
        },
        cold_start={"p50_ms": 1200, "p95_ms": 3400, "count": 7, "max_ms": 8200},
        operations=[
            {"ts": "2026-04-24T18:00:00Z", "type": "WAKE", "outcome": "SUCCESS",
             "duration_ms": 1100, "db_id": "db_xyz"},
            {"ts": "2026-04-24T17:00:00Z", "type": "COLD_START", "outcome": "SUCCESS",
             "duration_ms": 2200, "db_id": "db_xyz"},
        ],
    )
    out = json.loads(database_status_impl(name_or_id="tcph-bench", _admin=admin))
    assert out["found"] is True
    assert out["database"]["status"] == "READY"
    assert out["cold_start_1h"]["p95_ms"] == 3400
    assert len(out["recent_events_1h"]) == 2


def test_status_disambiguation_for_multiple_matches():
    admin = MagicMock()
    admin.get_database = lambda *, db_id: None
    admin.list_databases = lambda *, name_contains=None, tenant_id=None: [
        {"id": "db_a", "name": "perf-a"},
        {"id": "db_b", "name": "perf-b"},
    ]
    out = json.loads(database_status_impl(name_or_id="perf", _admin=admin))
    assert out["found"] is False
    assert out.get("multiple") is True


def test_status_not_found():
    admin = _fake_admin(None, {}, [])
    out = json.loads(database_status_impl(name_or_id="ghost", _admin=admin))
    assert out["found"] is False
