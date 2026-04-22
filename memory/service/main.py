from fastapi import FastAPI, Header, HTTPException, Query
from typing import Optional
import asyncio
import schema
import engine
from models import IngestRequest, IngestExtractedRequest, DigestExtractedRequest, RecallRequest, DeriveRequest

app = FastAPI(title="DBay Memory Service")


@app.post("/init")
async def init_memory(x_database_connstr: str = Header(...),
                      x_embedding_dim: int = Header(1024)):
    schema.init_schema(x_database_connstr, embedding_dim=x_embedding_dim)
    return {"status": "ok"}


@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...),
                 x_scene: str = Header("CHAT_ASSISTANT")):
    if req.signal == "memory":
        # Structured memory from AI agent — store directly
        if not req.memory_type:
            raise HTTPException(400, "memory_type is required when signal='memory'")
        metadata = {"source": req.source} if req.source else {}
        # Audit log: record raw interaction for debugging (7-day TTL)
        asyncio.create_task(engine.store_raw_message(
            x_database_connstr, req.content, req.role, req.source, op="ingest_memory"))
        mem = await engine.ingest(x_database_connstr, req.content, req.role,
                                  req.memory_type, req.importance, metadata,
                                  embedding=req.embedding)
        return {"memory_id": mem.id, "memory_type": mem.memory_type, "status": "stored"}

    elif req.signal == "conversation":
        # Raw conversation — server extracts memories automatically
        message_id = await engine.store_raw_message(
            x_database_connstr, req.content, req.role, req.source, op="ingest_conversation")
        asyncio.create_task(engine.background_extract(x_database_connstr, message_id, req.content, x_scene))
        return {"message_id": message_id, "status": "extracting"}


@app.post("/agentfs/derive")
async def agentfs_derive(req: DeriveRequest,
                          x_database_connstr: str = Header(...)):
    """Phase 2 hook: lakeon-api AgentFSEventForwarder forwards per-tenant
    events here. Idempotent via ingest_idempotent on (source_path, source_etag)
    UNIQUE index; delete via delete_by_source_path.
    """
    metadata = {
        "source_system": "agentfs",
        "source_path": req.path,
        "source_etag": req.source_etag,
        "source_agent": req.source_agent,
    }
    if req.source_frontmatter:
        metadata["source_frontmatter"] = req.source_frontmatter

    if req.op == "delete":
        n = await engine.delete_by_source_path(x_database_connstr, req.path)
        return {"status": "deleted", "rows_deleted": n}

    # create / update / backfill
    if req.content is None or req.memory_type is None:
        raise HTTPException(400, f"content and memory_type required for op={req.op}")

    mem = await engine.ingest_idempotent(
        x_database_connstr, req.content, req.memory_type, 0.5, metadata,
        embedding=None,  # let the engine compute
    )
    if mem is None:
        return {"status": "idempotent_noop", "memory_id": None}
    return {"status": "ingested", "memory_id": mem.id}


@app.post("/ingest_extracted")
async def ingest_extracted(req: IngestExtractedRequest, x_database_connstr: str = Header(...)):
    counts = await engine.ingest_extracted(x_database_connstr, req.message_id, req.data.model_dump())
    return counts


@app.post("/recall")
async def recall(req: RecallRequest, x_database_connstr: str = Header(...)):
    if not req.query and not req.query_embedding:
        raise HTTPException(400, "Either query or query_embedding is required")
    results = await engine.recall(x_database_connstr, req.query, req.top_k,
                                   req.memory_types, query_embedding=req.query_embedding)
    return {"memories": [m.model_dump() for m in results]}


@app.get("/memories")
async def list_memories(
    x_database_connstr: str = Header(...),
    memory_type: Optional[str] = None,
    offset: int = 0,
    limit: int = 20,
):
    result = await engine.list_memories(x_database_connstr, memory_type, offset, limit)
    return {"memories": [m.model_dump() for m in result["memories"]], "total": result["total"]}


@app.get("/memories/{memory_id}")
async def get_memory(memory_id: int, x_database_connstr: str = Header(...)):
    try:
        mem = await engine.get_memory(x_database_connstr, memory_id)
        return mem.model_dump()
    except ValueError as e:
        raise HTTPException(404, str(e))


@app.delete("/memories/{memory_id}")
async def delete_memory(memory_id: int, x_database_connstr: str = Header(...)):
    await engine.delete_memory(x_database_connstr, memory_id)
    return {"status": "ok"}


@app.get("/stats")
async def get_stats(x_database_connstr: str = Header(...)):
    stats = await engine.get_stats(x_database_connstr)
    return stats.model_dump()


@app.get("/traits")
async def list_traits(x_database_connstr: str = Header(...)):
    traits = await engine.list_traits(x_database_connstr)
    return [t.model_dump() for t in traits]


@app.get("/graph")
async def get_graph(x_database_connstr: str = Header(...)):
    return await engine.get_graph(x_database_connstr)


@app.post("/digest")
async def digest(x_database_connstr: str = Header(...)):
    memories, total = await engine.get_unreflected_memories(x_database_connstr)

    if total == 0:
        return {"unreflected_count": 0, "traits_generated": 0}

    from digest_prompt import build_digest_prompt, format_memories_for_digest
    from llm_client import chat_extract
    existing = await engine.get_existing_traits(x_database_connstr)
    prompt = build_digest_prompt(memories, existing)
    formatted = format_memories_for_digest(memories)
    full_prompt = f"{formatted}\n\n{prompt}"
    result = await chat_extract(full_prompt)
    traits = result.get("traits", [])
    stored = await engine.store_digest_traits(x_database_connstr, traits)
    return {"traits_generated": stored}


@app.post("/digest_extracted")
async def digest_extracted(req: DigestExtractedRequest, x_database_connstr: str = Header(...)):
    traits = [t.model_dump() for t in req.data.traits]
    stored = await engine.store_digest_traits(x_database_connstr, traits)
    return {"traits_stored": stored}


@app.get("/raw_messages")
async def list_raw_messages(
    x_database_connstr: str = Header(...),
    offset: int = 0,
    limit: int = 20,
    op: Optional[str] = None,
):
    # Lazy TTL cleanup: on first page fetch, purge entries older than 7 days
    if offset == 0:
        asyncio.create_task(engine.purge_old_raw_messages(x_database_connstr, days=7))
    result = await engine.list_raw_messages(x_database_connstr, offset, limit, op=op)
    return result


@app.get("/raw_messages/{message_id}")
async def get_raw_message(message_id: str, x_database_connstr: str = Header(...)):
    result = await engine.get_raw_message_with_memories(x_database_connstr, message_id)
    if not result:
        raise HTTPException(404, "Message not found")
    return result


@app.get("/health")
async def health():
    return {"status": "ok"}
