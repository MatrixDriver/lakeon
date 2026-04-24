"""Session write API.

One Session instance writes to one session directory. Branches are lightweight
context managers that share the same store.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from agent_session_log.evidence import Blob
from agent_session_log.ids import new_session_id, utc_now_iso
from agent_session_log.store import FilesystemStore
from agent_session_log.types import SessionManifest, SessionStatus


@dataclass
class Branch:
    """Cheap handle that writes to a named branch within a session."""
    _session: "Session"
    name: str
    _next_turn: int = 0

    def append_turn(self, type: str, **kwargs: Any) -> int:
        tid = self._next_turn
        self._next_turn += 1
        event = {
            "turn": tid,
            "t": utc_now_iso(),
            "type": type,
            **kwargs,
        }
        self._session._store.append_event(self._session.id, self.name, event)
        return tid


@dataclass
class Session:
    id: str
    type: str
    status: str
    _store: FilesystemStore
    _next_turn: int = 0
    _branches: dict[str, Branch] = field(default_factory=dict)

    # ---- factory methods ----

    @classmethod
    def new(
        cls,
        store: FilesystemStore,
        type: str,
        trigger: dict[str, Any],
        tags: list[str] | None = None,
        model: str | None = None,
        runtime: str | None = None,
        parent_sessions: list[str] | None = None,
    ) -> "Session":
        sid = new_session_id()
        manifest = SessionManifest(
            id=sid,
            type=type,
            created_at=utc_now_iso(),
            closed_at=None,
            status=SessionStatus.OPEN.value,
            trigger=trigger or {},
            tags=tags or [],
            parent_sessions=parent_sessions or [],
            model=model,
            runtime=runtime,
        )
        store.init_session(manifest)
        return cls(id=sid, type=type, status="open", _store=store)

    @classmethod
    def load(cls, store: FilesystemStore, session_id: str) -> "Session":
        manifest = store.read_manifest(session_id)
        events = store.read_events(session_id, "main")
        next_turn = max((e.get("turn", -1) for e in events), default=-1) + 1

        # Reconstruct branches from disk
        branches: dict[str, "Branch"] = {}
        branches_dir = store.session_dir(session_id) / "branches"
        if branches_dir.exists():
            for bf in branches_dir.glob("*.jsonl"):
                name = bf.stem
                events_b = store.read_events(session_id, name)
                next_t = max((e.get("turn", -1) for e in events_b), default=-1) + 1
                # Create Branch without _session; will be patched below
                branches[name] = Branch(_session=None, name=name, _next_turn=next_t)  # type: ignore[arg-type]

        sess = cls(
            id=manifest.id,
            type=manifest.type,
            status=manifest.status,
            _store=store,
            _next_turn=next_turn,
        )
        # Patch _session references now that sess exists
        for b in branches.values():
            b._session = sess
        sess._branches = branches
        return sess

    # ---- writes ----

    def append_turn(self, type: str, **kwargs: Any) -> int:
        if self.status in {"closed", "abandoned"}:
            raise RuntimeError(f"cannot append turn to {self.status} session {self.id}")
        tid = self._next_turn
        self._next_turn += 1
        event = {"turn": tid, "t": utc_now_iso(), "type": type, **kwargs}
        self._store.append_event(self.id, "main", event)
        return tid

    def branch(self, name: str) -> Branch:
        if name == "main":
            raise ValueError("'main' is reserved; use append_turn for main branch")
        if name in self._branches:
            return self._branches[name]
        existing = self._store.read_events(self.id, name)
        next_turn = max((e.get("turn", -1) for e in existing), default=-1) + 1
        if not existing:
            # Fresh branch: record the branch_open in main
            self.append_turn(type="branch_open", branch=name)
        b = Branch(_session=self, name=name, _next_turn=next_turn)
        self._branches[name] = b
        return b

    def resolve_branches(
        self,
        keep: str,
        discard: list[str],
        reason: str,
        evidence: list[str] | None = None,
    ) -> None:
        decision = {
            "t": utc_now_iso(),
            "kept": keep,
            "discarded": discard,
            "reason": reason,
            "evidence": evidence or [],
        }
        self._store.append_branch_decision(self.id, decision)
        self.append_turn(
            type="branch_resolve",
            keep=keep,
            discard=discard,
            reason=reason,
            evidence=evidence or [],
        )

    def attach_evidence(self, data: bytes, mime: str, source: str | None = None) -> Blob:
        return self._store.write_blob(self.id, data, mime=mime, source=source)

    def conclude(self, markdown: str) -> None:
        self._store.write_conclusion(self.id, markdown)
        self.append_turn(type="conclude", ref="conclusion.md")

    def record_outcome(self, did_work: bool, notes: str = "") -> None:
        body = (
            f"## {utc_now_iso()}\n"
            f"- did_work: {'true' if did_work else 'false'}\n"
            f"- notes: {notes}\n"
        )
        existing = self._store.read_outcome(self.id) or ""
        self._store.write_outcome(self.id, existing + body)

    def close(self, status: str = "closed") -> None:
        if self.status in {"closed", "abandoned"}:
            raise RuntimeError(f"session {self.id} is already {self.status}")
        if status not in {"closed", "abandoned"}:
            raise ValueError(status)
        m = self._store.read_manifest(self.id)
        m.status = status
        m.closed_at = utc_now_iso()
        self._store.write_manifest(m)
        self.status = status

    @property
    def next_turn_id(self) -> int:
        return self._next_turn
