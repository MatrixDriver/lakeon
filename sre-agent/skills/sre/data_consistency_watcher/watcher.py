"""Data consistency watcher — runs invariant rules; LLM diagnoses non-trivial violations.

Severity policy (set server-side in lakeon-api DataConsistencyCheckService):
  INFO  — single recent transient on a self-healable rule; we log and skip.
  WARN  — opens an incident, asks LLM for a hypothesis.
  ERROR — opens an incident with elevated tag.

Self-healable rules (e.g. db_ready_implies_pod_running) have an L3 reconciler in
lakeon-api that fixes drifts within ~60s, so single short-lived violations are
usually noise. We don't want every cold-start blip to wake an SRE.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional, Protocol

from skills.sre._base.watcher_base import WatcherBase

logger = logging.getLogger(__name__)


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

            severity = result.get("severity", "WARN")
            self_healable = result.get("self_healable", False)
            max_age = result.get("max_age_seconds")

            if severity == "INFO":
                # Self-healable transient — let the L3 reconciler do its job.
                # We still log so it shows up in retrospectives, but no incident,
                # no LLM call, no notification.
                logger.info(
                    "data-consistency: suppressing INFO violation rule=%s count=%s "
                    "self_healable=%s max_age=%ss",
                    rule, count, self_healable, max_age,
                )
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
                      .replace("{severity}", severity)
                      .replace("{self_healable}", "yes" if self_healable else "no")
                      .replace("{max_age_seconds}",
                               str(max_age) if max_age is not None else "n/a")
                      .replace("{violations_json}",
                               json.dumps(violations[:10], ensure_ascii=False, indent=2)))
            llm_resp = self.llm.complete(system="你是谨慎的 SRE 工程师。", user=prompt)
            hypothesis = (llm_resp.get("text") or "").strip()

            sid = self.open_incident(
                trigger={
                    "alert": f"data consistency violation: {rule} × {count}",
                    "signal_id": signal_id,
                    "rule": rule, "count": count,
                    "severity": severity, "self_healable": self_healable,
                    "max_age_seconds": max_age,
                },
                tags=[
                    f"rule:{rule}",
                    "component:data-consistency",
                    f"severity:{severity.lower()}",
                ],
            )
            conclusion = (
                f"# Data consistency violation: {rule}\n\n"
                f"**Severity**: {severity}  \n"
                f"**Count**: {count}  \n"
                f"**Self-healable**: {'yes' if self_healable else 'no'}"
                + (f"  \n**Max age**: {max_age}s" if max_age is not None else "")
                + "\n\n"
                f"**Rule**: {description}\n\n"
                f"## 违规样本\n\n"
                f"```json\n{json.dumps(violations[:5], ensure_ascii=False, indent=2)}\n```\n\n"
                f"## LLM 根因假设\n\n{hypothesis}\n"
            )
            self.conclude_and_close(sid, conclusion)
            opened.append(sid)
        return opened
