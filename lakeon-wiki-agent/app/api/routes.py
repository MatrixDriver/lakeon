"""HTTP routes for lakeon-wiki-agent.

Three run types (ingest, curate, lint) each map to a POST endpoint that:
1. Deserializes a pydantic request body
2. Constructs a RunRequest with a fresh run_id
3. Hands the agent coroutine to TaskRegistry.submit
4. Returns 202 Accepted with `{task_id, run_id, status: "accepted"}`

Progress is polled via GET /v1/wiki/tasks/{task_id}.
Interactive chat is available via POST /v1/wiki/chat (SSE streaming).
"""
import json as _json

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.agent.loop import AgentRunner, RunRequest, new_run_id
from app.deps import get_registry, get_runner, require_token
from app.tasks import TaskRegistry

router = APIRouter(tags=["wiki-agent"])


class IngestRequest(BaseModel):
    tenant_id: str = Field(..., min_length=1)
    kb_id: str = Field(..., min_length=1)
    document_id: str = Field(..., min_length=1)
    source: str = Field("queue", min_length=1)
    callback_url: str | None = None


class CurateRequest(BaseModel):
    tenant_id: str = Field(..., min_length=1)
    kb_id: str = Field(..., min_length=1)
    source: str = Field("manual", min_length=1)
    callback_url: str | None = None


class LintRequest(BaseModel):
    tenant_id: str = Field(..., min_length=1)
    kb_id: str = Field(..., min_length=1)
    source: str = Field("manual", min_length=1)
    callback_url: str | None = None


# ── Public (no auth) ───────────────────────────────────────────


@router.get("/health", summary="Liveness probe")
def health() -> dict:
    return {"status": "ok"}


# ── Authenticated run endpoints ────────────────────────────────


@router.post(
    "/v1/wiki/ingest",
    status_code=202,
    dependencies=[Depends(require_token)],
    summary="Dispatch a wiki ingest run for a single document",
)
async def ingest(
    req: IngestRequest,
    registry: TaskRegistry = Depends(get_registry),
    runner: AgentRunner = Depends(get_runner),
) -> dict:
    run_req = RunRequest(
        tenant_id=req.tenant_id,
        kb_id=req.kb_id,
        run_id=new_run_id(),
        source=req.source,
        document_id=req.document_id,
        run_type="ingest",
        callback_url=req.callback_url,
    )
    task_id = await registry.submit("ingest", runner.run_ingest(run_req))
    return {
        "task_id": task_id,
        "run_id": run_req.run_id,
        "status": "accepted",
    }


@router.post(
    "/v1/wiki/curate",
    status_code=202,
    dependencies=[Depends(require_token)],
    summary="Dispatch a wiki curate run",
)
async def curate(
    req: CurateRequest,
    registry: TaskRegistry = Depends(get_registry),
    runner: AgentRunner = Depends(get_runner),
) -> dict:
    run_req = RunRequest(
        tenant_id=req.tenant_id,
        kb_id=req.kb_id,
        run_id=new_run_id(),
        source=req.source,
        run_type="curate",
        callback_url=req.callback_url,
    )
    task_id = await registry.submit("curate", runner.run_curate(run_req))
    return {
        "task_id": task_id,
        "run_id": run_req.run_id,
        "status": "accepted",
    }


@router.post(
    "/v1/wiki/lint",
    status_code=202,
    dependencies=[Depends(require_token)],
    summary="Dispatch a wiki lint run",
)
async def lint(
    req: LintRequest,
    registry: TaskRegistry = Depends(get_registry),
    runner: AgentRunner = Depends(get_runner),
) -> dict:
    run_req = RunRequest(
        tenant_id=req.tenant_id,
        kb_id=req.kb_id,
        run_id=new_run_id(),
        source=req.source,
        run_type="lint",
        callback_url=req.callback_url,
    )
    task_id = await registry.submit("lint", runner.run_lint(run_req))
    return {
        "task_id": task_id,
        "run_id": run_req.run_id,
        "status": "accepted",
    }


@router.get(
    "/v1/wiki/tasks/{task_id}",
    dependencies=[Depends(require_token)],
    summary="Poll the status of a wiki agent run",
)
def task_status(
    task_id: str,
    registry: TaskRegistry = Depends(get_registry),
) -> dict:
    snap = registry.get(task_id)
    if snap is None:
        raise HTTPException(404, detail="task not found")
    return snap


# ── Interactive chat (SSE) ────────────────────────────────────


class ChatRequest(BaseModel):
    tenant_id: str = Field(..., min_length=1)
    kb_id: str = Field(..., min_length=1)
    question: str = Field(..., min_length=1)
    history: list[dict[str, str]] = Field(default_factory=list)


@router.post(
    "/v1/wiki/chat",
    dependencies=[Depends(require_token)],
    summary="Interactive wiki chat with SSE streaming",
)
async def chat_stream(
    req: ChatRequest,
    runner: AgentRunner = Depends(get_runner),
) -> StreamingResponse:
    run_req = RunRequest(
        tenant_id=req.tenant_id,
        kb_id=req.kb_id,
        run_id=new_run_id(),
        source="chat",
        run_type="chat",
    )

    async def event_generator():
        async for event in runner.run_chat(run_req, req.question, req.history):
            yield f"data: {_json.dumps(event, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
