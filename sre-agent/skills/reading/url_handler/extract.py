"""LLM-driven extraction of title / key points / keywords / quotes."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol


_PROMPT = (Path(__file__).parent / "extract_prompt.md").read_text(encoding="utf-8")

# Hard cap on body chars passed to LLM — bounds prompt cost on long articles.
# Plan says "long articles lose tail; record in friction log if it bites."
# (See docs/superpowers/plans/2026-04-23-sre-agent-phase-0b-plan.md OPEN_ISSUES #5.)
MAX_BODY_CHARS = 12_000


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


@dataclass
class LLMMeta:
    model: str
    tokens_in: int | None
    tokens_out: int | None
    cost_usd: float | None


@dataclass
class Extraction:
    title: str
    key_points: list[str] = field(default_factory=list)
    keywords: list[str] = field(default_factory=list)
    quotes: list[dict[str, str]] = field(default_factory=list)
    parse_ok: bool = True
    llm_meta: LLMMeta | None = None

    def as_json(self) -> str:
        """Serialize the *content* fields only.

        ``llm_meta`` is deliberately omitted: it is recorded separately as
        the ``llm_completion`` turn in the session log, not duplicated into
        the evidence blob.
        """
        return json.dumps(
            {
                "title": self.title,
                "key_points": self.key_points,
                "keywords": self.keywords,
                "quotes": self.quotes,
                "parse_ok": self.parse_ok,
            },
            ensure_ascii=False,
            indent=2,
        )


def _first_nonempty_line(text: str) -> str:
    for line in text.splitlines():
        line = line.strip(" #>-*\t")
        if line:
            return line[:60]
    return "(untitled)"


_PLACEHOLDER_RE = re.compile(r"\{(url|body)\}")


def _render_prompt(url: str, body: str) -> str:
    values = {"url": url, "body": body[:MAX_BODY_CHARS]}
    return _PLACEHOLDER_RE.sub(lambda m: values[m.group(1)], _PROMPT)


def _strip_json_fence(s: str) -> str:
    m = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", s, re.DOTALL)
    if m:
        return m.group(1)
    return s


def extract(*, url: str, body: str, llm: LLMClient) -> Extraction:
    prompt = _render_prompt(url, body)
    resp = llm.complete(system="你是精确的阅读助手。", user=prompt)
    meta = LLMMeta(
        model=resp.get("model", ""),
        tokens_in=resp.get("tokens_in"),
        tokens_out=resp.get("tokens_out"),
        cost_usd=resp.get("cost_usd"),
    )
    text = (resp.get("text") or "").strip()
    payload_str = _strip_json_fence(text)

    try:
        payload = json.loads(payload_str)
    except Exception:
        return Extraction(title=_first_nonempty_line(body), parse_ok=False, llm_meta=meta)

    return Extraction(
        title=str(payload.get("title") or _first_nonempty_line(body)),
        key_points=list(payload.get("key_points") or []),
        keywords=list(payload.get("keywords") or []),
        quotes=list(payload.get("quotes") or []),
        parse_ok=True,
        llm_meta=meta,
    )
