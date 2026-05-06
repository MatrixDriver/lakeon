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
        blob_store=None,
    ):
        self.driver = driver
        self.ollama = ollama
        self.summarizer = SummarizerWorker(driver, ollama, model=summary_model,
                                           blob_store=blob_store)
        self.extractor = EntityExtractorWorker(driver, ollama, model=extract_model,
                                               confidence_threshold=confidence_threshold,
                                               blob_store=blob_store)
        self.timeline = TimelineWorker(driver)
        self.embedding_model = embedding_model

        # concurrency=1: CPU-only Ollama can't run gemma in parallel without
        # massive RAM and per-request slowdown. Serialize the LLM-heavy workers
        # at the pool level. Throughput is acceptable: ~35s/gemma call × 2 calls
        # per ingest (summarize + extract) ≈ 70s end-to-end.
        self.pool = WorkerPool(
            driver,
            handlers={
                TaskKind.SUMMARIZE: self._summarize_memory,
                TaskKind.EXTRACT_ENTITY: self._extract_memory,
                TaskKind.AGGREGATE_TIMELINE: self._timeline_async,
                TaskKind.SUMMARIZE_BLOB: self._summarize_blob,
                TaskKind.EXTRACT_BLOB: self._extract_blob,
            },
            concurrency=1,
        )

    async def _summarize_memory(self, ref: str) -> None:
        await self.summarizer.handle("memory", ref)

    async def _extract_memory(self, ref: str) -> None:
        await self.extractor.handle("memory", ref)

    async def _summarize_blob(self, ref: str) -> None:
        await self.summarizer.handle("blob", ref)

    async def _extract_blob(self, ref: str) -> None:
        await self.extractor.handle("blob", ref)

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

    async def on_blob_ingested(self, sha256: str) -> None:
        await self.pool.enqueue(TaskKind.SUMMARIZE_BLOB, memory_id=sha256)
        await self.pool.enqueue(TaskKind.EXTRACT_BLOB, memory_id=sha256)
