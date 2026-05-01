from __future__ import annotations

from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.logging import get_logger

log = get_logger("echomem.embedder")


class EmbedderWorker:
    def __init__(self, driver: SQLiteDriver, ollama: OllamaClient, *, model: str):
        self.driver = driver
        self.ollama = ollama
        self.model = model

    async def handle(self, memory_id: str, text: str) -> None:
        embedding = await self.ollama.embed(text, model=self.model)
        # 不调 upsert_memory（那要完整 Memory）— 直接写 vec 表
        self.driver._upsert_vec(memory_id, embedding)
        self.driver.con.commit()
        log.info("embedded", memory_id=memory_id, dim=len(embedding))
