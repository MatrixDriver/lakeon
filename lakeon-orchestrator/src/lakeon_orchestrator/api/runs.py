from __future__ import annotations

import uuid
from typing import Any, Optional

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel

router = APIRouter()


# --- Request / Response models ---

class CreateRunRequest(BaseModel):
    pipeline_id: str
    pipeline_version: int
    tenant_id: str
    run_id: Optional[str] = None  # If provided, reuse existing run record from API
    input_dataset_id: Optional[str] = None
    input_dataset_version: Optional[int] = None


class CreateRunResponse(BaseModel):
    run_id: str
    status: str = "PENDING"


class ResumeRunRequest(BaseModel):
    approved_items: Optional[list[Any]] = None


class RunActionResponse(BaseModel):
    run_id: str
    status: str


# --- Orchestrator dependency (set at startup) ---

_orchestrator = None


def set_orchestrator(orch):
    global _orchestrator
    _orchestrator = orch


def get_orchestrator():
    if _orchestrator is None:
        raise RuntimeError("Orchestrator not initialized")
    return _orchestrator


# --- Endpoints ---

@router.post("", response_model=CreateRunResponse, status_code=202)
async def create_run(req: CreateRunRequest, background_tasks: BackgroundTasks):
    """Trigger a new pipeline run.

    Called by lakeon-api when user clicks "Run Pipeline".
    The actual orchestration runs in the background.
    """
    orch = get_orchestrator()
    run_id = req.run_id or f"run_{uuid.uuid4().hex[:12]}"

    # Start orchestration in background
    background_tasks.add_task(
        orch.start_run,
        run_id=run_id,
        pipeline_id=req.pipeline_id,
        pipeline_version=req.pipeline_version,
        tenant_id=req.tenant_id,
        input_dataset_id=req.input_dataset_id,
        input_dataset_version=req.input_dataset_version,
    )

    return CreateRunResponse(run_id=run_id, status="PENDING")


@router.post("/{run_id}/resume", response_model=RunActionResponse, status_code=202)
async def resume_run(run_id: str, req: ResumeRunRequest, background_tasks: BackgroundTasks):
    """Resume a paused pipeline run after human review.

    Called by lakeon-api when user approves/modifies data in HUMAN_REVIEW step.
    """
    orch = get_orchestrator()

    background_tasks.add_task(
        orch.resume_run,
        run_id=run_id,
        approved_data=req.approved_items,
    )

    return RunActionResponse(run_id=run_id, status="RUNNING")


@router.post("/{run_id}/cancel", response_model=RunActionResponse, status_code=202)
async def cancel_run(run_id: str):
    """Cancel a running or paused pipeline run."""
    orch = get_orchestrator()
    await orch.cancel_run(run_id)
    return RunActionResponse(run_id=run_id, status="CANCELLED")
