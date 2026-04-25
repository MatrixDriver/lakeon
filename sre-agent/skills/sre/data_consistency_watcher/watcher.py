"""Data consistency watcher — runs 4 invariant rules; LLM diagnoses violations."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional, Protocol

from skills.sre._base.watcher_base import WatcherBase


_RULES = [
    "kb_implies_db_id",
    "enqueued_implies_drained",
    "db_ready_implies_pod_running",
    # 'schema_seeded' was dropped — wiki seed pages live in OBS, not in a SQL
    # table, so there is no pure-SQL invariant to check. WikiSchemaSeeder
    # failures show up in lakeon-api logs (search for "schema seeder").
]

_PROMPT = (Path(__file__).parent / "diagnose_prompt.md").read_text(encoding="utf-8")


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str,
                 tools: list[dict] | None = None) -> dict: ...


@dataclass
class DataConsistencyWatcher(WatcherBase):
    skill_name: str = "data-consistency-watcher"
    mcp: Any = None
    llm: Optional[LLMClient] = None

    def __post_init__(self) -> None:
        super().__post_init__()

    def scan_once(self) -> list[str]:
        opened: list[str] = []
        for rule in _RULES:
            result = self.mcp.data_consistency_check(rule=rule)
            if result.get("ok", True):
                continue
            count = result.get("count", 0)
            if count == 0:
                continue
            signal_id = f"consistency:{rule}"
            if self.is_recently_seen(signal_id=signal_id):
                continue

            violations = result.get("violations", [])
            description = result.get("description", "")
            prompt = (_PROMPT
                      .replace("{rule}", rule)
                      .replace("{count}", str(count))
                      .replace("{description}", description)
                      .replace("{violations_json}",
                               json.dumps(violations[:10], ensure_ascii=False, indent=2)))
            llm_resp = self.llm.complete(system="你是谨慎的 SRE 工程师。", user=prompt)
            hypothesis = (llm_resp.get("text") or "").strip()

            sid = self.open_incident(
                trigger={
                    "alert": f"data consistency violation: {rule} × {count}",
                    "signal_id": signal_id,
                    "rule": rule, "count": count,
                },
                tags=[f"rule:{rule}", "component:data-consistency", "severity:medium"],
            )
            conclusion = (
                f"# Data consistency violation: {rule}\n\n"
                f"**Count**: {count}\n"
                f"**Rule**: {description}\n\n"
                f"## 违规样本\n\n"
                f"```json\n{json.dumps(violations[:5], ensure_ascii=False, indent=2)}\n```\n\n"
                f"## LLM 根因假设\n\n{hypothesis}\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
