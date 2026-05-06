from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable


@dataclass(slots=True)
class Memory:
    id: str
    agent_id: str
    source_kind: str  # 'explicit' | 'session' | 'document'
    source_ref: str | None
    text: str
    meta: dict | None
    created_at: int
    updated_at: int
    deleted_at: int | None = None
    embedding: list[float] | None = field(default=None, repr=False)


@dataclass(slots=True)
class RecallHit:
    memory_id: str
    text: str
    score: float
    source_kind: str
    source_ref: str | None
    meta: dict | None = None


@runtime_checkable
class StorageDriver(Protocol):
    def upsert_memory(self, mem: Memory) -> str: ...

    def get_memory(self, memory_id: str) -> Memory | None: ...

    def list_memories(
        self,
        agent_id: str | None = None,
        limit: int = 50,
        before: int | None = None,
    ) -> list[Memory]: ...

    def delete_memory(self, memory_id: str) -> bool: ...

    def recall(
        self,
        query_embedding: list[float],
        query_text: str,
        k: int = 10,
        agent_id: str | None = None,
    ) -> list[RecallHit]: ...

    def close(self) -> None: ...

    def upsert_summary(self, s: "Summary") -> str: ...
    def query_tree(self, source_kind: str, source_ref: str) -> "list[Summary]": ...

    def upsert_entity(self, e: "Entity") -> str: ...
    def upsert_triple(self, t: "Triple") -> str: ...
    def upsert_pending_triple(self, *, id: str, subject_text: str, predicate: str,
                              object_text: str, source_memory_id: str, confidence: float,
                              created_at: int) -> str: ...
    def list_pending_triples(self, limit: int = 100) -> "list[dict]": ...
    def query_subgraph(self, seed_id: str, hops: int = 2) -> "Subgraph": ...

    def upsert_event(self, e: "Event") -> str: ...
    def query_timeline(self, start_ms: int, end_ms: int, agent_id: "str | None" = None) -> "list[Event]": ...

    def upsert_skill(self, s: "Skill") -> str: ...
    def query_skills(self, query_emb: "list[float]", k: int = 5) -> "list[Skill]": ...

    def upsert_blob_ref(self, b: "BlobRef") -> str: ...
    def get_blob_ref(self, sha256: str) -> "BlobRef | None": ...
    def list_blob_refs(self, *, origin_prefix: "str | None" = None, limit: int = 100) -> "list[BlobRef]": ...
    def set_path_alias(self, path: str, sha256: str, created_at: int) -> None: ...
    def resolve_path(self, path: str) -> "str | None": ...
    def move_path_alias(self, *, old: str, new: str) -> bool: ...
    def list_paths(self, prefix: "str | None" = None, limit: int = 100) -> "list[dict]": ...


@dataclass(slots=True)
class Summary:
    id: str
    source_kind: str            # 'memory' | 'blob' | 'session'
    source_ref: str
    level: int                  # 0 | 1 | 2
    parent_id: str | None
    text: str
    token_estimate: int | None
    created_at: int
    rationale: str | None = None


@dataclass(slots=True)
class Entity:
    id: str
    name: str
    kind: str | None
    meta: dict | None
    first_seen_at: int
    last_seen_at: int


@dataclass(slots=True)
class Triple:
    id: str
    subject_id: str
    predicate: str
    object_id: str
    source_memory_id: str
    confidence: float
    created_at: int


@dataclass(slots=True)
class Event:
    id: str
    window_start: int
    window_end: int
    agent_id: str
    title: str
    summary: str | None
    member_memory_ids: list[str]
    created_at: int
    rationale: str | None = None


@dataclass(slots=True)
class Skill:
    id: str
    name: str
    trigger_pattern: str
    trigger_emb: list[float] | None
    steps: list[str]
    agent_scope: str | None
    source: str                 # 'imported' | 'extracted'
    observed_count: int
    success_count: int
    last_used_at: int | None
    created_at: int
    rationale: str | None = None


@dataclass(slots=True)
class Subgraph:
    """A small in-memory graph slice; `edges` are 3-tuples (subject_id, object_id, attrs)."""
    nodes: list[Entity]
    edges: list[tuple[str, str, dict]]


@dataclass(slots=True)
class BlobRef:
    sha256: str
    mime: str
    byte_size: int | None
    origin_url: str | None
    meta: dict | None
    created_at: int
