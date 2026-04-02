import pytest
from unittest.mock import AsyncMock, MagicMock, patch, PropertyMock
from lakeon_orchestrator.orchestrator import Orchestrator
from lakeon_orchestrator.dag.parser import DAGParser


SIMPLE_YAML = """
name: simple-test
data_type: TEXT
steps:
  - id: step_a
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: step_b
    component: comp_b
    component_version: 1
    inputs: { text: step_a.text }
    outputs: { text: b_out }
"""


@pytest.fixture
def mock_state_manager():
    sm = AsyncMock()
    sm.create_run = AsyncMock()
    sm.update_run_status = AsyncMock()
    sm.create_step_run = AsyncMock()
    sm.update_step_status = AsyncMock()
    sm.get_step_runs = AsyncMock(return_value=[])
    sm.get_active_runs = AsyncMock(return_value=[])

    # Mock get_pipeline_version to return YAML
    version_mock = MagicMock()
    version_mock.dag_yaml = SIMPLE_YAML
    sm.get_pipeline_version = AsyncMock(return_value=version_mock)
    return sm


@pytest.fixture
def mock_ray_client():
    rc = AsyncMock()
    rc.connect = AsyncMock()
    rc.disconnect = AsyncMock()
    rc.is_connected = True
    rc.submit_pipeline_step = AsyncMock(return_value="pl-run-001-step-a")
    rc.wait_for_completion = AsyncMock(return_value={"status": "SUCCEEDED", "ray_job_name": "pl-run-001-step-a", "message": ""})
    rc.delete_job = AsyncMock()
    return rc


@pytest.fixture
def mock_checkpoint_mgr():
    cm = AsyncMock()
    cm.save = AsyncMock(return_value="obs://bucket/ckpt/path")
    cm.load = AsyncMock(return_value={"data": "restored"})
    return cm


@pytest.fixture
def mock_component_loader():
    loader = MagicMock()

    def mock_component(ctx):
        return {"text": "processed"}

    loader.load = MagicMock(return_value=mock_component)
    return loader


@pytest.fixture
def mock_session_factory():
    factory = MagicMock()
    session = AsyncMock()
    session.__aenter__ = AsyncMock(return_value=session)
    session.__aexit__ = AsyncMock(return_value=False)
    session.commit = AsyncMock()
    factory.return_value = session
    return factory


@pytest.fixture
def mock_python_client():
    pc = AsyncMock()
    pc.connect = AsyncMock()
    pc.disconnect = AsyncMock()
    pc.is_connected = True
    pc.submit_pipeline_step = AsyncMock(return_value="pl-py-run-001-step-a")
    pc.wait_for_completion = AsyncMock(return_value={"status": "SUCCEEDED", "job_name": "pl-py-run-001-step-a", "message": ""})
    pc.delete_job = AsyncMock()
    return pc


@pytest.fixture
def orchestrator(
    mock_state_manager,
    mock_ray_client,
    mock_checkpoint_mgr,
    mock_component_loader,
    mock_session_factory,
    mock_python_client,
):
    return Orchestrator(
        session_factory=mock_session_factory,
        ray_client=mock_ray_client,
        checkpoint_manager=mock_checkpoint_mgr,
        component_loader=mock_component_loader,
        python_job_client=mock_python_client,
        _state_manager_override=mock_state_manager,
    )


@pytest.mark.asyncio
async def test_start_run_creates_run_and_steps(orchestrator, mock_state_manager):
    await orchestrator.start_run(
        run_id="run_001",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Should create the run
    mock_state_manager.create_run.assert_awaited_once()
    # Should create step runs for each DAG node
    assert mock_state_manager.create_step_run.await_count == 2


@pytest.mark.asyncio
async def test_start_run_connects_ray(orchestrator, mock_ray_client):
    await orchestrator.start_run(
        run_id="run_002",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    mock_ray_client.connect.assert_called_once()


@pytest.mark.asyncio
async def test_start_run_updates_status_to_running(orchestrator, mock_state_manager):
    await orchestrator.start_run(
        run_id="run_003",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Should transition to RUNNING
    calls = mock_state_manager.update_run_status.await_args_list
    statuses = [c.args[1] for c in calls]
    assert "RUNNING" in statuses


@pytest.mark.asyncio
async def test_cancel_run(orchestrator, mock_state_manager):
    await orchestrator.cancel_run("run_004")
    mock_state_manager.update_run_status.assert_awaited_with("run_004", "CANCELLED")


@pytest.mark.asyncio
async def test_start_run_disconnects_ray_on_completion(orchestrator, mock_ray_client, mock_python_client):
    await orchestrator.start_run(
        run_id="run_005",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    mock_ray_client.disconnect.assert_called()
    mock_python_client.disconnect.assert_called()


@pytest.mark.asyncio
async def test_python_engine_uses_python_client(
    mock_state_manager, mock_ray_client, mock_checkpoint_mgr,
    mock_component_loader, mock_session_factory, mock_python_client,
):
    """When default engine is python, PythonJobClient should be used."""
    # The default SIMPLE_YAML has no execution_engine, so defaults to python
    orch = Orchestrator(
        session_factory=mock_session_factory,
        ray_client=mock_ray_client,
        checkpoint_manager=mock_checkpoint_mgr,
        component_loader=mock_component_loader,
        python_job_client=mock_python_client,
        _state_manager_override=mock_state_manager,
    )
    await orch.start_run(
        run_id="run_py",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Python client should have been called for submission (default engine = python)
    assert mock_python_client.submit_pipeline_step.await_count >= 1
    # Ray client should NOT have been called for submission
    assert mock_ray_client.submit_pipeline_step.await_count == 0


RAY_ENGINE_YAML = """
name: ray-test
data_type: TEXT
execution_engine: ray
steps:
  - id: step_a
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: step_b
    component: comp_b
    component_version: 1
    inputs: { text: step_a.text }
    outputs: { text: b_out }
"""


@pytest.mark.asyncio
async def test_ray_engine_uses_ray_client(
    mock_state_manager, mock_ray_client, mock_checkpoint_mgr,
    mock_component_loader, mock_session_factory, mock_python_client,
):
    """When pipeline sets execution_engine: ray, RayClient should be used."""
    version_mock = MagicMock()
    version_mock.dag_yaml = RAY_ENGINE_YAML
    mock_state_manager.get_pipeline_version = AsyncMock(return_value=version_mock)

    orch = Orchestrator(
        session_factory=mock_session_factory,
        ray_client=mock_ray_client,
        checkpoint_manager=mock_checkpoint_mgr,
        component_loader=mock_component_loader,
        python_job_client=mock_python_client,
        _state_manager_override=mock_state_manager,
    )
    await orch.start_run(
        run_id="run_ray",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Ray client should have been called
    assert mock_ray_client.submit_pipeline_step.await_count >= 1
    # Python client should NOT have been called
    assert mock_python_client.submit_pipeline_step.await_count == 0


MIXED_ENGINE_YAML = """
name: mixed-test
data_type: TEXT
execution_engine: python
steps:
  - id: step_a
    component: comp_a
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: a_out }
  - id: step_b
    component: comp_b
    component_version: 1
    execution_engine: ray
    inputs: { text: step_a.text }
    outputs: { text: b_out }
"""


@pytest.mark.asyncio
async def test_mixed_engine_dispatch(
    mock_state_manager, mock_ray_client, mock_checkpoint_mgr,
    mock_component_loader, mock_session_factory, mock_python_client,
):
    """step_a uses python (default), step_b overrides to ray."""
    version_mock = MagicMock()
    version_mock.dag_yaml = MIXED_ENGINE_YAML
    mock_state_manager.get_pipeline_version = AsyncMock(return_value=version_mock)

    orch = Orchestrator(
        session_factory=mock_session_factory,
        ray_client=mock_ray_client,
        checkpoint_manager=mock_checkpoint_mgr,
        component_loader=mock_component_loader,
        python_job_client=mock_python_client,
        _state_manager_override=mock_state_manager,
    )
    await orch.start_run(
        run_id="run_mix",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    # Python client called once (step_a), Ray client called once (step_b)
    assert mock_python_client.submit_pipeline_step.await_count == 1
    assert mock_ray_client.submit_pipeline_step.await_count == 1
