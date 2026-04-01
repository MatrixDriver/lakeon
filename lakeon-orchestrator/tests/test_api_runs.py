import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from httpx import AsyncClient, ASGITransport
from lakeon_orchestrator.main import app, create_app


@pytest.fixture
def configured_app():
    return create_app()


@pytest.fixture
def mock_orchestrator():
    orch = AsyncMock()
    orch.start_run = AsyncMock(return_value="run_new001")
    orch.resume_run = AsyncMock()
    orch.cancel_run = AsyncMock()
    return orch


@pytest.mark.asyncio
async def test_create_run(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs", json={
                "pipeline_id": "pipe_abc",
                "pipeline_version": 1,
                "tenant_id": "tn_test",
                "input_dataset_id": "ds_001",
                "input_dataset_version": 1,
            })
        assert resp.status_code == 202
        data = resp.json()
        assert data["run_id"].startswith("run_")
        assert data["status"] == "PENDING"


@pytest.mark.asyncio
async def test_create_run_missing_fields(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs", json={"pipeline_id": "pipe_abc"})
        assert resp.status_code == 422


@pytest.mark.asyncio
async def test_resume_run(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs/run_001/resume", json={
                "approved_items": ["clip1.mp4", "clip3.mp4"],
            })
        assert resp.status_code == 202
        mock_orchestrator.resume_run.assert_awaited_once()


@pytest.mark.asyncio
async def test_cancel_run(configured_app, mock_orchestrator):
    with patch("lakeon_orchestrator.api.runs.get_orchestrator", return_value=mock_orchestrator):
        transport = ASGITransport(app=configured_app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            resp = await client.post("/runs/run_001/cancel")
        assert resp.status_code == 202
        mock_orchestrator.cancel_run.assert_awaited_once_with("run_001")
