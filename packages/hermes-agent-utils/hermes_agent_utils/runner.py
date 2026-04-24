"""Main-loop primitives shared by SRE and reading services."""
from __future__ import annotations

import logging
import os
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from typing import Callable

from croniter import croniter


_log = logging.getLogger("hermes_agent_utils.runner")


_CHILD_PROCS: list[subprocess.Popen] = []


def start_subprocess(cmd: list[str], label: str) -> subprocess.Popen:
    """Launch a child subprocess in the background, tracked for cleanup on shutdown."""
    _log.info("[runner] starting %s: %s", label, " ".join(cmd))
    env = {**os.environ, "PYTHONUNBUFFERED": "1"}
    proc = subprocess.Popen(cmd, env=env)
    _CHILD_PROCS.append(proc)
    return proc


def shutdown_children(signum: int, frame: object) -> None:
    """SIGTERM/SIGINT handler — terminate tracked subprocesses then exit."""
    _log.info("[runner] signal %s received — shutting down children", signum)
    for proc in _CHILD_PROCS:
        try:
            proc.terminate()
        except Exception:
            pass
    sys.exit(0)


def install_signal_handlers() -> None:
    signal.signal(signal.SIGTERM, shutdown_children)
    signal.signal(signal.SIGINT, shutdown_children)


def cron_loop(tasks: list[tuple[str, Callable[[], None]]]) -> None:
    """Block forever, running tasks on schedule.

    tasks: list of (cron_expr_in_UTC, callable). Cron expressions are evaluated
    against UTC time; convert wall-clock requirements (e.g. 22:00 Asia/Shanghai
    → 14:00 UTC) at the call site.
    """
    iters = {expr: croniter(expr, datetime.now(timezone.utc)) for expr, _ in tasks}
    next_runs = {expr: iters[expr].get_next(datetime) for expr, _ in tasks}

    _log.info("[cron] loop started with %d task(s)", len(tasks))

    while True:
        now = datetime.now(timezone.utc)
        for expr, task in tasks:
            if now >= next_runs[expr]:
                _log.info("[cron] firing %s → %s", expr, task.__name__)
                try:
                    task()
                except Exception as exc:
                    _log.exception("[cron] task %s raised: %s", task.__name__, exc)
                next_runs[expr] = iters[expr].get_next(datetime)

        soonest = min(next_runs.values())
        sleep_secs = max(
            0.0,
            min(60.0, (soonest - datetime.now(timezone.utc)).total_seconds()),
        )
        time.sleep(sleep_secs)
