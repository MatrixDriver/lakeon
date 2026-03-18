import os
import subprocess
import time

import pytest

from conftest import poll_until, run_psql


def psql_with_retry(connstr, sql, password, retries=5, delay=10):
    """Retry psql calls to allow compute to wake up via proxy."""
    for i in range(retries):
        try:
            return run_psql(connstr, sql, password)
        except RuntimeError:
            if i == retries - 1:
                raise
            time.sleep(delay)


class TestConnection:
    """Connection and psql tests (4 cases)."""

    @pytest.fixture(scope="class")
    def conn_db(self, e2e_client):
        """Create a database for connection tests. Capture password from creation."""
        db = e2e_client.create_database(name=f"e2e-conn-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=120,
            interval=3,
        )
        assert db["status"] == "RUNNING", f"Database creation failed: {db}"
        db["password"] = creation_password

        yield db

        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    # -----------------------------------------------------------------------
    # Test cases
    # -----------------------------------------------------------------------

    def test_default_branch_psql(self, conn_db):
        """psql SELECT 1 on default connection_uri returns '1'."""
        connstr = conn_db["connection_uri"]
        password = conn_db.get("password", "")
        result = psql_with_retry(connstr, "SELECT 1", password)
        assert result == "1"

    def test_branch_psql(self, e2e_client, conn_db):
        """Create branch, psql SELECT 1 on branch connection_uri returns '1'."""
        db_id = conn_db["id"]
        password = conn_db.get("password", "")

        branch = e2e_client.create_branch(db_id, name="conn-dev")
        branch_connstr = branch.get("connection_uri")
        if not branch_connstr:
            branch = e2e_client.find_branch_by_name(db_id, "conn-dev")
            assert branch is not None, "Branch 'conn-dev' not found"
            branch_connstr = branch["connection_uri"]

        result = psql_with_retry(branch_connstr, "SELECT 1", password)
        assert result == "1"

    def test_ssl_required(self, conn_db):
        """Connecting without sslmode=require should fail."""
        connstr = conn_db["connection_uri"]
        password = conn_db.get("password", "")

        # Strip any existing sslmode and explicitly set sslmode=disable
        # to test that the server rejects insecure connections.
        if "sslmode=" in connstr:
            # Remove existing sslmode parameter
            import re
            connstr = re.sub(r"[?&]sslmode=[^&]*", "", connstr)

        sep = "&" if "?" in connstr else "?"
        insecure_connstr = f"{connstr}{sep}sslmode=disable"

        env = {**os.environ, "no_proxy": "pg.dbay.cloud"}
        if password:
            env["PGPASSWORD"] = password

        result = subprocess.run(
            ["psql", insecure_connstr, "-c", "SELECT 1", "-t", "-A"],
            capture_output=True,
            text=True,
            timeout=60,
            env=env,
        )
        # Connection should fail — psql returns non-zero
        assert result.returncode != 0, "Expected psql to fail without SSL"

    def test_connstr_format(self, conn_db):
        """connection_uri should contain 'options=endpoint%3D' for SNI routing."""
        connstr = conn_db["connection_uri"]
        assert "options=endpoint%3D" in connstr, (
            f"Expected 'options=endpoint%3D' in connection_uri: {connstr}"
        )
