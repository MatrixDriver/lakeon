from pathlib import Path

from hermes_agent_utils.factory import (
    bridge_env_vars,
    hermes_home,
    make_log_store,
    make_skill_ledger,
)


def test_hermes_home_default(monkeypatch):
    monkeypatch.delenv("HERMES_HOME", raising=False)
    assert hermes_home() == Path.home() / ".hermes"


def test_hermes_home_env(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    assert hermes_home() == tmp_path


def test_make_log_store(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    log = make_log_store()
    s = log.new_session(type="incident", trigger={}, tags=[])
    assert s.id


def test_make_skill_ledger(monkeypatch, tmp_path):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    ledger = make_skill_ledger()
    ledger.record_invocation("x", version="v1", session_id="s1",
                             triggered_at="2026-04-24T00:00:00Z")
    stats = ledger.stats("x")
    assert stats["total_invocations"] == 1


def test_bridge_env_vars_sets_log_db_dsn(monkeypatch):
    monkeypatch.setenv("DBAY_LOGS_DSN", "postgresql://x")
    monkeypatch.delenv("LOG_DB_DSN", raising=False)
    bridge_env_vars()
    import os
    assert os.environ["LOG_DB_DSN"] == "postgresql://x"


def test_bridge_env_vars_no_overwrite(monkeypatch):
    monkeypatch.setenv("LOG_DB_DSN", "existing")
    monkeypatch.setenv("DBAY_LOGS_DSN", "other")
    bridge_env_vars()
    import os
    assert os.environ["LOG_DB_DSN"] == "existing"
