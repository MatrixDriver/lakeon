"""
Performance & startup timing tests.

Measures cold start (compute pod created from scratch), hot start (auto-wake
from suspended state via proxy), and creates multiple databases to test
elastic pool scaling behavior.

Run:
    pytest tests/e2e/test_performance.py -v -s

Use -s to see timing output printed to console.
"""
import subprocess
import time

import pytest

from conftest import poll_until, run_psql


def psql_timed(connstr, sql, password, timeout=120):
    """Run psql and return (result_text, elapsed_seconds).

    Does NOT retry — we want to measure the actual first-connection latency.
    """
    env = {**__import__("os").environ, "no_proxy": "pg.dbay.cloud"}
    if password:
        env["PGPASSWORD"] = password

    if "sslmode=" not in connstr:
        sep = "&" if "?" in connstr else "?"
        connstr += f"{sep}sslmode=require"

    t0 = time.time()
    result = subprocess.run(
        ["psql", connstr, "-c", sql, "-t", "-A"],
        capture_output=True,
        text=True,
        timeout=timeout,
        env=env,
    )
    elapsed = time.time() - t0

    if result.returncode != 0:
        raise RuntimeError(f"psql failed ({elapsed:.1f}s): {result.stderr}")

    return result.stdout.strip(), elapsed


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


class TestStartupPerformance:
    """Measure cold start and hot start (auto-wake) latencies."""

    def test_cold_start_timing(self, e2e_client):
        """Measure cold start: create database → first query.

        Reports the time for:
        - API create call until status=RUNNING
        - First psql connection after RUNNING
        """
        t_create = time.time()
        db = e2e_client.create_database(name=f"e2e-cold-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=2,
        )
        t_running = time.time()
        assert db["status"] == "RUNNING"

        cold_api_time = t_running - t_create

        # First psql connection
        try:
            result, psql_time = psql_timed(
                db["connection_uri"], "SELECT 1", creation_password
            )
            assert result == "1"
        except RuntimeError:
            # If first attempt fails, retry with timing
            time.sleep(5)
            result, psql_time = psql_timed(
                db["connection_uri"], "SELECT 1", creation_password
            )

        total_cold = cold_api_time + psql_time

        print(f"\n{'=' * 60}")
        print(f"COLD START TIMING")
        print(f"  API create → RUNNING:  {cold_api_time:.1f}s")
        print(f"  First psql query:      {psql_time:.1f}s")
        print(f"  Total cold start:      {total_cold:.1f}s")
        print(f"{'=' * 60}")

        # Cleanup
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

        # Soft assertion: cold start should be under 30s typically
        assert total_cold < 60, f"Cold start too slow: {total_cold:.1f}s"

    def test_hot_start_timing(self, e2e_client):
        """Measure hot start (auto-wake): suspend → psql triggers auto-resume.

        The proxy auto-wakes the compute when a connection arrives.
        """
        # Create and wait for running
        db = e2e_client.create_database(name=f"e2e-hot-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"

        # Warm up — first connection to ensure compute is ready
        psql_with_retry(db["connection_uri"], "SELECT 1", creation_password)

        # Suspend
        e2e_client.suspend_database(db["id"])
        poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] == "SUSPENDED",
            timeout=60,
        )
        # Small wait for cleanup
        time.sleep(3)

        # Measure hot start — connect via proxy which triggers auto-wake
        t_wake = time.time()
        # psql_timed will wait for the proxy to wake the compute
        try:
            result, psql_time = psql_timed(
                db["connection_uri"], "SELECT 1", creation_password, timeout=120
            )
            hot_start = time.time() - t_wake
        except RuntimeError:
            # Retry once — proxy may need a moment
            time.sleep(5)
            result, psql_time = psql_timed(
                db["connection_uri"], "SELECT 1", creation_password, timeout=120
            )
            hot_start = time.time() - t_wake

        assert result == "1"

        print(f"\n{'=' * 60}")
        print(f"HOT START (AUTO-WAKE) TIMING")
        print(f"  Suspended → first query response: {hot_start:.1f}s")
        print(f"  (psql connection time:             {psql_time:.1f}s)")
        print(f"{'=' * 60}")

        # Cleanup
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

        # Hot start should be faster than cold start, but proxy wake still takes time
        assert hot_start < 30, f"Hot start too slow: {hot_start:.1f}s"

    def test_warm_query_timing(self, e2e_client):
        """Measure query latency on an already-running compute (warm path).

        This is the best-case scenario — compute is already running.
        """
        db = e2e_client.create_database(name=f"e2e-warm-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"

        # Warm up
        psql_with_retry(db["connection_uri"], "SELECT 1", creation_password)
        time.sleep(2)

        # Measure 5 consecutive queries
        timings = []
        for i in range(5):
            _, elapsed = psql_timed(
                db["connection_uri"], f"SELECT {i}", creation_password
            )
            timings.append(elapsed)

        avg_time = sum(timings) / len(timings)
        min_time = min(timings)
        max_time = max(timings)

        print(f"\n{'=' * 60}")
        print(f"WARM QUERY TIMING (5 queries)")
        print(f"  Min:  {min_time:.3f}s")
        print(f"  Max:  {max_time:.3f}s")
        print(f"  Avg:  {avg_time:.3f}s")
        print(f"  All:  {[f'{t:.3f}' for t in timings]}")
        print(f"{'=' * 60}")

        # Cleanup
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

        # Warm queries should be well under 2 seconds
        assert avg_time < 5, f"Warm query avg too slow: {avg_time:.3f}s"


class TestElasticPoolScaling:
    """Create multiple databases to test elastic node pool scaling behavior.

    This measures how startup times change as the pool fills up and needs
    to scale. Use -s flag to see timing output.
    """

    def test_sequential_cold_starts_3_databases(self, e2e_client):
        """Create 3 databases sequentially, measure each cold start.

        When elastic pool has capacity, each should be fast.
        When pool needs to scale, later databases may be slower.
        """
        timings = []
        db_ids = []

        for i in range(3):
            t0 = time.time()
            db = e2e_client.create_database(name=f"e2e-scale-{i}-{int(time.time())}")
            creation_password = db.get("password")
            db_ids.append(db["id"])

            db = poll_until(
                lambda db_id=db["id"]: e2e_client.get_database(db_id),
                condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
                timeout=300,  # Higher timeout for scaling
                interval=3,
            )
            t_running = time.time() - t0

            assert db["status"] == "RUNNING", f"DB {i} failed to start"

            # Measure first query
            _, psql_time = psql_timed(
                db["connection_uri"], "SELECT 1", creation_password
            )

            total = t_running + psql_time
            timings.append({
                "db_index": i,
                "api_time": t_running,
                "psql_time": psql_time,
                "total": total,
            })

        print(f"\n{'=' * 60}")
        print(f"SEQUENTIAL COLD START — 3 DATABASES")
        for t in timings:
            print(
                f"  DB #{t['db_index']}: "
                f"API={t['api_time']:.1f}s  "
                f"psql={t['psql_time']:.1f}s  "
                f"total={t['total']:.1f}s"
            )
        print(f"{'=' * 60}")

        # Cleanup
        for db_id in db_ids:
            try:
                e2e_client.delete_database(db_id)
            except Exception:
                pass

    def test_parallel_cold_starts_2_databases(self, e2e_client):
        """Create 2 databases in parallel, measure cold start under contention."""
        from concurrent.futures import ThreadPoolExecutor, as_completed

        results = {}
        errors = []

        def create_and_measure(index):
            try:
                t0 = time.time()
                db = e2e_client.create_database(
                    name=f"e2e-par-{index}-{int(time.time())}"
                )
                creation_password = db.get("password")

                db = poll_until(
                    lambda db_id=db["id"]: e2e_client.get_database(db_id),
                    condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
                    timeout=300,
                    interval=3,
                )
                t_running = time.time() - t0

                assert db["status"] == "RUNNING"

                psql_with_retry(
                    db["connection_uri"], "SELECT 1", creation_password
                )
                total = time.time() - t0

                return {
                    "index": index,
                    "db_id": db["id"],
                    "api_time": t_running,
                    "total": total,
                }
            except Exception as e:
                return {"index": index, "error": str(e)}

        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(create_and_measure, i) for i in range(2)]
            for f in as_completed(futures):
                r = f.result()
                if "error" in r:
                    errors.append(r)
                else:
                    results[r["index"]] = r

        print(f"\n{'=' * 60}")
        print(f"PARALLEL COLD START — 2 DATABASES")
        for idx in sorted(results):
            r = results[idx]
            print(f"  DB #{idx}: API={r['api_time']:.1f}s  total={r['total']:.1f}s")
        for e in errors:
            print(f"  DB #{e['index']}: ERROR: {e['error']}")
        print(f"{'=' * 60}")

        # Cleanup
        for r in results.values():
            try:
                e2e_client.delete_database(r["db_id"])
            except Exception:
                pass

        assert len(errors) == 0, f"Parallel cold starts failed: {errors}"
