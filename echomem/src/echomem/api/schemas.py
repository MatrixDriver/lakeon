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


class TimelineEventOut(BaseModel):
    id: str
    window_start: int
    window_end: int
    agent_id: str
    title: str
    summary: str | None
    member_memory_ids: list[str]
    rationale: str | None


class TimelineResponse(BaseModel):
    events: list[TimelineEventOut]


class TreeNodeOut(BaseModel):
    id: str
    level: int
    parent_id: str | None
    text: str
    token_estimate: int | None
    rationale: str | None


class TreeResponse(BaseModel):
    levels: list[TreeNodeOut]


class GraphNodeOut(BaseModel):
    id: str
    name: str
    kind: str | None


class GraphEdgeOut(BaseModel):
    subject_id: str
    object_id: str
    predicate: str
    confidence: float


class GraphResponse(BaseModel):
    nodes: list[GraphNodeOut]
    edges: list[GraphEdgeOut]


class SkillOut(BaseModel):
    id: str
    name: str
    trigger_pattern: str
    steps: list[str]
    source: str
    observed_count: int
    success_count: int


class SkillsResponse(BaseModel):
    skills: list[SkillOut]


class AddUrlRequest(BaseModel):
    url: str
    path: str | None = None  # optional path_alias to set


class BlobOut(BaseModel):
    sha256: str
    mime: str
    byte_size: int
    origin_url: str | None
    path: str | None = None
    created_at: int


class WriteRequest(BaseModel):
    path: str
    content: str
    mime: str = "text/plain"


class MoveRequest(BaseModel):
    old: str
    new: str


class LsResponse(BaseModel):
    items: list[dict]


class DaemonHealth(BaseModel):
    status: str
    version: str
    data_dir: str
    db_size_bytes: int


class OllamaHealth(BaseModel):
    status: str  # "ok" | "unreachable" | "timeout"
    latency_ms: int | None
    generate_model: str
    embedding_model: str
    embedding_dim: int


class WorkerStatus(BaseModel):
    queue_depth: int
    last_run_at: int | None
    processed_total: int
    throttle: str | None


class DiagnosticCounts(BaseModel):
    memories: int
    cognitions: int
    entities: int
    skills: int


class DeadLetterEntry(BaseModel):
    mem_id: str | None
    worker: str
    kind: str
    retries: int
    at: int
    traceback: str | None


class DiagnosticResponse(BaseModel):
    daemon: DaemonHealth
    ollama: OllamaHealth
    workers: dict[str, WorkerStatus]
    counts: DiagnosticCounts
    dead_letter: list[DeadLetterEntry]
