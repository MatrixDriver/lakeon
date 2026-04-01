"""Tests for dbay_sre_mcp SQL builder functions (no DB required)."""

import pytest

from dbay_sre_mcp.server import (
    _build_errors_query,
    _build_search_query,
    _build_stats_query,
    _build_trace_query,
    _parse_interval,
)


# ---------------------------------------------------------------------------
# _parse_interval tests
# ---------------------------------------------------------------------------

class TestParseInterval:
    def test_hours(self):
        assert _parse_interval("1h") == "1 hours"

    def test_minutes(self):
        assert _parse_interval("30m") == "30 minutes"

    def test_days(self):
        assert _parse_interval("2d") == "2 days"

    def test_weeks(self):
        assert _parse_interval("1w") == "1 weeks"

    def test_strips_whitespace(self):
        assert _parse_interval("  6h  ") == "6 hours"

    def test_invalid_unit(self):
        with pytest.raises(ValueError, match="Unknown interval unit"):
            _parse_interval("5x")

    def test_invalid_amount(self):
        with pytest.raises(ValueError, match="Invalid interval value"):
            _parse_interval("abch")

    def test_empty(self):
        with pytest.raises(ValueError, match="must not be empty"):
            _parse_interval("")


# ---------------------------------------------------------------------------
# test_search_query_basic
# ---------------------------------------------------------------------------

def test_search_query_basic():
    """With component and level, SQL should have correct WHERE clauses."""
    sql, params = _build_search_query(component="api", level="ERROR", since="1h", limit=50)

    assert "WHERE" in sql
    assert "component = %s" in sql
    assert "level = %s" in sql
    assert "ORDER BY ts DESC" in sql
    assert "LIMIT %s" in sql

    # params: interval, component, level, limit
    assert "1 hours" in params
    assert "api" in params
    assert "ERROR" in params
    assert 50 in params


def test_search_query_level_uppercased():
    """Level filter value should be uppercased."""
    _, params = _build_search_query(level="warn", since="30m")
    assert "WARN" in params


# ---------------------------------------------------------------------------
# test_search_query_keyword
# ---------------------------------------------------------------------------

def test_search_query_keyword():
    """With keyword param, SQL should use to_tsvector."""
    sql, params = _build_search_query(keyword="connection refused", since="2h")

    assert "to_tsvector" in sql
    assert "plainto_tsquery" in sql
    assert "connection refused" in params


def test_search_query_no_keyword_no_tsvector():
    """Without keyword, SQL should NOT contain to_tsvector."""
    sql, _ = _build_search_query(component="worker", since="1h")
    assert "to_tsvector" not in sql


def test_search_query_all_filters():
    """All filters combined produce correct param list."""
    sql, params = _build_search_query(
        component="api",
        level="INFO",
        keyword="startup",
        tenant_id="t123",
        db_id="db456",
        since="30m",
        limit=10,
    )
    assert "tenant_id = %s" in sql
    assert "db_id = %s" in sql
    assert "t123" in params
    assert "db456" in params
    assert 10 in params


# ---------------------------------------------------------------------------
# test_trace_query
# ---------------------------------------------------------------------------

def test_trace_query():
    """Verify request_id param and ORDER BY ts (ascending)."""
    request_id = "req-abc-123"
    sql, params = _build_trace_query(request_id)

    assert "request_id = %s" in sql
    assert "ORDER BY ts" in sql
    # Must be ascending (no DESC)
    assert "ORDER BY ts DESC" not in sql
    assert params == [request_id]


def test_trace_query_selects_all_columns():
    """Trace query must select the standard log columns."""
    sql, _ = _build_trace_query("any-id")
    for col in ("id", "ts", "level", "component", "request_id", "msg", "duration_ms"):
        assert col in sql


# ---------------------------------------------------------------------------
# test_errors_query
# ---------------------------------------------------------------------------

def test_errors_query():
    """Verify level IN ('ERROR', 'WARN') and time filter."""
    sql, params = _build_errors_query(since="1h")

    assert "level IN ('ERROR', 'WARN')" in sql
    assert "LIMIT 200" in sql
    assert "ORDER BY ts DESC" in sql
    assert "1 hours" in params


def test_errors_query_component_filter():
    """With component, SQL should include component = %s."""
    sql, params = _build_errors_query(since="2h", component="scheduler")

    assert "component = %s" in sql
    assert "scheduler" in params
    # level filter still present
    assert "level IN ('ERROR', 'WARN')" in sql


def test_errors_query_no_component():
    """Without component, SQL should NOT contain component = %s."""
    sql, _ = _build_errors_query(since="1h")
    assert "component = %s" not in sql


# ---------------------------------------------------------------------------
# test_stats_query
# ---------------------------------------------------------------------------

def test_stats_query():
    """Verify GROUP BY component, level in the stats SQL."""
    sql, params = _build_stats_query(since="24h")

    assert "GROUP BY component, level" in sql
    assert "24 hours" in params


def test_stats_query_slow_ops():
    """Stats SQL should include slow ops ordered by duration_ms."""
    sql, params = _build_stats_query(since="12h")

    assert "duration_ms" in sql
    assert "ORDER BY duration_ms DESC" in sql
    assert "LIMIT 10" in sql
    # Two interval params (counts + slow ops)
    assert params.count("12 hours") == 2
