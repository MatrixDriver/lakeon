"""FastAPI entry point for lakeon-wiki-agent.

Exposes /health (public) and /v1/wiki/{ingest,curate,lint,tasks/{id}}
(authenticated by WIKI_AGENT_INTERNAL_TOKEN).

A background task evicts completed TaskRegistry snapshots older than
`task_retention_seconds` to prevent unbounded memory growth.
"""
import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import router
from app.config import settings
from app.deps import get_registry

logging.basicConfig(
    level=settings.log_level,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger(__name__)


# Evict task snapshots older than 30 minutes, checked every 5 minutes.
TASK_RETENTION_SECONDS = 30 * 60
EVICT_INTERVAL_SECONDS = 5 * 60


async def _evict_sweeper() -> None:
    """Background loop that prunes terminal task snapshots from the registry."""
    registry = get_registry()
    while True:
        try:
            await asyncio.sleep(EVICT_INTERVAL_SECONDS)
            evicted = registry.evict_older_than(TASK_RETENTION_SECONDS)
            if evicted:
                log.debug("evicted %d stale task snapshots", evicted)
        except asyncio.CancelledError:
            break
        except Exception as e:
            log.warning("evict sweeper iteration failed: %s", e)


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("lakeon-wiki-agent starting on %s:%d", settings.host, settings.port)
    if not settings.llm_api_key:
        log.warning(
            "LLM_API_KEY is empty — agent runs will fail until configured"
        )

    sweeper_task = asyncio.create_task(_evict_sweeper(), name="task-registry-sweeper")
    try:
        yield
    finally:
        sweeper_task.cancel()
        try:
            await sweeper_task
        except asyncio.CancelledError:
            pass
        log.info("lakeon-wiki-agent stopped")


app = FastAPI(
    title="lakeon-wiki-agent",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(router)
