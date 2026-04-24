"""pod_create_failures — k8s pod creation failures aggregated by category."""
from __future__ import annotations

import json
import re
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


_CATEGORIES = [
    ("InvalidName", re.compile(r"InvalidName|invalid.*name", re.IGNORECASE)),
    ("CrashLoopBackOff", re.compile(r"CrashLoopBackOff", re.IGNORECASE)),
    ("ImagePullBackOff", re.compile(r"ImagePull(BackOff|Error)", re.IGNORECASE)),
    ("FailedScheduling", re.compile(r"FailedScheduling|insufficient", re.IGNORECASE)),
    ("ContainerCreating", re.compile(r"ContainerCreating.*timeout", re.IGNORECASE)),
    ("DuplicateName", re.compile(r"already exists|AlreadyExists", re.IGNORECASE)),
]


def _categorize(error: str) -> str:
    for name, pat in _CATEGORIES:
        if pat.search(error or ""):
            return name
    return "Other"


def pod_create_failures_impl(
    *,
    since: str = "1h",
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    admin = _admin or LakeonAdminClient()
    ops = admin.get_operations(component="compute", since=since)
    failures = []
    for op in ops:
        if op.get("type") != "POD_CREATE" or op.get("outcome") != "FAILURE":
            continue
        failures.append({
            "ts": op.get("ts"),
            "tenant_id": op.get("tenant_id"),
            "db_id": op.get("db_id"),
            "error": op.get("error", ""),
            "category": _categorize(op.get("error", "")),
        })

    by_cat: dict[str, int] = {}
    for f in failures:
        by_cat[f["category"]] = by_cat.get(f["category"], 0) + 1

    return json.dumps({
        "since": since,
        "count": len(failures),
        "by_category": by_cat,
        "failures": failures[:50],
    })
