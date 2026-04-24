"""Pod-create-failure watcher.

Polls dbay-sre-mcp.pod_create_failures every N min; opens one incident per
category that has new failures since the last dedupe window.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from agent_session_log import LogStore
from skills.sre._base.watcher_base import WatcherBase

_DEFAULT_SKILL_NAME = "pod-create-failure-watcher"


@dataclass
class PodCreateFailureWatcher(WatcherBase):
    # Override parent's required skill_name with a watcher-specific default.
    skill_name: str = _DEFAULT_SKILL_NAME
    mcp: Any = None
    since: str = "5m"

    def __post_init__(self) -> None:
        super().__post_init__()

    def scan_once(self) -> list[str]:
        result = self.mcp.pod_create_failures(since=self.since)
        if not result.get("count"):
            return []
        failures = result.get("failures", [])
        by_cat = result.get("by_category", {})

        opened: list[str] = []
        for cat, count in by_cat.items():
            if count <= 0:
                continue
            signal_id = f"pod_create:{cat}"
            if self.is_recently_seen(signal_id=signal_id):
                continue

            cat_failures = [f for f in failures if f.get("category") == cat]
            tenants = sorted({f.get("tenant_id") for f in cat_failures if f.get("tenant_id")})

            sid = self.open_incident(
                trigger={
                    "alert": f"k8s pod create failure: {cat} x{count}",
                    "signal_id": signal_id,
                    "category": cat,
                    "count": count,
                    "tenants": tenants,
                },
                tags=["component:k8s", f"category:{cat}", "severity:medium"],
            )
            conclusion = self.build_conclusion(cat, count, tenants, cat_failures)
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened

    def build_conclusion(self, cat: str, count: int, tenants: list[str],
                         failures: list[dict]) -> str:
        sample = "\n".join(
            f"- {f.get('ts', '?')}: tenant={f.get('tenant_id', '?')} err={f.get('error', '')[:80]}"
            for f in failures[:3]
        )
        return (
            f"# k8s pod-create failure: {cat} x {count}\n\n"
            f"**Affected tenants** ({len(tenants)}): {', '.join(tenants) or '(none)'}\n\n"
            f"**Sample failures**:\n{sample}\n\n"
            f"**Next step**: query `database_status` on each affected tenant's DB or "
            f"`log_search(component='compute', keyword='{cat}')` for deeper trace.\n"
        )

    def build_feishu_report(self, cat: str, count: int, tenants: list[str]) -> str:
        return (
            f"[SRE] {cat} pod create 失败 {count} 次\n"
            f"涉及 tenant: {', '.join(tenants[:5])}{' ...' if len(tenants) > 5 else ''}"
        )
