"""Cold-start-watcher scanner.

Separated from hermes integration for testability. Hermes cron calls
Watcher.scan_once() via a thin wrapper that passes the MCP client.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

from agent_session_log import LogStore, utc_now_iso
from agent_session_log.skill_ledger import SkillLedger


_COLD_START_RE = re.compile(
    r"compute started in (?P<ms>\d+)ms for tenant=(?P<tenant>\S+) db=(?P<db>\S+)"
)


@dataclass
class Watcher:
    log: LogStore
    mcp: Any
    threshold_ms: int = 5000
    dedupe_window_sec: int = 600
    ledger: SkillLedger | None = None
    skill_version: str = "v0.1"

    def __post_init__(self) -> None:
        if self.ledger is None:
            self.ledger = SkillLedger(self.log.store.root)

    def scan_once(self) -> list[str]:
        """Fetch recent logs, open sessions for slow starts. Return opened session ids."""
        rows = self.mcp.log_search(
            component="lakeon-api",
            keyword="compute started in",
            since="3m",
            limit=200,
        )
        opened: list[str] = []
        seen_pairs: set[tuple[str, str]] = set()
        for row in rows:
            match = _COLD_START_RE.search(row.get("msg", ""))
            if not match:
                continue
            ms = int(match.group("ms"))
            if ms <= self.threshold_ms:
                continue
            tenant = match.group("tenant")
            db = match.group("db")
            pair = (tenant, db)
            if pair in seen_pairs or self._recently_seen(tenant, db):
                continue
            seen_pairs.add(pair)
            sid = self._open_incident(tenant=tenant, db=db, ms=ms, raw=row)
            opened.append(sid)
        return opened

    def _recently_seen(self, tenant: str, db: str) -> bool:
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.dedupe_window_sec)
        for meta in self.log.list_sessions(type="incident", limit=50):
            m_time = datetime.fromisoformat(meta["created_at"].replace("Z", "+00:00"))
            if m_time < cutoff:
                break  # list_sessions is newest-first
            # reload full manifest to check trigger
            full = self.log.store.read_manifest(meta["id"])
            t = full.trigger or {}
            if t.get("tenant_id") == tenant and t.get("db_id") == db:
                return True
        return False

    def _open_incident(self, *, tenant: str, db: str, ms: int, raw: dict) -> str:
        s = self.log.new_session(
            type="incident",
            trigger={
                "source": "cron/cold-start-watcher",
                "skill_version": self.skill_version,
                "alert": f"compute cold start {ms}ms exceeds threshold {self.threshold_ms}ms",
                "tenant_id": tenant,
                "db_id": db,
                "raw_log_ts": raw.get("ts"),
            },
            tags=[
                "severity:medium" if ms < 15000 else "severity:high",
                "component:compute",
                "skill:cold-start-watcher",
            ],
            model="deepseek-chat",
            runtime="hermes@0.10.0",
        )
        s.append_turn(type="trigger", content={"alert_ms": ms, "tenant": tenant, "db": db})
        self.ledger.record_invocation(
            "cold-start-watcher",
            version=self.skill_version,
            session_id=s.id,
            triggered_at=utc_now_iso(),
        )
        return s.id
