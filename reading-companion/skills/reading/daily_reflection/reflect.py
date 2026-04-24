"""22:00 daily reflection over today's reading sessions."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from agent_session_log import LogStore


_PROMPT = (Path(__file__).parent / "reflect_prompt.md").read_text(encoding="utf-8")
_TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
_KP_RE = re.compile(r"##\s+要点\s*\n(.*?)(?=\n##\s|\Z)", re.DOTALL)


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


@dataclass
class ReflectionResult:
    session_id: str | None
    reflection_text: str | None
    skipped_reason: str | None = None


def _readings_payload(log: LogStore) -> tuple[list[dict], list[str]]:
    metas = log.list_sessions(type="reading", since="24h", limit=50)
    payload: list[dict] = []
    ids: list[str] = []
    for meta in metas:
        concl = log.store.read_conclusion(meta["id"]) or ""
        title_m = _TITLE_RE.search(concl)
        kp_m = _KP_RE.search(concl)
        payload.append({
            "id": meta["id"],
            "title": (title_m.group(1) if title_m else "(无标题)")[:80],
            "created_at": meta["created_at"],
            "key_points": (kp_m.group(1).strip() if kp_m else ""),
        })
        ids.append(meta["id"])
    return payload, ids


def reflect_today(*, log: LogStore, llm: LLMClient) -> ReflectionResult:
    readings, ids = _readings_payload(log)
    if not readings:
        return ReflectionResult(
            session_id=None, reflection_text=None,
            skipped_reason="no reading sessions in last 24h",
        )

    prompt = _PROMPT.replace("{readings}", json.dumps(readings, ensure_ascii=False, indent=2))
    resp = llm.complete(system="你是 Jacky 的阅读伙伴,帮他写一句简短的夜晚总结。", user=prompt)
    text = (resp.get("text") or "").strip()

    session = log.new_session(
        type="reflection",
        trigger={"source": "cron/daily-reflection", "readings_count": len(readings)},
        tags=["type:reflection", "skill:daily-reflection"],
        parent_sessions=ids,
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
        skill="reading/daily_reflection",
        skill_version="v0.1",
    )
    session.conclude(f"# 今日反思\n\n{text}\n")
    session.close()

    return ReflectionResult(session_id=session.id, reflection_text=text)
