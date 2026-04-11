"""In-process task registry with concurrency bound.

FastAPI routes submit an agent coroutine here and return a task_id immediately
(HTTP 202). The caller polls `get(task_id)` to watch status transitions:
    running -> completed  (result contains the agent run dict)
    running -> error      (error contains the exception message)

All tasks run under a single asyncio.Semaphore so bursty uploads don't
overwhelm the LLM API rate limits or the lakeon-api internal endpoints.

Terminal snapshots accumulate in memory — call `evict_older_than(seconds)`
periodically (e.g. from a FastAPI lifespan sweeper) to prevent unbounded growth.
"""
import asyncio
import logging
import time
from typing import Any, Awaitable

from ulid import ULID

log = logging.getLogger(__name__)


class TaskRegistry:
    def __init__(self, max_concurrent: int = 8) -> None:
        if max_concurrent < 1:
            raise ValueError(f"max_concurrent must be >= 1, got {max_concurrent}")
        self._sem = asyncio.Semaphore(max_concurrent)
        self._tasks: dict[str, dict[str, Any]] = {}
        self._bg: set[asyncio.Task] = set()
        self._lock = asyncio.Lock()

    async def submit(
        self, run_type: str, coro: Awaitable[dict[str, Any]]
    ) -> str:
        """Register a task and schedule it. Returns the task_id immediately.

        The caller does NOT await the agent coroutine — control returns as
        soon as the asyncio task is scheduled.
        """
        task_id = f"task_{ULID()}"
        snap: dict[str, Any] = {
            "task_id": task_id,
            "run_type": run_type,
            "status": "running",
            "created_at": time.time(),
            "finished_at": None,
            "result": None,
            "error": None,
        }
        async with self._lock:
            self._tasks[task_id] = snap
        log.debug("task %s submitted (run_type=%s)", task_id, run_type)

        async def runner() -> None:
            async with self._sem:
                try:
                    result = await coro
                    snap["result"] = result
                    snap["finished_at"] = time.time()
                    snap["status"] = "completed"  # set last — terminal marker
                except Exception as e:
                    log.exception("task %s failed", task_id)
                    snap["error"] = f"{type(e).__name__}: {e}"
                    snap["finished_at"] = time.time()
                    snap["status"] = "error"  # set last
                finally:
                    log.debug("task %s finished (status=%s)", task_id, snap["status"])

        bg = asyncio.create_task(runner(), name=f"wiki-agent-{task_id}")
        self._bg.add(bg)
        bg.add_done_callback(self._bg.discard)
        return task_id

    def get(self, task_id: str) -> dict[str, Any] | None:
        return self._tasks.get(task_id)

    def count_running(self) -> int:
        return sum(1 for t in self._tasks.values() if t["status"] == "running")

    def evict_older_than(self, max_age_seconds: float) -> int:
        """Drop terminal (completed|error) snapshots older than N seconds.

        Called periodically by the FastAPI lifespan sweeper (Task 2.8) to
        prevent unbounded growth of `self._tasks` in long-running services.

        Returns the number of snapshots evicted.
        """
        cutoff = time.time() - max_age_seconds
        stale = [
            tid
            for tid, snap in self._tasks.items()
            if snap["status"] in ("completed", "error")
            and snap["finished_at"] is not None
            and snap["finished_at"] < cutoff
        ]
        for tid in stale:
            del self._tasks[tid]
        return len(stale)
