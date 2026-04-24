import json
from unittest.mock import MagicMock, patch

from dbay_sre_mcp.tools.multi_tenant_blast_radius import multi_tenant_blast_radius_impl


def _fake_pg(rows):
    cursor = MagicMock()
    cursor.fetchall.return_value = rows
    cursor.__enter__ = lambda self: cursor
    cursor.__exit__ = lambda *a: None
    conn = MagicMock()
    conn.cursor.return_value = cursor
    conn.__enter__ = lambda self: conn
    conn.__exit__ = lambda *a: None
    return conn


def test_no_blast():
    fake = _fake_pg([])
    with patch("dbay_sre_mcp.tools.multi_tenant_blast_radius.log_db_connect", return_value=fake):
        out = json.loads(multi_tenant_blast_radius_impl(window="15m", min_tenant_count=3))
    assert out["count"] == 0


def test_detects_cross_tenant_pattern():
    fake = _fake_pg([
        ("agentfs", "MemorySvcClient connection refused", 5, 47),  # 5 tenants, 47 occurrences
        ("compute", "InvalidName must consist of lower case", 4, 8),
    ])
    with patch("dbay_sre_mcp.tools.multi_tenant_blast_radius.log_db_connect", return_value=fake):
        out = json.loads(multi_tenant_blast_radius_impl(window="15m", min_tenant_count=3))
    assert out["count"] == 2
    assert out["incidents"][0]["distinct_tenant_count"] == 5
    assert "MemorySvcClient" in out["incidents"][0]["error_signature"]


def test_threshold_filters_low_blast():
    fake = _fake_pg([
        ("agentfs", "common error", 2, 100),  # only 2 tenants
    ])
    with patch("dbay_sre_mcp.tools.multi_tenant_blast_radius.log_db_connect", return_value=fake):
        out = json.loads(multi_tenant_blast_radius_impl(window="15m", min_tenant_count=3))
    assert out["count"] == 0
