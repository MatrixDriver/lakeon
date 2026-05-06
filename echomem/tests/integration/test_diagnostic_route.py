import pytest
from fastapi.testclient import TestClient

from echomem.daemon.app import create_app
from echomem.config import EchomemConfig


@pytest.fixture
def client(tmp_path):
    cfg = EchomemConfig(
        data_dir=tmp_path,
        ollama_url="http://127.0.0.1:1",      # force unreachable for deterministic test
        embedding_model="nomic-embed-text",
        generate_model="gemma2:2b",
        embedding_dim=1024,
    )
    app = create_app(cfg)
    with TestClient(app) as c:
        yield c


def test_diagnostic_returns_full_shape(client):
    resp = client.get("/health/diagnostic")
    assert resp.status_code == 200
    data = resp.json()
    assert set(data.keys()) >= {"daemon", "ollama", "workers", "counts", "dead_letter"}
    assert data["daemon"]["status"] == "ok"
    assert data["ollama"]["status"] in ("unreachable", "timeout")
    assert data["counts"] == {"memories": 0, "cognitions": 0, "entities": 0, "skills": 0}
    assert isinstance(data["workers"], dict)
    assert "summarize" in data["workers"]
    assert data["dead_letter"] == []
