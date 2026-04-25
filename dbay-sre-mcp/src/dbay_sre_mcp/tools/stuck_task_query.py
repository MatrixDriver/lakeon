"""stuck_task_query tool — calls lakeon-api /admin/stuck-tasks.

In 0.2.1, this tool no longer connects to PG directly. The 3-table union
query is implemented in the lakeon-api Java service.
"""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


def stuck_task_query_impl(
    *,
    threshold_minutes: int = 10,
    type: Optional[str] = None,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    admin = _admin or LakeonAdminClient()
    result = admin.stuck_task_query(threshold_minutes=threshold_minutes,
                                    type=type or "")
    return json.dumps(result, ensure_ascii=False)
