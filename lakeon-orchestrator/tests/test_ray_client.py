import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from lakeon_orchestrator.ray_client.client import RayClient


@pytest.fixture
def ray_client():
    return RayClient(address="local", namespace="test")


def test_ray_client_init(ray_client):
    assert ray_client._address == "local"
    assert ray_client._namespace == "test"
    assert ray_client._connected is False


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_connect(mock_ray, ray_client):
    mock_ray.is_initialized.return_value = False
    ray_client.connect()
    mock_ray.init.assert_called_once_with(
        address="local", namespace="test", ignore_reinit_error=True
    )
    assert ray_client._connected is True


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_connect_already_initialized(mock_ray, ray_client):
    mock_ray.is_initialized.return_value = True
    ray_client.connect()
    mock_ray.init.assert_not_called()
    assert ray_client._connected is True


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_disconnect(mock_ray, ray_client):
    ray_client._connected = True
    ray_client.disconnect()
    mock_ray.shutdown.assert_called_once()
    assert ray_client._connected is False


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_submit_task(mock_ray, ray_client):
    ray_client._connected = True

    mock_func = MagicMock()
    mock_remote = MagicMock()
    mock_ref = MagicMock()
    mock_remote.remote.return_value = mock_ref
    mock_ray.remote.return_value = mock_remote

    ref = ray_client.submit_task(mock_func, "arg1", key="val1")
    mock_ray.remote.assert_called_once_with(mock_func)
    mock_remote.remote.assert_called_once_with("arg1", key="val1")
    assert ref == mock_ref


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_submit_task_not_connected(mock_ray, ray_client):
    ray_client._connected = False
    with pytest.raises(RuntimeError, match="Not connected"):
        ray_client.submit_task(lambda: None)


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_get_result(mock_ray, ray_client):
    ray_client._connected = True
    mock_ref = MagicMock()
    mock_ray.get.return_value = {"result": "ok"}
    result = ray_client.get_result(mock_ref, timeout=30)
    mock_ray.get.assert_called_once_with(mock_ref, timeout=30)
    assert result == {"result": "ok"}


@patch("lakeon_orchestrator.ray_client.client.ray")
def test_put_object(mock_ray, ray_client):
    ray_client._connected = True
    mock_ray.put.return_value = "obj_ref_123"
    ref = ray_client.put_object({"data": "test"})
    mock_ray.put.assert_called_once_with({"data": "test"})
    assert ref == "obj_ref_123"
