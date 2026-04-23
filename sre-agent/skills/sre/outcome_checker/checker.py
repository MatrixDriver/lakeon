"""OutcomeChecker: Re-check incident sessions 24h after close to verify suggested fixes worked."""
from __future__ import annotations

import re as _re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from agent_session_log import LogStore
from agent_session_log.skill_ledger import SkillLedger

_MS_RE = _re.compile(r"compute started in (\d+)ms")


def _p95_from_rows(rows: list[dict]) -> int | None:
    samples: list[int] = []
    for row in rows:
        m = _MS_RE.search(row.get("msg", ""))
        if m:
            samples.append(int(m.group(1)))
    if not samples:
        return None
    samples.sort()
    idx = max(0, int(round(len(samples) * 0.95)) - 1)
    return samples[idx]


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

            rows = self.mcp.log_search(
                component="lakeon-api",
                keyword="compute started in",
                since="24h",
                tenant_id=tenant,
                db_id=db,
                limit=500,
            )
            current_p95 = _p95_from_rows(rows)
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
