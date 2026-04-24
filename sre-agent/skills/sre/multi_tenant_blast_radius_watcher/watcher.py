"""Multi-tenant blast radius watcher — detect cross-tenant fault; LLM guess fault domain."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional, Protocol

from skills.sre._base.watcher_base import WatcherBase


_PROMPT = (Path(__file__).parent / "diagnose_prompt.md").read_text(encoding="utf-8")


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str,
                 tools: list[dict] | None = None) -> dict: ...


@dataclass
class MultiTenantBlastRadiusWatcher(WatcherBase):
    skill_name: str = "multi-tenant-blast-radius-watcher"
    mcp: Any = None
    llm: Optional[LLMClient] = None
    window: str = "15m"
    min_tenant_count: int = 3

    def __post_init__(self) -> None:
        super().__post_init__()

    def scan_once(self) -> list[str]:
        result = self.mcp.multi_tenant_blast_radius(
            window=self.window, min_tenant_count=self.min_tenant_count,
        )
        if result.get("count", 0) == 0:
            return []

        opened: list[str] = []
        for inc in result.get("incidents", []):
            component = inc.get("component", "")
            sig = inc.get("error_signature", "")
            signal_id = f"blast:{component}:{sig[:40]}"
            if self.is_recently_seen(signal_id=signal_id):
                continue

            prompt = (_PROMPT
                      .replace("{distinct_tenant_count}", str(inc.get("distinct_tenant_count", 0)))
                      .replace("{window}", self.window)
                      .replace("{component}", component)
                      .replace("{error_signature}", sig)
                      .replace("{total_occurrences}", str(inc.get("total_occurrences", 0))))
            llm_resp = self.llm.complete(system="你是谨慎的 SRE 工程师。", user=prompt)
            hypothesis = (llm_resp.get("text") or "").strip()

            sid = self.open_incident(
                trigger={
                    "alert": f"cross-tenant blast: {component} × {inc.get('distinct_tenant_count', 0)} tenants",
                    "signal_id": signal_id,
                    "component": component,
                    "error_signature": sig,
                    "distinct_tenant_count": inc.get("distinct_tenant_count"),
                    "total_occurrences": inc.get("total_occurrences"),
                },
                tags=[f"component:{component}", "category:blast-radius", "severity:high"],
            )
            conclusion = (
                f"# Cross-tenant blast: {component}\n\n"
                f"**Error signature**: `{sig}`\n"
                f"**Affected tenants**: {inc.get('distinct_tenant_count', 0)}\n"
                f"**Total occurrences (window {self.window})**: {inc.get('total_occurrences', 0)}\n\n"
                f"## LLM 根因假设\n\n{hypothesis}\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
