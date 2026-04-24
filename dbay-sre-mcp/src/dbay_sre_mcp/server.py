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
        "Flexible keyword search over dbay-logs. Returns up to `limit` matching log lines as JSON.\n\n"
        "USE WHEN: You have an exact keyword / phrase that should appear in log message text; "
        "filtering by component (lakeon-api, pageserver, etc.) and time window; "
        "looking up by tenant_id or db_id when those fields are populated.\n\n"
        "DO NOT USE WHEN: You only know a database name (use find_database first to get db_id); "
        "you only know a tenant name (use find_tenant first); asking about cold-start performance "
        "(use database_status); cross-tenant pattern detection (use multi_tenant_blast_radius).\n\n"
        "PARAMETERS: component (e.g. lakeon-api, pageserver); keyword (PG full-text simple tokenizer); "
        "since (1h, 30m, 2d); limit (default 100); tenant_id; db_id.\n\n"
        "RETURNS: JSON array of log rows with ts, component, level, msg, tenant_id, db_id."
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
        "Follow a request_id chain — pull all log entries with the given request_id across components.\n\n"
        "USE WHEN: You have a specific request_id from an error or alert and want the full call chain; "
        "investigating 'why did THIS specific request fail / take so long'.\n\n"
        "DO NOT USE WHEN: You don't have a request_id (use log_search first to find one); "
        "looking for patterns across many requests (use log_stats or log_errors).\n\n"
        "PARAMETERS: request_id (the unique ID present in correlated log lines).\n\n"
        "RETURNS: JSON array of log rows for the chain, ordered by ts."
    )
)
def log_trace(request_id: str) -> str:
    """Retrieve all log entries for a given request_id, ordered by ts."""
    from dbay_sre_mcp.log_db import log_trace_impl
    return log_trace_impl(request_id=request_id)


@mcp.tool(
    description=(
        "Recent error-level log lines with optional component filter, auto-aggregated by message.\n\n"
        "USE WHEN: 'What's broken right now' triage; periodic sweep for new error spikes; "
        "after a deploy to verify error rate didn't climb.\n\n"
        "DO NOT USE WHEN: You want errors for a specific tenant/db (use log_search with tenant_id/db_id); "
        "looking for cross-tenant patterns (use multi_tenant_blast_radius).\n\n"
        "PARAMETERS: since (default 1h); component (filter to one component, default all).\n\n"
        "RETURNS: JSON with grouped error signatures and their counts."
    )
)
def log_errors(since: str = "1h", component: str = "") -> str:
    """Recent ERROR/WARN entries, newest first."""
    from dbay_sre_mcp.log_db import log_errors_impl
    return log_errors_impl(since=since, component=component)


@mcp.tool(
    description=(
        "Activity overview by component / level over a time window — high-level health pulse.\n\n"
        "USE WHEN: Daily / weekly health snapshot; quick sanity check 'is the system alive'; "
        "detecting overall log volume anomalies.\n\n"
        "DO NOT USE WHEN: You need specific log lines (use log_search); "
        "investigating a single tenant (use find_tenant + database_status per db).\n\n"
        "PARAMETERS: since (default 24h, examples: 1h, 7d).\n\n"
        "RETURNS: JSON with counts per (component, level)."
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


from dbay_sre_mcp.tools.data_consistency_check import data_consistency_check_impl


@mcp.tool(
    description=(
        "Run a named invariant check against the lakeon-api production DB. Read-only.\n"
        "Use this to detect data anomalies caused by event-timing / tx-ordering / listener bugs.\n\n"
        "USE WHEN: User reports 'X created but Y can't find it' (cross-table consistency); "
        "you suspect a tx-commit timing bug; periodic cron sweep for orphans / undrained queues.\n\n"
        "DO NOT USE WHEN: Looking at runtime errors — use log_errors; "
        "asking about cold-start performance — use database_status.\n\n"
        "PARAMETERS: rule (name of the invariant to check; pass '__list__' to see all available); "
        "threshold_minutes (for time-based rules e.g. enqueued_implies_drained, default 10).\n\n"
        "AVAILABLE RULES: kb_implies_db_id (KB marked READY but db_id is NULL); "
        "enqueued_implies_drained (writes enqueued but not drained > threshold); "
        "db_ready_implies_pod_running (DB marked READY but no compute_host); "
        "schema_seeded (wiki-enabled KB missing its schema row).\n\n"
        "RETURNS JSON: ok=true (no violations) or ok=false with count + violations=[...]."
    )
)
def data_consistency_check(rule: str, threshold_minutes: int = 10) -> str:
    return data_consistency_check_impl(rule=rule, threshold_minutes=threshold_minutes)


from dbay_sre_mcp.tools.stuck_task_query import stuck_task_query_impl


@mcp.tool(
    description=(
        "Find async tasks stuck in_progress beyond a threshold across known task tables "
        "(wiki_run_logs, agentfs_jobs, kb_processing_tasks).\n\n"
        "USE WHEN: Investigating 'task X never completes'; periodic sweep for stuck tasks "
        "(e.g. WIKI_UPDATE was using 30-min recovery instead of 5-min — bug b742634d); "
        "detecting agent loop hangs (DeepSeek skips done() call — bug 5f9e1fc9).\n\n"
        "DO NOT USE WHEN: Asking about completed task error rates — use log_errors with "
        "the task component.\n\n"
        "PARAMETERS: threshold_minutes (how long is 'too long', default 10); "
        "type (filter by task_type e.g. 'WIKI_UPDATE', 'FUSE_BACKFILL'; empty = all types).\n\n"
        "RETURNS JSON: count; tasks=[{task_id, kb_id, task_type, status, started_at, age_sec, source}]; "
        "warnings=[...] if some task tables don't exist in this DB schema variant."
    )
)
def stuck_task_query(threshold_minutes: int = 10, type: str = "") -> str:
    return stuck_task_query_impl(threshold_minutes=threshold_minutes, type=type or None)


from dbay_sre_mcp.tools.pod_create_failures import pod_create_failures_impl


@mcp.tool(
    description=(
        "Aggregate k8s pod creation failures by category over a time window. Sources from "
        "lakeon-api /admin/operations endpoint (no kubectl needed).\n\n"
        "USE WHEN: User reports 'X tenant can't deploy' / 'compute won't start'; periodic sweep "
        "for pod-spec issues (e.g. sanitize bug 00a65ec0 caused InvalidName); investigating "
        "multi-tenant deploy failures.\n\n"
        "DO NOT USE WHEN: You want raw events — use admin /admin/operations directly via your "
        "own caller; asking about runtime crashes after pod started — use log_errors.\n\n"
        "PARAMETERS: since (time window, default 1h, examples: 30m, 6h, 1d).\n\n"
        "RETURNS JSON: count; by_category={InvalidName, CrashLoopBackOff, ImagePullBackOff, "
        "FailedScheduling, ContainerCreating, DuplicateName, Other: N}; "
        "failures=[{ts, tenant_id, db_id, error, category}, ...] (max 50)."
    )
)
def pod_create_failures(since: str = "1h") -> str:
    return pod_create_failures_impl(since=since)


from dbay_sre_mcp.tools.multi_tenant_blast_radius import multi_tenant_blast_radius_impl


@mcp.tool(
    description=(
        "Detect a single error pattern that is simultaneously affecting multiple tenants "
        "— blast-radius assessment for cross-tenant incidents.\n\n"
        "USE WHEN: Investigating whether an error is isolated to one tenant or is a systemic "
        "incident; after a deploy to check if new errors are appearing across many tenants; "
        "on-call triage 'how many tenants are affected by this error'.\n\n"
        "DO NOT USE WHEN: You already know the incident is single-tenant (use log_search with "
        "tenant_id); looking for a specific request chain (use log_trace); "
        "asking about pod scheduling failures (use pod_create_failures).\n\n"
        "PARAMETERS: window (time window, default 15m, examples: 5m, 1h); "
        "min_tenant_count (minimum distinct tenants to report, default 3).\n\n"
        "RETURNS JSON: window; min_tenant_count; count; "
        "incidents=[{component, error_signature, distinct_tenant_count, total_occurrences}, ...]."
    )
)
def multi_tenant_blast_radius(window: str = "15m", min_tenant_count: int = 3) -> str:
    return multi_tenant_blast_radius_impl(window=window, min_tenant_count=min_tenant_count)
