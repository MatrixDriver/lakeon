import subprocess
import time

import pytest

from dbay_cli.client import DbayApiError
from conftest import poll_until, run_psql


def psql_with_retry(connstr, sql, password, retries=8, delay=15):
    """Retry psql calls to allow compute to wake up via proxy."""
    for i in range(retries):
        try:
            return run_psql(connstr, sql, password)
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            if i == retries - 1:
                raise
            # Postgres recovery takes longer than pod startup
            if "not yet accepting connections" in str(e):
                time.sleep(20)
            else:
                time.sleep(delay)


def find_default_branch(branches):
    """Return the branch with is_default=True."""
    return next((b for b in branches if b.get("is_default")), None)


def get_branch_connstr(e2e_client, db_id, branch_name):
    """Find a branch by name and return its connection_uri."""
    branch = e2e_client.find_branch_by_name(db_id, branch_name)
    assert branch is not None, f"Branch '{branch_name}' not found"
    return branch["connection_uri"]


class TestBranch:
    """Branch lifecycle tests (15 cases).

    Uses a class-scoped branch_db fixture so all tests share one database.
    """

    @pytest.fixture(scope="class")
    def branch_db(self, e2e_client):
        """Create a database for all branch tests. Capture password from creation."""
        db = e2e_client.create_database(name=f"e2e-branch-{int(time.time())}")
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

    def test_create_branch(self, e2e_client, branch_db):
        """Create branch 'dev', verify status=active and parent_branch set."""
        branch = e2e_client.create_branch(branch_db["id"], name="dev")
        assert branch["name"] == "dev"
        assert branch.get("status", "").lower() == "active"
        assert branch.get("parent_branch_id") is not None or branch.get("parentBranchId") is not None

    def test_list_branches(self, e2e_client, branch_db):
        """List should include both 'main' (default) and 'dev'."""
        branches = e2e_client.list_branches(branch_db["id"])
        names = [b["name"] for b in branches]
        assert "main" in names
        assert "dev" in names

    def test_delete_branch(self, e2e_client, branch_db):
        """Create 'temp' branch, delete it, verify not in list."""
        temp = e2e_client.create_branch(branch_db["id"], name="temp")
        temp_id = temp["id"]

        e2e_client.delete_branch(branch_db["id"], temp_id)

        branches = e2e_client.list_branches(branch_db["id"])
        assert not any(b["id"] == temp_id for b in branches)

    def test_delete_default_branch_rejected(self, e2e_client, branch_db):
        """Deleting the default branch should return 400."""
        branches = e2e_client.list_branches(branch_db["id"])
        default_branch = find_default_branch(branches)
        assert default_branch is not None, "No default branch found"

        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.delete_branch(branch_db["id"], default_branch["id"])
        assert exc_info.value.status_code == 400

    # -----------------------------------------------------------------------
    # Data isolation (3)
    # -----------------------------------------------------------------------

    def test_branch_inherits_data(self, e2e_client, branch_db):
        """Write to main, create branch 'inherit-test', branch sees the row."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")
        main_connstr = branch_db["connection_uri"]

        # Write data on main
        psql_with_retry(main_connstr, "CREATE TABLE IF NOT EXISTS e2e_inherit(v int)", password)
        psql_with_retry(main_connstr, "INSERT INTO e2e_inherit VALUES(1)", password)

        # Create branch
        branch = e2e_client.create_branch(db_id, name="inherit-test")
        branch_connstr = branch.get("connection_uri")
        if not branch_connstr:
            branch_connstr = get_branch_connstr(e2e_client, db_id, "inherit-test")

        # Branch should see inherited data
        result = psql_with_retry(branch_connstr, "SELECT v FROM e2e_inherit WHERE v=1", password)
        assert result == "1"

    def test_branch_write_isolation(self, e2e_client, branch_db):
        """Write to branch 'iso-test', main should NOT see it."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")
        main_connstr = branch_db["connection_uri"]

        # Create branch
        branch = e2e_client.create_branch(db_id, name="iso-test")
        branch_connstr = branch.get("connection_uri")
        if not branch_connstr:
            branch_connstr = get_branch_connstr(e2e_client, db_id, "iso-test")

        # Write on branch only
        psql_with_retry(branch_connstr, "INSERT INTO e2e_inherit VALUES(99)", password)

        # Main should NOT see 99
        result = psql_with_retry(main_connstr, "SELECT v FROM e2e_inherit WHERE v=99", password)
        assert result == ""

    def test_main_write_after_branch(self, e2e_client, branch_db):
        """Write to main after branch creation, branch 'iso-test' should NOT see it."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")
        main_connstr = branch_db["connection_uri"]

        # Write on main
        psql_with_retry(main_connstr, "INSERT INTO e2e_inherit VALUES(200)", password)

        # iso-test branch should NOT see 200
        branch_connstr = get_branch_connstr(e2e_client, db_id, "iso-test")
        result = psql_with_retry(branch_connstr, "SELECT v FROM e2e_inherit WHERE v=200", password)
        assert result == ""

    # -----------------------------------------------------------------------
    # Promote (3)
    # -----------------------------------------------------------------------

    def test_promote_data_visible(self, e2e_client, branch_db):
        """Create branch, write unique data, promote, default connection sees data."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")

        # Create branch and write unique data
        branch = e2e_client.create_branch(db_id, name="promo-test")
        branch_connstr = branch.get("connection_uri")
        if not branch_connstr:
            branch_connstr = get_branch_connstr(e2e_client, db_id, "promo-test")

        psql_with_retry(
            branch_connstr,
            "CREATE TABLE IF NOT EXISTS e2e_promo(v int); INSERT INTO e2e_promo VALUES(777)",
            password,
        )

        # Promote the branch
        e2e_client.promote_branch(db_id, branch["id"])

        # After promote, the DB's default connection should see promo data.
        # Refresh DB info to get the current default connection_uri.
        db = e2e_client.get_database(db_id)
        default_connstr = db["connection_uri"]

        result = psql_with_retry(default_connstr, "SELECT v FROM e2e_promo WHERE v=777", password)
        assert result == "777"

    def test_promote_old_default_demoted(self, e2e_client, branch_db):
        """After promote, old default should have is_default=false and 'before-promote' in name."""
        db_id = branch_db["id"]
        branches = e2e_client.list_branches(db_id)

        # Find demoted branches (not default, name contains 'before-promote')
        demoted = [b for b in branches if not b.get("is_default") and "before-promote" in b.get("name", "")]
        assert len(demoted) >= 1, f"No demoted branch found: {[b['name'] for b in branches]}"

    def test_promote_new_branch_from_promoted(self, e2e_client, branch_db):
        """Create a new branch after promote, parent should be the new default."""
        db_id = branch_db["id"]

        branches = e2e_client.list_branches(db_id)
        new_default = find_default_branch(branches)
        assert new_default is not None, "No default branch after promote"

        new_branch = e2e_client.create_branch(db_id, name="post-promote-child")
        parent_id = new_branch.get("parent_branch_id") or new_branch.get("parentBranchId")
        assert parent_id == new_default["id"], (
            f"Expected parent {new_default['id']}, got {parent_id}"
        )

    # -----------------------------------------------------------------------
    # Restore (2)
    # -----------------------------------------------------------------------

    def test_restore_to_version(self, e2e_client, branch_db):
        """Create version v1, write new data, restore to v1, new data gone."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")

        # Find current default branch
        branches = e2e_client.list_branches(db_id)
        default_branch = find_default_branch(branches)
        assert default_branch is not None
        branch_id = default_branch["id"]

        # Get connection for default branch
        db = e2e_client.get_database(db_id)
        connstr = db["connection_uri"]

        # Create a table and version v1
        psql_with_retry(connstr, "CREATE TABLE IF NOT EXISTS e2e_restore(v int)", password)
        psql_with_retry(connstr, "INSERT INTO e2e_restore VALUES(1)", password)
        v1 = e2e_client.create_version(db_id, branch_id, name="v1")

        # Write more data after v1
        psql_with_retry(connstr, "INSERT INTO e2e_restore VALUES(2)", password)
        result = psql_with_retry(connstr, "SELECT v FROM e2e_restore WHERE v=2", password)
        assert result == "2", "Post-v1 data should exist before restore"

        # Restore to v1
        e2e_client.restore_branch(db_id, branch_id, target_version_id=v1["id"])

        # After restore, re-fetch connection (may change) and verify data
        db = e2e_client.get_database(db_id)
        connstr = db["connection_uri"]

        result = psql_with_retry(connstr, "SELECT v FROM e2e_restore WHERE v=2", password)
        assert result == "", "Post-v1 data should be gone after restore"

        # v1 data should still be there
        result = psql_with_retry(connstr, "SELECT v FROM e2e_restore WHERE v=1", password)
        assert result == "1"

    def test_restore_creates_backup(self, e2e_client, branch_db):
        """After restore, list branches should include a backup branch."""
        db_id = branch_db["id"]
        branches = e2e_client.list_branches(db_id)
        backup_branches = [b for b in branches if "backup" in b.get("name", "").lower()]
        assert len(backup_branches) >= 1, (
            f"No backup branch found: {[b['name'] for b in branches]}"
        )

    # -----------------------------------------------------------------------
    # Edge cases (3)
    # -----------------------------------------------------------------------

    def test_duplicate_branch_name_rejected(self, e2e_client, branch_db):
        """Creating a branch with a duplicate name should return 409."""
        db_id = branch_db["id"]
        e2e_client.create_branch(db_id, name="dup-test")

        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.create_branch(db_id, name="dup-test")
        assert exc_info.value.status_code == 409

    def test_nested_branch(self, e2e_client, branch_db):
        """Create branch on branch, verify data inheritance chain."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")

        # Create parent branch
        parent_br = e2e_client.create_branch(db_id, name="parent-br")
        parent_connstr = parent_br.get("connection_uri")
        if not parent_connstr:
            parent_connstr = get_branch_connstr(e2e_client, db_id, "parent-br")

        # Write data on parent branch
        psql_with_retry(
            parent_connstr,
            "CREATE TABLE IF NOT EXISTS e2e_nested(v int); INSERT INTO e2e_nested VALUES(50)",
            password,
        )
        # Verify data is readable on parent
        psql_with_retry(parent_connstr, "SELECT v FROM e2e_nested WHERE v=50", password)
        # Create a version to ensure WAL is flushed and LSN is recorded on pageserver
        e2e_client.create_version(db_id, parent_br["id"], name="pre-child-snap")
        time.sleep(3)

        # Create child branch from parent
        child_br = e2e_client.create_branch(db_id, name="child-br", parent_branch_id=parent_br["id"])
        child_connstr = child_br.get("connection_uri")
        if not child_connstr:
            child_connstr = get_branch_connstr(e2e_client, db_id, "child-br")

        # Child should see parent's data
        result = psql_with_retry(child_connstr, "SELECT v FROM e2e_nested WHERE v=50", password)
        assert result == "50"

    def test_delete_branch_cleans_compute(self, e2e_client, branch_db):
        """Create branch, connect via psql (starts compute), delete, verify gone."""
        db_id = branch_db["id"]
        password = branch_db.get("password", "")

        branch = e2e_client.create_branch(db_id, name="compute-cleanup")
        branch_connstr = branch.get("connection_uri")
        if not branch_connstr:
            branch_connstr = get_branch_connstr(e2e_client, db_id, "compute-cleanup")

        # Connect to start compute
        psql_with_retry(branch_connstr, "SELECT 1", password)

        # Delete branch
        e2e_client.delete_branch(db_id, branch["id"])

        # Verify gone from list
        branches = e2e_client.list_branches(db_id)
        assert not any(b["id"] == branch["id"] for b in branches)
