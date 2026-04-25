"""data_consistency_check tool — calls lakeon-api /admin/data-consistency/{rule}.

In 0.2.1, this tool no longer connects to PG directly. The 4 invariant rules
are implemented in the lakeon-api Java service and exposed as admin REST
endpoints. This keeps lakeon DB credentials inside the CCE network and
avoids requiring LAKEON_DB_DSN env on the dbay-sre-mcp side.
"""
from __future__ import annotations

import json
from typing import Optional

from dbay_sre_mcp.admin_client import LakeonAdminClient


# Static list mirrors the Java service's RULES map. The dynamic '__list__' rule
# also returns these — but having them here lets callers query without a round
# trip when they just want to know the names.
AVAILABLE_RULES = [
    "kb_implies_db_id",
    "enqueued_implies_drained",
    "db_ready_implies_pod_running",
    "schema_seeded",
]


def data_consistency_check_impl(
    *,
    rule: str,
    threshold_minutes: int = 10,
    _admin: Optional[LakeonAdminClient] = None,
) -> str:
    admin = _admin or LakeonAdminClient()
    result = admin.data_consistency_check(rule=rule, threshold_minutes=threshold_minutes)
    return json.dumps(result, ensure_ascii=False)
