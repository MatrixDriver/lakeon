import pytest
import pickle
from unittest.mock import AsyncMock, MagicMock, patch
from lakeon_orchestrator.checkpoint.manager import CheckpointManager


@pytest.fixture
def mock_obs_client():
    client = MagicMock()
    client.put_object = MagicMock()
    client.get_object = MagicMock()
    return client


@pytest.fixture
def ckpt_mgr(mock_obs_client):
    return CheckpointManager(
        obs_client=mock_obs_client,
        bucket="lakeon-data",
        prefix="checkpoints",
    )


@pytest.mark.asyncio
async def test_save_checkpoint(ckpt_mgr, mock_obs_client):
    data = {"clips": ["a.mp4", "b.mp4"], "metrics": {"count": 2}}
    path = await ckpt_mgr.save(run_id="run_001", step_id="scene_split", data=data)

    assert path == "obs://lakeon-data/checkpoints/run_001/scene_split/checkpoint.pkl"
    mock_obs_client.put_object.assert_called_once()
    call_args = mock_obs_client.put_object.call_args
    assert call_args[1]["Bucket"] == "lakeon-data"
    assert "run_001/scene_split/checkpoint.pkl" in call_args[1]["Key"]


@pytest.mark.asyncio
async def test_load_checkpoint(ckpt_mgr, mock_obs_client):
    original_data = {"clips": ["a.mp4", "b.mp4"]}
    pickled = pickle.dumps(original_data)

    body_mock = MagicMock()
    body_mock.read.return_value = pickled
    mock_obs_client.get_object.return_value = {"Body": body_mock}

    loaded = await ckpt_mgr.load(
        "obs://lakeon-data/checkpoints/run_001/scene_split/checkpoint.pkl"
    )
    assert loaded == original_data


@pytest.mark.asyncio
async def test_save_generates_correct_path(ckpt_mgr):
    path = await ckpt_mgr.save(run_id="run_abc", step_id="normalize", data={"v": 1})
    assert "run_abc" in path
    assert "normalize" in path


def test_parse_obs_path(ckpt_mgr):
    bucket, key = ckpt_mgr.parse_obs_path(
        "obs://lakeon-data/checkpoints/run_001/step/checkpoint.pkl"
    )
    assert bucket == "lakeon-data"
    assert key == "checkpoints/run_001/step/checkpoint.pkl"
