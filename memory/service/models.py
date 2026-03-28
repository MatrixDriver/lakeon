from pydantic import BaseModel
from typing import Optional, Literal
from datetime import datetime


class Memory(BaseModel):
    id: int
    content: str
    memory_type: str
    importance: float = 0.5
    access_count: int = 0
    last_accessed_at: Optional[datetime] = None
    metadata: dict = {}
    event_time: Optional[datetime] = None
    created_at: datetime


class Trait(BaseModel):
    id: int
    content: str
    trait_stage: str
    trait_subtype: Optional[str] = None
    confidence: float
    reinforcement_count: int = 0
    contradiction_count: int = 0
    context: Optional[str] = None
    created_at: datetime


class GraphNode(BaseModel):
    node_type: str
    node_id: str
    properties: dict = {}


class GraphEdge(BaseModel):
    source_type: str
    source_id: str
    target_type: str
    target_id: str
    edge_type: str


class LegacyIngestRequest(BaseModel):
    content: str
    role: str = "user"
    memory_type: Literal['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] = "fact"
    importance: float = 0.5
    metadata: dict = {}


class RecallRequest(BaseModel):
    query: str
    top_k: int = 10
    memory_types: Optional[list[str]] = None


class MemoryStats(BaseModel):
    total: int
    by_type: dict
    trait_count: int


# New IngestRequest for refactored /ingest endpoint
class IngestRequest(BaseModel):
    """Ingest endpoint — behavior determined by `signal`:
    - "memory": content is a structured memory, memory_type required → store directly
    - "conversation": content is raw conversation → server extracts memories automatically
    """
    content: str
    signal: Literal['memory', 'conversation'] = "memory"
    role: str = "user"  # kept for backward compat (conversation participant role)
    source: Optional[str] = None  # e.g. "openclaw", "claude-code", "api"
    memory_type: Optional[Literal['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention']] = None
    importance: float = 0.5

    model_config = {"extra": "ignore"}


class IngestExtractedItem(BaseModel):
    content: str
    importance: float = 0.5
    category: Optional[str] = None
    timestamp: Optional[str] = None
    rationale: Optional[str] = None
    project: Optional[str] = None
    reason: Optional[str] = None
    scope: Optional[str] = None

    model_config = {"extra": "ignore"}


class IngestExtractedData(BaseModel):
    facts: list[IngestExtractedItem] = []
    episodes: list[IngestExtractedItem] = []
    procedural: list[IngestExtractedItem] = []
    decisions: list[IngestExtractedItem] = []
    rejections: list[IngestExtractedItem] = []
    conventions: list[IngestExtractedItem] = []

    model_config = {"extra": "ignore"}  # silently drop "triples" from LLM response


class IngestExtractedRequest(BaseModel):
    message_id: str
    data: IngestExtractedData


class DigestExtractedTrait(BaseModel):
    content: str
    category: Optional[str] = None
    importance: int = 5  # 1-10 scale; only >= 7 stored


class DigestExtractedData(BaseModel):
    traits: list[DigestExtractedTrait] = []


class DigestExtractedRequest(BaseModel):
    data: DigestExtractedData
