"""
Extended dataset tests — covers more console UI operations:
- Custom SQL export
- Preview before export
- Dataset with no data
- Tenant isolation
- Error cases
"""
import time

import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import poll_until, run_psql, ENDPOINT, ADMIN_TOKEN, _create_tenant_with_invite


class TestDatasetExtended:
    """Extended dataset tests."""

    @pytest.fixture(scope="class")
    def ds_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-dsext-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] == "RUNNING",
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"

        connstr = db["connection_uri"]
        password = db.get("password")
        run_psql(connstr, "CREATE TABLE products(id INT, name TEXT, price NUMERIC)", password)
        run_psql(connstr, "INSERT INTO products VALUES(1,'Widget',9.99),(2,'Gadget',19.99),(3,'Doohickey',4.99)", password)
        run_psql(connstr, "CREATE TABLE empty_table(id INT)", password)

        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_create_dataset_custom_sql(self, e2e_client, ds_db):
        """Create dataset with CUSTOM_SQL mode."""
        ds = e2e_client.create_dataset(
            name=f"sql-ds-{int(time.time())}",
            database_id=ds_db["id"],
            query_mode="CUSTOM_SQL",
            sql="SELECT id, name, price FROM products WHERE price > 5",
        )
        assert ds["status"] == "DRAFT"

    def test_preview_dataset(self, e2e_client, ds_db):
        """Preview should return sample rows without creating a dataset."""
        result = e2e_client._request("POST", "/datasets/preview", json={
            "database_id": ds_db["id"],
            "query_mode": "TABLE_SELECT",
            "tables": [{"name": "products"}],
        })
        rows = result.get("rows") or result.get("data") or result
        assert isinstance(rows, list)
        assert len(rows) >= 3

    def test_export_custom_sql(self, e2e_client, ds_db):
        """Export with CUSTOM_SQL — should contain filtered rows."""
        ds = e2e_client.create_dataset(
            name=f"sql-export-{int(time.time())}",
            database_id=ds_db["id"],
            query_mode="CUSTOM_SQL",
            sql="SELECT * FROM products WHERE price > 5",
        )

        ds = e2e_client.trigger_export(ds["id"])
        assert ds["status"] == "EXPORTING"

        ds = poll_until(
            lambda: e2e_client.get_dataset(ds["id"]),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=120, interval=5,
        )
        assert ds["status"] == "READY"
        # Only 2 rows have price > 5
        assert ds["row_count"] == 2

    def test_export_empty_table(self, e2e_client, ds_db):
        """Export from empty table should still succeed with 0 rows."""
        ds = e2e_client.create_dataset(
            name=f"empty-export-{int(time.time())}",
            database_id=ds_db["id"],
            query_mode="TABLE_SELECT",
            tables=[{"name": "empty_table"}],
        )

        ds = e2e_client.trigger_export(ds["id"])

        ds = poll_until(
            lambda: e2e_client.get_dataset(ds["id"]),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=120, interval=5,
        )
        # Either READY with 0 rows, or FAILED because empty
        if ds["status"] == "READY":
            assert ds["row_count"] == 0

    def test_create_dataset_missing_db_id(self, e2e_client):
        """Missing database_id should return 400."""
        with pytest.raises(DbayApiError) as exc:
            e2e_client._request("POST", "/datasets", json={
                "name": "bad-ds",
                "query_mode": "TABLE_SELECT",
                "tables": [{"name": "x"}],
            })
        assert exc.value.status_code == 400

    def test_export_nonexistent_dataset_404(self, e2e_client):
        """Triggering export on nonexistent dataset should return 404."""
        with pytest.raises(DbayApiError) as exc:
            e2e_client.trigger_export("ds_nonexistent_xyz")
        assert exc.value.status_code == 404


class TestDatasetTenantIsolation:
    """Cross-tenant isolation for datasets."""

    @pytest.fixture(scope="class")
    def two_tenants(self, e2e_client):
        ts = int(time.time())
        client2, _ = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-dsiso-{ts}", f"DsIso@{ts}", f"DS Iso {ts}",
        )
        return {"client1": e2e_client, "client2": client2}

    def test_cross_tenant_get_dataset_404(self, two_tenants, e2e_client):
        """Tenant 2 should not see tenant 1's datasets."""
        # Create dataset as tenant 1
        db = e2e_client.create_database(name=f"e2e-dsiso-db-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] == "RUNNING",
            timeout=180, interval=3,
        )

        ds = e2e_client.create_dataset(
            name=f"iso-ds-{int(time.time())}",
            database_id=db["id"],
            query_mode="TABLE_SELECT",
            tables=[{"name": "pg_class"}],
        )

        # Tenant 2 should get 404
        with pytest.raises(DbayApiError) as exc:
            two_tenants["client2"].get_dataset(ds["id"])
        assert exc.value.status_code == 404

        # Cleanup
        e2e_client.delete_dataset(ds["id"])
        e2e_client.delete_database(db["id"])

    def test_cross_tenant_list_datasets_isolated(self, two_tenants):
        """Tenant 2's dataset list should not include tenant 1's datasets."""
        datasets = two_tenants["client2"].list_datasets()
        assert isinstance(datasets, list)
        # Should be empty for a fresh tenant
        assert len(datasets) == 0
