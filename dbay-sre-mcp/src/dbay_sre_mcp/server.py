"""DBay SRE MCP server — log search, trace, errors, stats.

Config: env var LOG_DB_DSN, or ~/.dbay/sre-config.json with key "dsn".

Logs table schema:
    logs(id, ts, level, component, request_id, tenant_id, db_id, logger, msg, duration_ms, extra, thread)
"""

import json
import os
from pathlib import Path
from typing import Optional

import psycopg2
import psycopg2.extras
from fastmcp import FastMCP

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

SRE_CONFIG_FILE = Path.home() / ".dbay" / "sre-config.json"


def _get_dsn() -> str:
    """Return the Postgres DSN for the logs database."""
    dsn = os.environ.get("LOG_DB_DSN")
    if dsn:
        return dsn
    if SRE_CONFIG_FILE.exists():
        cfg = json.loads(SRE_CONFIG_FILE.read_text())
        if cfg.get("dsn"):
            return cfg["dsn"]
    raise RuntimeError(
        "No log DB DSN configured. Set LOG_DB_DSN env var or add 'dsn' to ~/.dbay/sre-config.json"
    )


# ---------------------------------------------------------------------------
# SQL helper functions (pure — testable without DB)
# ---------------------------------------------------------------------------

_INTERVAL_UNITS = {"m": "minutes", "h": "hours", "d": "days", "w": "weeks"}


def _parse_interval(since: str) -> str:
    """Convert shorthand like '1h', '30m', '2d' to a PG interval string.

    Examples:
        '1h'  -> '1 hours'
        '30m' -> '30 minutes'
        '2d'  -> '2 days'
        '1w'  -> '1 weeks'
    """
    since = since.strip()
    if not since:
        raise ValueError("since must not be empty")
    unit = since[-1].lower()
    if unit not in _INTERVAL_UNITS:
        raise ValueError(f"Unknown interval unit '{unit}'. Use m/h/d/w.")
    try:
        amount = int(since[:-1])
    except ValueError:
        raise ValueError(f"Invalid interval value '{since}'. Expected e.g. '1h', '30m', '2d'.")
    return f"{amount} {_INTERVAL_UNITS[unit]}"


def _build_search_query(
    component: str = "",
    level: str = "",
    keyword: str = "",
    tenant_id: str = "",
    db_id: str = "",
    since: str = "1h",
    limit: int = 100,
) -> tuple[str, list]:
    """Build a parameterized SQL query for flexible log search.

    Returns (sql, params) tuple suitable for psycopg2 execution.
    """
    interval = _parse_interval(since)
    conditions = ["ts >= NOW() - INTERVAL %s"]
    params: list = [interval]

    if component:
        conditions.append("component = %s")
        params.append(component)
    if level:
        conditions.append("level = %s")
        params.append(level.upper())
    if keyword:
        conditions.append("to_tsvector('simple', msg) @@ plainto_tsquery('simple', %s)")
        params.append(keyword)
    if tenant_id:
        conditions.append("tenant_id = %s")
        params.append(tenant_id)
    if db_id:
        conditions.append("db_id = %s")
        params.append(db_id)

    where = " AND ".join(conditions)
    sql = (
        f"SELECT id, ts, level, component, request_id, tenant_id, db_id, "
        f"logger, msg, duration_ms, extra, thread "
        f"FROM logs "
        f"WHERE {where} "
        f"ORDER BY ts DESC "
        f"LIMIT %s"
    )
    params.append(limit)
    return sql, params


def _build_trace_query(request_id: str) -> tuple[str, list]:
    """Build a parameterized SQL query to fetch the full call chain for one request_id.

    Returns (sql, params) tuple, ordered by ts ascending.
    """
    sql = (
        "SELECT id, ts, level, component, request_id, tenant_id, db_id, "
        "logger, msg, duration_ms, extra, thread "
        "FROM logs "
        "WHERE request_id = %s "
        "ORDER BY ts"
    )
    return sql, [request_id]


def _build_errors_query(since: str = "1h", component: str = "") -> tuple[str, list]:
    """Build a parameterized SQL query for recent ERROR/WARN log entries.

    Returns (sql, params) tuple limited to 200 rows.
    """
    interval = _parse_interval(since)
    conditions = [
        "ts >= NOW() - INTERVAL %s",
        "level IN ('ERROR', 'WARN')",
    ]
    params: list = [interval]

    if component:
        conditions.append("component = %s")
        params.append(component)

    where = " AND ".join(conditions)
    sql = (
        "SELECT id, ts, level, component, request_id, tenant_id, db_id, "
        "logger, msg, duration_ms, extra, thread "
        "FROM logs "
        f"WHERE {where} "
        "ORDER BY ts DESC "
        "LIMIT 200"
    )
    return sql, params


def _build_stats_query(since: str = "24h") -> tuple[str, list]:
    """Build a parameterized SQL query for log stats:
    - Count grouped by component, level
    - Slow operations top 10 by duration_ms

    Returns (sql, params) tuple for the counts query; the slow-ops query is appended.
    This returns a combined SQL with two SELECT statements separated by ';'.
    """
    interval = _parse_interval(since)
    sql = (
        "SELECT component, level, COUNT(*) AS count "
        "FROM logs "
        "WHERE ts >= NOW() - INTERVAL %s "
        "GROUP BY component, level "
        "ORDER BY component, level"
        ";"
        "SELECT id, ts, component, msg, duration_ms "
        "FROM logs "
        "WHERE ts >= NOW() - INTERVAL %s "
        "AND duration_ms IS NOT NULL "
        "ORDER BY duration_ms DESC "
        "LIMIT 10"
    )
    return sql, [interval, interval]


# ---------------------------------------------------------------------------
# DB helper
# ---------------------------------------------------------------------------

def _query(sql: str, params: list) -> list[dict]:
    """Execute a SQL query against the logs DB and return rows as list of dicts."""
    dsn = _get_dsn()
    with psycopg2.connect(dsn) as conn:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(sql, params)
            return [dict(row) for row in cur.fetchall()]


# ---------------------------------------------------------------------------
# MCP server
# ---------------------------------------------------------------------------

mcp = FastMCP(
    "dbay-sre",
    instructions=(
        "SRE diagnostic tools for DBay log analysis. "
        "Use log_search for flexible filtering, log_trace to follow a request chain, "
        "log_errors for recent failures, and log_stats for an overview of activity.\n"
        "Strategy: start broad (log_stats or log_errors), then narrow down with log_search. "
        "Do NOT guess keywords — browse logs first by component/level/time, "
        "then use exact words from actual log messages as keywords."
    ),
)


@mcp.tool(
    description=(
        "Search logs with flexible filters: component, log level, full-text keyword, "
        "tenant_id, db_id, time window (since e.g. '1h','30m','2d'), and row limit.\n"
        "IMPORTANT: keyword uses PostgreSQL full-text search (simple tokenizer, no stemming). "
        "Use exact words that appear in the log message. If you don't know what words the logs "
        "contain, first search WITHOUT keyword (filter by component/level/time only) to see "
        "actual log messages, then refine with keyword. "
        "Avoid guessing keywords like function names or camelCase identifiers."
    )
)
def log_search(
    component: str = "",
    level: str = "",
    keyword: str = "",
    tenant_id: str = "",
    db_id: str = "",
    since: str = "1h",
    limit: int = 100,
) -> str:
    """Flexible log search with optional filters."""
    from dbay_sre_mcp.log_db import log_search_impl
    return log_search_impl(
        component=component,
        level=level,
        keyword=keyword,
        tenant_id=tenant_id,
        db_id=db_id,
        since=since,
        limit=limit,
    )


@mcp.tool(
    description=(
        "Fetch the full call chain for a single request_id, ordered by timestamp ascending. "
        "Useful for tracing request flows across components."
    )
)
def log_trace(request_id: str) -> str:
    """Retrieve all log entries for a given request_id, ordered by ts."""
    from dbay_sre_mcp.log_db import log_trace_impl
    return log_trace_impl(request_id=request_id)


@mcp.tool(
    description=(
        "List recent ERROR and WARN log entries. "
        "Filter by component and time window (since e.g. '1h','2d'). Returns up to 200 rows."
    )
)
def log_errors(since: str = "1h", component: str = "") -> str:
    """Recent ERROR/WARN entries, newest first."""
    from dbay_sre_mcp.log_db import log_errors_impl
    return log_errors_impl(since=since, component=component)


@mcp.tool(
    description=(
        "Get log statistics for a time window (since e.g. '24h'): "
        "counts grouped by component+level, plus slow operations Top 10 by duration_ms."
    )
)
def log_stats(since: str = "24h") -> str:
    """Log volume stats by component/level + slowest operations."""
    from dbay_sre_mcp.log_db import log_stats_impl
    return log_stats_impl(since=since)


from dbay_sre_mcp.tools.find_database import find_database_impl
from dbay_sre_mcp.tools.find_tenant import find_tenant_impl


@mcp.tool(
    description=(
        "Resolve a human-readable database name to its internal id, tenant_id, status, "
        "and current compute host.\n\n"
        "USE WHEN: User mentions a database by name (e.g. 'tcph-bench', 'perf-test'); "
        "you need db_id or tenant_id before calling other tools (log_search, database_status); "
        "disambiguating between databases with similar names.\n\n"
        "DO NOT USE WHEN: You already have the db_id (UUID-like string) — call other tools "
        "directly; the user is asking about logs/errors/metrics — use log_search/log_errors instead.\n\n"
        "PARAMETERS: name (string match, returns multiple if ambiguous) OR db_id (preferred if known); "
        "provide either name OR db_id, not both.\n\n"
        "RETURNS JSON: found=true with database={id, name, tenant_id, status, compute_host, created_at}; "
        "or found=false with message; or multiple=true with matches=[...] when name was ambiguous."
    )
)
def find_database(name: str = "", db_id: str = "") -> str:
    return find_database_impl(name=name or None, db_id=db_id or None)


@mcp.tool(
    description=(
        "Resolve a tenant name to id, status, quota, and (by default) list of held databases.\n\n"
        "USE WHEN: User mentions a tenant by name and you need tenant_id for downstream queries; "
        "you want to enumerate which databases a tenant owns; diagnosing 'is this tenant healthy' — "
        "combine with database_status per db.\n\n"
        "DO NOT USE WHEN: You only need a single database — use find_database directly; "
        "asking about cross-tenant patterns — use multi_tenant_blast_radius.\n\n"
        "PARAMETERS: name (tenant name) OR tenant_id (preferred if known); "
        "include_databases (default True; set False for tenant metadata only).\n\n"
        "RETURNS JSON: tenant={id, name, status, quota, created_at}; "
        "databases=[{id, name, status}, ...] (if include_databases=True)."
    )
)
def find_tenant(name: str = "", tenant_id: str = "", include_databases: bool = True) -> str:
    return find_tenant_impl(name=name or None, tenant_id=tenant_id or None,
                            include_databases=include_databases)


from dbay_sre_mcp.tools.database_status import database_status_impl


@mcp.tool(
    description=(
        "Comprehensive snapshot of a database — current status + last 1h cold-start metrics "
        "+ recent lifecycle events. One call replaces 'find_database + log_search + log_stats' sequence.\n\n"
        "USE WHEN: User asks 'what's the state of <db>', 'is <db> healthy', 'why is <db> slow'; "
        "first step in any database-specific incident triage.\n\n"
        "DO NOT USE WHEN: You need raw log lines — use log_search; "
        "asking about a cross-database trend — use log_stats.\n\n"
        "PARAMETERS: name_or_id — database name OR id (auto-detected via heuristic).\n\n"
        "RETURNS JSON: database={id, name, tenant_id, status, compute_host}; "
        "cold_start_1h={p50_ms, p95_ms, count, max_ms}; "
        "recent_events_1h=[{ts, type, outcome, duration_ms}, ...] (max 20)."
    )
)
def database_status(name_or_id: str) -> str:
    return database_status_impl(name_or_id=name_or_id)
