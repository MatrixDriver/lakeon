import pytest
from unittest.mock import AsyncMock, MagicMock
from lakeon_orchestrator.pause.manager import PauseManager


@pytest.fixture
def checkpoint_mgr():
    mgr = MagicMock()
    mgr.save = AsyncMock(return_value="obs://bucket/checkpoints/run_001/qc/data.pkl")
    mgr.load = AsyncMock(return_value={"clips": ["a.mp4", "b.mp4"]})
    return mgr


@pytest.fixture
def state_mgr():
    mgr = MagicMock()
    mgr.update_step_status = AsyncMock()
    mgr.update_run_status = AsyncMock()
    return mgr


@pytest.fixture
def ray_client():
    client = MagicMock()
    client.get_result.return_value = {"clips": ["a.mp4", "b.mp4"]}
    client.disconnect = MagicMock()
    client.connect = MagicMock()
    client.put_object.return_value = "obj_ref_123"
    return client


@pytest.mark.asyncio
async def test_pause_step(checkpoint_mgr, state_mgr, ray_client):
    pm = PauseManager(
        checkpoint_manager=checkpoint_mgr,
        state_manager=state_mgr,
        ray_client=ray_client,
    )
    await pm.pause_step(
        run_id="run_001",
        step_run_id="sr_qc",
        step_id="qc",
        data_ref="ray_obj_ref",
    )
    # Should save checkpoint
    checkpoint_mgr.save.assert_awaited_once()
    # Should update step status to PAUSED
    state_mgr.update_step_status.assert_awaited_with(
        "sr_qc", "PAUSED",
        checkpoint_path="obs://bucket/checkpoints/run_001/qc/data.pkl",
    )
    # Should update run status to PAUSED
    state_mgr.update_run_status.assert_awaited_with("run_001", "PAUSED")
    # Should disconnect Ray (release cluster)
    ray_client.disconnect.assert_called_once()


@pytest.mark.asyncio
async def test_resume_step(checkpoint_mgr, state_mgr, ray_client):
    pm = PauseManager(
        checkpoint_manager=checkpoint_mgr,
        state_manager=state_mgr,
        ray_client=ray_client,
    )
    data_ref = await pm.resume_step(
        run_id="run_001",
        step_run_id="sr_qc",
        step_id="qc",
        checkpoint_path="obs://bucket/checkpoints/run_001/qc/data.pkl",
    )
    # Should reconnect Ray
    ray_client.connect.assert_called_once()
    # Should load checkpoint from OBS
    checkpoint_mgr.load.assert_awaited_once_with(
        "obs://bucket/checkpoints/run_001/qc/data.pkl"
    )
    # Should put data back into Ray object store
    ray_client.put_object.assert_called_once_with({"clips": ["a.mp4", "b.mp4"]})
    assert data_ref == "obj_ref_123"
    # Should update step status to RUNNING
    state_mgr.update_step_status.assert_awaited_with("sr_qc", "RUNNING")
    # Should update run status to RUNNING
    state_mgr.update_run_status.assert_awaited_with("run_001", "RUNNING")
