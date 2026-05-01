from __future__ import annotations

import time
from fastapi import APIRouter, HTTPException, Request

from echomem.api.schemas import (
    IngestRequest,
    IngestResponse,
    ListResponse,
    MemoryOut,
    RecallRequest,
    RecallResponse,
    RecallHitOut,
)
from echomem.drivers.base import Memory
from echomem.ulid import new as new_id
from echomem.workers.embedder import EmbedderWorker

router = APIRouter(prefix="/memory")


@router.post("/ingest", response_model=IngestResponse)
async def ingest(req: IngestRequest, request: Request) -> IngestResponse:
    driver = request.app.state.driver
    ollama = request.app.state.ollama
    cfg = request.app.state.config

    now = int(time.time() * 1000)
    mid = new_id()
    embedding = await ollama.embed(req.text, model=cfg.embedding_model)
    mem = Memory(
        id=mid,
        agent_id=req.agent_id,
        source_kind=req.source_kind,
        source_ref=req.source_ref,
        text=req.text,
        meta=req.meta,
        created_at=now,
        updated_at=now,
        deleted_at=None,
        embedding=embedding,
    )
    driver.upsert_memory(mem)
    return IngestResponse(id=mid, agent_id=req.agent_id, created_at=now)
