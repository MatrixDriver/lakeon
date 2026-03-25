from pydantic import BaseModel
from typing import Optional, Literal
from datetime import datetime


class Memory(BaseModel):
    id: int
    content: str
    memory_type: str
    importance: float = 0.5
    access_count: int = 0
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


class IngestRequest(BaseModel):
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
