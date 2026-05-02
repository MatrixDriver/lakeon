from __future__ import annotations

import re
import time
from pathlib import Path
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Skill
from echomem.ollama_client import OllamaClient
from echomem.logging import get_logger

log = get_logger("echomem.skill_importer")

FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def _parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    m = FRONTMATTER_RE.match(text)
    if not m:
        return {}, text
    fm: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            fm[k.strip()] = v.strip()
    return fm, text[m.end():]


def _parse_steps(body: str) -> list[str]:
    # Pull numbered list items "1. xxx" — first match block only
    steps: list[str] = []
    for line in body.splitlines():
        m = re.match(r"\s*\d+[.\)]\s+(.+)", line)
        if m:
            steps.append(m.group(1).strip())
    return steps


async def import_skills_from_directory(
    driver: SQLiteDriver,
    ollama: OllamaClient,
    *,
    directory: Path,
    embedding_model: str,
    agent_scope: str | None = None,
) -> int:
    """Scan a directory of *.md skill files (with name/description frontmatter)
    and import them as derivative_skill rows. Returns the count imported."""
    if not directory.exists():
        log.warning("importer.dir_missing", path=str(directory))
        return 0

    count = 0
    now = int(time.time() * 1000)
    for path in sorted(directory.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        fm, body = _parse_frontmatter(text)
        if "name" not in fm or "description" not in fm:
            log.info("importer.skip_no_frontmatter", file=str(path))
            continue

        try:
            emb = await ollama.embed(fm["description"], model=embedding_model)
        except Exception as e:
            log.warning("importer.embed_failed", file=str(path), err=str(e))
            continue

        sk = Skill(
            id=f"skill:imported:{fm['name']}",
            name=fm["name"],
            trigger_pattern=fm["description"],
            trigger_emb=emb,
            steps=_parse_steps(body),
            agent_scope=agent_scope,
            source="imported",
            observed_count=0,
            success_count=0,
            last_used_at=None,
            created_at=now,
            rationale=f"imported from {path.name}",
        )
        driver.upsert_skill(sk)
        count += 1
    log.info("importer.done", count=count, dir=str(directory))
    return count
