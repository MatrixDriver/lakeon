"""AgentFS forwarder orphan watcher — detect WARN spam from deleted-tenant subscriptions."""
from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass
from typing import Any

from skills.sre._base.watcher_base import WatcherBase


_TENANT_RE = re.compile(r"forwarder:\s*tenant\s+(?P<tid>tn_[A-Za-z0-9]+)\s+not\s+found")


@dataclass
class AgentFSForwarderOrphanWatcher(WatcherBase):
    skill_name: str = "agentfs-forwarder-orphan-watcher"
    mcp: Any = None
    since: str = "30m"
    occurrence_threshold: int = 5
    # Orphans persist until lakeon-api fixes the leak; one signal per tenant per 6h is enough.
    dedupe_window_sec: int = 6 * 3600

    def __post_init__(self) -> None:
        super().__post_init__()

    def scan_once(self) -> list[str]:
        rows = self.mcp.log_search(
            component="lakeon-api", keyword="forwarder",
            since=self.since, limit=500,
        )
        if not rows:
            return []

        per_tenant: dict[str, int] = defaultdict(int)
        for row in rows:
            if row.get("level") and row["level"] != "WARN":
                continue
            m = _TENANT_RE.search(row.get("msg", ""))
            if m:
                per_tenant[m.group("tid")] += 1

        opened: list[str] = []
        for tid, count in sorted(per_tenant.items()):
            if count < self.occurrence_threshold:
                continue
            signal_id = f"agentfs_forwarder_orphan:{tid}"
            if self.is_recently_seen(signal_id=signal_id):
                continue
            sid = self.open_incident(
                trigger={
                    "alert": (
                        f"AgentFS forwarder pushing to deleted tenant {tid} "
                        f"({count} WARN in {self.since})"
                    ),
                    "signal_id": signal_id,
                    "tenant_id": tid,
                    "warn_count": count,
                    "window": self.since,
                },
                tags=[
                    "component:lakeon-api",
                    "logger:AgentFSEventForwarder",
                    "severity:low",
                ],
            )
            conclusion = (
                f"# AgentFS forwarder orphan: {tid}\n\n"
                f"**WARN count**: {count} within {self.since}\n"
                f"**Source**: lakeon-api `c.l.agentfs.AgentFSEventForwarder` (scheduling-1 thread)\n\n"
                f"**Cause**: tenant `{tid}` was deleted but its AgentFS forwarder subscription was not "
                f"cleaned up. Each scheduled push fails with 'not found' and emits this WARN.\n\n"
                f"**Fix (lakeon-api side)**: on tenant delete, drop the AgentFS forwarder subscription; "
                f"OR auto-unsubscribe forwarder when push receives 'not found'. SRE watcher only reports.\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
