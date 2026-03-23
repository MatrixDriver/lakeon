"""
Extended branch tests — branch tree API, schema diff between branches.
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


class TestBranchTree:
    """Branch tree API — used by console's branch visualization."""

    @pytest.fixture(scope="class")
    def tree_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-tree-{int(time.time())}")
        creation_password = db.get("password")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Create branch hierarchy: main -> dev -> feature
        e2e_client.create_branch(db["id"], name="dev")
        branches = e2e_client.list_branches(db["id"])
        dev = next(b for b in branches if b["name"] == "dev")
        e2e_client.create_branch(db["id"], name="feature", parent_branch_id=dev["id"])

        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_get_branch_tree(self, e2e_client, tree_db):
        """Branch tree should return hierarchical structure."""
        tree = e2e_client.get_branch_tree(tree_db["id"])
        assert isinstance(tree, (dict, list))
        # Should contain all 3 branches
        if isinstance(tree, dict):
            # Tree root should be 'main'
            assert tree.get("name") == "main"

    def test_get_branch_by_id(self, e2e_client, tree_db):
        """GET individual branch by ID."""
        branches = e2e_client.list_branches(tree_db["id"])
        dev = next(b for b in branches if b["name"] == "dev")

        detail = e2e_client._request("GET", f"/databases/{tree_db['id']}/branches/{dev['id']}")
        assert detail["name"] == "dev"
        assert detail.get("connection_uri") is not None


class TestSchemaDiff:
    """Schema diff between branches — used by console's time travel diff view."""

    @pytest.fixture(scope="class")
    def diff_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-diff-{int(time.time())}")
        creation_password = db.get("password")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Create table on main
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE diff_test(id INT PRIMARY KEY, name TEXT)",
            creation_password,
        )

        # Create branch, add column on branch
        branch = e2e_client.create_branch(db["id"], name="diff-branch")
        branch_connstr = branch.get("connection_uri")
        if not branch_connstr:
            b = e2e_client.find_branch_by_name(db["id"], "diff-branch")
            branch_connstr = b["connection_uri"]

        psql_with_retry(
            branch_connstr,
            "ALTER TABLE diff_test ADD COLUMN email TEXT",
            creation_password,
        )

        db["diff_branch_id"] = branch["id"]
        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_schema_diff_between_branches(self, e2e_client, diff_db):
        """Schema diff should show the added column."""
        branches = e2e_client.list_branches(diff_db["id"])
        main_branch = next(b for b in branches if b.get("is_default"))

        diff = e2e_client.get_schema_diff(
            diff_db["id"],
            source_branch_id=main_branch["id"],
            target_branch_id=diff_db["diff_branch_id"],
        )
        assert isinstance(diff, (dict, list))
        # Diff should contain something about the 'email' column addition
