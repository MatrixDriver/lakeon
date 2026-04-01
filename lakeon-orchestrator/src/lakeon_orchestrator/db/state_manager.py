from datetime import datetime, timezone
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from lakeon_orchestrator.db.models import PipelineRun, PipelineStepRun, PipelineVersion


class StateManager:
    def __init__(self, session: AsyncSession):
        self._session = session

    async def create_run(
        self,
        run_id: str,
        pipeline_id: str,
        pipeline_version: int,
        tenant_id: str,
        input_dataset_id: Optional[str] = None,
        input_dataset_version: Optional[int] = None,
    ) -> PipelineRun:
        run = PipelineRun(
            id=run_id,
            pipeline_id=pipeline_id,
            pipeline_version=pipeline_version,
            tenant_id=tenant_id,
            input_dataset_id=input_dataset_id,
            input_dataset_version=input_dataset_version,
            status="PENDING",
        )
        self._session.add(run)
        await self._session.flush()
        return run

    async def get_run(self, run_id: str) -> Optional[PipelineRun]:
        result = await self._session.get(PipelineRun, run_id)
        return result

    async def update_run_status(
        self,
        run_id: str,
        status: str,
        output_dataset_version_id: Optional[str] = None,
    ) -> PipelineRun:
        run = await self.get_run(run_id)
        if run is None:
            raise ValueError(f"Run {run_id} not found")
        run.status = status
        now = datetime.now(timezone.utc)
        if status == "RUNNING" and run.started_at is None:
            run.started_at = now
        if status in ("SUCCEEDED", "FAILED", "CANCELLED"):
            run.finished_at = now
        if output_dataset_version_id:
            run.output_dataset_version_id = output_dataset_version_id
        await self._session.flush()
        return run

    async def create_step_run(
        self,
        step_run_id: str,
        run_id: str,
        step_id: str,
        component_id: Optional[str] = None,
        component_version: Optional[int] = None,
    ) -> PipelineStepRun:
        step = PipelineStepRun(
            id=step_run_id,
            run_id=run_id,
            step_id=step_id,
            component_id=component_id,
            component_version=component_version,
            status="PENDING",
        )
        self._session.add(step)
        await self._session.flush()
        return step

    async def update_step_status(
        self,
        step_run_id: str,
        status: str,
        output_ref: Optional[str] = None,
        checkpoint_path: Optional[str] = None,
        metrics: Optional[str] = None,
        error: Optional[str] = None,
    ) -> PipelineStepRun:
        step = await self._session.get(PipelineStepRun, step_run_id)
        if step is None:
            raise ValueError(f"StepRun {step_run_id} not found")
        step.status = status
        now = datetime.now(timezone.utc)
        if status == "RUNNING" and step.started_at is None:
            step.started_at = now
        if status in ("SUCCEEDED", "FAILED", "SKIPPED"):
            step.finished_at = now
        if output_ref is not None:
            step.output_ref = output_ref
        if checkpoint_path is not None:
            step.checkpoint_path = checkpoint_path
        if metrics is not None:
            step.metrics = metrics
        if error is not None:
            step.error = error
        await self._session.flush()
        return step

    async def get_step_runs(self, run_id: str) -> list[PipelineStepRun]:
        result = await self._session.execute(
            select(PipelineStepRun).where(PipelineStepRun.run_id == run_id)
        )
        return list(result.scalars().all())

    async def get_active_runs(self) -> list[PipelineRun]:
        """Get RUNNING and PAUSED runs (for recovery on restart)."""
        result = await self._session.execute(
            select(PipelineRun).where(PipelineRun.status.in_(["RUNNING", "PAUSED"]))
        )
        return list(result.scalars().all())

    async def get_pipeline_version(
        self, pipeline_id: str, version: int
    ) -> Optional[PipelineVersion]:
        result = await self._session.execute(
            select(PipelineVersion).where(
                PipelineVersion.pipeline_id == pipeline_id,
                PipelineVersion.version == version,
            )
        )
        return result.scalar_one_or_none()
