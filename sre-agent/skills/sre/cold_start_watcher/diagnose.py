# skills/sre/cold_start_watcher/diagnose.py
"""LLM-driven diagnosis for an open cold-start incident.

Hermes will invoke diagnose() after Watcher.scan_once() returns new session ids.
This module uses the hermes LLM + MCP bridge (passed in), not a direct client,
so it stays testable via dependency injection.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Protocol

from agent_session_log import Session


class LLMClient(Protocol):
    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict: ...


class MCPClient(Protocol):
    def log_search(self, **kwargs: Any) -> list[dict]: ...
    def log_trace(self, request_id: str) -> list[dict]: ...


PROMPT_TEMPLATE = (Path(__file__).parent / "diagnose_prompt.md").read_text()


def diagnose(
    session: Session,
    *,
    llm: LLMClient,
    mcp: MCPClient,
    max_hypothesis_branches: int = 3,
) -> None:
    """Run diagnosis in-place. Writes conclusion on session and closes it."""
    trigger = session._store.read_manifest(session.id).trigger
    ms = _extract_ms(trigger.get("alert", ""))
    prompt = PROMPT_TEMPLATE.format(
        alert=trigger.get("alert", ""),
        tenant_id=trigger.get("tenant_id", ""),
        db_id=trigger.get("db_id", ""),
        raw_log_ts=trigger.get("raw_log_ts", ""),
        ms=ms,
    )

    # Round 1: LLM proposes hypotheses
    session.append_turn(type="thought", content="starting diagnosis; proposing hypotheses")
    out = llm.complete(system="You are a careful SRE.", user=prompt)
    session.append_turn(
        type="llm_completion",
        model=out.get("model"),
        tokens_in=out.get("tokens_in"),
        tokens_out=out.get("tokens_out"),
        cost_usd=out.get("cost_usd"),
        content=out.get("text", "")[:1000],
        skill="cold-start-watcher",
        skill_version="v0.1",
    )

    # Extract hypothesis list (LLM returns markdown; we look for headers like "## Hypotheses")
    hypotheses = _parse_hypotheses(out.get("text", ""), max_hypothesis_branches)
    if not hypotheses:
        session.conclude(out.get("text", "(LLM returned no structured hypotheses)"))
        session.close()
        return

    # Round 2: branch + evidence collection per hypothesis
    branch_results = {}
    for h in hypotheses:
        b = session.branch(_slug(h["name"]))
        b.append_turn(type="thought", content=f"investigating: {h['name']}")
        # Call log_search with hypothesis-specific filter
        results = mcp.log_search(
            component=h.get("component", "lakeon-api"),
            keyword=h.get("keyword", ""),
            since="5m",
            limit=50,
        )
        blob = session.attach_evidence(
            json.dumps(results, ensure_ascii=False).encode("utf-8"),
            mime="application/json",
            source=f"log_search(hypothesis={h['name']})",
        )
        b.append_turn(
            type="tool_result",
            ref_turn=1,
            evidence=[blob.sha256],
            truncated=len(results) >= 50,
        )
        branch_results[h["name"]] = {"count": len(results), "evidence": blob.sha256}

    # Round 3: LLM picks winning hypothesis
    summary = "\n".join(
        f"- {name}: {info['count']} matching rows (evidence {info['evidence'][:8]})"
        for name, info in branch_results.items()
    )
    session.append_turn(type="thought", content=f"branch evidence summary:\n{summary}")
    decision_prompt = (
        "Given the evidence per hypothesis below, pick the single most likely "
        "root cause and write the final markdown conclusion per the earlier format.\n\n"
        + summary
    )
    final = llm.complete(system="You are a careful SRE.", user=decision_prompt)
    session.append_turn(
        type="llm_completion",
        model=final.get("model"),
        tokens_in=final.get("tokens_in"),
        tokens_out=final.get("tokens_out"),
        cost_usd=final.get("cost_usd"),
        content=final.get("text", "")[:1000],
        skill="cold-start-watcher",
        skill_version="v0.1",
    )

    # Determine keep / discard from LLM's response
    winning = _extract_winner(final.get("text", ""), list(branch_results.keys()))
    losers = [h for h in branch_results if h != winning]
    if winning:
        session.resolve_branches(
            keep=_slug(winning),
            discard=[_slug(n) for n in losers],
            reason=final.get("text", "")[:500],
            evidence=[branch_results[winning]["evidence"]],
        )
    session.conclude(final.get("text", "(no final)"))
    session.close()


# ---- helpers ----

def _extract_ms(alert: str) -> str:
    m = re.search(r"(\d+)ms", alert)
    return m.group(1) if m else "?"


def _slug(name: str) -> str:
    return name.lower().replace(" ", "-").replace("_", "-")[:40]


def _parse_hypotheses(text: str, limit: int) -> list[dict]:
    """Rough parse: lines under '## Hypotheses' or numbered list.

    Each hypothesis needs a name and ideally a component/keyword to search.
    Fallback: build hypotheses from known patterns (pageserver, image-pull, wal).
    """
    # Simple heuristic — in practice the prompt should ask for JSON.
    known = [
        {"name": "pageserver-reattach", "component": "pageserver", "keyword": "re-attach"},
        {"name": "image-pull-slow", "component": "k8s", "keyword": "ImagePulling"},
        {"name": "wal-replay-backlog", "component": "pageserver", "keyword": "wal_lag"},
    ]
    mentioned = [h for h in known if h["name"].split("-")[0] in text.lower()]
    if not mentioned:
        return known[:limit]
    return mentioned[:limit]


def _extract_winner(text: str, candidates: list[str]) -> str | None:
    low = text.lower()
    for c in candidates:
        if c.lower().split("-")[0] in low and "root cause" in low:
            return c
    return candidates[0] if candidates else None
