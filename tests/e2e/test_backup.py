"""
Backup & restore E2E tests — covers the console backup management page.

Tests: create backup, list, get, restore (creates new DB), delete.
"""
import subprocess
import time

import pytest

from dbay_cli.client import DbayApiError
from conftest import poll_until, run_psql


def psql_with_retry(connstr, sql, password, retries=8, delay=15):
    for i in range(retries):
        try:
            return run_psql(connstr, sql, password)
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            if i == retries - 1:
                raise
            if "not yet accepting connections" in str(e):
                time.sleep(20)
            else:
                time.sleep(delay)


class TestBackup:
    """Backup lifecycle tests."""

    @pytest.fixture(scope="class")
    def backup_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-backup-{int(time.time())}")
        creation_password = db.get("password")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Seed data
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE backup_test(id INT PRIMARY KEY, val TEXT)",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "INSERT INTO backup_test VALUES(1, 'before-backup'), (2, 'before-backup')",
            creation_password,
        )

        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_create_backup(self, e2e_client, backup_db):
        """Create a named backup."""
        backup = e2e_client.create_backup(backup_db["id"], name="test-backup-1")
        assert backup.get("id") is not None
        assert backup.get("name") == "test-backup-1"
        assert backup.get("status") in ("PENDING", "CREATING", "READY", "COMPLETED")

    def test_list_backups(self, e2e_client, backup_db):
        """List backups for the database."""
        backups = e2e_client.list_backups(backup_db["id"])
        assert isinstance(backups, list)
        assert len(backups) >= 1
        names = [b.get("name") for b in backups]
        assert "test-backup-1" in names

    def test_list_all_backups(self, e2e_client):
        """List all backups across databases."""
        backups = e2e_client.list_all_backups()
        assert isinstance(backups, list)
        assert len(backups) >= 1

    def test_get_backup(self, e2e_client, backup_db):
        """Get backup details by ID."""
        backups = e2e_client.list_backups(backup_db["id"])
        backup = backups[0]

        detail = e2e_client.get_backup(backup_db["id"], backup["id"])
        assert detail.get("id") == backup["id"]
        assert detail.get("name") is not None

    def test_restore_from_backup(self, e2e_client, backup_db):
        """Restore from backup creates a new database with the data."""
        backups = e2e_client.list_backups(backup_db["id"])
        backup = backups[0]

        # Wait for backup to be ready if still creating
        if backup.get("status") not in ("READY", "COMPLETED"):
            backup = poll_until(
                lambda: e2e_client.get_backup(backup_db["id"], backup["id"]),
                condition=lambda b: b.get("status") in ("READY", "COMPLETED"),
                timeout=120,
            )

        # Restore creates a new database
        restored = e2e_client.restore_backup(
            backup_db["id"], backup["id"],
            new_name=f"e2e-restored-{int(time.time())}",
        )
        assert restored.get("id") is not None

        # Wait for restored DB to be running
        restored_db = poll_until(
            lambda: e2e_client.get_database(restored["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert restored_db["status"] == "RUNNING"

        # Verify data exists in restored database
        password = backup_db.get("password", "")
        result = psql_with_retry(
            restored_db["connection_uri"],
            "SELECT COUNT(*) FROM backup_test",
            password,
        )
        assert int(result) == 2

        # Cleanup restored DB
        try:
            e2e_client.delete_database(restored["id"])
        except Exception:
            pass

    def test_delete_backup(self, e2e_client, backup_db):
        """Create and delete a backup."""
        backup = e2e_client.create_backup(backup_db["id"], name="to-delete")
        e2e_client.delete_backup(backup_db["id"], backup["id"])

        backups = e2e_client.list_backups(backup_db["id"])
        assert not any(b.get("id") == backup["id"] for b in backups)

    def test_get_deleted_backup_404(self, e2e_client, backup_db):
        """Getting a deleted backup should return 404."""
        backup = e2e_client.create_backup(backup_db["id"], name="get-404")
        e2e_client.delete_backup(backup_db["id"], backup["id"])

        with pytest.raises(DbayApiError) as exc:
            e2e_client.get_backup(backup_db["id"], backup["id"])
        assert exc.value.status_code == 404
