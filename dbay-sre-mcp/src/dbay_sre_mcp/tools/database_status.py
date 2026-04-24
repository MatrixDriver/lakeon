"""database_status tool — single call to get DB status + recent activity."""
from __future__ import annotations

import json
import re
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


_UUID_RE = re.compile(r"^[a-f0-9]{8}-?[a-f0-9-]+$|^db_[a-z0-9]+$", re.IGNORECASE)


def database_status_impl(
    *,
    name_or_id: str,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    """Return comprehensive snapshot. _admin for test injection."""
    admin = _admin or LakeonAdminClient()

    # Resolve to a single db record
    record = None
    if _looks_like_id(name_or_id):
        record = admin.get_database(db_id=name_or_id)
    if record is None:
        matches = admin.list_databases(name_contains=name_or_id)
        exact = [m for m in matches if m.get("name") == name_or_id]
        if exact:
            record = exact[0]
        elif len(matches) == 1:
            record = matches[0]
        elif len(matches) > 1:
            return json.dumps({
                "found": False, "multiple": True,
                "matches": [{"id": m.get("id"), "name": m.get("name")} for m in matches],
                "message": f"{len(matches)} databases match {name_or_id!r}; pass exact name or id",
            })

    if record is None:
        return json.dumps({"found": False, "message": f"no database matching {name_or_id!r}"})

    db_id = record["id"]
    cold_start = admin.get_compute_cold_start(since="1h", db_id=db_id)
    operations = admin.get_operations(component="compute", since="1h")
    db_ops = [op for op in operations if op.get("db_id") == db_id or op.get("database_id") == db_id]

    return json.dumps({
        "found": True,
        "database": {
            "id": db_id,
            "name": record.get("name"),
            "tenant_id": record.get("tenant_id"),
            "status": record.get("status"),
            "compute_host": record.get("compute_host"),
        },
        "cold_start_1h": {
            "p50_ms": cold_start.get("p50_ms"),
            "p95_ms": cold_start.get("p95_ms"),
            "count": cold_start.get("count"),
            "max_ms": cold_start.get("max_ms"),
        },
        "recent_events_1h": db_ops[:20],
    })


def _looks_like_id(s: str) -> bool:
    return bool(_UUID_RE.match(s))
