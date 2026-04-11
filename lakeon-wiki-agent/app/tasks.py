"""In-process task registry with concurrency bound.

FastAPI routes submit an agent coroutine here and return a task_id immediately
(HTTP 202). The caller polls `get(task_id)` to watch status transitions:
    running -> completed  (result contains the agent run dict)
    running -> error      (error contains the exception message)

All tasks run under a single asyncio.Semaphore so bursty uploads don't
overwhelm the LLM API rate limits or the lakeon-api internal endpoints.
"""
import asyncio
import logging
import time
from typing import Any, Awaitable

from ulid import ULID

log = logging.getLogger(__name__)


class TaskRegistry:
    def __init__(self, max_concurrent: int = 8) -> None:
        self._sem = asyncio.Semaphore(max_concurrent)
        self._tasks: dict[str, dict[str, Any]] = {}
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

        async def runner() -> None:
            async with self._sem:
                try:
                    result = await coro
                    snap["status"] = "completed"
                    snap["result"] = result
                except Exception as e:
                    log.exception("task %s failed", task_id)
                    snap["status"] = "error"
                    snap["error"] = f"{type(e).__name__}: {e}"
                finally:
                    snap["finished_at"] = time.time()

        asyncio.create_task(runner())
        return task_id

    def get(self, task_id: str) -> dict[str, Any] | None:
        return self._tasks.get(task_id)

    def count_running(self) -> int:
        return sum(1 for t in self._tasks.values() if t["status"] == "running")
