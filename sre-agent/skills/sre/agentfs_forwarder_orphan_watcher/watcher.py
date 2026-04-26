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
                        f"AgentFS forwarder 向已删除的租户 {tid} 推送事件 "
                        f"（{self.since} 内 {count} 条 WARN）"
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
                f"# AgentFS forwarder 孤儿订阅：{tid}\n\n"
                f"**WARN 次数**：{self.since} 内 {count} 条\n"
                f"**来源**：lakeon-api `c.l.agentfs.AgentFSEventForwarder`（scheduling-1 线程）\n\n"
                f"**原因**：租户 `{tid}` 已被删除，但 AgentFS forwarder 订阅记录"
                f"（`agentfs_assignments` 表）残留未清理，每次定时推送都会因找不到 tenant 报 WARN。\n\n"
                f"**修复**：新版 lakeon-api 已让 forwarder 在遇到 not-found 时自动清理孤儿订阅。"
                f"如告警仍持续，说明部署还未更新到含修复的版本。SRE watcher 仅做汇报。\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
