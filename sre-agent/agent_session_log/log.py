"""Top-level LogStore — create + query sessions."""
from __future__ import annotations

from pathlib import Path
from typing import Any

from agent_session_log.session import Session
from agent_session_log.store import FilesystemStore


class LogStore:
    def __init__(self, root: Path | str):
        self._store = FilesystemStore(Path(root))

    @property
    def store(self) -> FilesystemStore:
        return self._store

    def new_session(self, **kwargs: Any) -> Session:
        return Session.new(store=self._store, **kwargs)

    def get_session(self, session_id: str) -> Session:
        return Session.load(store=self._store, session_id=session_id)

    def list_sessions(
        self,
        type: str | None = None,
        tags: list[str] | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """Return list of manifests (as dicts), newest first, optionally filtered."""
        ids = self._store.iter_session_ids()
        out: list[dict[str, Any]] = []
        for sid in reversed(ids):  # newest first
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if type and m.type != type:
                continue
            if tags and not all(tag in m.tags for tag in tags):
                continue
            out.append({
                "id": m.id,
                "type": m.type,
                "status": m.status,
                "created_at": m.created_at,
                "closed_at": m.closed_at,
                "tags": m.tags,
            })
            if len(out) >= limit:
                break
        return out

    def search_text(self, query: str, type: str | None = None, limit: int = 20) -> list[dict[str, Any]]:
        """Simple substring search over conclusions + manifest trigger text."""
        q = query.lower()
        out: list[dict[str, Any]] = []
        for sid in reversed(self._store.iter_session_ids()):
            try:
                m = self._store.read_manifest(sid)
            except FileNotFoundError:
                continue
            if type and m.type != type:
                continue
            hay = ""
            concl = self._store.read_conclusion(sid)
            if concl:
                hay += concl.lower()
            hay += " " + str(m.trigger).lower()
            if q in hay:
                out.append({
                    "id": m.id,
                    "type": m.type,
                    "snippet": (concl or "")[:200],
                    "created_at": m.created_at,
                })
                if len(out) >= limit:
                    break
        return out

    def similar(self, session_id: str, k: int = 5) -> list[dict[str, Any]]:
        """Return sessions semantically similar to the given session.

        Deferred to Phase 0b — requires embeddings. In Phase 0a, use search_text()
        with extracted key phrases as a substitute.
        """
        raise NotImplementedError(
            "log.similar() requires embedding index; implement in Phase 0b. "
            "Phase 0a callers should use search_text() with key phrases extracted "
            "from the session's trigger or conclusion."
        )

    def replay(self, session_id: str, at_turn: int, branch: str = "main") -> list[dict[str, Any]]:
        """Return all events up to and including at_turn on the given branch."""
        events = self._store.read_events(session_id, branch)
        return [e for e in events if e.get("turn", -1) <= at_turn]
