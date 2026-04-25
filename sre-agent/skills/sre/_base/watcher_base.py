"""Shared base for all SRE watchers.

Each watcher scans for a symptom signal, dedupes recent same signals, and opens
an incident session when a new signal is detected. This base provides those
shared capabilities so per-watcher code stays focused on the signal logic.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

from agent_session_log import LogStore, utc_now_iso
from agent_session_log.skill_ledger import SkillLedger


@dataclass
class WatcherBase:
    log: LogStore
    skill_name: str
    skill_version: str = "v0.1"
    dedupe_window_sec: int = 86400  # 24h — same signal won't burn LLM/Feishu twice in a day
    ledger: Optional[SkillLedger] = None

    def __post_init__(self) -> None:
        if self.ledger is None:
            self.ledger = SkillLedger(self.log.store.root)

    def is_recently_seen(self, *, signal_id: str) -> bool:
        """Check if a session with matching trigger.signal_id exists within dedupe_window_sec."""
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.dedupe_window_sec)
        for meta in self.log.list_sessions(type="incident", limit=100):
            m_time = datetime.fromisoformat(meta["created_at"].replace("Z", "+00:00"))
            if m_time < cutoff:
                return False  # list_sessions is newest-first; anything older is stale
            full = self.log.store.read_manifest(meta["id"])
            if (full.trigger or {}).get("signal_id") == signal_id:
                return True
        return False

    def open_incident(self, *, trigger: dict[str, Any], tags: list[str]) -> str:
        """Open a new `type=incident` session and record invocation."""
        skill_tag = f"skill:{self.skill_name}"
        if skill_tag not in tags:
            tags = list(tags) + [skill_tag]
        trigger = {**trigger, "source": trigger.get("source", f"cron/{self.skill_name}"),
                   "skill_version": self.skill_version}

        s = self.log.new_session(
            type="incident",
            trigger=trigger,
            tags=tags,
            model="deepseek-chat",
            runtime="hermes@0.10.0",
        )
        s.append_turn(type="trigger", content=trigger)
        self.ledger.record_invocation(
            self.skill_name,
            version=self.skill_version,
            session_id=s.id,
            triggered_at=utc_now_iso(),
        )
        return s.id

    def conclude_and_close(self, session_id: str, markdown: str) -> None:
        """Write conclusion + close session in one shot."""
        s = self.log.get_session(session_id)
        s.conclude(markdown)
        s.close()
