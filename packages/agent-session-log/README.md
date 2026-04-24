# agent-session-log

LLM-native session/turn/branch/evidence commit log. Runtime-agnostic Python library
that lets any agent record its work in a structured, content-addressable, query-able
form on disk (and optionally sync archives to OBS).

Concepts: Session, Turn, Branch, Evidence, SkillLedger.

Public API:
- `LogStore(root)` — top-level store
- `Session` — write API (append_turn, branch, attach_evidence, conclude, close, record_outcome)
- `SkillLedger` — per-skill invocation/outcome stats
- `FilesystemStore` — low-level file backend
- `Blob`, `BlobRef`, `SessionManifest`, `SessionType`, `SessionStatus`, `TurnType`
- `new_session_id()`, `utc_now_iso()`

This package is the Phase 2 target: extract it from `lakeon/` into its own repo
and publish to PyPI when the abstraction is verified by 2+ consumers (Phase 0b done).

See also: `docs/superpowers/specs/2026-04-23-agent-commit-log-phase0-design.md`.
