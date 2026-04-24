"""stuck_task_query — async tasks stuck in_progress beyond threshold across known tables."""
from __future__ import annotations

import json
from typing import Optional

import psycopg2

from dbay_sre_mcp.lakeon_db import connect


# (table_name, columns_select, columns_normalized)
_SOURCES = [
    {
        "table": "wiki_run_logs",
        "sql": """
            SELECT id, kb_id, task_type, status, started_at,
                   EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
            FROM wiki_run_logs
            WHERE status = 'in_progress'
              AND started_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY started_at ASC
            LIMIT 50
        """,
        "columns": ["task_id", "kb_id", "task_type", "status", "started_at", "age_sec"],
    },
    {
        "table": "agentfs_jobs",
        "sql": """
            SELECT id, NULL AS kb_id, job_type AS task_type, status, started_at,
                   EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
            FROM agentfs_jobs
            WHERE status = 'in_progress'
              AND started_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY started_at ASC
            LIMIT 50
        """,
        "columns": ["task_id", "kb_id", "task_type", "status", "started_at", "age_sec"],
    },
    {
        "table": "kb_processing_tasks",
        "sql": """
            SELECT id, kb_id, task_type, status, started_at,
                   EXTRACT(EPOCH FROM (NOW() - started_at))::int AS age_sec
            FROM kb_processing_tasks
            WHERE status = 'in_progress'
              AND started_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY started_at ASC
            LIMIT 50
        """,
        "columns": ["task_id", "kb_id", "task_type", "status", "started_at", "age_sec"],
    },
]


def stuck_task_query_impl(
    *,
    threshold_minutes: int = 10,
    type: Optional[str] = None,
) -> str:
    tasks: list[dict] = []
    warnings: list[str] = []

    with connect() as conn:
        for src in _SOURCES:
            try:
                with conn.cursor() as cur:
                    cur.execute(src["sql"], {"threshold_minutes": threshold_minutes})
                    rows = cur.fetchall()
                for row in rows:
                    record = dict(zip(src["columns"], row))
                    record["source"] = src["table"]
                    if type and record.get("task_type") != type:
                        continue
                    tasks.append(record)
            except psycopg2.errors.UndefinedTable:
                warnings.append(f"table {src['table']} does not exist in this DB; skipped")
                conn.rollback()
            except psycopg2.Error as exc:
                warnings.append(f"query against {src['table']} failed: {exc}")
                conn.rollback()

    out: dict = {"count": len(tasks), "threshold_minutes": threshold_minutes, "tasks": tasks}
    if warnings:
        out["warnings"] = warnings
    return json.dumps(out)
