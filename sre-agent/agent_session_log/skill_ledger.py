"""Skill invocation + outcome ledger.

Layout:
    <root>/skills-ledger/<skill_name>/
        invocations.jsonl
        outcomes.jsonl
        stats.json (computed)
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


class SkillLedger:
    def __init__(self, root: Path | str):
        self.root = Path(root) / "skills-ledger"
        self.root.mkdir(parents=True, exist_ok=True)

    def _skill_dir(self, skill: str) -> Path:
        d = self.root / skill
        d.mkdir(parents=True, exist_ok=True)
        return d

    def record_invocation(
        self,
        skill: str,
        *,
        version: str,
        session_id: str,
        triggered_at: str,
    ) -> None:
        path = self._skill_dir(skill) / "invocations.jsonl"
        entry = {
            "skill": skill,
            "version": version,
            "session_id": session_id,
            "triggered_at": triggered_at,
        }
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def record_outcome(
        self,
        skill: str,
        *,
        session_id: str,
        did_work: bool,
        notes: str = "",
    ) -> None:
        path = self._skill_dir(skill) / "outcomes.jsonl"
        entry = {"session_id": session_id, "did_work": did_work, "notes": notes}
        with open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")

    def list_invocations(self, skill: str) -> list[dict[str, Any]]:
        path = self._skill_dir(skill) / "invocations.jsonl"
        if not path.exists():
            return []
        return [json.loads(ln) for ln in path.read_text().splitlines() if ln.strip()]

    def list_outcomes(self, skill: str) -> list[dict[str, Any]]:
        path = self._skill_dir(skill) / "outcomes.jsonl"
        if not path.exists():
            return []
        return [json.loads(ln) for ln in path.read_text().splitlines() if ln.strip()]

    def stats(self, skill: str) -> dict[str, Any]:
        invs = self.list_invocations(skill)
        outs = self.list_outcomes(skill)
        did_work = sum(1 for o in outs if o.get("did_work"))
        rate = (did_work / len(outs)) if outs else None
        stats = {
            "skill": skill,
            "total_invocations": len(invs),
            "outcomes_filed": len(outs),
            "did_work_count": did_work,
            "did_work_rate": rate,
        }
        (self._skill_dir(skill) / "stats.json").write_text(json.dumps(stats, indent=2))
        return stats
