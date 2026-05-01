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
