import subprocess
import time

import pytest
import httpx
import requests

from dbay_cli.client import DbayApiError
from conftest import ENDPOINT, poll_until, run_psql


DATABASE_READY_TIMEOUT = 180
DATABASE_RESUME_TIMEOUT = 120
DATABASE_CREATE_ATTEMPTS = 2


def stage(message):
    print(f"[cdf-iceberg-e2e] {message}", flush=True)


def setup_module():
    try:
        session = requests.Session()
        session.trust_env = False
        response = session.get(f"{ENDPOINT.rstrip('/')}/api/v1/health", verify=False, timeout=10)
    except requests.RequestException as exc:
        pytest.fail(f"Lakeon API is not reachable before CDF E2E starts: {exc}")
    assert response.status_code in (200, 401, 404), response.text[:300]


def psql_with_retry(connstr, sql, password, retries=8, delay=15):
    """Retry psql calls to allow compute to wake up via proxy."""
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


def default_branch(client, database_id):
    branches = client.list_branches(database_id)
    return next(branch for branch in branches if branch.get("is_default"))


def create_cdf_stream(client, database_id, branch_id, source_table, target_table):
    return client._request(
        "POST",
        f"/databases/{database_id}/cdf-streams",
        json={
            "database_id": database_id,
            "branch_id": branch_id,
            "source_schema": "public",
            "source_table": source_table,
            "target_namespace": "public",
            "target_table": target_table,
            "mode": "APPEND_CHANGELOG",
            "initial_backfill": True,
        },
    )


def get_cdf_stream(client, database_id, stream_id):
    streams = client._request("GET", f"/databases/{database_id}/cdf-streams")
    return next(stream for stream in streams if stream["id"] == stream_id)


def plan_table(client, database_id, branch_id, namespace, table, body=None):
    return client._request(
        "POST",
        f"/iceberg/catalog/{database_id}/{branch_id}/v1/namespaces/{namespace}/tables/{table}/plan",
        json=body or {},
    )


def file_count(plan):
    return len(plan.get("file-scan-tasks") or [])


def file_paths(plan):
    return [
        (task.get("data-file") or {}).get("file-path") or task.get("file-path")
        for task in (plan.get("file-scan-tasks") or [])
        if ((task.get("data-file") or {}).get("file-path") or task.get("file-path"))
    ]


def wait_database_running(client, database_id):
    db = poll_until(
        lambda: client.get_database(database_id),
        condition=lambda d: d["status"] in ("RUNNING", "SUSPENDED", "ERROR"),
        timeout=DATABASE_READY_TIMEOUT,
        interval=3,
    )
    assert db["status"] != "ERROR", f"Database creation failed: {db}"
    if db["status"] == "SUSPENDED":
        resume_database_with_retry(client, database_id)
        db = poll_until(
            lambda: client.get_database(database_id),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=DATABASE_READY_TIMEOUT,
            interval=3,
        )
    assert db["status"] == "RUNNING", f"Database did not become running: {db}"
    assert db.get("connection_uri"), db
    return db


def resume_database_with_retry(client, database_id, timeout=DATABASE_RESUME_TIMEOUT, interval=10):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        db = client.get_database(database_id)
        if db["status"] == "RUNNING":
            return db
        if db["status"] == "ERROR":
            raise AssertionError(f"Database resume failed: {db}")
        try:
            client.resume_database(database_id)
        except httpx.TimeoutException as exc:
            last_error = exc
            stage(f"resume request timed out for {database_id}; retrying")
        except DbayApiError as exc:
            if exc.status_code not in (409, 500, 502, 503, 504):
                raise
            last_error = exc
        time.sleep(interval)
    raise AssertionError(f"Database did not resume within {timeout}s; last error: {last_error}")


def create_running_database(client):
    last_error = None
    for attempt in range(DATABASE_CREATE_ATTEMPTS):
        stage(f"database create attempt {attempt + 1}/{DATABASE_CREATE_ATTEMPTS}")
        db = client.create_database(name=f"e2e-cdf-iceberg-{int(time.time())}-{attempt}")
        password = db.get("password")
        try:
            db = wait_database_running(client, db["id"])
            db["password"] = password
            return db
        except (AssertionError, TimeoutError, httpx.TimeoutException, DbayApiError) as exc:
            last_error = exc
            try:
                client.delete_database(db["id"])
            except Exception:
                pass
            time.sleep(5)
    raise last_error or AssertionError("database did not become running")


class TestLakebaseCdfIceberg:
    def test_lakebase_cdf_to_managed_iceberg_table(self, e2e_client):
        e2e_client.http.timeout = httpx.Timeout(30.0, connect=10.0)
        stage("creating database")
        db = create_running_database(e2e_client)
        creation_password = db.get("password")
        try:
            stage(f"database running: {db['id']}")
            branch = default_branch(e2e_client, db["id"])
            branch_id = branch["id"]

            stage("creating source table and seed rows")
            psql_with_retry(
                db["connection_uri"],
                """
                CREATE TABLE public.orders(
                    id text PRIMARY KEY,
                    region text NOT NULL,
                    amount numeric NOT NULL,
                    status text NOT NULL
                );
                INSERT INTO public.orders(id, region, amount, status) VALUES
                    ('o-1', 'apac', 12.50, 'new'),
                    ('o-2', 'emea', 30.00, 'paid');
                """,
                creation_password,
            )

            stage("creating CDF stream")
            stream = create_cdf_stream(e2e_client, db["id"], branch_id, "orders", "orders_cdf")
            assert stream["status"] == "PAUSED"
            assert stream["backfill_status"] in ("PENDING", "RUNNING", "SUCCEEDED")

            stage("resuming CDF stream")
            resumed = e2e_client._request(
                "POST",
                f"/databases/{db['id']}/cdf-streams/{stream['id']}/resume",
            )
            assert resumed["status"] == "RUNNING"

            stage("waiting for backfill")
            stream = poll_until(
                lambda: get_cdf_stream(e2e_client, db["id"], stream["id"]),
                condition=lambda s: s["backfill_status"] in ("SUCCEEDED", "FAILED"),
                timeout=180,
                interval=3,
            )
            assert stream["backfill_status"] == "SUCCEEDED", stream
            assert stream["readable"] is True

            stage("checking REST catalog config")
            config = e2e_client._request(
                "GET",
                f"/iceberg/catalog/{db['id']}/{branch_id}/v1/config",
            )
            assert "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}" in config["endpoints"]
            assert "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}" in config["endpoints"]
            assert "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan" in config["endpoints"]
            assert config["warehouse"].startswith("obs://")
            assert f"/lakeon-managed/iceberg/" in config["warehouse"]

            stage("loading Iceberg table")
            load = e2e_client._request(
                "GET",
                f"/iceberg/catalog/{db['id']}/{branch_id}/v1/namespaces/public/tables/orders_cdf",
            )
            assert load["metadata"]["current-snapshot-id"] is not None
            assert load["metadata-location"].startswith("obs://")

            stage("planning initial scan")
            initial_plan = plan_table(
                e2e_client,
                db["id"],
                branch_id,
                "public",
                "orders_cdf",
                {"select": ["id", "region", "amount", "status", "_lakeon_cdf_op"]},
            )
            assert initial_plan["status"] == "completed"
            assert file_count(initial_plan) >= 1
            assert file_paths(initial_plan)
            assert all(path.startswith("obs://") for path in file_paths(initial_plan))

            stage("checking external Iceberg writes are explicitly read-only")
            commit_response = e2e_client.post(
                f"/iceberg/catalog/{db['id']}/{branch_id}/v1/namespaces/public/tables/orders_cdf",
                json={},
            )
            assert commit_response.status_code == 400
            assert "Lakeon-managed Iceberg tables are read-only" in commit_response.text

            stage("writing incremental source changes")
            psql_with_retry(
                db["connection_uri"],
                """
                INSERT INTO public.orders(id, region, amount, status)
                    VALUES ('o-3', 'apac', 42.00, 'new');
                UPDATE public.orders SET status = 'paid' WHERE id = 'o-1';
                DELETE FROM public.orders WHERE id = 'o-2';
                """,
                creation_password,
            )

            stage("waiting for incremental CDF files")
            incremental_plan = poll_until(
                lambda: plan_table(
                    e2e_client,
                    db["id"],
                    branch_id,
                    "public",
                    "orders_cdf",
                    {
                        "select": ["id", "region", "amount", "status", "_lakeon_cdf_op"],
                        "filter": {"op": "=", "field": "region", "value": "apac"},
                        "case-sensitive": True,
                    },
                ),
                condition=lambda p: file_count(p) > file_count(initial_plan),
                timeout=180,
                interval=5,
            )
            assert incremental_plan["status"] == "completed"
            stream_after_incremental = poll_until(
                lambda: get_cdf_stream(e2e_client, db["id"], stream["id"]),
                condition=lambda s: bool(s.get("last_commit_lsn")),
                timeout=60,
                interval=2,
            )
            assert stream_after_incremental["status"] == "RUNNING", stream_after_incremental
            assert stream_after_incremental.get("last_snapshot_id") is not None, stream_after_incremental
            assert stream_after_incremental.get("observed_lag_ms") is not None, stream_after_incremental
            assert stream_after_incremental.get("last_error") in (None, ""), stream_after_incremental

            stage("checking standard Iceberg manifest export is deferred for OBS-backed tables")
            export_response = e2e_client.post(
                f"/databases/{db['id']}/cdf-streams/{stream['id']}/export",
            )
            assert export_response.status_code == 400
            assert "local or file:// table location" in export_response.text

            stage("checking export status remains not materialized")
            export_status = e2e_client._request(
                "GET",
                f"/databases/{db['id']}/cdf-streams/{stream['id']}/export",
            )
            assert export_status["status"] in ("NOT_MATERIALIZED", "FAILED")
            assert export_status["status"] != "MATERIALIZED"
        finally:
            try:
                stage(f"deleting database: {db['id']}")
                e2e_client.delete_database(db["id"])
            except Exception:
                pass
