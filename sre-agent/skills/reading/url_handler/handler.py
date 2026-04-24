"""Full URL handling flow: open → fetch → extract → relate → conclude → close.

Pure function of injected dependencies (LogStore, http client, LLM client). The CLI
wires real DeepseekLLMClient + httpx.Client + LogStore from main.py.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol

from agent_session_log import LogStore

from skills.reading.url_handler.extract import extract
from skills.reading.url_handler.fetch import fetch_url, FetchError, HttpClient
from skills.reading.url_handler.related import find_related
from skills.reading.url_handler.reply import build_reply


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


@dataclass
class HandleResult:
    session_id: str
    feishu_reply: str
    status: str  # "closed" | "abandoned"


def handle_url(
    *,
    log: LogStore,
    http: HttpClient,
    llm: LLMClient,
    url: str,
    user_open_id: str | None,
    received_at: str,
    source: str = "cli",
) -> HandleResult:
    session = log.new_session(
        type="reading",
        trigger={
            "source": source,
            "url": url,
            "received_at": received_at,
            "user_open_id": user_open_id,
        },
        tags=[f"source:{source}", "type:web"],
        model="deepseek-chat",
        runtime="hermes@phase0a",
    )

    # ---- Fetch ----
    session.append_turn(type="thought", content=f"fetching {url}")
    try:
        doc = fetch_url(url, client=http)
    except FetchError as exc:
        session.append_turn(type="tool_result", ref_turn=0, error=f"fetch failed: {exc}")
        session.conclude(
            f"# (fetch failed)\n\nURL: {url}\n\n## 错误\n- fetch failed: {exc}\n"
        )
        session.close(status="abandoned")
        return HandleResult(
            session_id=session.id,
            feishu_reply=f"📖 抓取失败:{url}\n{exc}",
            status="abandoned",
        )

    raw_blob = session.attach_evidence(
        doc.raw_html.encode("utf-8"), mime="text/plain", source=f"httpx.get({url})"
    )
    session.append_turn(type="tool_call", tool="httpx.get", args={"url": url})
    session.append_turn(
        type="tool_result", ref_turn=1,
        evidence=[raw_blob.sha256],
        truncated=(len(doc.raw_html) > 200000),
    )

    # ---- Extract ----
    extraction = extract(url=url, body=doc.body, llm=llm)
    extraction_blob = session.attach_evidence(
        extraction.as_json().encode("utf-8"),
        mime="application/json",
        source="extract_prompt.md",
    )
    if extraction.llm_meta:
        session.append_turn(
            type="llm_completion",
            model=extraction.llm_meta.model,
            tokens_in=extraction.llm_meta.tokens_in,
            tokens_out=extraction.llm_meta.tokens_out,
            cost_usd=extraction.llm_meta.cost_usd,
            content=extraction.title,
            evidence=[extraction_blob.sha256],
            skill="reading/url_handler",
            skill_version="v0.1",
        )

    # ---- Relate ----
    related = find_related(
        log=log,
        keywords=extraction.keywords,
        since="30d",
        limit=5,
        exclude_session_id=session.id,
    )
    session.append_turn(
        type="tool_call",
        tool="agent_session_log.list_sessions+search",
        args={"keywords": extraction.keywords, "since": "30d"},
    )
    session.append_turn(
        type="tool_result", ref_turn=3,
        content=[{"id": r["id"], "title": r["title"], "matched": r["matched_keywords"]} for r in related],
    )

    # ---- Conclude ----
    session.conclude(_build_conclusion(url=url, extraction=extraction, related=related))
    session.close()

    return HandleResult(
        session_id=session.id,
        feishu_reply=build_reply(log, session.id),
        status="closed",
    )


def _build_conclusion(*, url: str, extraction, related: list[dict[str, Any]]) -> str:
    kp_lines = "\n".join(f"- {p}" for p in extraction.key_points) or "- (无)"
    if related:
        rel_lines = "\n".join(
            f"- {r['id']}: [《{r['title']}》]({r.get('url') or ''}) — 关键词 {', '.join(r['matched_keywords'])}"
            for r in related
        )
    else:
        rel_lines = "- (首次读到这个主题)"
    quotes_block = ""
    if extraction.quotes:
        quotes_block = "\n## 摘抄\n" + "\n".join(
            f"> {q.get('text', '')} — {q.get('context', '')}" for q in extraction.quotes
        ) + "\n"
    return (
        f"# {extraction.title}\n\n"
        f"URL: {url}\n\n"
        f"## 要点\n{kp_lines}\n\n"
        f"## 相关阅读\n{rel_lines}\n"
        f"{quotes_block}"
    )
