"""find_database tool — resolve human-readable name to internal id + full record."""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


def find_database_impl(
    *,
    name: Optional[str] = None,
    db_id: Optional[str] = None,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    """Internal: returns JSON string. _admin is for test injection."""
    if not name and not db_id:
        raise ValueError("must provide either name or db_id")

    admin = _admin or LakeonAdminClient()

    if db_id:
        record = admin.get_database(db_id=db_id)
        if not record:
            return json.dumps({"found": False, "message": f"no database with id={db_id}"})
        return json.dumps({"found": True, "database": _normalize(record)})

    matches = admin.list_databases(name_contains=name)
    exact = [m for m in matches if m.get("name") == name]
    if exact:
        return json.dumps({"found": True, "database": _normalize(exact[0])})
    if not matches:
        return json.dumps({"found": False, "message": f"no database matching name={name!r}"})
    if len(matches) == 1:
        return json.dumps({"found": True, "database": _normalize(matches[0])})
    return json.dumps({
        "found": True,
        "multiple": True,
        "matches": [_normalize(m) for m in matches],
        "message": f"{len(matches)} databases matched name~={name!r}; refine with exact name or db_id",
    })


def _normalize(record: dict) -> dict:
    """Pick the fields the LLM cares about. Avoid leaking large/internal fields."""
    return {
        "id": record.get("id"),
        "name": record.get("name"),
        "tenant_id": record.get("tenant_id"),
        "status": record.get("status"),
        "compute_host": record.get("compute_host"),
        "created_at": record.get("created_at"),
    }
