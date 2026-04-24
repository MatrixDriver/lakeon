"""data_consistency_check tool — parameterized invariant checks against lakeon-api PG.

All rules are READ-ONLY SELECT queries. No writes possible.
"""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.lakeon_db import connect


# Each rule is (description, SQL with optional %(param)s placeholders, result_columns)
_RULES = {
    "kb_implies_db_id": {
        "description": "Knowledge bases marked READY but with NULL db_id (event timing bug)",
        "sql": """
            SELECT id, name, tenant_id, db_id
            FROM knowledge_base
            WHERE status = 'READY' AND db_id IS NULL
            LIMIT 100
        """,
        "columns": ["kb_id", "name", "tenant_id", "db_id"],
        "params": [],
    },
    "enqueued_implies_drained": {
        "description": "Writes enqueued but not drained beyond threshold (tx commit ordering bug)",
        "sql": """
            SELECT id, kb_id, enqueued_at,
                   EXTRACT(EPOCH FROM (NOW() - enqueued_at))::int AS age_sec
            FROM kb_write_queue
            WHERE drained_at IS NULL
              AND enqueued_at < NOW() - INTERVAL '%(threshold_minutes)s minutes'
            ORDER BY enqueued_at ASC
            LIMIT 100
        """,
        "columns": ["write_id", "kb_id", "enqueued_at", "age_sec"],
        "params": ["threshold_minutes"],
    },
    "db_ready_implies_pod_running": {
        "description": "Databases marked READY but compute_host is unknown / pod missing",
        "sql": """
            SELECT id, name, tenant_id, status, compute_host
            FROM database
            WHERE status = 'READY' AND (compute_host IS NULL OR compute_host = '')
            LIMIT 100
        """,
        "columns": ["db_id", "name", "tenant_id", "status", "compute_host"],
        "params": [],
    },
    "schema_seeded": {
        "description": "Wiki-enabled KBs missing their wiki_schema row (seeder listener bug)",
        "sql": """
            SELECT kb.id, kb.name, kb.tenant_id
            FROM knowledge_base kb
            LEFT JOIN wiki_schema ws ON ws.kb_id = kb.id
            WHERE kb.wiki_enabled = true AND ws.id IS NULL
            LIMIT 100
        """,
        "columns": ["kb_id", "name", "tenant_id"],
        "params": [],
    },
}


AVAILABLE_RULES = list(_RULES.keys())


def data_consistency_check_impl(
    *,
    rule: str,
    threshold_minutes: int = 10,
) -> str:
    if rule == "__list__":
        return json.dumps({
            "rules": AVAILABLE_RULES,
            "details": {k: v["description"] for k, v in _RULES.items()},
        })

    spec = _RULES.get(rule)
    if spec is None:
        return json.dumps({
            "ok": False,
            "message": f"unknown rule {rule!r}; available: {AVAILABLE_RULES}",
        })

    params: dict = {}
    if "threshold_minutes" in spec["params"]:
        params["threshold_minutes"] = threshold_minutes

    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(spec["sql"], params if params else None)
            rows = cur.fetchall()

    violations = [dict(zip(spec["columns"], row)) for row in rows]
    return json.dumps({
        "ok": len(violations) == 0,
        "rule": rule,
        "description": spec["description"],
        "count": len(violations),
        "violations": violations,
    })
