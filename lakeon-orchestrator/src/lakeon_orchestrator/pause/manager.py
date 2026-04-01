from __future__ import annotations

import logging
from typing import Any, Optional

logger = logging.getLogger(__name__)


class PauseManager:
    """Manages HUMAN_REVIEW pause/resume lifecycle.

    On pause:
    1. Materialize data from Ray object store to OBS checkpoint
    2. Update step status to PAUSED
    3. Update run status to PAUSED
    4. Disconnect Ray cluster (release resources)

    On resume:
    1. Reconnect Ray cluster
    2. Load checkpoint from OBS
    3. Put data back into Ray object store
    4. Update step/run status to RUNNING
    5. Continue orchestration from the paused step
    """

    def __init__(self, checkpoint_manager, state_manager, ray_client):
        self._checkpoint = checkpoint_manager
        self._state = state_manager
        self._ray = ray_client

    async def pause_step(
        self,
        run_id: str,
        step_run_id: str,
        step_id: str,
        data_ref: Any,
    ) -> None:
        """Pause a step for human review.

        Args:
            run_id: Pipeline run ID.
            step_run_id: Step run ID.
            step_id: DAG step ID.
            data_ref: Ray ObjectRef or data to checkpoint.
        """
        logger.info(f"Pausing step {step_id} in run {run_id} for HUMAN_REVIEW")

        # Materialize data from Ray and save to OBS
        if hasattr(data_ref, '__class__') and 'ObjectRef' in type(data_ref).__name__:
            data = self._ray.get_result(data_ref)
        else:
            data = data_ref

        checkpoint_path = await self._checkpoint.save(
            run_id=run_id,
            step_id=step_id,
            data=data,
        )

        # Update state
        await self._state.update_step_status(
            step_run_id, "PAUSED", checkpoint_path=checkpoint_path
        )
        await self._state.update_run_status(run_id, "PAUSED")

        # Release Ray cluster
        self._ray.disconnect()
        logger.info(f"Step {step_id} paused. Checkpoint: {checkpoint_path}")

    async def resume_step(
        self,
        run_id: str,
        step_run_id: str,
        step_id: str,
        checkpoint_path: str,
        approved_data: Optional[Any] = None,
    ) -> Any:
        """Resume a paused step after human review.

        Args:
            run_id: Pipeline run ID.
            step_run_id: Step run ID.
            step_id: DAG step ID.
            checkpoint_path: OBS path to saved checkpoint.
            approved_data: Optional filtered/approved data from human review.
                          If None, loads full checkpoint.

        Returns:
            Ray ObjectRef for the resumed data.
        """
        logger.info(f"Resuming step {step_id} in run {run_id}")

        # Reconnect Ray cluster
        self._ray.connect()

        # Load data: use approved_data if provided, otherwise load checkpoint
        if approved_data is not None:
            data = approved_data
        else:
            data = await self._checkpoint.load(checkpoint_path)

        # Put data back into Ray object store
        data_ref = self._ray.put_object(data)

        # Update state
        await self._state.update_step_status(step_run_id, "RUNNING")
        await self._state.update_run_status(run_id, "RUNNING")

        logger.info(f"Step {step_id} resumed with data ref {data_ref}")
        return data_ref
