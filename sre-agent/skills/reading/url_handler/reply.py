"""Build a feishu markdown reply from a closed reading session."""
from __future__ import annotations

import re
from pathlib import Path

from agent_session_log import LogStore


_TEMPLATE = (Path(__file__).parent / "reply_template.md").read_text(encoding="utf-8")
_TITLE_RE = re.compile(r"^#\s+(.+?)\s*$", re.MULTILINE)
_KEY_POINTS_SECTION_RE = re.compile(r"##\s+要点\s*\n(.*?)(?=\n##\s|\Z)", re.DOTALL)
_RELATED_SECTION_RE = re.compile(r"##\s+相关阅读\s*\n(.*?)(?=\n##\s|\Z)", re.DOTALL)


def build_reply(log: LogStore, session_id: str) -> str:
    m = log.store.read_manifest(session_id)
    concl = log.store.read_conclusion(session_id) or ""

    title_m = _TITLE_RE.search(concl)
    title = title_m.group(1).strip() if title_m else "(无标题)"

    kp_m = _KEY_POINTS_SECTION_RE.search(concl)
    key_points = kp_m.group(1).strip() if kp_m else "- (无)"

    rel_m = _RELATED_SECTION_RE.search(concl)
    related = rel_m.group(1).strip() if rel_m else "- (首次读到这个主题)"

    return _TEMPLATE.format(
        title=title,
        url=m.trigger.get("url", ""),
        key_points=key_points,
        related=related,
        session_id=session_id,
    )
