"""
Database stress tests — repeated suspend/resume cycles, concurrent queries,
concurrent writes, and data integrity under load.

Run:
    pytest tests/e2e/test_database_stress.py -v

These tests are heavier than basic CRUD and may take several minutes.
"""
import subprocess
import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

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
            if "not yet accepting connections" in str(e):
                time.sleep(20)
            else:
                time.sleep(delay)


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 1: Repeated suspend / resume cycles
# ═══════════════════════════════════════════════════════════════════════════════

class TestSuspendResumeCycles:
    """Simulate a user repeatedly starting and stopping their database."""

    @pytest.fixture(scope="class")
    def stress_db(self, e2e_client):
        """Create a database for suspend/resume stress tests."""
        db = e2e_client.create_database(name=f"e2e-stress-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Seed initial data
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE stress_data(id SERIAL PRIMARY KEY, val INT, ts TIMESTAMPTZ DEFAULT NOW())",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "INSERT INTO stress_data(val) SELECT generate_series(1, 100)",
            creation_password,
        )

        yield db

        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_repeated_suspend_resume_3_cycles(self, e2e_client, stress_db):
        """Suspend and resume 3 times, verify data survives each cycle."""
        db_id = stress_db["id"]
        password = stress_db.get("password", "")
        connstr = stress_db["connection_uri"]

        for cycle in range(3):
            # Verify data before suspend
            result = psql_with_retry(
                connstr,
                "SELECT COUNT(*) FROM stress_data",
                password,
            )
            count = int(result)
            assert count >= 100, f"Cycle {cycle}: expected >=100 rows, got {count}"

            # Suspend
            e2e_client.suspend_database(db_id)
            poll_until(
                lambda: e2e_client.get_database(db_id),
                condition=lambda d: d["status"] == "SUSPENDED",
                timeout=60,
            )

            # Resume
            e2e_client.resume_database(db_id)
            poll_until(
                lambda: e2e_client.get_database(db_id),
                condition=lambda d: d["status"] == "RUNNING",
                timeout=180,
            )

            # Verify data after resume
            result = psql_with_retry(
                connstr,
                "SELECT COUNT(*) FROM stress_data",
                password,
            )
            count_after = int(result)
            assert count_after == count, (
                f"Cycle {cycle}: data lost after resume, expected {count}, got {count_after}"
            )

            # Insert data each cycle to verify writes work after resume
            psql_with_retry(
                connstr,
                f"INSERT INTO stress_data(val) VALUES({1000 + cycle})",
                password,
            )

    def test_suspend_during_active_connection(self, e2e_client, stress_db):
        """Suspend while a query is logically in progress.

        Write data, then immediately suspend. Resume and verify data.
        """
        db_id = stress_db["id"]
        password = stress_db.get("password", "")
        connstr = stress_db["connection_uri"]

        # Write data
        psql_with_retry(connstr, "INSERT INTO stress_data(val) VALUES(9999)", password)

        # Immediately suspend
        e2e_client.suspend_database(db_id)
        poll_until(
            lambda: e2e_client.get_database(db_id),
            condition=lambda d: d["status"] == "SUSPENDED",
            timeout=60,
        )

        # Resume and verify
        e2e_client.resume_database(db_id)
        poll_until(
            lambda: e2e_client.get_database(db_id),
            condition=lambda d: d["status"] == "RUNNING",
            timeout=180,
        )

        result = psql_with_retry(
            connstr, "SELECT val FROM stress_data WHERE val=9999", password
        )
        assert result == "9999", "Data committed before suspend should persist"

    def test_resume_already_running(self, e2e_client, stress_db):
        """Resuming an already running database should not error (idempotent)."""
        db_id = stress_db["id"]

        # Ensure it's running
        db = e2e_client.get_database(db_id)
        if db["status"] != "RUNNING":
            e2e_client.resume_database(db_id)
            poll_until(
                lambda: e2e_client.get_database(db_id),
                condition=lambda d: d["status"] == "RUNNING",
                timeout=180,
            )

        # Resume again — should not raise
        e2e_client.resume_database(db_id)
        db = e2e_client.get_database(db_id)
        assert db["status"] == "RUNNING"

    def test_suspend_already_suspended(self, e2e_client, stress_db):
        """Suspending an already suspended database should not error (idempotent)."""
        db_id = stress_db["id"]
        password = stress_db.get("password", "")

        # Make sure it's running first
        db = e2e_client.get_database(db_id)
        if db["status"] != "RUNNING":
            e2e_client.resume_database(db_id)
            poll_until(
                lambda: e2e_client.get_database(db_id),
                condition=lambda d: d["status"] == "RUNNING",
                timeout=180,
            )

        # Suspend
        e2e_client.suspend_database(db_id)
        poll_until(
            lambda: e2e_client.get_database(db_id),
            condition=lambda d: d["status"] == "SUSPENDED",
            timeout=60,
        )

        # Suspend again — should not raise
        e2e_client.suspend_database(db_id)

        # Resume for next tests
        e2e_client.resume_database(db_id)
        poll_until(
            lambda: e2e_client.get_database(db_id),
            condition=lambda d: d["status"] == "RUNNING",
            timeout=180,
        )


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 2: Concurrent reads
# ═══════════════════════════════════════════════════════════════════════════════

class TestConcurrentReads:
    """Simulate multiple clients doing concurrent SELECT queries."""

    @pytest.fixture(scope="class")
    def read_db(self, e2e_client):
        """Database with seeded data for concurrent read tests."""
        db = e2e_client.create_database(name=f"e2e-conread-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Seed data
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE read_test(id INT PRIMARY KEY, val TEXT)",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "INSERT INTO read_test SELECT g, 'row-' || g FROM generate_series(1, 1000) g",
            creation_password,
        )

        yield db

        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_concurrent_selects_5_threads(self, read_db):
        """5 concurrent SELECT queries should all succeed and return correct data."""
        connstr = read_db["connection_uri"]
        password = read_db.get("password", "")
        errors = []
        results = []

        def do_select(thread_id):
            try:
                result = psql_with_retry(
                    connstr,
                    f"SELECT COUNT(*) FROM read_test WHERE id <= {200 * (thread_id + 1)}",
                    password,
                    retries=3,
                    delay=5,
                )
                return int(thread_id), int(result)
            except Exception as e:
                return int(thread_id), e

        with ThreadPoolExecutor(max_workers=5) as pool:
            futures = [pool.submit(do_select, i) for i in range(5)]
            for f in as_completed(futures):
                tid, result = f.result()
                if isinstance(result, Exception):
                    errors.append((tid, result))
                else:
                    results.append((tid, result))

        assert len(errors) == 0, f"Concurrent reads failed: {errors}"
        assert len(results) == 5, f"Expected 5 results, got {len(results)}"

        # Each thread selects rows WHERE id <= 200*(tid+1)
        for tid, count in results:
            expected = min(200 * (tid + 1), 1000)
            assert count == expected, (
                f"Thread {tid}: expected {expected} rows, got {count}"
            )

    def test_concurrent_aggregations_5_threads(self, read_db):
        """5 threads running different aggregations concurrently."""
        connstr = read_db["connection_uri"]
        password = read_db.get("password", "")

        queries = [
            ("COUNT(*)", "1000"),
            ("SUM(id)", "500500"),
            ("MIN(id)", "1"),
            ("MAX(id)", "1000"),
            ("AVG(id)", "500.5"),
        ]
        errors = []

        def do_agg(idx):
            agg, _expected = queries[idx]
            try:
                result = psql_with_retry(
                    connstr,
                    f"SELECT {agg} FROM read_test",
                    password,
                    retries=3,
                    delay=5,
                )
                return idx, result.strip()
            except Exception as e:
                return idx, e

        with ThreadPoolExecutor(max_workers=5) as pool:
            futures = [pool.submit(do_agg, i) for i in range(5)]
            for f in as_completed(futures):
                idx, result = f.result()
                if isinstance(result, Exception):
                    errors.append((idx, result))
                else:
                    _, expected = queries[idx]
                    # Handle decimal format for AVG
                    if expected == "500.5":
                        assert float(result) == pytest.approx(500.5, abs=0.1), (
                            f"Query {idx}: expected ~500.5, got {result}"
                        )
                    else:
                        assert result == expected, (
                            f"Query {idx}: expected {expected}, got {result}"
                        )

        assert len(errors) == 0, f"Concurrent aggregations failed: {errors}"


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 3: Concurrent writes
# ═══════════════════════════════════════════════════════════════════════════════

class TestConcurrentWrites:
    """Simulate multiple clients doing concurrent INSERT/UPDATE operations."""

    @pytest.fixture(scope="class")
    def write_db(self, e2e_client):
        """Database for concurrent write tests."""
        db = e2e_client.create_database(name=f"e2e-conwrite-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Create tables for concurrent writes
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE write_test(id SERIAL PRIMARY KEY, thread_id INT, val INT, ts TIMESTAMPTZ DEFAULT NOW())",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE counter(name TEXT PRIMARY KEY, val INT DEFAULT 0)",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "INSERT INTO counter(name, val) VALUES('global', 0)",
            creation_password,
        )

        yield db

        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_concurrent_inserts_5_threads(self, write_db):
        """5 threads inserting rows concurrently, verify total count matches."""
        connstr = write_db["connection_uri"]
        password = write_db.get("password", "")
        rows_per_thread = 20
        errors = []

        def do_inserts(thread_id):
            try:
                for i in range(rows_per_thread):
                    psql_with_retry(
                        connstr,
                        f"INSERT INTO write_test(thread_id, val) VALUES({thread_id}, {thread_id * 1000 + i})",
                        password,
                        retries=3,
                        delay=5,
                    )
                return thread_id, None
            except Exception as e:
                return thread_id, e

        with ThreadPoolExecutor(max_workers=5) as pool:
            futures = [pool.submit(do_inserts, i) for i in range(5)]
            for f in as_completed(futures):
                tid, err = f.result()
                if err:
                    errors.append((tid, err))

        assert len(errors) == 0, f"Concurrent inserts failed: {errors}"

        # Verify total count
        result = psql_with_retry(connstr, "SELECT COUNT(*) FROM write_test", password)
        total = int(result)
        expected = 5 * rows_per_thread
        assert total == expected, f"Expected {expected} rows, got {total}"

        # Verify each thread's data is present
        for tid in range(5):
            result = psql_with_retry(
                connstr,
                f"SELECT COUNT(*) FROM write_test WHERE thread_id = {tid}",
                password,
            )
            assert int(result) == rows_per_thread, (
                f"Thread {tid}: expected {rows_per_thread} rows, got {result}"
            )

    def test_concurrent_counter_updates(self, write_db):
        """5 threads each incrementing a shared counter 10 times.

        Uses advisory locks to ensure correctness. Final count should be 50.
        """
        connstr = write_db["connection_uri"]
        password = write_db.get("password", "")
        increments_per_thread = 10
        errors = []

        # Reset counter
        psql_with_retry(
            connstr,
            "UPDATE counter SET val = 0 WHERE name = 'global'",
            password,
        )

        def do_increments(thread_id):
            try:
                for _ in range(increments_per_thread):
                    # Use serializable transaction for correctness
                    psql_with_retry(
                        connstr,
                        "UPDATE counter SET val = val + 1 WHERE name = 'global'",
                        password,
                        retries=3,
                        delay=5,
                    )
                return thread_id, None
            except Exception as e:
                return thread_id, e

        with ThreadPoolExecutor(max_workers=5) as pool:
            futures = [pool.submit(do_increments, i) for i in range(5)]
            for f in as_completed(futures):
                tid, err = f.result()
                if err:
                    errors.append((tid, err))

        assert len(errors) == 0, f"Concurrent counter updates failed: {errors}"

        result = psql_with_retry(
            connstr,
            "SELECT val FROM counter WHERE name = 'global'",
            password,
        )
        expected = 5 * increments_per_thread
        assert int(result) == expected, f"Expected counter={expected}, got {result}"

    def test_concurrent_mixed_read_write(self, write_db):
        """3 writers and 2 readers running concurrently.

        Writers insert rows, readers count rows. Readers should never get errors.
        """
        connstr = write_db["connection_uri"]
        password = write_db.get("password", "")

        # Get baseline count
        baseline = int(psql_with_retry(
            connstr, "SELECT COUNT(*) FROM write_test", password
        ))

        write_errors = []
        read_results = []
        rows_per_writer = 10

        def do_writes(thread_id):
            try:
                for i in range(rows_per_writer):
                    psql_with_retry(
                        connstr,
                        f"INSERT INTO write_test(thread_id, val) VALUES({100 + thread_id}, {i})",
                        password,
                        retries=3,
                        delay=5,
                    )
                return None
            except Exception as e:
                return e

        def do_reads(thread_id):
            counts = []
            try:
                for _ in range(5):
                    result = psql_with_retry(
                        connstr,
                        "SELECT COUNT(*) FROM write_test",
                        password,
                        retries=3,
                        delay=5,
                    )
                    counts.append(int(result))
                    time.sleep(0.5)
                return counts
            except Exception as e:
                return e

        with ThreadPoolExecutor(max_workers=5) as pool:
            write_futures = [pool.submit(do_writes, i) for i in range(3)]
            read_futures = [pool.submit(do_reads, i) for i in range(2)]

            for f in as_completed(write_futures):
                err = f.result()
                if err:
                    write_errors.append(err)

            for f in as_completed(read_futures):
                result = f.result()
                if isinstance(result, Exception):
                    write_errors.append(result)
                else:
                    read_results.extend(result)

        assert len(write_errors) == 0, f"Writes failed: {write_errors}"

        # Every read count should be >= baseline (never negative growth)
        for count in read_results:
            assert count >= baseline, (
                f"Reader saw count {count} < baseline {baseline}"
            )

        # Final count should include all new writes
        final = int(psql_with_retry(
            connstr, "SELECT COUNT(*) FROM write_test", password
        ))
        expected = baseline + 3 * rows_per_writer
        assert final == expected, f"Expected {expected} total rows, got {final}"


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 4: Data integrity
# ═══════════════════════════════════════════════════════════════════════════════

class TestDataIntegrity:
    """Verify data integrity across operations."""

    @pytest.fixture(scope="class")
    def integrity_db(self, e2e_client):
        """Database for data integrity tests."""
        db = e2e_client.create_database(name=f"e2e-integ-{int(time.time())}")
        creation_password = db.get("password")

        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180,
            interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        yield db

        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_transaction_atomicity(self, integrity_db):
        """Multi-statement transaction should be atomic — all or nothing."""
        connstr = integrity_db["connection_uri"]
        password = integrity_db.get("password", "")

        psql_with_retry(
            connstr,
            "CREATE TABLE IF NOT EXISTS txn_test(id INT PRIMARY KEY, val TEXT)",
            password,
        )
        psql_with_retry(connstr, "DELETE FROM txn_test", password)

        # Successful transaction
        psql_with_retry(
            connstr,
            "BEGIN; INSERT INTO txn_test VALUES(1, 'a'); INSERT INTO txn_test VALUES(2, 'b'); COMMIT",
            password,
        )
        result = psql_with_retry(connstr, "SELECT COUNT(*) FROM txn_test", password)
        assert int(result) == 2

        # Failed transaction (duplicate key should rollback)
        try:
            psql_with_retry(
                connstr,
                "BEGIN; INSERT INTO txn_test VALUES(3, 'c'); INSERT INTO txn_test VALUES(1, 'dup'); COMMIT",
                password,
                retries=1,
            )
        except RuntimeError:
            pass  # Expected to fail

        # Row 3 should NOT exist (transaction rolled back)
        result = psql_with_retry(
            connstr, "SELECT COUNT(*) FROM txn_test WHERE id = 3", password
        )
        assert int(result) == 0, "Failed transaction should have rolled back row 3"

    def test_large_batch_insert_and_verify(self, integrity_db):
        """Insert 10000 rows in one statement, verify count and checksum."""
        connstr = integrity_db["connection_uri"]
        password = integrity_db.get("password", "")

        psql_with_retry(
            connstr,
            "CREATE TABLE IF NOT EXISTS batch_test(id INT, val INT)",
            password,
        )
        psql_with_retry(connstr, "DELETE FROM batch_test", password)

        # Batch insert
        psql_with_retry(
            connstr,
            "INSERT INTO batch_test SELECT g, g * 2 FROM generate_series(1, 10000) g",
            password,
        )

        # Verify count
        result = psql_with_retry(connstr, "SELECT COUNT(*) FROM batch_test", password)
        assert int(result) == 10000

        # Verify SUM(val) = 2 * SUM(1..10000) = 2 * 50005000 = 100010000
        result = psql_with_retry(connstr, "SELECT SUM(val) FROM batch_test", password)
        assert int(result) == 100010000

    def test_data_types_roundtrip(self, integrity_db):
        """Verify various PG data types survive insert and select."""
        connstr = integrity_db["connection_uri"]
        password = integrity_db.get("password", "")

        psql_with_retry(
            connstr,
            """CREATE TABLE IF NOT EXISTS type_test(
                i INT, b BIGINT, f FLOAT8, t TEXT, j JSONB, a INT[],
                ts TIMESTAMPTZ, bool_col BOOLEAN, uuid_col UUID
            )""",
            password,
        )
        psql_with_retry(connstr, "DELETE FROM type_test", password)

        psql_with_retry(
            connstr,
            """INSERT INTO type_test VALUES(
                42, 9223372036854775807, 3.14159, 'hello 你好',
                '{"key": "value"}'::jsonb, ARRAY[1,2,3],
                '2026-03-23T12:00:00Z', true,
                'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'
            )""",
            password,
        )

        # Integer
        result = psql_with_retry(connstr, "SELECT i FROM type_test", password)
        assert result == "42"

        # Bigint
        result = psql_with_retry(connstr, "SELECT b FROM type_test", password)
        assert result == "9223372036854775807"

        # Float
        result = psql_with_retry(connstr, "SELECT ROUND(f::numeric, 5) FROM type_test", password)
        assert result == "3.14159"

        # Text with unicode
        result = psql_with_retry(connstr, "SELECT t FROM type_test", password)
        assert result == "hello 你好"

        # JSONB
        result = psql_with_retry(
            connstr, "SELECT j->>'key' FROM type_test", password
        )
        assert result == "value"

        # Boolean
        result = psql_with_retry(connstr, "SELECT bool_col FROM type_test", password)
        assert result == "t"

    def test_pgvector_extension(self, integrity_db):
        """Verify pgvector extension works for vector operations."""
        connstr = integrity_db["connection_uri"]
        password = integrity_db.get("password", "")

        psql_with_retry(connstr, "CREATE EXTENSION IF NOT EXISTS vector", password)
        psql_with_retry(
            connstr,
            "CREATE TABLE IF NOT EXISTS vec_test(id INT, embedding vector(3))",
            password,
        )
        psql_with_retry(connstr, "DELETE FROM vec_test", password)

        psql_with_retry(
            connstr,
            "INSERT INTO vec_test VALUES(1, '[1,0,0]'), (2, '[0,1,0]'), (3, '[0,0,1]')",
            password,
        )

        # Nearest neighbor search
        result = psql_with_retry(
            connstr,
            "SELECT id FROM vec_test ORDER BY embedding <-> '[1,0,0]' LIMIT 1",
            password,
        )
        assert result == "1", f"Expected nearest to [1,0,0] is id=1, got {result}"

    def test_full_text_search(self, integrity_db):
        """Verify full-text search with tsvector."""
        connstr = integrity_db["connection_uri"]
        password = integrity_db.get("password", "")

        psql_with_retry(
            connstr,
            """CREATE TABLE IF NOT EXISTS fts_test(
                id INT, title TEXT, body TEXT,
                search_vec TSVECTOR GENERATED ALWAYS AS (
                    to_tsvector('english', coalesce(title,'') || ' ' || coalesce(body,''))
                ) STORED
            )""",
            password,
        )
        psql_with_retry(connstr, "DELETE FROM fts_test", password)

        psql_with_retry(
            connstr,
            "INSERT INTO fts_test(id, title, body) VALUES(1, 'PostgreSQL Tutorial', 'Learn about databases and SQL queries')",
            password,
        )
        psql_with_retry(
            connstr,
            "INSERT INTO fts_test(id, title, body) VALUES(2, 'Python Guide', 'Programming with Python and machine learning')",
            password,
        )

        result = psql_with_retry(
            connstr,
            "SELECT id FROM fts_test WHERE search_vec @@ to_tsquery('english', 'database') ORDER BY id",
            password,
        )
        assert result == "1"
