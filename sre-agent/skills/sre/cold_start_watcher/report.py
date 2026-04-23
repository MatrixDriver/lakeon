"""Build a feishu card summary from a closed cold-start-watcher session."""
from __future__ import annotations

import json
import re
from pathlib import Path

from agent_session_log import LogStore


TEMPLATE = (Path(__file__).parent / "report_template.md").read_text()


def build_report(log: LogStore, session_id: str) -> str:
    m = log.store.read_manifest(session_id)
    concl = log.store.read_conclusion(session_id) or ""

    ms = _extract_ms(m.trigger.get("alert", ""))
    tenant = m.trigger.get("tenant_id", "?")
    db = m.trigger.get("db_id", "?")
    root_cause = _extract_section(concl, "Root cause")
    confidence = _extract_confidence(concl)
    actions = _extract_section(concl, "Suggested actions") or "(none)"
    rejected = _extract_rejected(log, session_id)

    return TEMPLATE.format(
        ms=ms, tenant=tenant, db=db,
        confidence=confidence,
        root_cause_one_liner=_first_sentence(root_cause),
        actions=actions.strip(),
        session_id=session_id,
        rejected_hypotheses=", ".join(rejected) or "none",
    )


def _extract_ms(alert: str) -> str:
    m = re.search(r"(\d+)ms", alert)
    return m.group(1) if m else "?"


def _extract_section(text: str, header: str) -> str:
    # Match ## Header ... until next ## or EOF
    pat = rf"##\s+{re.escape(header)}[^\n]*\n(.*?)(?=\n##\s|\Z)"
    m = re.search(pat, text, re.DOTALL)
    return m.group(1).strip() if m else ""


def _extract_confidence(text: str) -> str:
    m = re.search(r"confidence\s+([\d.]+)", text, re.IGNORECASE)
    return f"confidence {m.group(1)}" if m else ""


def _first_sentence(text: str) -> str:
    for sep in ("\n\n", ". ", "\n"):
        if sep in text:
            return text.split(sep)[0].strip()
    return text.strip()[:160]


def _extract_rejected(log: LogStore, session_id: str) -> list[str]:
    out: list[str] = []
    # 1) branch-decisions.jsonl (written by Session.resolve_branches)
    path = log.store.session_dir(session_id) / "branch-decisions.jsonl"
    if path.exists():
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            dec = json.loads(line)
            out.extend(dec.get("discarded", []))
    # 2) main events.jsonl branch_resolve events (fallback / redundant source)
    for ev in log.store.read_events(session_id, "main"):
        if ev.get("type") == "branch_resolve":
            out.extend(ev.get("discard", []))
    # dedupe preserving order
    seen = set()
    dedup = []
    for n in out:
        if n not in seen:
            seen.add(n)
            dedup.append(n)
    return dedup
