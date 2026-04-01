import pytest
from datetime import datetime, timezone

from lakeon_orchestrator.db.state_manager import StateManager
from lakeon_orchestrator.db.models import PipelineRun, PipelineStepRun


@pytest.mark.asyncio
async def test_create_run(db_session):
    sm = StateManager(db_session)
    run = await sm.create_run(
        run_id="run_test001",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
        input_dataset_id="ds_input",
        input_dataset_version=1,
    )
    assert run.id == "run_test001"
    assert run.status == "PENDING"


@pytest.mark.asyncio
async def test_update_run_status(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test002",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    updated = await sm.update_run_status("run_test002", "RUNNING")
    assert updated.status == "RUNNING"
    assert updated.started_at is not None


@pytest.mark.asyncio
async def test_create_step_run(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test003",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    step = await sm.create_step_run(
        step_run_id="sr_test001",
        run_id="run_test003",
        step_id="normalize",
        component_id="comp_normalize",
        component_version=1,
    )
    assert step.id == "sr_test001"
    assert step.status == "PENDING"


@pytest.mark.asyncio
async def test_update_step_status(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test004",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    await sm.create_step_run(
        step_run_id="sr_test002",
        run_id="run_test004",
        step_id="normalize",
    )
    updated = await sm.update_step_status(
        "sr_test002", "SUCCEEDED", output_ref='{"video": "ref_123"}', metrics='{"input_count": 1}'
    )
    assert updated.status == "SUCCEEDED"
    assert updated.finished_at is not None


@pytest.mark.asyncio
async def test_get_step_runs_by_run_id(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_test005",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    await sm.create_step_run(step_run_id="sr_a", run_id="run_test005", step_id="step_a")
    await sm.create_step_run(step_run_id="sr_b", run_id="run_test005", step_id="step_b")
    steps = await sm.get_step_runs("run_test005")
    assert len(steps) == 2


@pytest.mark.asyncio
async def test_get_active_runs(db_session):
    sm = StateManager(db_session)
    await sm.create_run(
        run_id="run_active1", pipeline_id="pipe_abc", pipeline_version=1, tenant_id="tn_test"
    )
    await sm.update_run_status("run_active1", "RUNNING")
    await sm.create_run(
        run_id="run_done1", pipeline_id="pipe_abc", pipeline_version=1, tenant_id="tn_test"
    )
    await sm.update_run_status("run_done1", "SUCCEEDED")
    active = await sm.get_active_runs()
    assert len(active) == 1
    assert active[0].id == "run_active1"
