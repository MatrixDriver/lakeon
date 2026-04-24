"""sre-agent main.py — SRE agent runtime entry point.

Cron tasks:
  - */2 * * * *  → cold_start_watcher
  - 0 9 * * *    → outcome_checker

(daily_reflection moved to reading-companion service in B2 refactor.)

Subprocesses managed:
  - obs sync loop (`python -m hermes_agent_utils.cli sync`)
  - hermes gateway (`hermes gateway run`) — for inbound feishu messages

Shared helpers (LLM, feishu DM, factory, cron loop) come from `hermes-agent-utils`.
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import sys
from pathlib import Path
from typing import Any

from hermes_agent_utils import (
    DeepseekLLMClient,
    bridge_env_vars,
    cron_loop,
    feishu_send_dm,
    hermes_config_path,
    hermes_home,
    install_signal_handlers,
    jacky_open_id,
    make_log_store,
    make_skill_ledger,
    start_subprocess,
)


_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

bridge_env_vars()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s  %(name)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("sre-agent")


# ─── MCP adapter (SRE-specific) ───────────────────────────────────────────────

class SREMCPAdapter:
    """Thin adapter over dbay_sre_mcp server functions."""

    def log_search(
        self,
        *,
        component: str = "",
        keyword: str = "",
        since: str = "1h",
        limit: int = 100,
        tenant_id: str = "",
        db_id: str = "",
        **_kwargs: Any,
    ) -> list[dict]:
        from dbay_sre_mcp.server import log_search as _log_search
        raw = _log_search(
            component=component, keyword=keyword, since=since, limit=limit,
            tenant_id=tenant_id, db_id=db_id,
        )
        return json.loads(raw)

    def log_trace(self, request_id: str) -> list[dict]:
        from dbay_sre_mcp.server import log_trace as _log_trace
        return json.loads(_log_trace(request_id))

    def log_stats(self, *, since: str = "24h") -> dict:
        from dbay_sre_mcp.server import log_stats as _log_stats
        return json.loads(_log_stats(since))


# ─── cron tasks ───────────────────────────────────────────────────────────────

def run_cold_start_watcher() -> None:
    """*/2 * * * * cron task."""
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.sre.cold_start_watcher.diagnose import diagnose

    log.info("[watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    watcher = Watcher(log=log_store, mcp=mcp)
    try:
        session_ids = watcher.scan_once()
    except Exception as exc:
        log.error("[watcher] scan_once failed: %s", exc)
        return

    if not session_ids:
        log.info("[watcher] no new cold-start incidents")
        return

    log.info("[watcher] opened %d incident session(s): %s",
             len(session_ids), session_ids)
    llm = DeepseekLLMClient()
    for sid in session_ids:
        log.info("[watcher] diagnosing session %s", sid)
        try:
            session = log_store.get_session(sid)
            diagnose(session, llm=llm, mcp=mcp)
            log.info("[watcher] diagnosis complete for %s", sid)
            open_id = jacky_open_id()
            if open_id:
                try:
                    feishu_send_dm(
                        open_id,
                        f"[SRE] 冷启动告警已诊断, session={sid}\n"
                        f"请查看 {hermes_home()}/data/{sid}/conclusion.md",
                    )
                except Exception as dm_exc:
                    log.warning("[watcher] feishu DM failed for %s: %s", sid, dm_exc)
        except Exception as exc:
            log.error("[watcher] diagnosis failed for session %s: %s", sid, exc)


def run_outcome_checker() -> None:
    """0 9 * * * cron task."""
    from skills.sre.outcome_checker.checker import OutcomeChecker

    log.info("[outcome_checker] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    ledger = make_skill_ledger(log_store)
    checker = OutcomeChecker(log=log_store, mcp=mcp, ledger=ledger)
    try:
        updated = checker.scan_once()
    except Exception as exc:
        log.error("[outcome_checker] scan_once failed: %s", exc)
        return

    log.info("[outcome_checker] updated %d session(s)", len(updated))

    open_id = jacky_open_id()
    if not open_id:
        return
    for sid in updated:
        try:
            outcome_path = log_store.store.root / sid / "outcome.md"
            if outcome_path.exists():
                text = outcome_path.read_text()
                if "did_work: false" in text.lower() or "did_work: no" in text.lower():
                    feishu_send_dm(open_id, f"[SRE] 建议未生效, 请看 {sid}")
        except Exception as exc:
            log.warning("[outcome_checker] feishu DM failed for %s: %s", sid, exc)


_CRON_TASKS = [
    ("*/2 * * * *", run_cold_start_watcher),
    ("0 9 * * *",   run_outcome_checker),
]


# ─── entrypoint ───────────────────────────────────────────────────────────────

def main() -> None:
    install_signal_handlers()

    # OBS sync loop — replaces the old scripts/sync_loop.py
    start_subprocess(
        [sys.executable, "-m", "hermes_agent_utils.cli", "sync"],
        "obs_sync_loop",
    )

    # Hermes gateway (feishu bidi). Seed config + skills into HERMES_HOME.
    hermes_config_src = hermes_config_path()
    home = hermes_home()
    home.mkdir(parents=True, exist_ok=True)
    hermes_config_dst = home / "config.yaml"
    if Path(hermes_config_src).exists():
        shutil.copy2(hermes_config_src, hermes_config_dst)
        log.info("[main] seeded hermes config → %s", hermes_config_dst)

    skills_src = _HERE / "skills"
    skills_dst = home / "skills"
    if skills_src.exists():
        if skills_dst.exists():
            shutil.rmtree(skills_dst)
        shutil.copytree(skills_src, skills_dst)
        log.info("[main] seeded hermes skills → %s", skills_dst)

    start_subprocess(["hermes", "gateway", "run"], "hermes_gateway")

    # Block forever in cron loop.
    cron_loop(_CRON_TASKS)


if __name__ == "__main__":
    main()
