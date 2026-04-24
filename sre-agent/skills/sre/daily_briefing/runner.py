"""Briefing runner — morning / evening / weekly."""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Protocol

from agent_session_log import LogStore
from agent_session_log.skill_ledger import SkillLedger


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str,
                 tools: list[dict] | None = None) -> dict: ...


_PROMPTS_DIR = Path(__file__).parent


_KINDS = {
    "morning": {
        "since": "24h",
        "prompt_file": "morning_prompt.md",
        "system": "你是 Jacky 的 SRE 早报助手, 简短准确。",
    },
    "evening": {
        "since": "24h",
        "prompt_file": "evening_prompt.md",
        "system": "你是 Jacky 的 SRE 晚报助手, 简短准确。",
    },
    "weekly": {
        "since": "7d",
        "prompt_file": "weekly_prompt.md",
        "system": "你是 Jacky 的 SRE 周报助手, 数据驱动简明。",
    },
}


@dataclass
class BriefingResult:
    session_id: Optional[str]
    text: Optional[str]
    kind: str


@dataclass
class BriefingRunner:
    log: LogStore
    llm: LLMClient

    def run(self, *, kind: str) -> BriefingResult:
        if kind not in _KINDS:
            raise ValueError(f"unknown kind {kind!r}; expected one of {list(_KINDS)}")
        spec = _KINDS[kind]
        since = spec["since"]

        incidents = self.log.list_sessions(type="incident", since=since, limit=100)
        incidents_payload = []
        for i in incidents:
            try:
                m = self.log.store.read_manifest(i["id"])
                trigger = m.trigger
            except Exception:
                trigger = None
            incidents_payload.append({
                "id": i["id"],
                "tags": i.get("tags", []),
                "created_at": i.get("created_at"),
                "status": i.get("status"),
                "trigger": trigger,
            })
        open_incidents = [i for i in incidents_payload if i.get("status") == "open"]

        ledger = SkillLedger(self.log.store.root)
        skill_stats: dict[str, dict] = {}
        for i in incidents:
            for tag in i.get("tags", []):
                if tag.startswith("skill:"):
                    name = tag.split(":", 1)[1]
                    if name not in skill_stats:
                        skill_stats[name] = ledger.stats(name)

        prompt_template = (_PROMPTS_DIR / spec["prompt_file"]).read_text(encoding="utf-8")
        prompt = (prompt_template
                  .replace("{incidents_json}",
                           json.dumps(incidents_payload, ensure_ascii=False, indent=2))
                  .replace("{skill_stats_json}",
                           json.dumps(skill_stats, ensure_ascii=False, indent=2))
                  .replace("{open_incidents_json}",
                           json.dumps(open_incidents, ensure_ascii=False, indent=2))
                  .replace("{count}", str(len(open_incidents))))

        resp = self.llm.complete(system=spec["system"], user=prompt)
        text = (resp.get("text") or "").strip()

        session = self.log.new_session(
            type="briefing",
            trigger={"source": f"cron/daily-briefing-{kind}",
                     "incidents_reviewed": len(incidents)},
            tags=[f"kind:{kind}", f"skill:daily-briefing-{kind}"],
            model=resp.get("model"),
            runtime="hermes@0.10.0",
        )
        session.append_turn(
            type="llm_completion",
            model=resp.get("model"),
            tokens_in=resp.get("tokens_in"),
            tokens_out=resp.get("tokens_out"),
            cost_usd=resp.get("cost_usd"),
            content=text[:1000],
            skill="daily-briefing",
            skill_version="v0.1",
        )
        session.conclude(f"# SRE {kind} 报\n\n{text}\n")
        session.close()

        return BriefingResult(session_id=session.id, text=text, kind=kind)
