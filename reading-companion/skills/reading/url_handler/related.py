"""Find past reading sessions related to a set of keywords (Phase 0: substring match)."""
from __future__ import annotations

import re
from typing import Any

from agent_session_log import LogStore


_TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)


def _extract_title(concl: str, fallback: str) -> str:
    m = _TITLE_RE.search(concl or "")
    return (m.group(1).strip() if m else fallback)[:80]


def find_related(
    *,
    log: LogStore,
    keywords: list[str],
    since: str = "30d",
    limit: int = 5,
    exclude_session_id: str | None,
) -> list[dict[str, Any]]:
    if not keywords:
        return []
    kw_lower = [k.strip().lower() for k in keywords if k.strip()]
    if not kw_lower:
        return []

    metas = log.list_sessions(type="reading", since=since, limit=200)

    out: list[dict[str, Any]] = []
    for meta in metas:
        sid = meta["id"]
        if exclude_session_id and sid == exclude_session_id:
            continue
        concl = log.store.read_conclusion(sid) or ""
        manifest = log.store.read_manifest(sid)
        hay = (concl + " " + str(manifest.trigger)).lower()
        matched = [kw for kw in kw_lower if kw in hay]
        if not matched:
            continue
        out.append({
            "id": sid,
            "title": _extract_title(concl, fallback="(untitled)"),
            "created_at": meta["created_at"],
            "matched_keywords": matched,
            "url": manifest.trigger.get("url"),
        })
        if len(out) >= limit:
            break
    return out
