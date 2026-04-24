"""multi_tenant_blast_radius — detect single error pattern affecting multiple tenants."""
from __future__ import annotations

import json
import os

import psycopg2


def log_db_connect():
    """Connect to dbay-logs PG (separate from lakeon-api PG)."""
    return psycopg2.connect(os.environ["LOG_DB_DSN"])


# Group recent errors by (component, error_signature) and count distinct tenants
# Assumes dbay-logs has columns: ts, component, level, msg, tenant_id
_QUERY = """
    SELECT
        component,
        SUBSTRING(msg FROM 1 FOR 80) AS error_signature,
        COUNT(DISTINCT tenant_id) AS distinct_tenant_count,
        COUNT(*) AS total_occurrences
    FROM logs
    WHERE level IN ('ERROR', 'WARN')
      AND ts >= NOW() - %s::interval
      AND tenant_id IS NOT NULL
    GROUP BY component, SUBSTRING(msg FROM 1 FOR 80)
    HAVING COUNT(DISTINCT tenant_id) >= %s
    ORDER BY distinct_tenant_count DESC, total_occurrences DESC
    LIMIT 20
"""


def multi_tenant_blast_radius_impl(
    *,
    window: str = "15m",
    min_tenant_count: int = 3,
) -> str:
    interval = window
    if interval.endswith("m") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} minutes"
    elif interval.endswith("h") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} hours"
    elif interval.endswith("s") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} seconds"
    elif interval.endswith("d") and interval[:-1].isdigit():
        interval = f"{interval[:-1]} days"

    with log_db_connect() as conn:
        with conn.cursor() as cur:
            cur.execute(_QUERY, (interval, min_tenant_count))
            rows = cur.fetchall()

    incidents = [
        {
            "component": component,
            "error_signature": sig,
            "distinct_tenant_count": tenants,
            "total_occurrences": total,
        }
        for component, sig, tenants, total in rows
        if tenants >= min_tenant_count
    ]
    return json.dumps({
        "window": window,
        "min_tenant_count": min_tenant_count,
        "count": len(incidents),
        "incidents": incidents,
    })
