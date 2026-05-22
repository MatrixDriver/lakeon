"""
E2E tests for Point-In-Time Restore (PITR) recovery API.

Covers the three Task 7 scenarios:
  1. PITR recovers a database to an intermediate state (data written
     before the midpoint is visible; data written after is not).
  2. PITR with a target_time before the database's createdAt is
     rejected with an HTTP error.
  3. The GET /pitr-window endpoint returns the expected window keys.

Wire format is snake_case per the PitrRequest/PitrResponse/PitrWindow DTOs.
"""
import subprocess
import time
from datetime import datetime, timezone

import pytest

from conftest import poll_until, run_psql
from dbay_cli.client import DbayApiError


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def psql_with_retry(connstr, sql, password, retries=8, delay=15):
    """Retry psql calls while compute is waking up via proxy."""
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


def _iso_z(ts: datetime) -> str:
    """Format a datetime as ISO 8601 with a trailing Z suffix (no microseconds)."""
    return ts.astimezone(timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def _wait_db_running(e2e_client, db_id, timeout=240):
    """Resume (if needed) then poll until the database is RUNNING."""
    db = e2e_client.get_database(db_id)
    if db["status"] == "SUSPENDED":
        try:
            e2e_client.resume_database(db_id)
        except DbayApiError:
            pass
    return poll_until(
        lambda: e2e_client.get_database(db_id),
        condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
        timeout=timeout,
        interval=3,
    )


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestPitr:
    """PITR API end-to-end tests."""

    def test_pitr_recovers_to_intermediate_state(self, e2e_client, test_db):
        """Write data, capture midpoint, write more data, PITR to midpoint,
        assert the new database only sees the pre-midpoint rows."""
        db_id = test_db["id"]
        password = test_db["password"]
        connstr = test_db["connection_uri"]

        # Seed table + first row (visible at midpoint)
        psql_with_retry(
            connstr,
            "CREATE TABLE IF NOT EXISTS e2e_pitr(id INT PRIMARY KEY, val TEXT)",
            password,
        )
        psql_with_retry(
            connstr,
            "INSERT INTO e2e_pitr VALUES (1, 'before-midpoint')",
            password,
        )
        # Flush WAL by creating a version so the LSN is acknowledged on the pageserver
        branches = e2e_client.list_branches(db_id)
        main_br = next(b for b in branches if b.get("is_default"))
        e2e_client.create_version(db_id, main_br["id"], name="pre-midpoint-snap")

        # Give the pageserver time to ingest the WAL upload before midpoint.
        # Pageserver WAL upload from safekeeper is async; the test needs enough
        # buffer so that CREATE TABLE + INSERT are durably ingested before
        # midpoint is captured (otherwise get_lsn_by_timestamp returns an LSN
        # earlier than CREATE TABLE).
        time.sleep(20)
        midpoint = datetime.now(timezone.utc)
        time.sleep(20)

        # Second row (should NOT be visible after PITR to midpoint)
        psql_with_retry(
            connstr,
            "INSERT INTO e2e_pitr VALUES (2, 'after-midpoint')",
            password,
        )
        e2e_client.create_version(db_id, main_br["id"], name="post-midpoint-snap")
        time.sleep(10)

        # Trigger PITR to midpoint — snake_case wire format
        target_time = _iso_z(midpoint)
        pitr_resp = e2e_client._request(
            "POST",
            f"/databases/{db_id}/pitr",
            json={"target_time": target_time, "new_db_name": f"pitr-mid-{int(time.time())}"},
        )

        assert pitr_resp.get("new_db_id"), f"Missing new_db_id: {pitr_resp}"
        assert pitr_resp.get("branch_id"), f"Missing branch_id: {pitr_resp}"
        assert pitr_resp.get("lsn"), f"Missing lsn: {pitr_resp}"
        assert pitr_resp.get("status") == "ready"

        new_db_id = pitr_resp["new_db_id"]
        try:
            # Recovered DB starts SUSPENDED — resume + wait for compute to come up
            recovered = _wait_db_running(e2e_client, new_db_id, timeout=240)
            assert recovered["status"] == "RUNNING", f"Recovery DB not running: {recovered}"

            recovered_connstr = recovered["connection_uri"]

            # Row 1 (pre-midpoint) must exist
            row1 = psql_with_retry(
                recovered_connstr,
                "SELECT val FROM e2e_pitr WHERE id=1",
                password,
            )
            assert row1 == "before-midpoint", f"Pre-midpoint row missing: {row1!r}"

            # Row 2 (post-midpoint) must NOT exist
            row2 = psql_with_retry(
                recovered_connstr,
                "SELECT val FROM e2e_pitr WHERE id=2",
                password,
            )
            assert row2 == "", f"Post-midpoint row leaked into PITR result: {row2!r}"
        finally:
            try:
                e2e_client.delete_database(new_db_id)
            except Exception:
                pass

    def test_pitr_rejects_out_of_window(self, e2e_client, test_db):
        """POST PITR with target_time before the database existed should fail.

        Expected: HTTP 400. The current implementation may surface 500 or
        another 4xx code if Neon's get_lsn_by_timestamp rejects the request;
        we tolerate any non-2xx outcome but flag a strong preference for 400.
        """
        db_id = test_db["id"]

        resp = e2e_client._request_raw(
            "POST",
            f"/databases/{db_id}/pitr",
            json={
                "target_time": "2020-01-01T00:00:00Z",
                "new_db_name": f"pitr-bad-{int(time.time())}",
            },
        )

        assert resp.status_code >= 400, (
            f"Out-of-window PITR should fail, got {resp.status_code}: {resp.text}"
        )
        # Prefer 400; tolerate 404/422/500 while the validator is being tightened.
        assert resp.status_code in (400, 404, 422, 500), (
            f"Unexpected status for out-of-window PITR: {resp.status_code} {resp.text}"
        )

        # If a database was created despite the error, clean it up
        try:
            body = resp.json()
            new_id = body.get("new_db_id") if isinstance(body, dict) else None
            if new_id:
                e2e_client.delete_database(new_id)
        except Exception:
            pass

    def test_pitr_window_endpoint(self, e2e_client, test_db):
        """GET /pitr-window returns 200 with the expected window keys."""
        db_id = test_db["id"]

        window = e2e_client._request("GET", f"/databases/{db_id}/pitr-window")

        assert isinstance(window, dict), f"Window response not a dict: {window!r}"
        assert "earliest" in window, f"Missing 'earliest' key: {window}"
        assert "latest_lsn" in window, f"Missing 'latest_lsn' key: {window}"
        # Sanity check the other two fields the DTO promises
        assert "latest" in window, f"Missing 'latest' key: {window}"
        assert "earliest_lsn" in window, f"Missing 'earliest_lsn' key: {window}"

        # Values should be non-empty strings
        assert window["earliest"], f"Empty earliest: {window}"
        assert window["latest"], f"Empty latest: {window}"
        assert window["latest_lsn"], f"Empty latest_lsn: {window}"
