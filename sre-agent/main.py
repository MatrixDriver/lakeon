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

    # ─── dbay-sre-mcp 0.2.0 additions ─────────────────────────────────────────

    def find_database(self, *, name: str = "", db_id: str = "") -> dict:
        from dbay_sre_mcp.server import find_database as _find_database
        return json.loads(_find_database(name=name, db_id=db_id))

    def find_tenant(self, *, name: str = "", tenant_id: str = "",
                    include_databases: bool = True) -> dict:
        from dbay_sre_mcp.server import find_tenant as _find_tenant
        return json.loads(_find_tenant(name=name, tenant_id=tenant_id,
                                       include_databases=include_databases))

    def database_status(self, *, name_or_id: str) -> dict:
        from dbay_sre_mcp.server import database_status as _database_status
        return json.loads(_database_status(name_or_id=name_or_id))

    def data_consistency_check(self, *, rule: str, threshold_minutes: int = 10) -> dict:
        from dbay_sre_mcp.server import data_consistency_check as _dcc
        return json.loads(_dcc(rule=rule, threshold_minutes=threshold_minutes))

    def stuck_task_query(self, *, threshold_minutes: int = 10, type: str = "") -> dict:
        from dbay_sre_mcp.server import stuck_task_query as _stq
        return json.loads(_stq(threshold_minutes=threshold_minutes, type=type))

    def pod_create_failures(self, *, since: str = "1h") -> dict:
        from dbay_sre_mcp.server import pod_create_failures as _pcf
        return json.loads(_pcf(since=since))

    def multi_tenant_blast_radius(self, *, window: str = "15m",
                                   min_tenant_count: int = 3) -> dict:
        from dbay_sre_mcp.server import multi_tenant_blast_radius as _mtbr
        return json.loads(_mtbr(window=window, min_tenant_count=min_tenant_count))


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


# ─── Phase 1 watchers & briefings ────────────────────────────────────────────

def run_pod_create_failure_watcher() -> None:
    from skills.sre.pod_create_failure_watcher.watcher import PodCreateFailureWatcher

    log.info("[pod_create_failure_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = PodCreateFailureWatcher(log=log_store, mcp=mcp,
                                skill_name="pod-create-failure-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[pod_create_failure_watcher] failed: %s", exc)
        return
    _dm_for_incidents("pod-create-failure", sids, log_store)


def run_fuse_queue_health_watcher() -> None:
    from skills.sre.fuse_queue_health_watcher.watcher import FuseQueueHealthWatcher

    log.info("[fuse_queue_health_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = FuseQueueHealthWatcher(log=log_store, mcp=mcp,
                               skill_name="fuse-queue-health-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[fuse_queue_health_watcher] failed: %s", exc)
        return
    _dm_for_incidents("fuse-queue-health", sids, log_store)


def run_stuck_task_watcher() -> None:
    from skills.sre.stuck_task_watcher.watcher import StuckTaskWatcher

    log.info("[stuck_task_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = StuckTaskWatcher(log=log_store, mcp=mcp,
                         skill_name="stuck-task-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[stuck_task_watcher] failed: %s", exc)
        return
    _dm_for_incidents("stuck-task", sids, log_store)


def run_data_consistency_watcher() -> None:
    from skills.sre.data_consistency_watcher.watcher import DataConsistencyWatcher

    log.info("[data_consistency_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    llm_client = DeepseekLLMClient()
    w = DataConsistencyWatcher(log=log_store, mcp=mcp, llm=llm_client,
                               skill_name="data-consistency-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[data_consistency_watcher] failed: %s", exc)
        return
    _dm_for_incidents("data-consistency", sids, log_store)


def run_agentfs_forwarder_orphan_watcher() -> None:
    from skills.sre.agentfs_forwarder_orphan_watcher.watcher import (
        AgentFSForwarderOrphanWatcher,
    )

    log.info("[agentfs_forwarder_orphan_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    w = AgentFSForwarderOrphanWatcher(log=log_store, mcp=mcp,
                                      skill_name="agentfs-forwarder-orphan-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[agentfs_forwarder_orphan_watcher] failed: %s", exc)
        return
    _dm_for_incidents("agentfs-forwarder-orphan", sids, log_store)


def run_multi_tenant_blast_radius_watcher() -> None:
    from skills.sre.multi_tenant_blast_radius_watcher.watcher import MultiTenantBlastRadiusWatcher

    log.info("[multi_tenant_blast_radius_watcher] scan_once starting")
    log_store = make_log_store()
    mcp = SREMCPAdapter()
    llm_client = DeepseekLLMClient()
    w = MultiTenantBlastRadiusWatcher(log=log_store, mcp=mcp, llm=llm_client,
                                      skill_name="multi-tenant-blast-radius-watcher")
    try:
        sids = w.scan_once()
    except Exception as exc:
        log.error("[multi_tenant_blast_radius_watcher] failed: %s", exc)
        return
    _dm_for_incidents("blast-radius", sids, log_store)


def _dm_for_incidents(kind: str, sids: list[str], log_store) -> None:
    """Shared helper: DM Jacky a short summary for each new incident."""
    if not sids:
        log.info("[%s] no new incidents", kind)
        return
    log.info("[%s] opened %d incident(s): %s", kind, len(sids), sids)
    open_id = jacky_open_id()
    if not open_id:
        return
    for sid in sids:
        try:
            m = log_store.store.read_manifest(sid)
            alert = (m.trigger or {}).get("alert", "?")
            feishu_send_dm(
                open_id,
                f"[SRE/{kind}] {alert}\nsession={sid}\n"
                f"details: {hermes_home()}/data/{sid}/conclusion.md",
            )
        except Exception as dm_exc:
            log.warning("[%s] feishu DM failed for %s: %s", kind, sid, dm_exc)


def _run_briefing(kind: str) -> None:
    from skills.sre.daily_briefing.runner import BriefingRunner

    log.info("[daily_briefing:%s] starting", kind)
    log_store = make_log_store()
    llm_client = DeepseekLLMClient()
    runner = BriefingRunner(log=log_store, llm=llm_client)
    try:
        result = runner.run(kind=kind)
    except Exception as exc:
        log.error("[daily_briefing:%s] failed: %s", kind, exc)
        return

    log.info("[daily_briefing:%s] wrote session %s", kind, result.session_id)
    open_id = jacky_open_id()
    if open_id and result.text:
        try:
            feishu_send_dm(open_id, f"[SRE] {kind} 报\n\n{result.text}")
        except Exception as exc:
            log.warning("[daily_briefing:%s] feishu DM failed: %s", kind, exc)


def run_morning_briefing() -> None:
    _run_briefing("morning")


def run_evening_briefing() -> None:
    _run_briefing("evening")


def run_weekly_briefing() -> None:
    _run_briefing("weekly")


_CRON_TASKS = [
    # Phase 0a
    ("*/2 * * * *", run_cold_start_watcher),
    ("0 9 * * *",   run_outcome_checker),
    # Phase 1 watchers
    ("*/2 * * * *", run_pod_create_failure_watcher),
    ("*/5 * * * *", run_fuse_queue_health_watcher),
    ("*/5 * * * *", run_stuck_task_watcher),
    ("*/15 * * * *", run_data_consistency_watcher),
    ("*/15 * * * *", run_agentfs_forwarder_orphan_watcher),
    ("*/5 * * * *", run_multi_tenant_blast_radius_watcher),
    # Phase 1 briefings (UTC → Asia/Shanghai: +8h)
    ("0 1 * * *",   run_morning_briefing),    # 9:00 CST
    ("0 14 * * *",  run_evening_briefing),    # 22:00 CST
    ("0 1 * * 1",   run_weekly_briefing),     # Monday 9:00 CST
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
