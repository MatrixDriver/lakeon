from pathlib import Path

import pytest


@pytest.fixture
def tmp_log_root(tmp_path: Path) -> Path:
    """Isolated agent_session_log root for a single test."""
    root = tmp_path / "hermes_data"
    root.mkdir()
    return root


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Prevent tests from accidentally hitting real services via env."""
    for key in ("DEEPSEEK_API_KEY", "FEISHU_APP_ID", "FEISHU_APP_SECRET",
                "FEISHU_ALLOWED_USERS", "OBS_ACCESS_KEY", "OBS_SECRET_KEY",
                "OBS_BUCKET", "OBS_ENDPOINT"):
        monkeypatch.delenv(key, raising=False)
