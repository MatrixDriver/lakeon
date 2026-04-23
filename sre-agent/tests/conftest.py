"""Shared pytest fixtures."""
import os
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
    for key in ("DEEPSEEK_API_KEY", "DBAY_LOGS_DSN", "OBS_ACCESS_KEY"):
        monkeypatch.delenv(key, raising=False)
