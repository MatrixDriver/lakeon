from __future__ import annotations

from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.pipeline.queue import WorkerPool, TaskKind
from echomem.workers.summarizer import SummarizerWorker
from echomem.workers.entity_extractor import EntityExtractorWorker
from echomem.workers.timeline import TimelineWorker
from echomem.logging import get_logger

log = get_logger("echomem.orchestrator")


class Orchestrator:
    """Glue: owns the WorkerPool and routes tasks to the per-kind worker."""

    def __init__(
        self,
        driver: SQLiteDriver,
        ollama: OllamaClient,
        *,
        summary_model: str,
        extract_model: str,
        embedding_model: str,
        confidence_threshold: float = 0.7,
    ):
        self.driver = driver
        self.ollama = ollama
        self.summarizer = SummarizerWorker(driver, ollama, model=summary_model)
        self.extractor = EntityExtractorWorker(driver, ollama, model=extract_model,
                                               confidence_threshold=confidence_threshold)
        self.timeline = TimelineWorker(driver)
        self.embedding_model = embedding_model

        self.pool = WorkerPool(
            driver,
            handlers={
                TaskKind.SUMMARIZE: self.summarizer.handle,
                TaskKind.EXTRACT_ENTITY: self.extractor.handle,
                TaskKind.AGGREGATE_TIMELINE: self._timeline_async,
            },
        )

    async def _timeline_async(self, memory_id: str) -> None:
        # TimelineWorker.handle is sync; wrap so the queue can await it
        self.timeline.handle(memory_id)

    async def start(self) -> None:
        await self.pool.start()

    async def stop(self) -> None:
        await self.pool.stop()

    async def drain(self) -> None:
        await self.pool.drain()

    async def on_memory_ingested(self, memory_id: str) -> None:
        await self.pool.enqueue(TaskKind.SUMMARIZE, memory_id=memory_id)
        await self.pool.enqueue(TaskKind.EXTRACT_ENTITY, memory_id=memory_id)
        await self.pool.enqueue(TaskKind.AGGREGATE_TIMELINE, memory_id=memory_id)
