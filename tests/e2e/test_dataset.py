import time
import pytest
from conftest import poll_until


class TestDataset:
    """Dataset export E2E tests."""

    @pytest.fixture(scope="class")
    def shared_db(self, e2e_client):
        """Create a database with test data for dataset export."""
        db = e2e_client.create_database(name=f"e2e-dataset-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] == "RUNNING",
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"

        # Insert test data via SQL
        from conftest import run_psql
        connstr = db["connection_uri"]
        password = db.get("password")
        run_psql(connstr, "CREATE TABLE test_export (id INT, name TEXT)", password)
        run_psql(connstr, "INSERT INTO test_export VALUES (1, 'Alice'), (2, 'Bob')", password)

        yield db

        # Cleanup
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_create_dataset_table_select(self, e2e_client, shared_db):
        """Create dataset with TABLE_SELECT mode."""
        ds = e2e_client.create_dataset(
            name=f"test-ds-{int(time.time())}",
            database_id=shared_db["id"],
            query_mode="TABLE_SELECT",
            tables=[{"name": "test_export"}]
        )
        assert ds["status"] == "DRAFT"
        assert ds["source_type"] == "DB_EXPORT"
        assert ds["database_id"] == shared_db["id"]
        return ds["id"]

    def test_trigger_export_and_poll(self, e2e_client, shared_db):
        """Trigger export and poll until READY."""
        ds = e2e_client.create_dataset(
            name=f"test-export-{int(time.time())}",
            database_id=shared_db["id"],
            query_mode="TABLE_SELECT",
            tables=[{"name": "test_export"}]
        )

        # Trigger export
        ds = e2e_client.trigger_export(ds["id"])
        assert ds["status"] == "EXPORTING"

        # Poll until READY or FAILED
        ds = poll_until(
            lambda: e2e_client.get_dataset(ds["id"]),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=120,
            interval=5,
        )

        assert ds["status"] == "READY", f"Export failed: {ds.get('error')}"
        assert ds["row_count"] == 2
        assert ds["file_size"] > 0
        assert ds["obs_path"]
        assert "download_url" in ds
        assert "code_snippets" in ds

    def test_list_datasets(self, e2e_client, shared_db):
        """List datasets with status filter."""
        # Create a dataset
        ds = e2e_client.create_dataset(
            name=f"test-list-{int(time.time())}",
            database_id=shared_db["id"],
            query_mode="TABLE_SELECT",
            tables=[{"name": "test_export"}]
        )

        # List all
        all_ds = e2e_client.list_datasets()
        assert any(d["id"] == ds["id"] for d in all_ds)

        # List DRAFT only
        draft_ds = e2e_client.list_datasets(status="DRAFT")
        assert any(d["id"] == ds["id"] for d in draft_ds)

    def test_delete_dataset(self, e2e_client, shared_db):
        """Delete dataset."""
        ds = e2e_client.create_dataset(
            name=f"test-delete-{int(time.time())}",
            database_id=shared_db["id"],
            query_mode="TABLE_SELECT",
            tables=[{"name": "test_export"}]
        )

        # Delete
        e2e_client.delete_dataset(ds["id"])

        # Verify deleted
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc:
            e2e_client.get_dataset(ds["id"])
        assert exc.value.status_code == 404
