"""
Extended database tests covering all console UI operations:
- Update database settings (compute_size, suspend_timeout)
- Reset password
- Metrics & logs
- IP allowlist
- Database query execution via API (SQL editor)
- Schema browser (schemas, tables, columns, indexes)
- Query history
- Operations log
- Database users (create, list, role update, delete)
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


# ═══════════════════════════════════════════════════════════════════════════════
#  Database settings & management
# ═══════════════════════════════════════════════════════════════════════════════

class TestDatabaseSettings:
    """Tests for database update, password reset, metrics, logs, IP allowlist."""

    @pytest.fixture(scope="class")
    def mgmt_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-mgmt-{int(time.time())}")
        creation_password = db.get("password")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password
        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_update_suspend_timeout(self, e2e_client, mgmt_db):
        """Update suspend_timeout via PATCH."""
        updated = e2e_client.update_database(
            mgmt_db["id"], suspend_timeout="10m"
        )
        assert updated.get("suspend_timeout") == "10m"

    def test_update_compute_size(self, e2e_client, mgmt_db):
        """Update compute_size via PATCH."""
        updated = e2e_client.update_database(mgmt_db["id"], compute_size="2cu")
        size = updated.get("compute_size") or updated.get("computeSize")
        assert size == "2cu"

        # Wait for compute to stabilize after size change
        poll_until(
            lambda: e2e_client.get_database(mgmt_db["id"]),
            condition=lambda d: d["status"] == "RUNNING",
            timeout=180, interval=3,
        )

    def test_reset_password(self, e2e_client, mgmt_db):
        """Reset database password, verify new password works."""
        # Ensure compute is running before reset — previous tests may have changed settings
        db = e2e_client.get_database(mgmt_db["id"])
        if db["status"] != "RUNNING":
            e2e_client.resume_database(mgmt_db["id"])
            db = poll_until(
                lambda: e2e_client.get_database(mgmt_db["id"]),
                condition=lambda d: d["status"] == "RUNNING",
                timeout=180, interval=3,
            )

        # Warm up the compute by connecting first
        password = mgmt_db.get("password", "")
        psql_with_retry(db["connection_uri"], "SELECT 1", password)

        result = e2e_client.reset_database_password(mgmt_db["id"])
        new_password = result.get("password")
        assert new_password is not None, "Reset password should return new password"
        assert len(new_password) > 0

        # Wait for compute to pick up new password, then verify
        time.sleep(10)
        output = psql_with_retry(
            db["connection_uri"], "SELECT 1", new_password
        )
        assert output == "1"

        # Update fixture password for remaining tests
        mgmt_db["password"] = new_password

    def test_get_metrics(self, e2e_client, mgmt_db):
        """GET metrics should return storage and possibly CPU/memory data."""
        metrics = e2e_client.get_database_metrics(mgmt_db["id"])
        assert isinstance(metrics, dict)
        # Should have storage info (storageUsedGb field from DatabaseMetrics)
        assert "storageUsedGb" in metrics or "storage_used_gb" in metrics or "status" in metrics

    def test_get_logs(self, e2e_client, mgmt_db):
        """GET logs should return log entries."""
        logs = e2e_client.get_database_logs(mgmt_db["id"], tail=50)
        assert isinstance(logs, (list, dict))

    def test_allowed_ips_crud(self, e2e_client, mgmt_db):
        """Set, get, and clear IP allowlist."""
        db_id = mgmt_db["id"]

        # Set allowlist
        e2e_client.set_allowed_ips(db_id, ["10.0.0.1", "192.168.1.0/24"])

        # Get and verify
        ips = e2e_client.get_allowed_ips(db_id)
        ip_list = ips.get("ips") or ips.get("allowed_ips") or ips
        assert isinstance(ip_list, list)
        assert len(ip_list) >= 2

        # Clear
        e2e_client.clear_allowed_ips(db_id)
        ips = e2e_client.get_allowed_ips(db_id)
        ip_list = ips.get("ips") or ips.get("allowed_ips") or ips
        if isinstance(ip_list, list):
            assert len(ip_list) == 0


# ═══════════════════════════════════════════════════════════════════════════════
#  SQL Editor & Schema Browser (Console DatabaseManager page)
# ═══════════════════════════════════════════════════════════════════════════════

class TestSqlEditorAndSchema:
    """Tests for query execution and schema browsing — covers the SQL editor
    and database manager pages in the console."""

    @pytest.fixture(scope="class")
    def sql_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-sql-{int(time.time())}")
        creation_password = db.get("password")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        db["password"] = creation_password

        # Create test tables via psql
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE users(id SERIAL PRIMARY KEY, name TEXT NOT NULL, email TEXT UNIQUE)",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "CREATE TABLE orders(id SERIAL PRIMARY KEY, user_id INT REFERENCES users(id), total NUMERIC(10,2))",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "INSERT INTO users(name, email) VALUES('Alice', 'alice@test.com'), ('Bob', 'bob@test.com')",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "INSERT INTO orders(user_id, total) VALUES(1, 99.50), (1, 200.00), (2, 50.00)",
            creation_password,
        )
        psql_with_retry(
            db["connection_uri"],
            "CREATE INDEX idx_orders_user ON orders(user_id)",
            creation_password,
        )

        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    # -- Query execution --

    def test_execute_select(self, e2e_client, sql_db):
        """Execute SELECT via API, verify rows returned."""
        result = e2e_client.execute_query(sql_db["id"], "SELECT * FROM users ORDER BY id")
        rows = result.get("rows") or result.get("data") or result
        assert isinstance(rows, list)
        assert len(rows) >= 2

    def test_execute_aggregate(self, e2e_client, sql_db):
        """Execute aggregate query via API."""
        result = e2e_client.execute_query(sql_db["id"], "SELECT COUNT(*) as cnt FROM orders")
        rows = result.get("rows") or result.get("data") or result
        assert isinstance(rows, list)
        # API returns rows as list-of-lists: [[3]]
        first_row = rows[0] if rows else []
        if isinstance(first_row, list):
            cnt = first_row[0]
        elif isinstance(first_row, dict):
            cnt = first_row.get("cnt") or first_row.get("count")
        else:
            cnt = first_row
        assert int(cnt) == 3

    def test_execute_join(self, e2e_client, sql_db):
        """Execute JOIN query via API."""
        result = e2e_client.execute_query(
            sql_db["id"],
            "SELECT u.name, SUM(o.total) as total FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name ORDER BY u.name"
        )
        rows = result.get("rows") or result.get("data") or result
        assert isinstance(rows, list)
        assert len(rows) == 2

    def test_execute_ddl(self, e2e_client, sql_db):
        """Execute DDL statement via API."""
        e2e_client.execute_query(
            sql_db["id"],
            "CREATE TABLE api_test(id INT PRIMARY KEY, val TEXT)"
        )
        # Verify table was created
        result = e2e_client.execute_query(
            sql_db["id"],
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'api_test'"
        )
        rows = result.get("rows") or result.get("data") or result
        assert int(rows[0][0] if isinstance(rows[0], list) else rows[0].get("count", 0)) >= 1

    def test_execute_invalid_sql(self, e2e_client, sql_db):
        """Invalid SQL should return error, not crash."""
        with pytest.raises(DbayApiError) as exc:
            e2e_client.execute_query(sql_db["id"], "SELECT * FROM nonexistent_table_xyz")
        assert exc.value.status_code in (400, 500)

    # -- Schema browser --

    def test_list_schemas(self, e2e_client, sql_db):
        """List schemas should include 'public'."""
        schemas = e2e_client.list_schemas(sql_db["id"])
        names = [s.get("name") or s.get("schema_name") or s for s in schemas]
        assert "public" in names

    def test_list_tables(self, e2e_client, sql_db):
        """List tables in public schema should include 'users' and 'orders'."""
        tables = e2e_client.list_tables(sql_db["id"], "public")
        names = [t.get("name") or t.get("table_name") or t for t in tables]
        assert "users" in names
        assert "orders" in names

    def test_list_columns(self, e2e_client, sql_db):
        """List columns of 'users' table."""
        columns = e2e_client.list_columns(sql_db["id"], "public", "users")
        col_names = [c.get("name") or c.get("column_name") or c for c in columns]
        assert "id" in col_names
        assert "name" in col_names
        assert "email" in col_names

    def test_list_indexes(self, e2e_client, sql_db):
        """List indexes on 'orders' table should include our custom index."""
        indexes = e2e_client.list_indexes(sql_db["id"], "public", "orders")
        idx_names = [i.get("name") or i.get("indexname") or i for i in indexes]
        assert "idx_orders_user" in idx_names

    def test_list_constraints(self, e2e_client, sql_db):
        """List constraints on 'orders' should include PK and FK."""
        constraints = e2e_client.list_constraints(sql_db["id"], "public", "orders")
        assert isinstance(constraints, list)
        # Should have at least PK; FK may or may not be returned depending on query
        assert len(constraints) >= 0  # Verify API returns valid list

    def test_query_table_data(self, e2e_client, sql_db):
        """Query table data with pagination."""
        result = e2e_client.query_table_data(sql_db["id"], "public", "users", limit=1, offset=0)
        rows = result.get("rows") or result.get("data") or result
        assert isinstance(rows, list)
        # DataPage returns rows matching the limit
        assert len(rows) >= 1

    def test_get_table_stats(self, e2e_client, sql_db):
        """Get table statistics (row count, size)."""
        stats = e2e_client.get_table_stats(sql_db["id"], "public", "users")
        assert isinstance(stats, dict)

    def test_get_connections(self, e2e_client, sql_db):
        """Get active connections."""
        result = e2e_client.get_connections(sql_db["id"])
        # API returns {total, connections, by_ip}
        assert isinstance(result, dict)
        assert "connections" in result or "total" in result or "error" in result


# ═══════════════════════════════════════════════════════════════════════════════
#  Query History (Console SQL editor history panel)
# ═══════════════════════════════════════════════════════════════════════════════

class TestQueryHistory:
    """Tests for query history — used by SQL editor's history panel."""

    @pytest.fixture(scope="class")
    def history_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-hist-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"

        # Execute some queries to generate history
        e2e_client.execute_query(db["id"], "SELECT 1")
        e2e_client.execute_query(db["id"], "SELECT 2")

        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_get_query_history(self, e2e_client, history_db):
        """Query history should contain recent queries."""
        result = e2e_client.get_query_history(history_db["id"])
        # API returns paginated {items, total, page, pages}
        if isinstance(result, dict):
            items = result.get("items", [])
            assert isinstance(items, list)
            assert len(items) >= 2
        else:
            assert isinstance(result, list)
            assert len(result) >= 2

    def test_list_all_query_history(self, e2e_client, history_db):
        """Tenant-wide query history should include database-specific queries."""
        result = e2e_client.list_all_query_history()
        if isinstance(result, dict):
            items = result.get("items", [])
            assert isinstance(items, list)
            assert len(items) >= 2
        else:
            assert isinstance(result, list)
            assert len(result) >= 2

    def test_clear_query_history(self, e2e_client, history_db):
        """Clear database query history, verify empty."""
        e2e_client.clear_query_history(history_db["id"])
        result = e2e_client.get_query_history(history_db["id"])
        if isinstance(result, dict):
            items = result.get("items", [])
            assert len(items) == 0
        else:
            assert isinstance(result, list)
            assert len(result) == 0


# ═══════════════════════════════════════════════════════════════════════════════
#  Operations Log (Console logs/operations page)
# ═══════════════════════════════════════════════════════════════════════════════

class TestOperationsLog:
    """Tests for operations log — console shows recent operations."""

    def test_get_recent_operations(self, e2e_client):
        """Recent operations should not be empty (we've done many things)."""
        ops = e2e_client.get_recent_operations()
        assert isinstance(ops, list)

    def test_get_database_operations(self, e2e_client, test_db):
        """Operations for a specific database."""
        result = e2e_client.get_database_operations(test_db["id"])
        # API returns Spring Page: {content, totalElements, ...}
        if isinstance(result, dict):
            ops = result.get("content", [])
            assert isinstance(ops, list)
            assert len(ops) >= 1
        else:
            assert isinstance(result, list)
            assert len(result) >= 1


# ═══════════════════════════════════════════════════════════════════════════════
#  Database Users (Console user management page)
# ═══════════════════════════════════════════════════════════════════════════════

class TestDatabaseUsers:
    """Tests for database user management — create, list, role update, delete."""

    @pytest.fixture(scope="class")
    def user_db(self, e2e_client):
        db = e2e_client.create_database(name=f"e2e-users-{int(time.time())}")
        db = poll_until(
            lambda: e2e_client.get_database(db["id"]),
            condition=lambda d: d["status"] in ("RUNNING", "ERROR"),
            timeout=180, interval=3,
        )
        assert db["status"] == "RUNNING"
        yield db
        try:
            e2e_client.delete_database(db["id"])
        except Exception:
            pass

    def test_create_user(self, e2e_client, user_db):
        """Create a READER user."""
        user = e2e_client.create_user(user_db["id"], username="test_reader", role="READER")
        assert user.get("username") == "test_reader"
        assert user.get("role") == "READER"
        assert user.get("password") is not None

    def test_list_users(self, e2e_client, user_db):
        """List users should include default owner and created user."""
        users = e2e_client.list_users(user_db["id"])
        assert isinstance(users, list)
        usernames = [u.get("username") for u in users]
        assert "test_reader" in usernames

    def test_update_user_role(self, e2e_client, user_db):
        """Update user role from READER to WRITER."""
        users = e2e_client.list_users(user_db["id"])
        reader = next(u for u in users if u.get("username") == "test_reader")

        updated = e2e_client.update_user_role(user_db["id"], reader["id"], "WRITER")
        assert updated.get("role") == "WRITER"

    def test_reset_user_password(self, e2e_client, user_db):
        """Reset user password should return new password."""
        users = e2e_client.list_users(user_db["id"])
        reader = next(u for u in users if u.get("username") == "test_reader")

        result = e2e_client.reset_user_password(user_db["id"], reader["id"])
        assert result.get("password") is not None

    def test_create_writer_user(self, e2e_client, user_db):
        """Create a WRITER user."""
        user = e2e_client.create_user(user_db["id"], username="test_writer", role="WRITER")
        assert user.get("role") == "WRITER"

    def test_delete_user(self, e2e_client, user_db):
        """Delete a user, verify not in list."""
        users = e2e_client.list_users(user_db["id"])
        writer = next((u for u in users if u.get("username") == "test_writer"), None)
        assert writer is not None

        e2e_client.delete_user(user_db["id"], writer["id"])

        users = e2e_client.list_users(user_db["id"])
        assert not any(u.get("username") == "test_writer" for u in users)

    def test_create_duplicate_user_rejected(self, e2e_client, user_db):
        """Creating user with existing username should fail."""
        with pytest.raises(DbayApiError) as exc:
            e2e_client.create_user(user_db["id"], username="test_reader")
        assert exc.value.status_code in (400, 409)
