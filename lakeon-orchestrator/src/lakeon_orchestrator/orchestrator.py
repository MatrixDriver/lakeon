from __future__ import annotations

import json
import logging
from typing import Any, Optional

from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.loader import ComponentLoader
from lakeon_orchestrator.dag.parser import DAGParser, DAG, DAGNode
from lakeon_orchestrator.dag.scheduler import DAGScheduler
from lakeon_orchestrator.dag.fan_out_handler import FanOutHandler
from lakeon_orchestrator.dag.fan_in_handler import FanInHandler
from lakeon_orchestrator.dag.branch_router import BranchRouter
from lakeon_orchestrator.db.state_manager import StateManager
from lakeon_orchestrator.ray_client.client import RayClient
from lakeon_orchestrator.ray_client.python_job_client import PythonJobClient

logger = logging.getLogger(__name__)


class Orchestrator:
    """Main orchestration engine -- the heart of the Pipeline Orchestrator.

    Orchestration loop:
    1. Parse DAG from pipeline version YAML
    2. Create step_runs for all DAG nodes
    3. Initialize K8s API client (for RayJob submission)
    4. Loop:
       a. Determine ready steps (all parents SUCCEEDED/SKIPPED)
       b. For each ready step: submit a RayJob CRD via K8s API
       c. Wait for RayJob completion
       d. Handle fan-out (expand), fan-in (merge), branch (route)
       e. Handle HUMAN_REVIEW (pause + checkpoint)
       f. Update step/run status in RDS
    5. On completion/failure: disconnect, update final status
    """

    def __init__(
        self,
        session_factory: async_sessionmaker[AsyncSession],
        ray_client: RayClient,
        checkpoint_manager,
        component_loader: Optional[ComponentLoader] = None,
        python_job_client: Optional[PythonJobClient] = None,
        _state_manager_override=None,
    ):
        self._session_factory = session_factory
        self._ray = ray_client
        self._python = python_job_client
        self._checkpoint = checkpoint_manager
        self._loader = component_loader or ComponentLoader()
        self._fan_out = FanOutHandler()
        self._fan_in = FanInHandler()
        self._state_override = _state_manager_override

        # Runtime state per run (keyed by run_id)
        self._run_data: dict[str, dict[str, Any]] = {}

    def _get_engine_client(self, execution_engine: str):
        """Return the appropriate job client based on execution engine."""
        if execution_engine == "ray":
            return self._ray
        if self._python is None:
            logger.warning("PythonJobClient not configured, falling back to RayClient")
            return self._ray
        return self._python

    def _get_state_manager(self, session: AsyncSession) -> StateManager:
        if self._state_override:
            return self._state_override
        return StateManager(session)

    async def start_run(
        self,
        run_id: str,
        pipeline_id: str,
        pipeline_version: int,
        tenant_id: str,
        input_dataset_id: Optional[str] = None,
        input_dataset_version: Optional[int] = None,
    ) -> None:
        """Entry point: start a new pipeline run."""
        logger.info(f"Starting pipeline run {run_id} for pipeline {pipeline_id} v{pipeline_version}")

        async with self._session_factory() as session:
            sm = self._get_state_manager(session)

            # 1. Create run record
            await sm.create_run(
                run_id=run_id,
                pipeline_id=pipeline_id,
                pipeline_version=pipeline_version,
                tenant_id=tenant_id,
                input_dataset_id=input_dataset_id,
                input_dataset_version=input_dataset_version,
            )

            # 2. Load and parse DAG
            pv = await sm.get_pipeline_version(pipeline_id, pipeline_version)
            if pv is None:
                await sm.update_run_status(run_id, "FAILED")
                logger.error(f"Pipeline version not found: {pipeline_id} v{pipeline_version}")
                return

            dag = DAGParser.parse(pv.dag_yaml)
            scheduler = DAGScheduler(dag)
            branch_router = BranchRouter(dag)

            # Validate DAG (cycle detection)
            scheduler.topological_sort()

            # 3. Create step_runs for all nodes
            step_statuses: dict[str, str] = {}
            step_run_ids: dict[str, str] = {}
            for node_id, node in dag.nodes.items():
                sr_id = f"sr_{run_id}_{node_id}"
                await sm.create_step_run(
                    step_run_id=sr_id,
                    run_id=run_id,
                    step_id=node_id,
                    component_id=node.component,
                    component_version=node.component_version,
                )
                step_statuses[node_id] = "PENDING"
                step_run_ids[node_id] = sr_id

            # 4. Connect K8s API clients
            await self._ray.connect()
            if self._python:
                await self._python.connect()
            await sm.update_run_status(run_id, "RUNNING")

            # Initialize data store for this run
            output_refs: dict[str, Any] = {}
            if input_dataset_id:
                output_refs["$input"] = {"dataset": input_dataset_id}

            try:
                await self._run_loop(
                    run_id=run_id,
                    tenant_id=tenant_id,
                    dag=dag,
                    scheduler=scheduler,
                    branch_router=branch_router,
                    sm=sm,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )
            except Exception as e:
                logger.exception(f"Pipeline run {run_id} failed: {e}")
                await sm.update_run_status(run_id, "FAILED")
            finally:
                await self._ray.disconnect()
                if self._python:
                    await self._python.disconnect()
                await session.commit()

    async def _run_loop(
        self,
        run_id: str,
        tenant_id: str,
        dag: DAG,
        scheduler: DAGScheduler,
        branch_router: BranchRouter,
        sm: StateManager,
        step_statuses: dict[str, str],
        step_run_ids: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Core scheduling loop: find ready steps, execute, repeat."""
        max_iterations = len(dag.nodes) * 10  # safety bound for fan-out expansion
        iteration = 0

        while iteration < max_iterations:
            iteration += 1

            # Check termination conditions
            if scheduler.is_complete(step_statuses):
                await sm.update_run_status(run_id, "SUCCEEDED")
                logger.info(f"Pipeline run {run_id} completed successfully")
                return

            if scheduler.has_failed(step_statuses):
                await sm.update_run_status(run_id, "FAILED")
                logger.info(f"Pipeline run {run_id} failed")
                return

            if scheduler.has_paused(step_statuses):
                # PAUSED -- exit loop, will resume via resume_run()
                logger.info(f"Pipeline run {run_id} paused for human review")
                return

            # Find ready steps
            ready = scheduler.get_ready_steps(step_statuses)
            if not ready:
                logger.warning(f"No ready steps but pipeline not complete. Statuses: {step_statuses}")
                await sm.update_run_status(run_id, "FAILED")
                return

            # Execute ready steps (could be parallel)
            for step_id in ready:
                node = dag.nodes[step_id]
                sr_id = step_run_ids[step_id]

                # Handle merge nodes
                if node.node_type == "merge":
                    await self._execute_merge(
                        node=node,
                        sr_id=sr_id,
                        sm=sm,
                        step_statuses=step_statuses,
                        output_refs=output_refs,
                    )
                    continue

                # Handle HUMAN_REVIEW
                if node.execution_mode == "HUMAN_REVIEW":
                    await self._execute_pause(
                        node=node,
                        sr_id=sr_id,
                        run_id=run_id,
                        sm=sm,
                        step_statuses=step_statuses,
                        output_refs=output_refs,
                    )
                    continue

                # Execute component step via RayJob CRD
                await self._execute_step(
                    node=node,
                    sr_id=sr_id,
                    run_id=run_id,
                    tenant_id=tenant_id,
                    dag=dag,
                    sm=sm,
                    branch_router=branch_router,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )

        logger.error(f"Pipeline run {run_id} exceeded max iterations")
        await sm.update_run_status(run_id, "FAILED")

    async def _execute_step(
        self,
        node: DAGNode,
        sr_id: str,
        run_id: str,
        tenant_id: str,
        dag: DAG,
        sm: StateManager,
        branch_router: BranchRouter,
        step_statuses: dict[str, str],
        step_run_ids: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Execute a single component step via the configured execution engine."""
        engine = node.execution_engine
        engine_client = self._get_engine_client(engine)
        logger.info(f"Executing step {node.id} (component: {node.component}, engine: {engine})")
        await sm.update_step_status(sr_id, "RUNNING")
        step_statuses[node.id] = "RUNNING"

        try:
            # Resolve input data from upstream outputs
            input_data = self._resolve_inputs(node, output_refs)

            # Submit via the selected engine client
            job_name = await engine_client.submit_pipeline_step(
                run_id=run_id,
                step_id=node.id,
                component_entrypoint=f"placeholder.{node.component}",
                params=node.params,
                input_data=input_data,
                tenant_id=tenant_id,
            )

            # Wait for the job to complete (positional args for cross-client compat)
            ns = f"datalake-tn-{tenant_id}"
            result = await engine_client.wait_for_completion(
                job_name, namespace=ns,
            )

            job_status = result["status"]
            if job_status != "SUCCEEDED":
                raise RuntimeError(
                    f"Job {job_name} ({engine}) finished with status {job_status}: {result.get('message', '')}"
                )

            # The component writes its output to OBS; store a reference
            result_data = {
                "job_name": job_name,
                "obs_output_key": f"pipeline-runs/{run_id}/{node.id}/output.json",
            }

            # Handle fan-out
            if self._fan_out.is_fan_out(result_data):
                await self._handle_fan_out(
                    node=node,
                    result=result_data,
                    run_id=run_id,
                    dag=dag,
                    sm=sm,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )
            # Handle branch classification
            elif branch_router.is_branch_result(result_data):
                branch_label = result_data["__branch__"]
                output_refs[node.id] = result_data
                targets = branch_router.route(node.id, result_data)
                logger.info(f"Step {node.id} classified as '{branch_label}', routing to {targets}")
            else:
                output_refs[node.id] = result_data

            # Checkpoint if configured
            if node.checkpoint:
                await self._checkpoint.save(
                    run_id=run_id, step_id=node.id, data=result_data
                )

            # Update metrics
            await sm.update_step_status(
                sr_id, "SUCCEEDED",
                output_ref=json.dumps({"job_name": job_name, "engine": engine}),
            )
            step_statuses[node.id] = "SUCCEEDED"
            logger.info(f"Step {node.id} succeeded (engine: {engine}, job: {job_name})")

            # Clean up Job (ttlSecondsAfterFinished also handles this)
            try:
                await engine_client.delete_job(job_name, namespace=ns)
            except Exception:
                logger.warning(f"Failed to delete job {job_name}", exc_info=True)

        except Exception as e:
            logger.exception(f"Step {node.id} failed: {e}")
            await sm.update_step_status(sr_id, "FAILED", error=str(e))
            step_statuses[node.id] = "FAILED"

    async def _execute_merge(
        self,
        node: DAGNode,
        sr_id: str,
        sm: StateManager,
        step_statuses: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Execute a merge (fan-in) node."""
        logger.info(f"Executing merge step {node.id}")
        await sm.update_step_status(sr_id, "RUNNING")
        step_statuses[node.id] = "RUNNING"

        # Collect results from input references
        input_refs = node.inputs if isinstance(node.inputs, list) else list(node.inputs.values())
        branch_results = []
        for ref in input_refs:
            if isinstance(ref, str) and "." in ref:
                source_id = ref.split(".")[0]
                data = output_refs.get(source_id)
                if data is not None:
                    branch_results.append({"data": data, "source": source_id})

        merged = self._fan_in.merge(branch_results)
        output_refs[node.id] = merged

        await sm.update_step_status(
            sr_id, "SUCCEEDED",
            metrics=json.dumps({"merged_count": merged["count"]}),
        )
        step_statuses[node.id] = "SUCCEEDED"
        logger.info(f"Merge step {node.id} completed with {merged['count']} items")

    async def _execute_pause(
        self,
        node: DAGNode,
        sr_id: str,
        run_id: str,
        sm: StateManager,
        step_statuses: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Pause for HUMAN_REVIEW -- checkpoint data and wait."""
        logger.info(f"Pausing step {node.id} for HUMAN_REVIEW")

        # Resolve input
        input_data = self._resolve_inputs(node, output_refs)

        # Save checkpoint
        checkpoint_path = await self._checkpoint.save(
            run_id=run_id, step_id=node.id, data=input_data
        )

        await sm.update_step_status(sr_id, "PAUSED", checkpoint_path=checkpoint_path)
        step_statuses[node.id] = "PAUSED"

        await sm.update_run_status(run_id, "PAUSED")
        await self._ray.disconnect()
        if self._python:
            await self._python.disconnect()

    async def _handle_fan_out(
        self,
        node: DAGNode,
        result: dict[str, Any],
        run_id: str,
        dag: DAG,
        sm: StateManager,
        step_statuses: dict[str, str],
        step_run_ids: dict[str, str],
        output_refs: dict[str, Any],
    ) -> None:
        """Handle fan-out: create expanded step_runs for downstream."""
        children = dag.get_children(node.id)
        items = result.get("items", [])
        logger.info(f"Fan-out from {node.id}: {len(items)} items -> {children}")

        output_refs[node.id] = result

        # For each downstream step, the Orchestrator will process items in parallel
        # The actual fan-out expansion happens when downstream steps execute

    def _resolve_inputs(
        self, node: DAGNode, output_refs: dict[str, Any]
    ) -> dict[str, Any]:
        """Resolve a node's input references to actual data."""
        if isinstance(node.inputs, list):
            # Merge node with list inputs
            resolved = []
            for ref in node.inputs:
                if isinstance(ref, str) and "." in ref:
                    source_id, _, key = ref.partition(".")
                    data = output_refs.get(source_id, {})
                    if isinstance(data, dict):
                        resolved.append(data.get(key, data))
                    else:
                        resolved.append(data)
            return {"items": resolved}

        resolved = {}
        for key, ref in node.inputs.items():
            if isinstance(ref, str) and ref.startswith("$"):
                # Pipeline-level input (e.g. "$input.dataset")
                parts = ref[1:].split(".")
                data = output_refs
                for p in parts:
                    data = data.get(p, {}) if isinstance(data, dict) else {}
                resolved[key] = data
            elif isinstance(ref, str) and "." in ref:
                # Upstream reference (e.g. "normalize.video")
                source_id, _, output_key = ref.partition(".")
                source_data = output_refs.get(source_id, {})
                if isinstance(source_data, dict):
                    resolved[key] = source_data.get(output_key, source_data)
                else:
                    resolved[key] = source_data
            else:
                resolved[key] = ref
        return resolved

    async def resume_run(self, run_id: str, approved_data: Any = None) -> None:
        """Resume a paused run after human review."""
        logger.info(f"Resuming pipeline run {run_id}")

        async with self._session_factory() as session:
            sm = self._get_state_manager(session)

            run = await sm.get_run(run_id)
            if run is None:
                raise ValueError(f"Run {run_id} not found")
            if run.status != "PAUSED":
                raise ValueError(f"Run {run_id} is not paused (status: {run.status})")

            # Find paused step
            step_runs = await sm.get_step_runs(run_id)
            paused_step = next((sr for sr in step_runs if sr.status == "PAUSED"), None)
            if paused_step is None:
                raise ValueError(f"No paused step found in run {run_id}")

            # Reconnect K8s API clients
            await self._ray.connect()
            if self._python:
                await self._python.connect()

            # Load checkpoint or use approved data
            if approved_data is not None:
                data = approved_data
            elif paused_step.checkpoint_path:
                data = await self._checkpoint.load(paused_step.checkpoint_path)
            else:
                raise ValueError("No checkpoint or approved data available")

            # Update statuses
            await sm.update_step_status(paused_step.id, "SUCCEEDED")
            await sm.update_run_status(run_id, "RUNNING")

            # Reload DAG and continue
            pv = await sm.get_pipeline_version(run.pipeline_id, run.pipeline_version)
            dag = DAGParser.parse(pv.dag_yaml)
            scheduler = DAGScheduler(dag)
            branch_router = BranchRouter(dag)

            # Rebuild step statuses from DB
            step_statuses = {}
            step_run_ids = {}
            for sr in step_runs:
                step_statuses[sr.step_id] = sr.status
                step_run_ids[sr.step_id] = sr.id

            # Mark the paused step as succeeded (data approved)
            step_statuses[paused_step.step_id] = "SUCCEEDED"

            # Rebuild output refs (simplified -- in production, load from checkpoints)
            output_refs = {paused_step.step_id: data}

            try:
                await self._run_loop(
                    run_id=run_id,
                    tenant_id=run.tenant_id if hasattr(run, "tenant_id") else "default",
                    dag=dag,
                    scheduler=scheduler,
                    branch_router=branch_router,
                    sm=sm,
                    step_statuses=step_statuses,
                    step_run_ids=step_run_ids,
                    output_refs=output_refs,
                )
            except Exception as e:
                logger.exception(f"Pipeline run {run_id} failed after resume: {e}")
                await sm.update_run_status(run_id, "FAILED")
            finally:
                await self._ray.disconnect()
                if self._python:
                    await self._python.disconnect()
                await session.commit()

    async def cancel_run(self, run_id: str) -> None:
        """Cancel a running or paused pipeline run."""
        logger.info(f"Cancelling pipeline run {run_id}")
        async with self._session_factory() as session:
            sm = self._get_state_manager(session)
            await sm.update_run_status(run_id, "CANCELLED")
            await session.commit()
        await self._ray.disconnect()
        if self._python:
            await self._python.disconnect()
