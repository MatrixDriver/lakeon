"""Postgres-backed log query implementations.

Extracted from server.py in 0.2.0 refactor. Behavior unchanged from 0.1.0:
all 4 log_* tools work the same, same return JSON shapes.

Connection: delegates to _query() / _get_dsn() from server module (reads
LOG_DB_DSN env var or ~/.dbay/sre-config.json).
"""
from __future__ import annotations

import json

from dbay_sre_mcp.server import (
    _build_errors_query,
    _build_search_query,
    _build_stats_query,
    _build_trace_query,
    _parse_interval,
    _query,
)


def log_search_impl(
    *,
    component: str = "",
    level: str = "",
    keyword: str = "",
    tenant_id: str = "",
    db_id: str = "",
    since: str = "1h",
    limit: int = 100,
) -> str:
    """Search dbay-logs by component / keyword / time window. Returns JSON string."""
    sql, params = _build_search_query(
        component=component,
        level=level,
        keyword=keyword,
        tenant_id=tenant_id,
        db_id=db_id,
        since=since,
        limit=limit,
    )
    rows = _query(sql, params)
    return json.dumps(rows, default=str)


def log_trace_impl(request_id: str) -> str:
    """Retrieve all log entries for a given request_id, ordered by ts."""
    sql, params = _build_trace_query(request_id)
    rows = _query(sql, params)
    return json.dumps(rows, default=str)


def log_errors_impl(since: str = "1h", component: str = "") -> str:
    """Recent ERROR/WARN entries, newest first."""
    sql, params = _build_errors_query(since=since, component=component)
    rows = _query(sql, params)
    return json.dumps(rows, default=str)


def log_stats_impl(since: str = "24h") -> str:
    """Log volume stats by component/level + slowest operations."""
    interval = _parse_interval(since)

    # Counts query
    counts_sql = (
        "SELECT component, level, COUNT(*) AS count "
        "FROM logs "
        "WHERE ts >= NOW() - INTERVAL %s "
        "GROUP BY component, level "
        "ORDER BY component, level"
    )
    counts = _query(counts_sql, [interval])

    # Slow ops query
    slow_sql = (
        "SELECT id, ts, component, msg, duration_ms "
        "FROM logs "
        "WHERE ts >= NOW() - INTERVAL %s "
        "AND duration_ms IS NOT NULL "
        "ORDER BY duration_ms DESC "
        "LIMIT 10"
    )
    slow_ops = _query(slow_sql, [interval])

    result = {"counts_by_component_level": counts, "slow_ops_top10": slow_ops}
    return json.dumps(result, default=str)
