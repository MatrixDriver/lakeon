import time

import pytest

from dbay_cli.client import DbayApiError
from conftest import poll_until, run_psql


class TestDatabase:
    """Database lifecycle tests. Uses a class-scoped shared_db to avoid
    creating a new database for every test case."""

    @pytest.fixture(scope="class")
    def shared_db(self, e2e_client):
        """Class-scoped database shared across the 8 test cases.

        The password is captured from the creation response since GET
        will not return it.
        """
        db = e2e_client.create_database(name=f"e2e-dbtest-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"].lower() in ("running", "error"),
            timeout=180,
            interval=3,
        )
        assert db["status"].lower() == "running", f"Database creation failed: {db}"

        # Re-attach password
        db["password"] = creation_password

        yield db

        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    # -- Tests (ordered for lifecycle: create -> use -> suspend -> resume -> verify -> delete) --

    def test_create_database(self, shared_db):
        """After creation and polling, status should be running with a connection URI."""
        assert shared_db["status"] == "running"
        assert shared_db.get("connection_uri") is not None

    def test_get_database_no_password(self, e2e_client, shared_db):
        """GET /databases/:id should NOT return the password field."""
        db = e2e_client.get_database(shared_db["id"])
        assert "password" not in db or db.get("password") is None

    def test_list_databases(self, e2e_client, shared_db):
        """The shared database should appear in the list."""
        dbs = e2e_client.list_databases()
        assert any(d["id"] == shared_db["id"] for d in dbs)

    def test_sql_operations(self, shared_db):
        """CREATE TABLE, INSERT, and SELECT should work via psql."""
        connstr = shared_db["connection_uri"]
        password = shared_db.get("password", "")
        run_psql(connstr, "CREATE TABLE IF NOT EXISTS e2e_test(id int)", password)
        run_psql(connstr, "INSERT INTO e2e_test VALUES(42)", password)
        result = run_psql(connstr, "SELECT id FROM e2e_test WHERE id=42", password)
        assert result == "42"

    def test_suspend_database(self, e2e_client, shared_db):
        """Suspending a database should transition its status to suspended."""
        e2e_client.suspend_database(shared_db["id"])
        db = poll_until(
            lambda: e2e_client.get_database(shared_db["id"]),
            condition=lambda d: d["status"] == "suspended",
            timeout=30,
        )
        assert db["status"].lower() == "suspended"

    def test_resume_database(self, e2e_client, shared_db):
        """Resuming a suspended database should bring it back to running."""
        e2e_client.resume_database(shared_db["id"])
        db = poll_until(
            lambda: e2e_client.get_database(shared_db["id"]),
            condition=lambda d: d["status"] == "running",
            timeout=180,
        )
        assert db["status"].lower() == "running"

    def test_data_persistence(self, shared_db):
        """Data written before suspend should still be readable after resume."""
        connstr = shared_db["connection_uri"]
        password = shared_db.get("password", "")
        result = run_psql(connstr, "SELECT id FROM e2e_test WHERE id=42", password)
        assert result == "42"

    def test_delete_database(self, e2e_client):
        """Creating then deleting a database should result in 404 on GET."""
        db = e2e_client.create_database(name=f"e2e-del-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] == "running",
            timeout=180,
        )
        e2e_client.delete_database(db["id"])
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_database(db["id"])
        assert exc_info.value.status_code == 404
