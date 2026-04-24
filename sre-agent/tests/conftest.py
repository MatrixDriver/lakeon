"""Shared pytest fixtures."""
import os
import sys
import types
from pathlib import Path
import pytest


# ─── stub dbay_sre_mcp so string-path monkeypatch/patch resolves the module ──
# dbay-sre-mcp is an optional runtime dep that is not installed in the test venv.
# Tests mock individual functions via monkeypatch/patch; we just need the module
# object to exist in sys.modules so those patching calls can resolve it.
def _stub_fn(*_a, **_kw):
    raise NotImplementedError("dbay_sre_mcp stub — replace via patch/monkeypatch in tests")


def _make_sre_mcp_stub() -> None:
    pkg = types.ModuleType("dbay_sre_mcp")
    srv = types.ModuleType("dbay_sre_mcp.server")
    # Stub every known server function so patch()/monkeypatch.setattr() can
    # target them by string path without requiring create=True.
    for _fn in (
        "log_search", "log_trace", "log_stats",
        "find_database", "find_tenant", "database_status",
        "data_consistency_check", "stuck_task_query",
        "pod_create_failures", "multi_tenant_blast_radius",
    ):
        setattr(srv, _fn, _stub_fn)
    pkg.server = srv  # type: ignore[attr-defined]
    sys.modules.setdefault("dbay_sre_mcp", pkg)
    sys.modules.setdefault("dbay_sre_mcp.server", srv)

_make_sre_mcp_stub()


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
