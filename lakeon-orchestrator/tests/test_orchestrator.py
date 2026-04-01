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
    rc = MagicMock()
    rc.connect = MagicMock()
    rc.disconnect = MagicMock()
    rc.is_connected = True
    rc.submit_task = MagicMock(return_value="obj_ref_result")
    rc.get_result = MagicMock(return_value={"text": "processed"})
    rc.put_object = MagicMock(return_value="obj_ref_input")
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
def orchestrator(
    mock_state_manager,
    mock_ray_client,
    mock_checkpoint_mgr,
    mock_component_loader,
    mock_session_factory,
):
    return Orchestrator(
        session_factory=mock_session_factory,
        ray_client=mock_ray_client,
        checkpoint_manager=mock_checkpoint_mgr,
        component_loader=mock_component_loader,
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
async def test_start_run_disconnects_ray_on_completion(orchestrator, mock_ray_client):
    await orchestrator.start_run(
        run_id="run_005",
        pipeline_id="pipe_abc",
        pipeline_version=1,
        tenant_id="tn_test",
    )
    mock_ray_client.disconnect.assert_called()
