"""Dataclasses and enums. No logic, no side effects."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class SessionType(str, Enum):
    INCIDENT = "incident"
    READING = "reading"
    REFLECTION = "reflection"


class SessionStatus(str, Enum):
    OPEN = "open"
    CLOSED = "closed"
    ABANDONED = "abandoned"


class TurnType(str, Enum):
    TRIGGER = "trigger"
    THOUGHT = "thought"
    TOOL_CALL = "tool_call"
    TOOL_RESULT = "tool_result"
    LLM_COMPLETION = "llm_completion"
    BRANCH_OPEN = "branch_open"
    BRANCH_RESOLVE = "branch_resolve"
    CONCLUDE = "conclude"


@dataclass(frozen=True)
class BlobRef:
    """Reference to evidence blob. Not the bytes themselves."""
    sha256: str
    mime: str
    size: int
    ext: str
    source: str | None = None


@dataclass
class SessionManifest:
    id: str
    type: str
    created_at: str
    closed_at: str | None
    status: str
    trigger: dict[str, Any]
    tags: list[str] = field(default_factory=list)
    parent_sessions: list[str] = field(default_factory=list)
    model: str | None = None
    runtime: str | None = None
    obs_ref: str | None = None  # filled after sync
