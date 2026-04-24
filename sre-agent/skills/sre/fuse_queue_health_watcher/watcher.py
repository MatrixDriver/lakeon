"""Fuse queue health watcher — detect stuck batches via log retry patterns."""
from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass
from typing import Any

from skills.sre._base.watcher_base import WatcherBase


_BLOB_RE = re.compile(r"blob_id=(?P<blob>\S+)")


@dataclass
class FuseQueueHealthWatcher(WatcherBase):
    skill_name: str = "fuse-queue-health-watcher"
    mcp: Any = None
    since: str = "15m"
    retry_threshold: int = 5

    def __post_init__(self) -> None:
        super().__post_init__()

    def scan_once(self) -> list[str]:
        rows = self.mcp.log_search(
            component="dbay-fuse", keyword="retry",
            since=self.since, limit=200,
        )
        if not rows:
            return []

        retries_by_blob: dict[str, list[dict]] = defaultdict(list)
        for row in rows:
            m = _BLOB_RE.search(row.get("msg", ""))
            if m:
                retries_by_blob[m.group("blob")].append(row)

        opened: list[str] = []
        for blob, events in retries_by_blob.items():
            if len(events) < self.retry_threshold:
                continue
            signal_id = f"fuse_stuck:{blob}"
            if self.is_recently_seen(signal_id=signal_id):
                continue
            tenants = sorted({e.get("tenant_id") for e in events if e.get("tenant_id")})
            sid = self.open_incident(
                trigger={
                    "alert": f"dbay-fuse blob {blob} stuck ({len(events)} retries)",
                    "signal_id": signal_id,
                    "blob_id": blob,
                    "retry_count": len(events),
                    "tenants": tenants,
                },
                tags=["component:dbay-fuse", "severity:medium"],
            )
            conclusion = (
                f"# dbay-fuse stuck blob: {blob}\n\n"
                f"**Retries**: {len(events)} within {self.since}\n"
                f"**Tenants**: {', '.join(tenants) or '(no tenant_id tagged)'}\n\n"
                f"**Next step**: `log_search(component='dbay-fuse', keyword='{blob}')` "
                f"for full error chain.\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
