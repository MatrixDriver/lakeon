import time

import pytest

from dbay_cli.client import DbayApiError
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


def get_default_branch(client, db_id):
    """Return the default branch for a database."""
    branches = client.list_branches(db_id)
    return next(b for b in branches if b.get("is_default"))


class TestVersion:
    """Version lifecycle tests (12 cases).

    Uses a class-scoped version_db fixture so all tests share one database.
    """

    @pytest.fixture(scope="class")
    def version_db(self, e2e_client):
        """Create a database for all version tests. Capture password from creation."""
        db = e2e_client.create_database(name=f"e2e-ver-{int(time.time())}")
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
    # CRUD (4)
    # -----------------------------------------------------------------------

    def test_create_version(self, e2e_client, version_db):
        """Create version 'v1.0' with description, verify name, lsn, created_by."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)

        ver = e2e_client.create_version(
            db_id, branch["id"], name="v1.0", description="initial release",
        )
        assert ver["name"] == "v1.0"
        assert ver.get("lsn") is not None
        assert ver.get("created_by") is not None

    def test_list_versions(self, e2e_client, version_db):
        """List should contain v1.0 created in the previous test."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)

        versions = e2e_client.list_versions(db_id, branch["id"])
        names = [v["name"] for v in versions]
        assert "v1.0" in names

    def test_get_version(self, e2e_client, version_db):
        """Get specific version by ID, verify fields match."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)

        versions = e2e_client.list_versions(db_id, branch["id"])
        v10 = next(v for v in versions if v["name"] == "v1.0")

        # The client doesn't have get_version, so we verify via list
        assert v10["name"] == "v1.0"
        assert v10.get("lsn") is not None
        assert v10.get("id") is not None

    def test_delete_version(self, e2e_client, version_db):
        """Create version 'v-del', delete it, verify not in list."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)

        ver = e2e_client.create_version(db_id, branch["id"], name="v-del")
        e2e_client.delete_version(db_id, branch["id"], ver["id"])

        versions = e2e_client.list_versions(db_id, branch["id"])
        assert not any(v["id"] == ver["id"] for v in versions)

    # -----------------------------------------------------------------------
    # Data (2)
    # -----------------------------------------------------------------------

    def test_version_lsn_increases(self, e2e_client, version_db):
        """Create v1, write data, create v2 — LSNs should differ."""
        db_id = version_db["id"]
        password = version_db.get("password", "")
        branch = get_default_branch(e2e_client, db_id)
        branch_id = branch["id"]

        db = e2e_client.get_database(db_id)
        connstr = db["connection_uri"]

        v1 = e2e_client.create_version(db_id, branch_id, name="lsn-v1")

        # Write data between versions
        psql_with_retry(
            connstr,
            "CREATE TABLE IF NOT EXISTS e2e_lsn(v int); INSERT INTO e2e_lsn VALUES(1)",
            password,
        )

        v2 = e2e_client.create_version(db_id, branch_id, name="lsn-v2")

        # LSNs should differ since data was written between them
        assert v2["lsn"] != v1["lsn"], (
            f"Expected different LSNs after data write, got v1={v1['lsn']} v2={v2['lsn']}"
        )

    def test_restore_to_version_data(self, e2e_client, version_db):
        """Create v1, write data (INSERT 999), create v2, restore to v1, 999 is gone."""
        db_id = version_db["id"]
        password = version_db.get("password", "")
        branch = get_default_branch(e2e_client, db_id)
        branch_id = branch["id"]

        db = e2e_client.get_database(db_id)
        connstr = db["connection_uri"]

        # Create table and version v1
        psql_with_retry(connstr, "CREATE TABLE IF NOT EXISTS e2e_vdata(v int)", password)
        v1 = e2e_client.create_version(db_id, branch_id, name="restore-v1")

        # Write data after v1
        psql_with_retry(connstr, "INSERT INTO e2e_vdata VALUES(999)", password)
        e2e_client.create_version(db_id, branch_id, name="restore-v2")

        # Verify 999 exists before restore
        result = psql_with_retry(connstr, "SELECT v FROM e2e_vdata WHERE v=999", password)
        assert result == "999", "Data should exist before restore"

        # Restore to v1
        e2e_client.restore_branch(db_id, branch_id, target_version_id=v1["id"])

        # After restore, compute may be rebuilt — re-fetch connstr and retry
        db = e2e_client.get_database(db_id)
        connstr = db["connection_uri"]

        result = psql_with_retry(connstr, "SELECT v FROM e2e_vdata WHERE v=999", password)
        assert result == "", "Data written after v1 should be gone after restore"

    # -----------------------------------------------------------------------
    # Squash (2)
    # -----------------------------------------------------------------------

    def test_squash_versions(self, e2e_client, version_db):
        """Create v1, write, v2, write, v3. Squash v1-v3. v2 gone, v1 and v3 remain."""
        db_id = version_db["id"]
        password = version_db.get("password", "")
        branch = get_default_branch(e2e_client, db_id)
        branch_id = branch["id"]

        db = e2e_client.get_database(db_id)
        connstr = db["connection_uri"]

        v1 = e2e_client.create_version(db_id, branch_id, name="sq-v1")

        psql_with_retry(
            connstr,
            "CREATE TABLE IF NOT EXISTS e2e_squash(v int); INSERT INTO e2e_squash VALUES(1)",
            password,
        )
        v2 = e2e_client.create_version(db_id, branch_id, name="sq-v2")

        psql_with_retry(connstr, "INSERT INTO e2e_squash VALUES(2)", password)
        v3 = e2e_client.create_version(db_id, branch_id, name="sq-v3")

        # Squash from v1 to v3
        e2e_client.squash_versions(db_id, branch_id, v1["id"], v3["id"])

        # After squash, v2 should be gone, v1 and v3 should remain
        versions = e2e_client.list_versions(db_id, branch_id)
        version_ids = [v["id"] for v in versions]
        assert v1["id"] in version_ids, "v1 should remain after squash"
        assert v3["id"] in version_ids, "v3 should remain after squash"
        assert v2["id"] not in version_ids, "v2 should be gone after squash"

    def test_squash_insufficient_versions(self, e2e_client, version_db):
        """With fewer than 3 versions between from/to, squash should fail."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)
        branch_id = branch["id"]

        # Create only two adjacent versions (no version between them to squash)
        va = e2e_client.create_version(db_id, branch_id, name="insuf-va")
        vb = e2e_client.create_version(db_id, branch_id, name="insuf-vb")

        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.squash_versions(db_id, branch_id, va["id"], vb["id"])
        assert exc_info.value.status_code in (400, 422), (
            f"Expected 400 or 422, got {exc_info.value.status_code}"
        )

    # -----------------------------------------------------------------------
    # Edge cases (4)
    # -----------------------------------------------------------------------

    def test_duplicate_version_name(self, e2e_client, version_db):
        """Create 'v-dup' twice — check if allowed or rejected."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)
        branch_id = branch["id"]

        e2e_client.create_version(db_id, branch_id, name="v-dup")

        # Try creating again with the same name.
        # If the API allows it, just verify no error.
        # If it rejects, expect 409.
        try:
            dup = e2e_client.create_version(db_id, branch_id, name="v-dup")
            # Allowed — verify it was created
            assert dup["name"] == "v-dup"
        except DbayApiError as e:
            assert e.status_code == 409

    def test_version_on_non_default_branch(self, e2e_client, version_db):
        """Create branch 'ver-branch', create version on it, verify branch_id matches."""
        db_id = version_db["id"]

        branch = e2e_client.create_branch(db_id, name="ver-branch")
        ver = e2e_client.create_version(db_id, branch["id"], name="vb-v1")

        branch_id_field = ver.get("branch_id") or ver.get("branchId")
        assert branch_id_field == branch["id"], (
            f"Version branch_id mismatch: expected {branch['id']}, got {branch_id_field}"
        )

    def test_versions_after_branch_delete(self, e2e_client, version_db):
        """Create branch, create version on it, delete branch, list versions -> 404."""
        db_id = version_db["id"]

        branch = e2e_client.create_branch(db_id, name="del-ver-branch")
        e2e_client.create_version(db_id, branch["id"], name="orphan-v1")

        # Delete the branch
        e2e_client.delete_branch(db_id, branch["id"])

        # Listing versions for deleted branch should return 404
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.list_versions(db_id, branch["id"])
        assert exc_info.value.status_code == 404

    def test_version_on_empty_database(self, e2e_client, version_db):
        """On the database (possibly with prior data), create a version, verify lsn is valid."""
        db_id = version_db["id"]
        branch = get_default_branch(e2e_client, db_id)

        ver = e2e_client.create_version(db_id, branch["id"], name="empty-v1")
        assert ver.get("lsn") is not None, "LSN should not be None on any database"
        # LSN should be a non-empty string like "0/1A2B3C4"
        assert len(ver["lsn"]) > 0, "LSN should be a non-empty string"
