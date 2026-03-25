from fastapi import FastAPI, Header, HTTPException, Query
from typing import Optional
import asyncio
import schema
import engine
from models import IngestRequest, IngestExtractedRequest, DigestExtractedRequest, RecallRequest

app = FastAPI(title="DBay Memory Service")


@app.post("/init")
async def init_memory(x_database_connstr: str = Header(...)):
    schema.init_schema(x_database_connstr)
    return {"status": "ok"}


@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...),
                 x_one_llm_mode: str = Header("false")):
    one_llm = x_one_llm_mode.lower() == "true"
    auto_extract = req.auto_extract if req.auto_extract is not None else (not one_llm)

    message_id = await engine.store_raw_message(x_database_connstr, req.content, req.role, req.source)

    if auto_extract:
        asyncio.create_task(engine.background_extract(x_database_connstr, message_id, req.content))
        return {"message_id": message_id, "extraction_required": False, "status": "extracting"}
    else:
        from extraction_prompt import build_extraction_prompt
        prompt = build_extraction_prompt(req.content)
        return {"message_id": message_id, "extraction_required": True, "extraction_prompt": prompt}


@app.post("/ingest_extracted")
async def ingest_extracted(req: IngestExtractedRequest, x_database_connstr: str = Header(...)):
    counts = await engine.ingest_extracted(x_database_connstr, req.message_id, req.data.model_dump())
    return counts


@app.post("/recall")
async def recall(req: RecallRequest, x_database_connstr: str = Header(...)):
    results = await engine.recall(x_database_connstr, req.query, req.top_k, req.memory_types)
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
async def digest(x_database_connstr: str = Header(...),
                 x_one_llm_mode: str = Header("false")):
    one_llm = x_one_llm_mode.lower() == "true"
    memories, total = await engine.get_unreflected_memories(x_database_connstr)

    if total == 0:
        return {"one_llm_mode": one_llm, "unreflected_count": 0, "traits_generated": 0}

    if one_llm:
        from digest_prompt import build_digest_prompt, format_memories_for_digest
        existing = await engine.get_existing_traits(x_database_connstr)
        prompt = build_digest_prompt(memories, existing)
        return {
            "one_llm_mode": True,
            "unreflected_count": total,
            "memories": memories,
            "existing_traits": existing,
            "reflection_prompt": prompt,
        }
    else:
        from digest_prompt import build_digest_prompt, format_memories_for_digest
        from llm_client import chat_extract
        existing = await engine.get_existing_traits(x_database_connstr)
        prompt = build_digest_prompt(memories, existing)
        formatted = format_memories_for_digest(memories)
        full_prompt = f"{formatted}\n\n{prompt}"
        result = await chat_extract(full_prompt)
        traits = result.get("traits", [])
        stored = await engine.store_digest_traits(x_database_connstr, traits)
        return {"one_llm_mode": False, "traits_generated": stored}


@app.post("/digest_extracted")
async def digest_extracted(req: DigestExtractedRequest, x_database_connstr: str = Header(...)):
    traits = [t.model_dump() for t in req.data.traits]
    stored = await engine.store_digest_traits(x_database_connstr, traits)
    return {"traits_stored": stored}


@app.get("/health")
async def health():
    return {"status": "ok"}
