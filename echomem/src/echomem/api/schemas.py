from __future__ import annotations

from pydantic import BaseModel, Field


class IngestRequest(BaseModel):
    text: str = Field(min_length=1)
    agent_id: str = Field(min_length=1)
    source_kind: str = "explicit"
    source_ref: str | None = None
    meta: dict | None = None


class IngestResponse(BaseModel):
    id: str
    agent_id: str
    created_at: int


class RecallRequest(BaseModel):
    query: str = Field(min_length=1)
    k: int = 10
    agent_id: str | None = None


class RecallHitOut(BaseModel):
    id: str
    text: str
    score: float
    source_kind: str
    source_ref: str | None
    meta: dict | None


class RecallResponse(BaseModel):
    hits: list[RecallHitOut]


class MemoryOut(BaseModel):
    id: str
    agent_id: str
    source_kind: str
    source_ref: str | None
    text: str
    meta: dict | None
    created_at: int
    updated_at: int


class ListResponse(BaseModel):
    items: list[MemoryOut]
