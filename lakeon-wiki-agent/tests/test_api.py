"""FastAPI route contracts — auth, response shape, dispatch to registry."""
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.deps import get_registry, get_runner
from app.main import app


class FakeRegistry:
    """Minimal TaskRegistry stand-in for route tests."""
    def __init__(self):
        self.submits = []

    async def submit(self, run_type, coro):
        self.submits.append(run_type)
        # Close the real coro so no RuntimeWarning
        try:
            coro.close()
        except Exception:
            pass
        return f"task_fake_{len(self.submits)}"

    def get(self, task_id):
        if task_id == "task_known":
            return {"task_id": task_id, "status": "completed", "result": {"ok": True}}
        return None


@pytest.fixture
def client():
    fake_reg = FakeRegistry()
    fake_runner = MagicMock()
    fake_runner.run_ingest = AsyncMock(return_value={"status": "success"})
    fake_runner.run_curate = AsyncMock(return_value={"status": "success"})
    fake_runner.run_lint = AsyncMock(return_value={"status": "success"})

    app.dependency_overrides[get_registry] = lambda: fake_reg
    app.dependency_overrides[get_runner] = lambda: fake_runner
    try:
        yield TestClient(app)
    finally:
        app.dependency_overrides.clear()


def _auth_headers() -> dict:
    return {"Authorization": f"Bearer {settings.wiki_agent_internal_token}"}


def test_health_no_auth_required(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_ingest_requires_token(client):
    r = client.post(
        "/v1/wiki/ingest",
        json={"tenant_id": "t", "kb_id": "k", "document_id": "d"},
    )
    assert r.status_code == 403


def test_ingest_rejects_wrong_token(client):
    r = client.post(
        "/v1/wiki/ingest",
        json={"tenant_id": "t", "kb_id": "k", "document_id": "d"},
        headers={"Authorization": "Bearer wrong"},
    )
    assert r.status_code == 403


def test_ingest_accepted_returns_202_and_task_id(client):
    r = client.post(
        "/v1/wiki/ingest",
        json={"tenant_id": "t", "kb_id": "k", "document_id": "d"},
        headers=_auth_headers(),
    )
    assert r.status_code == 202
    body = r.json()
    assert body["task_id"].startswith("task_fake_")
    assert body["run_id"].startswith("run_")
    assert body["status"] == "accepted"


def test_ingest_rejects_missing_document_id(client):
    r = client.post(
        "/v1/wiki/ingest",
        json={"tenant_id": "t", "kb_id": "k"},
        headers=_auth_headers(),
    )
    assert r.status_code == 422


def test_curate_accepted(client):
    r = client.post(
        "/v1/wiki/curate",
        json={"tenant_id": "t", "kb_id": "k"},
        headers=_auth_headers(),
    )
    assert r.status_code == 202
    assert r.json()["status"] == "accepted"


def test_lint_accepted(client):
    r = client.post(
        "/v1/wiki/lint",
        json={"tenant_id": "t", "kb_id": "k"},
        headers=_auth_headers(),
    )
    assert r.status_code == 202


def test_task_status_returns_snapshot(client):
    r = client.get(
        "/v1/wiki/tasks/task_known",
        headers=_auth_headers(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["task_id"] == "task_known"
    assert body["status"] == "completed"


def test_task_status_404_unknown_id(client):
    r = client.get(
        "/v1/wiki/tasks/task_nope",
        headers=_auth_headers(),
    )
    assert r.status_code == 404


def test_task_status_requires_auth(client):
    r = client.get("/v1/wiki/tasks/task_known")
    assert r.status_code == 403


def test_ingest_dispatches_correct_run_type(client):
    fake_reg = app.dependency_overrides[get_registry]()
    client.post(
        "/v1/wiki/ingest",
        json={"tenant_id": "t", "kb_id": "k", "document_id": "d"},
        headers=_auth_headers(),
    )
    client.post(
        "/v1/wiki/curate",
        json={"tenant_id": "t", "kb_id": "k"},
        headers=_auth_headers(),
    )
    client.post(
        "/v1/wiki/lint",
        json={"tenant_id": "t", "kb_id": "k"},
        headers=_auth_headers(),
    )
    assert fake_reg.submits == ["ingest", "curate", "lint"]
