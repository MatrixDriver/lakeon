from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from echomem.daemon.app import create_app
from echomem.config import EchomemConfig


@pytest.fixture
def client_with_dashboard(tmp_path, monkeypatch):
    import echomem
    pkg_dir = Path(echomem.__file__).parent
    dist_dir = pkg_dir / "_dashboard_dist"
    created = not dist_dir.exists()
    dist_dir.mkdir(exist_ok=True)
    index = dist_dir / "index.html"
    pre_existing = index.exists()
    if not pre_existing:
        index.write_text("<html><body>echomem dashboard</body></html>")

    cfg = EchomemConfig(
        data_dir=tmp_path,
        ollama_url="http://127.0.0.1:1",
        embedding_model="m", generate_model="g", embedding_dim=4,
    )
    app = create_app(cfg)
    with TestClient(app) as c:
        yield c

    if not pre_existing:
        index.unlink()
    if created:
        try:
            dist_dir.rmdir()
        except OSError:
            pass


def test_dashboard_root_returns_index(client_with_dashboard):
    resp = client_with_dashboard.get("/dashboard/")
    assert resp.status_code == 200
    assert "echomem dashboard" in resp.text or "<html" in resp.text
