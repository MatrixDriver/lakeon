"""Filesystem-backed storage for agent_session_log.

Layout:
    <root>/sessions/YYYY/MM/DD/<session_id>/
        manifest.yaml
        events.jsonl
        branches/<branch>.jsonl
        branch-decisions.jsonl
        evidence/by-hash/<sha256>-<short>.<ext>
        evidence/index.json
        conclusion.md
        conclusion-history/v<N>.md
        outcome.md
"""
from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path
from typing import Any

import yaml

from agent_session_log.evidence import Blob, hash_bytes, ext_for_mime
from agent_session_log.types import SessionManifest


class FilesystemStore:
    """Single-agent single-writer filesystem store.

    Concurrency: assumes one process writes to a given session at a time.
    Cross-session writes are safe (different directories).
    """

    def __init__(self, root: Path):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def session_dir(self, session_id: str) -> Path:
        # session_id = sess_YYYYMMDDTHHMMSS_xxxxxx
        date_part = session_id.split("_")[1]  # e.g. 20260423T091230
        year, month, day = date_part[:4], date_part[4:6], date_part[6:8]
        return self.root / "sessions" / year / month / day / session_id

    # ---- session lifecycle ----

    def init_session(self, manifest: SessionManifest) -> Path:
        d = self.session_dir(manifest.id)
        d.mkdir(parents=True, exist_ok=False)
        (d / "branches").mkdir()
        (d / "evidence" / "by-hash").mkdir(parents=True)
        (d / "conclusion-history").mkdir()
        self.write_manifest(manifest)
        # Seed empty main branch file so append is a pure append
        (d / "events.jsonl").touch()
        return d

    def write_manifest(self, manifest: SessionManifest) -> None:
        d = self.session_dir(manifest.id)
        (d / "manifest.yaml").write_text(yaml.safe_dump(asdict(manifest), sort_keys=False))

    def read_manifest(self, session_id: str) -> SessionManifest:
        path = self.session_dir(session_id) / "manifest.yaml"
        data = yaml.safe_load(path.read_text())
        return SessionManifest(**data)

    # ---- events ----

    def _events_path(self, session_id: str, branch: str) -> Path:
        d = self.session_dir(session_id)
        if branch == "main":
            return d / "events.jsonl"
        return d / "branches" / f"{branch}.jsonl"

    def append_event(self, session_id: str, branch: str, event: dict[str, Any]) -> None:
        path = self._events_path(session_id, branch)
        path.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        with open(path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
            f.flush()
            os.fsync(f.fileno())

    def read_events(self, session_id: str, branch: str = "main") -> list[dict[str, Any]]:
        path = self._events_path(session_id, branch)
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def list_branches(self, session_id: str) -> list[str]:
        d = self.session_dir(session_id) / "branches"
        if not d.exists():
            return []
        return sorted(p.stem for p in d.glob("*.jsonl"))

    def append_branch_decision(self, session_id: str, decision: dict[str, Any]) -> None:
        d = self.session_dir(session_id)
        with open(d / "branch-decisions.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(decision, ensure_ascii=False) + "\n")

    # ---- evidence ----

    def write_blob(
        self,
        session_id: str,
        data: bytes,
        mime: str,
        source: str | None = None,
    ) -> Blob:
        sha = hash_bytes(data)
        ext = ext_for_mime(mime)
        filename = f"{sha}-{sha[:8]}.{ext}"
        path = self.session_dir(session_id) / "evidence" / "by-hash" / filename
        # De-dup: skip write if exists with same content
        if not path.exists():
            path.write_bytes(data)
        blob = Blob(sha256=sha, mime=mime, size=len(data), ext=ext, source=source, _bytes=data)
        self._update_evidence_index(session_id, blob)
        return blob

    def read_blob(self, session_id: str, sha256: str) -> bytes:
        ev_dir = self.session_dir(session_id) / "evidence" / "by-hash"
        matches = list(ev_dir.glob(f"{sha256}*"))
        if not matches:
            raise FileNotFoundError(f"blob {sha256[:8]} not in session {session_id}")
        return matches[0].read_bytes()

    def _update_evidence_index(self, session_id: str, blob: Blob) -> None:
        path = self.session_dir(session_id) / "evidence" / "index.json"
        if path.exists():
            idx = json.loads(path.read_text())
        else:
            idx = {}
        idx[blob.sha256] = {
            "mime": blob.mime,
            "size": blob.size,
            "ext": blob.ext,
            "source": blob.source,
        }
        path.write_text(json.dumps(idx, indent=2))

    # ---- conclusion / outcome ----

    def write_conclusion(self, session_id: str, markdown: str) -> None:
        d = self.session_dir(session_id)
        main = d / "conclusion.md"
        if main.exists():
            # preserve previous version
            hist_dir = d / "conclusion-history"
            n = len(list(hist_dir.glob("v*.md"))) + 1
            (hist_dir / f"v{n}.md").write_text(main.read_text())
        main.write_text(markdown)

    def read_conclusion(self, session_id: str) -> str | None:
        path = self.session_dir(session_id) / "conclusion.md"
        return path.read_text() if path.exists() else None

    def write_outcome(self, session_id: str, markdown: str) -> None:
        path = self.session_dir(session_id) / "outcome.md"
        path.write_text(markdown)

    def read_outcome(self, session_id: str) -> str | None:
        path = self.session_dir(session_id) / "outcome.md"
        return path.read_text() if path.exists() else None

    # ---- iteration ----

    def iter_session_ids(self) -> list[str]:
        """Walk sessions/ tree; return all session ids."""
        sessions_root = self.root / "sessions"
        if not sessions_root.exists():
            return []
        out = []
        for y in sorted(sessions_root.iterdir()):
            if not y.is_dir():
                continue
            for m in sorted(y.iterdir()):
                for d in sorted(m.iterdir()):
                    for s in sorted(d.iterdir()):
                        if s.is_dir() and s.name.startswith("sess_"):
                            out.append(s.name)
        return out
