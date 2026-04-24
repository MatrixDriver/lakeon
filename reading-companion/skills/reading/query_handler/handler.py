"""Answer free-form reading-history questions without opening a new session."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Protocol

from agent_session_log import LogStore


_PROMPT = (Path(__file__).parent / "query_prompt.md").read_text(encoding="utf-8")

_STOPWORDS = {
    "我", "你", "他", "的", "在", "是", "了", "吗", "什么", "关于", "最近", "过去", "那个",
    "a", "an", "the", "of", "about", "recent", "any", "something", "on",
}

_PLACEHOLDER_RE = re.compile(r"\{(question|hits)\}")


def _render_prompt(question: str, hits_json: str) -> str:
    values = {"question": question, "hits": hits_json}
    return _PLACEHOLDER_RE.sub(lambda m: values[m.group(1)], _PROMPT)


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


def _extract_terms(question: str, limit: int = 4) -> list[str]:
    tokens = re.findall(r"[\w\u4e00-\u9fff]+", question)
    filtered = [t for t in tokens if t.lower() not in _STOPWORDS and len(t) >= 2]
    filtered.sort(key=lambda t: -len(t))
    seen: set[str] = set()
    out: list[str] = []
    for t in filtered:
        k = t.lower()
        if k in seen:
            continue
        seen.add(k)
        out.append(t)
        if len(out) >= limit:
            break
    return out


def _hits_for_prompt(log: LogStore, terms: list[str], limit: int = 10) -> list[dict[str, Any]]:
    seen: set[str] = set()
    hits: list[dict[str, Any]] = []
    for term in terms:
        for h in log.search_text(term, type="reading", limit=limit):
            if h["id"] in seen:
                continue
            seen.add(h["id"])
            concl = log.store.read_conclusion(h["id"]) or ""
            title_m = re.search(r"^#\s+(.+?)\s*$", concl, re.MULTILINE)
            hits.append({
                "id": h["id"],
                "title": (title_m.group(1) if title_m else "(无标题)")[:80],
                "created_at": h["created_at"],
                "snippet": h.get("snippet", "")[:200],
            })
    if not hits:
        for meta in log.list_sessions(type="reading", since="30d", limit=5):
            concl = log.store.read_conclusion(meta["id"]) or ""
            title_m = re.search(r"^#\s+(.+?)\s*$", concl, re.MULTILINE)
            hits.append({
                "id": meta["id"],
                "title": (title_m.group(1) if title_m else "(无标题)")[:80],
                "created_at": meta["created_at"],
                "snippet": concl[:200],
            })
    return hits[:limit]


def answer_question(*, log: LogStore, llm: LLMClient, question: str) -> str:
    terms = _extract_terms(question)
    hits = _hits_for_prompt(log, terms)
    prompt = _render_prompt(
        question=question,
        hits_json=json.dumps(hits, ensure_ascii=False, indent=2),
    )
    resp = llm.complete(system="你是 Jacky 的阅读伙伴,简短准确。", user=prompt)
    return (resp.get("text") or "").strip()
