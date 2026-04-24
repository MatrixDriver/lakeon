"""sre-agent main.py

Orchestrates the SRE agent runtime:
  1. Starts OBS sync loop as a subprocess (background).
  2. Starts hermes gateway (feishu bot) as a subprocess (background).
  3. Runs a croniter-based dispatch loop in the main thread:
       - */2 * * * *  → cold_start_watcher (Watcher + diagnose)
       - 0 9 * * *    → outcome_checker (OutcomeChecker)

Why not hermes cron?
  Hermes skill SKILL.md files are prompt-only — the scheduler calls
  AIAgent.run_conversation(prompt) and never invokes Python code from skill
  directories. Our watcher/diagnose/checker classes need deterministic Python
  execution (regex, dedupe, session management), so we self-host the cron.
  See sre-agent/docs/HERMES_SKILL_DISPATCH.md for full investigation notes.

MCP adapter:
  dbay-sre-mcp is installed as a Python package in the same Docker image.
  We import its server-side functions directly (no subprocess stdio overhead)
  and wrap them in a thin adapter that returns list[dict] (our watcher protocol).

Feishu DM push:
  We call the Feishu REST API directly for outbound DMs (incident reports).
  This avoids IPC with the hermes subprocess and works even if the bot is busy.

Environment variables required (validated by scripts/verify_env.py):
  HERMES_HOME         — hermes data root (default: /data/hermes)
  HERMES_CONFIG       — path to hermes config.yaml
  DEEPSEEK_API_KEY    — LLM API key
  DEEPSEEK_BASE_URL   — LLM base URL (OpenAI-compat endpoint)
  FEISHU_APP_ID       — feishu bot app id
  FEISHU_APP_SECRET   — feishu bot app secret
  FEISHU_ALLOWED_USERS — comma-separated open_id list (first entry gets DMs)
  OBS_ACCESS_KEY, OBS_SECRET_KEY, OBS_BUCKET, OBS_ENDPOINT — OBS backup
"""
from __future__ import annotations

import json
import logging
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import httpx
from croniter import croniter

# ─── paths ────────────────────────────────────────────────────────────────────
_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

# ─── env bridges ──────────────────────────────────────────────────────────────
# dbay-sre-mcp reads LOG_DB_DSN; our runbook/Railway uses DBAY_LOGS_DSN. Bridge
# the two names so operators don't have to set both.
if not os.environ.get("LOG_DB_DSN") and os.environ.get("DBAY_LOGS_DSN"):
    os.environ["LOG_DB_DSN"] = os.environ["DBAY_LOGS_DSN"]

# ─── logging ──────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s  %(name)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("sre-agent")


# ─── helpers ──────────────────────────────────────────────────────────────────

def _hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))


def _hermes_config() -> str:
    return os.environ.get("HERMES_CONFIG", str(_HERE / "hermes_config" / "config.yaml"))


# ─── MCP adapter ──────────────────────────────────────────────────────────────

class SREMCPAdapter:
    """Thin adapter over dbay_sre_mcp server functions.

    Our watcher/checker classes expect mcp.log_search(...) → list[dict].
    The server module functions return JSON strings (they are MCP tools
    that serialise for the protocol layer). We decode them here.
    """

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
            component=component,
            keyword=keyword,
            since=since,
            limit=limit,
            tenant_id=tenant_id,
            db_id=db_id,
        )
        return json.loads(raw)

    def log_trace(self, request_id: str) -> list[dict]:
        from dbay_sre_mcp.server import log_trace as _log_trace
        return json.loads(_log_trace(request_id))

    def log_stats(self, *, since: str = "24h") -> dict:
        from dbay_sre_mcp.server import log_stats as _log_stats
        return json.loads(_log_stats(since))


# ─── LLM client ───────────────────────────────────────────────────────────────

class DeepseekLLMClient:
    """Thin OpenAI-compatible client for Deepseek / HWC MaaS."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str = "deepseek-chat",
        timeout: float = 120.0,
    ) -> None:
        self._api_key = api_key or os.environ["DEEPSEEK_API_KEY"]
        self._base_url = (base_url or os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")).rstrip("/")
        self._model = model
        self._timeout = timeout

    def complete(self, *, system: str, user: str, tools: list[dict] | None = None) -> dict:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        if tools:
            payload["tools"] = tools

        with httpx.Client(timeout=self._timeout) as client:
            resp = client.post(
                f"{self._base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"},
                json=payload,
            )
        resp.raise_for_status()
        data = resp.json()
        choice = data["choices"][0]
        text = choice.get("message", {}).get("content") or ""
        usage = data.get("usage", {})
        return {
            "text": text,
            "model": data.get("model", self._model),
            "tokens_in": usage.get("prompt_tokens"),
            "tokens_out": usage.get("completion_tokens"),
            "cost_usd": None,  # Deepseek API does not return cost
        }


# ─── Feishu DM push ───────────────────────────────────────────────────────────

def _feishu_app_access_token() -> str:
    app_id = os.environ["FEISHU_APP_ID"]
    app_secret = os.environ["FEISHU_APP_SECRET"]
    resp = httpx.post(
        "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal",
        json={"app_id": app_id, "app_secret": app_secret},
        timeout=10.0,
    )
    resp.raise_for_status()
    return resp.json()["app_access_token"]


def feishu_send_dm(open_id: str, text: str) -> None:
    """Send a plain-text DM to a feishu user by open_id."""
    token = _feishu_app_access_token()
    resp = httpx.post(
        "https://open.feishu.cn/open-apis/im/v1/messages",
        params={"receive_id_type": "open_id"},
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        json={
            "receive_id": open_id,
            "msg_type": "text",
            "content": json.dumps({"text": text}),
        },
        timeout=15.0,
    )
    resp.raise_for_status()


def _jacky_open_id() -> str | None:
    """Return the first open_id from FEISHU_ALLOWED_USERS (Jacky's id)."""
    users = os.environ.get("FEISHU_ALLOWED_USERS", "")
    parts = [u.strip() for u in users.split(",") if u.strip()]
    return parts[0] if parts else None


# ─── cron tasks ───────────────────────────────────────────────────────────────

def _make_log_store() -> "LogStore":
    from agent_session_log import LogStore
    return LogStore(_hermes_home() / "data")


def _make_ledger(log_store: "LogStore") -> "SkillLedger":
    from agent_session_log.skill_ledger import SkillLedger
    return SkillLedger(log_store.store.root)


def run_cold_start_watcher() -> None:
    """*/2 * * * * cron task."""
    from skills.sre.cold_start_watcher.watcher import Watcher
    from skills.sre.cold_start_watcher.diagnose import diagnose

    log.info("[watcher] scan_once starting")
    log_store = _make_log_store()
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

    log.info("[watcher] opened %d incident session(s): %s", len(session_ids), session_ids)
    llm = DeepseekLLMClient()
    for sid in session_ids:
        log.info("[watcher] diagnosing session %s", sid)
        try:
            session = log_store.get_session(sid)
            diagnose(session, llm=llm, mcp=mcp)
            log.info("[watcher] diagnosis complete for %s", sid)
            # Notify Jacky via feishu DM
            open_id = _jacky_open_id()
            if open_id:
                try:
                    session_obj = log_store.get_session(sid)
                    feishu_send_dm(
                        open_id,
                        f"[SRE] 冷启动告警已诊断，session={sid}\n"
                        f"请查看 {_hermes_home()}/data/{sid}/conclusion.md",
                    )
                except Exception as dm_exc:
                    log.warning("[watcher] feishu DM failed for %s: %s", sid, dm_exc)
        except Exception as exc:
            log.error("[watcher] diagnosis failed for session %s: %s", sid, exc)


def run_outcome_checker() -> None:
    """0 9 * * * cron task."""
    from skills.sre.outcome_checker.checker import OutcomeChecker

    log.info("[outcome_checker] scan_once starting")
    log_store = _make_log_store()
    mcp = SREMCPAdapter()
    ledger = _make_ledger(log_store)
    checker = OutcomeChecker(log=log_store, mcp=mcp, ledger=ledger)
    try:
        updated = checker.scan_once()
    except Exception as exc:
        log.error("[outcome_checker] scan_once failed: %s", exc)
        return

    log.info("[outcome_checker] updated %d session(s)", len(updated))

    # For sessions where did_work=False, DM Jacky
    open_id = _jacky_open_id()
    if not open_id:
        return
    for sid in updated:
        try:
            outcome_path = log_store.store.root / sid / "outcome.md"
            if outcome_path.exists():
                text = outcome_path.read_text()
                if "did_work: false" in text.lower() or "did_work: no" in text.lower():
                    feishu_send_dm(open_id, f"[SRE] 建议未生效，请看 {sid}")
        except Exception as exc:
            log.warning("[outcome_checker] feishu DM failed for %s: %s", sid, exc)


def run_daily_reflection() -> None:
    """0 22 * * * cron task — review today's reading, push reflection to Jacky."""
    from skills.reading.daily_reflection.reflect import reflect_today

    log.info("[daily_reflection] reflect_today starting")
    log_store = _make_log_store()
    llm = DeepseekLLMClient()
    try:
        result = reflect_today(log=log_store, llm=llm)
    except Exception as exc:
        log.error("[daily_reflection] reflect_today failed: %s", exc)
        return

    if result.skipped_reason:
        log.info("[daily_reflection] skipped: %s", result.skipped_reason)
        return

    log.info("[daily_reflection] wrote session %s", result.session_id)

    open_id = _jacky_open_id()
    if open_id and result.reflection_text:
        try:
            feishu_send_dm(open_id, f"📖 今日反思\n\n{result.reflection_text}")
            log.info("[daily_reflection] feishu DM sent to %s", open_id)
        except Exception as exc:  # noqa: BLE001
            log.warning("[daily_reflection] feishu DM failed: %s", exc)


# ─── subprocess management ────────────────────────────────────────────────────

_CHILD_PROCS: list[subprocess.Popen] = []


def _start_subprocess(cmd: list[str], label: str) -> subprocess.Popen:
    log.info("[main] starting %s: %s", label, " ".join(cmd))
    # Force unbuffered I/O so subprocess logs appear in Railway logs immediately
    env = {**os.environ, "PYTHONUNBUFFERED": "1"}
    proc = subprocess.Popen(cmd, env=env)
    _CHILD_PROCS.append(proc)
    return proc


def _shutdown_children(signum: int, frame: object) -> None:
    log.info("[main] signal %s received — shutting down children", signum)
    for proc in _CHILD_PROCS:
        try:
            proc.terminate()
        except Exception:
            pass
    sys.exit(0)


# ─── cron loop ────────────────────────────────────────────────────────────────

_CRON_TASKS = [
    ("*/2 * * * *", run_cold_start_watcher),
    ("0 9 * * *",   run_outcome_checker),
    ("0 22 * * *",  run_daily_reflection),
]


def cron_loop() -> None:
    """Block forever, running tasks on schedule. Sleeps until next tick (max 60s)."""
    from datetime import datetime, timezone

    iters = {expr: croniter(expr, datetime.now(timezone.utc)) for expr, _ in _CRON_TASKS}
    next_runs = {expr: iters[expr].get_next(datetime) for expr, _ in _CRON_TASKS}

    log.info("[cron] loop started with %d task(s)", len(_CRON_TASKS))

    while True:
        now = datetime.now(timezone.utc)
        for expr, task in _CRON_TASKS:
            if now >= next_runs[expr]:
                log.info("[cron] firing %s → %s", expr, task.__name__)
                try:
                    task()
                except Exception as exc:
                    log.exception("[cron] task %s raised: %s", task.__name__, exc)
                next_runs[expr] = iters[expr].get_next(datetime)

        # Sleep until the soonest upcoming task (max 60s to stay responsive)
        soonest = min(next_runs.values())
        sleep_secs = max(0.0, min(60.0, (soonest - datetime.now(timezone.utc)).total_seconds()))
        time.sleep(sleep_secs)


# ─── entrypoint ───────────────────────────────────────────────────────────────

def main() -> None:
    signal.signal(signal.SIGTERM, _shutdown_children)
    signal.signal(signal.SIGINT, _shutdown_children)

    # 1. OBS sync loop (background subprocess)
    obs_script = str(_HERE / "scripts" / "sync_loop.py")
    if Path(obs_script).exists():
        _start_subprocess([sys.executable, obs_script], "obs_sync_loop")
    else:
        log.warning("[main] obs sync script not found at %s — skipping", obs_script)

    # 2. Hermes gateway (background subprocess — feishu bidirectional bot)
    # Hermes reads config.yaml + skills from $HERMES_HOME automatically.
    # Seed them from our baked-in paths so the LLM can `skill_view` etc.
    hermes_config_src = _hermes_config()
    hermes_home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    hermes_home.mkdir(parents=True, exist_ok=True)
    hermes_config_dst = hermes_home / "config.yaml"
    if Path(hermes_config_src).exists():
        # Always overwrite — packaged config is the source of truth; Jacky's
        # runtime edits should be done via Railway env vars, not by hand on
        # the volume.
        shutil.copy2(hermes_config_src, hermes_config_dst)
        log.info("[main] seeded hermes config → %s", hermes_config_dst)

    # Seed skills (always overwrite — packaged skills are the source of truth)
    skills_src = _HERE / "skills"
    skills_dst = hermes_home / "skills"
    if skills_src.exists():
        if skills_dst.exists():
            shutil.rmtree(skills_dst)
        shutil.copytree(skills_src, skills_dst)
        log.info("[main] seeded hermes skills → %s", skills_dst)
    # Use `hermes gateway run` (foreground mode) not `start` (background/systemd);
    # in Docker we want the subprocess to stay attached.
    _start_subprocess(
        ["hermes", "gateway", "run"],
        "hermes_gateway",
    )

    # 3. Cron loop (blocks main thread)
    cron_loop()


if __name__ == "__main__":
    main()
