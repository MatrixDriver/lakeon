from fastapi import FastAPI, Header, HTTPException, Query
from typing import Optional
import schema
import engine
from models import IngestRequest, RecallRequest

app = FastAPI(title="DBay Memory Service")


@app.post("/init")
async def init_memory(x_database_connstr: str = Header(...)):
    schema.init_schema(x_database_connstr)
    return {"status": "ok"}


@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...)):
    mem = await engine.ingest(x_database_connstr, req.content, req.role,
                               req.memory_type, req.importance, req.metadata)
    return mem.model_dump()


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
async def digest(x_database_connstr: str = Header(...)):
    return {"status": "not_implemented", "message": "Digest/reflection coming soon"}


@app.get("/health")
async def health():
    return {"status": "ok"}
