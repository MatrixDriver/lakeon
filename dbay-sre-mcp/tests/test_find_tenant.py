import json
from unittest.mock import MagicMock

import pytest

from dbay_sre_mcp.tools.find_tenant import find_tenant_impl


def _fake_admin(tenants, dbs_by_tenant):
    c = MagicMock()
    by_id = {t["id"]: t for t in tenants}
    c.get_tenant = lambda *, tenant_id: by_id.get(tenant_id)
    c.list_tenants = lambda *, name_contains=None: [
        t for t in tenants if (name_contains is None or name_contains in t["name"])
    ]
    c.list_databases = lambda *, name_contains=None, tenant_id=None: dbs_by_tenant.get(tenant_id, [])
    return c


def test_find_tenant_by_id_with_databases():
    admin = _fake_admin(
        tenants=[{"id": "t_abc", "name": "perf-team", "status": "ACTIVE", "quota": 10}],
        dbs_by_tenant={"t_abc": [{"id": "db_1", "name": "tcph-bench", "status": "READY"}]},
    )
    out = json.loads(find_tenant_impl(tenant_id="t_abc", _admin=admin))
    assert out["found"] is True
    assert out["tenant"]["name"] == "perf-team"
    assert len(out["databases"]) == 1
    assert out["databases"][0]["name"] == "tcph-bench"


def test_find_tenant_no_match():
    admin = _fake_admin(tenants=[], dbs_by_tenant={})
    out = json.loads(find_tenant_impl(name="ghost", _admin=admin))
    assert out["found"] is False


def test_must_provide_name_or_id():
    admin = _fake_admin([], {})
    with pytest.raises(ValueError, match="provide"):
        find_tenant_impl(_admin=admin)
