"""find_tenant tool — resolve tenant name to id + held databases."""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


def find_tenant_impl(
    *,
    name: Optional[str] = None,
    tenant_id: Optional[str] = None,
    include_databases: bool = True,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    if not name and not tenant_id:
        raise ValueError("must provide either name or tenant_id")
    admin = _admin or LakeonAdminClient()

    if tenant_id:
        record = admin.get_tenant(tenant_id=tenant_id)
        if not record:
            return json.dumps({"found": False, "message": f"no tenant with id={tenant_id}"})
    else:
        matches = admin.list_tenants(name_contains=name)
        exact = [m for m in matches if m.get("name") == name]
        if exact:
            record = exact[0]
        elif matches and len(matches) == 1:
            record = matches[0]
        elif not matches:
            return json.dumps({"found": False, "message": f"no tenant matching name={name!r}"})
        else:
            return json.dumps({
                "found": True,
                "multiple": True,
                "matches": [_normalize_tenant(m) for m in matches],
            })

    out = {"found": True, "tenant": _normalize_tenant(record)}
    if include_databases:
        dbs = admin.list_databases(tenant_id=record["id"])
        out["databases"] = [
            {"id": d.get("id"), "name": d.get("name"), "status": d.get("status")}
            for d in dbs
        ]
    return json.dumps(out)


def _normalize_tenant(record: dict) -> dict:
    return {
        "id": record.get("id"),
        "name": record.get("name"),
        "status": record.get("status"),
        "quota": record.get("quota"),
        "created_at": record.get("created_at"),
    }
