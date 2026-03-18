import time

import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import ENDPOINT, ADMIN_TOKEN, poll_until, run_psql, _create_tenant_with_invite


def psql_with_retry(connstr, sql, password, retries=5, delay=10):
    """Retry psql calls to allow compute to wake up via proxy."""
    for i in range(retries):
        try:
            return run_psql(connstr, sql, password)
        except RuntimeError:
            if i == retries - 1:
                raise
            time.sleep(delay)


class TestMultiTenant:
    """Multi-tenant isolation tests (5 cases)."""

    @pytest.fixture(scope="class")
    def tenant_a(self):
        """First tenant with a database."""
        ts = int(time.time())
        client, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN, f"e2e-mt-a-{ts}", f"MtTestA@{ts}", f"MT-A {ts}"
        )
        db = client.create_database(name=f"mt-a-db-{ts}")
        creation_password = db.get("password")
        db = poll_until(
            lambda: client.get_database(db["id"]),
            lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=120,
        )
        assert db["status"] == "RUNNING", f"Database creation failed: {db}"
        db["password"] = creation_password
        yield {"client": client, "tenant": t, "db": db}
        # cleanup
        try:
            client.delete_database(db["id"])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def tenant_b(self):
        """Second tenant."""
        ts = int(time.time())
        client, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN, f"e2e-mt-b-{ts}", f"MtTestB@{ts}", f"MT-B {ts}"
        )
        yield {"client": client, "tenant": t}

    # -----------------------------------------------------------------------
    # Isolation tests
    # -----------------------------------------------------------------------

    def test_cross_tenant_get_404(self, tenant_a, tenant_b):
        """Tenant B GET tenant A's database should return 404."""
        db_id = tenant_a["db"]["id"]
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b["client"].get_database(db_id)
        assert exc_info.value.status_code == 404

    def test_cross_tenant_delete_404(self, tenant_a, tenant_b):
        """Tenant B DELETE tenant A's database should return 404."""
        db_id = tenant_a["db"]["id"]
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b["client"].delete_database(db_id)
        assert exc_info.value.status_code == 404

    def test_list_isolation(self, tenant_a, tenant_b):
        """Tenant B's database list should NOT contain tenant A's database."""
        db_a_id = tenant_a["db"]["id"]
        dbs_b = tenant_b["client"].list_databases()
        db_ids_b = [d["id"] for d in dbs_b]
        assert db_a_id not in db_ids_b

    def test_psql_data_isolation(self, tenant_a, tenant_b):
        """Both tenants write to the same table name; each sees only own data."""
        # Tenant A writes
        db_a = tenant_a["db"]
        pw_a = db_a.get("password", "")
        connstr_a = db_a["connection_uri"]
        psql_with_retry(connstr_a, "CREATE TABLE IF NOT EXISTS shared_name(v text)", pw_a)
        psql_with_retry(connstr_a, "INSERT INTO shared_name VALUES('tenant_a')", pw_a)

        # Tenant B creates own DB, writes to same table name
        client_b = tenant_b["client"]
        ts = int(time.time())
        db_b = client_b.create_database(name=f"mt-b-db-{ts}")
        creation_password_b = db_b.get("password")
        db_b = poll_until(
            lambda: client_b.get_database(db_b["id"]),
            lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=120,
        )
        assert db_b["status"] == "RUNNING", f"Database creation failed: {db_b}"
        db_b["password"] = creation_password_b

        pw_b = db_b.get("password", "")
        connstr_b = db_b["connection_uri"]
        psql_with_retry(connstr_b, "CREATE TABLE IF NOT EXISTS shared_name(v text)", pw_b)
        psql_with_retry(connstr_b, "INSERT INTO shared_name VALUES('tenant_b')", pw_b)

        # Tenant A sees only own data
        result_a = psql_with_retry(connstr_a, "SELECT v FROM shared_name", pw_a)
        assert "tenant_a" in result_a
        assert "tenant_b" not in result_a

        # Tenant B sees only own data
        result_b = psql_with_retry(connstr_b, "SELECT v FROM shared_name", pw_b)
        assert "tenant_b" in result_b
        assert "tenant_a" not in result_b

        # Cleanup tenant B's DB
        try:
            client_b.delete_database(db_b["id"])
        except Exception:
            pass

    def test_cross_tenant_branch_404(self, tenant_a, tenant_b):
        """Tenant B listing branches on tenant A's database should return 404."""
        db_id = tenant_a["db"]["id"]
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b["client"].list_branches(db_id)
        assert exc_info.value.status_code == 404
