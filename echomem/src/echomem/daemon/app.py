from __future__ import annotations

from contextlib import asynccontextmanager
from fastapi import FastAPI

from echomem.config import EchomemConfig
from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.api.health import router as health_router
from echomem.api.memory import router as memory_router


@asynccontextmanager
async def _lifespan(app: FastAPI):
    cfg: EchomemConfig = app.state.config
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    driver = SQLiteDriver(cfg.data_dir / "db.sqlite", embedding_dim=cfg.embedding_dim)
    ollama = OllamaClient(cfg.ollama_url)
    app.state.driver = driver
    app.state.ollama = ollama
    try:
        yield
    finally:
        await ollama.aclose()
        driver.close()


def create_app(config: EchomemConfig) -> FastAPI:
    app = FastAPI(title="echomem", version="0.1.0", lifespan=_lifespan)
    app.state.config = config
    app.include_router(health_router)
    app.include_router(memory_router)
    return app
