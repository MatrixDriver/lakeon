from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Awaitable, Callable

from echomem.drivers.sqlite import SQLiteDriver
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.pipeline")


class TaskKind(str, Enum):
    SUMMARIZE = "summarize"
    EXTRACT_ENTITY = "extract_entity"
    AGGREGATE_TIMELINE = "aggregate_timeline"
    REFLECT = "reflect"


Handler = Callable[[str], Awaitable[None]]


@dataclass
class _Task:
    id: str
    kind: TaskKind
    memory_id: str | None
    attempts: int = 0


class WorkerPool:
    def __init__(
        self,
        driver: SQLiteDriver,
        *,
        handlers: dict[TaskKind, Handler],
        max_attempts: int = 3,
        retry_base_seconds: float = 1.0,
        concurrency: int = 2,
    ):
        self.driver = driver
        self.handlers = handlers
        self.max_attempts = max_attempts
        self.retry_base_seconds = retry_base_seconds
        self.concurrency = concurrency
        self._q: asyncio.Queue[_Task] = asyncio.Queue()
        self._workers: list[asyncio.Task] = []
        self._running = False

    async def start(self) -> None:
        self._running = True
        for _ in range(self.concurrency):
            self._workers.append(asyncio.create_task(self._loop()))

    async def stop(self) -> None:
        self._running = False
        for w in self._workers:
            w.cancel()
        for w in self._workers:
            try:
                await w
            except (asyncio.CancelledError, Exception):
                pass
        self._workers.clear()

    async def drain(self) -> None:
        await self._q.join()

    async def enqueue(self, kind: TaskKind, *, memory_id: str | None) -> str:
        now = int(time.time() * 1000)
        tid = new_id()
        self.driver.con.execute(
            "INSERT INTO derivative_task(id, kind, memory_id, status, attempts, created_at, updated_at) "
            "VALUES(?, ?, ?, 'pending', 0, ?, ?)",
            (tid, kind.value, memory_id, now, now),
        )
        self.driver.con.commit()
        await self._q.put(_Task(id=tid, kind=kind, memory_id=memory_id))
        return tid

    async def _loop(self) -> None:
        while self._running:
            try:
                task = await self._q.get()
            except asyncio.CancelledError:
                return
            try:
                await self._handle_one(task)
            finally:
                self._q.task_done()

    async def _handle_one(self, task: _Task) -> None:
        handler = self.handlers.get(task.kind)
        if handler is None:
            self._dead_letter(task, "no handler for kind: " + task.kind.value)
            return
        try:
            self._mark_status(task.id, "running", attempts=task.attempts + 1)
            if task.memory_id is not None:
                await handler(task.memory_id)
            else:
                await handler("")
            self._mark_status(task.id, "done", attempts=task.attempts + 1)
        except Exception as e:
            task.attempts += 1
            self._mark_status(task.id, "failed", attempts=task.attempts, last_error=str(e))
            if task.attempts >= self.max_attempts:
                self._dead_letter(task, str(e))
                return
            await asyncio.sleep(self.retry_base_seconds * (4 ** (task.attempts - 1)))
            await self._q.put(task)

    def _mark_status(self, task_id: str, status: str, *, attempts: int,
                     last_error: str | None = None) -> None:
        now = int(time.time() * 1000)
        self.driver.con.execute(
            "UPDATE derivative_task SET status = ?, attempts = ?, last_error = ?, updated_at = ? WHERE id = ?",
            (status, attempts, last_error, now, task_id),
        )
        self.driver.con.commit()

    def _dead_letter(self, task: _Task, error: str) -> None:
        now = int(time.time() * 1000)
        self.driver.con.execute(
            "INSERT INTO dead_letter(id, task_id, kind, memory_id, error, created_at) "
            "VALUES(?, ?, ?, ?, ?, ?)",
            (new_id(), task.id, task.kind.value, task.memory_id, error, now),
        )
        self.driver.con.commit()
        log.warning("dead_letter", task_id=task.id, kind=task.kind.value, err=error)
