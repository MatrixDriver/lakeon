"""OutcomeChecker: Re-check incident sessions 24h after close to verify suggested fixes worked."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from agent_session_log import LogStore
from agent_session_log.skill_ledger import SkillLedger


@dataclass
class OutcomeChecker:
    log: LogStore
    mcp: Any
    ledger: SkillLedger
    lookback_hours: int = 36
    improvement_threshold_ms: int = 5000

    def scan_once(self) -> list[str]:
        """Check closed incidents without outcome. Return session ids updated."""
        updated: list[str] = []
        cutoff = datetime.now(timezone.utc) - timedelta(hours=self.lookback_hours)
        for meta in self.log.list_sessions(type="incident", limit=200):
            if meta["status"] != "closed":
                continue
            closed_at = meta.get("closed_at")
            if not closed_at:
                continue
            ct = datetime.fromisoformat(closed_at.replace("Z", "+00:00"))
            if ct < cutoff:
                break  # newest-first; done
            if self.log.store.read_outcome(meta["id"]):
                continue

            manifest = self.log.store.read_manifest(meta["id"])
            tenant = manifest.trigger.get("tenant_id")
            db = manifest.trigger.get("db_id")
            if not tenant or not db:
                continue

            stats = self.mcp.log_stats(since="24h")
            p95_map = stats.get("cold_start_p95_by_db", {})
            current_p95 = p95_map.get(f"{tenant}/{db}")
            original_ms = _extract_ms(manifest.trigger.get("alert", ""))

            did_work = self._classify(current_p95=current_p95, original_ms=original_ms)
            notes = f"current p95 {current_p95}ms vs original trigger {original_ms}ms"

            session = self.log.get_session(meta["id"])
            session.record_outcome(did_work=did_work, notes=notes)
            skill = _extract_skill(manifest.tags)
            if skill:
                self.ledger.record_outcome(skill, session_id=meta["id"],
                                           did_work=did_work, notes=notes)
            updated.append(meta["id"])
        return updated

    def _classify(self, *, current_p95: int | None, original_ms: int | None) -> bool:
        if current_p95 is None or original_ms is None:
            return False
        return current_p95 < self.improvement_threshold_ms and current_p95 < original_ms // 2


def _extract_ms(alert: str) -> int | None:
    import re
    m = re.search(r"(\d+)ms", alert or "")
    return int(m.group(1)) if m else None


def _extract_skill(tags: list[str]) -> str | None:
    for t in tags:
        if t.startswith("skill:"):
            return t.split(":", 1)[1]
    return None
