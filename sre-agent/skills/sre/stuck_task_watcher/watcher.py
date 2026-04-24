"""Stuck task watcher — reports stuck async tasks (wiki/agentfs/kb) grouped."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Any

from skills.sre._base.watcher_base import WatcherBase


@dataclass
class StuckTaskWatcher(WatcherBase):
    skill_name: str = "stuck-task-watcher"
    mcp: Any = None
    threshold_minutes: int = 10

    def __post_init__(self) -> None:
        super().__post_init__()

    def scan_once(self) -> list[str]:
        result = self.mcp.stuck_task_query(threshold_minutes=self.threshold_minutes)
        count = result.get("count", 0)
        if count == 0:
            return []
        tasks = result.get("tasks", [])
        # signal_id based on (count, task_types) — re-fire only if pattern changes
        type_counter = Counter(t.get("task_type") for t in tasks)
        types_summary = ",".join(f"{t}x{n}" for t, n in sorted(type_counter.items()))
        signal_id = f"stuck_tasks:{types_summary}"
        if self.is_recently_seen(signal_id=signal_id):
            return []

        oldest_age = max(t.get("age_sec", 0) for t in tasks)
        sid = self.open_incident(
            trigger={
                "alert": f"{count} async tasks stuck > {self.threshold_minutes}min",
                "signal_id": signal_id,
                "count": count,
                "type_summary": dict(type_counter),
                "oldest_age_sec": oldest_age,
            },
            tags=["component:async-task", "severity:low"],
        )
        sample_lines = "\n".join(
            f"- {t.get('source', '?')}: {t.get('task_type', '?')} "
            f"task_id={t.get('task_id', '?')} age={t.get('age_sec', 0)}s "
            f"kb_id={t.get('kb_id') or '-'}"
            for t in tasks[:10]
        )
        conclusion = (
            f"# Stuck async tasks: {count}\n\n"
            f"**Types**: {types_summary}\n"
            f"**Oldest age**: {oldest_age}s\n\n"
            f"**Top 10**:\n{sample_lines}\n\n"
            f"**Next step**: `log_search(keyword=<task_id>)` for each stuck task "
            f"to find where it hung.\n"
        )
        self.conclude_and_close(sid, conclusion)
        return [sid]
