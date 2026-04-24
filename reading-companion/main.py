"""reading-companion main.py — Reading agent runtime entry point.

Cron tasks:
  - 0 14 * * *  → daily_reflection (= 22:00 Asia/Shanghai)

Subprocesses managed:
  - obs sync loop (`python -m hermes_agent_utils.cli sync`)

NOT started here (vs. sre-agent):
  - hermes gateway — reading does not need inbound feishu listening; it is
    push-only (DM via REST) plus CLI-triggered URL ingestion + query.

CLI entry points (invoked by user, not by main):
  - python -m skills.reading.url_handler.cli --url <url> [--no-push]
  - python -m skills.reading.query_handler.cli "<question>" [--no-push]

Shared helpers come from `hermes-agent-utils`.
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

from hermes_agent_utils import (
    DeepseekLLMClient,
    cron_loop,
    feishu_send_dm,
    install_signal_handlers,
    jacky_open_id,
    make_log_store,
    start_subprocess,
)


_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s  %(name)s  %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("reading-companion")


# ─── cron tasks ───────────────────────────────────────────────────────────────

def run_daily_reflection() -> None:
    """0 14 * * * UTC = 22:00 Asia/Shanghai cron task."""
    from skills.reading.daily_reflection.reflect import reflect_today

    log.info("[daily_reflection] reflect_today starting")
    log_store = make_log_store()
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

    open_id = jacky_open_id()
    if open_id and result.reflection_text:
        try:
            feishu_send_dm(open_id, f"📖 今日反思\n\n{result.reflection_text}")
            log.info("[daily_reflection] feishu DM sent to %s", open_id)
        except Exception as exc:
            log.warning("[daily_reflection] feishu DM failed: %s", exc)


_CRON_TASKS = [
    ("0 14 * * *", run_daily_reflection),  # 14:00 UTC = 22:00 Asia/Shanghai
]


# ─── entrypoint ───────────────────────────────────────────────────────────────

def main() -> None:
    install_signal_handlers()

    start_subprocess(
        [sys.executable, "-m", "hermes_agent_utils.cli", "sync"],
        "obs_sync_loop",
    )

    cron_loop(_CRON_TASKS)


if __name__ == "__main__":
    main()
