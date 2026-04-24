"""Factory helpers for wiring agent_session_log from env vars."""
from __future__ import annotations

import os
from pathlib import Path

from agent_session_log import LogStore, SkillLedger


def hermes_home() -> Path:
    """HERMES_HOME or ~/.hermes."""
    return Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))


def hermes_config_path() -> str:
    """HERMES_CONFIG or default ./hermes_config/config.yaml relative to caller's dir."""
    return os.environ.get(
        "HERMES_CONFIG",
        str(Path.cwd() / "hermes_config" / "config.yaml"),
    )


def make_log_store() -> LogStore:
    return LogStore(hermes_home() / "data")


def make_skill_ledger(log_store: LogStore | None = None) -> SkillLedger:
    if log_store is None:
        log_store = make_log_store()
    return SkillLedger(log_store.store.root)


def bridge_env_vars() -> None:
    """Set LOG_DB_DSN from DBAY_LOGS_DSN if missing (for dbay-sre-mcp).

    Idempotent — safe to call multiple times.
    """
    if not os.environ.get("LOG_DB_DSN") and os.environ.get("DBAY_LOGS_DSN"):
        os.environ["LOG_DB_DSN"] = os.environ["DBAY_LOGS_DSN"]
