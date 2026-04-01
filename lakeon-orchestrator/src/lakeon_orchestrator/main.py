from contextlib import asynccontextmanager

from fastapi import FastAPI

from lakeon_orchestrator.config import settings
from lakeon_orchestrator.db.engine import init_db, close_db


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
    yield
    await close_db()


app = FastAPI(
    title="Lakeon Pipeline Orchestrator",
    version="0.1.0",
    lifespan=lifespan,
)


def create_app() -> FastAPI:
    from lakeon_orchestrator.api.runs import router as runs_router

    app.include_router(runs_router, prefix="/runs", tags=["runs"])
    return app


if __name__ == "__main__":
    import uvicorn

    create_app()
    uvicorn.run(app, host=settings.host, port=settings.port)
