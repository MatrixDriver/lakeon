from fastapi import APIRouter, Request

from echomem import __version__
from echomem.api.schemas import (
    DaemonHealth, OllamaHealth, WorkerStatus,
    DiagnosticCounts, DeadLetterEntry, DiagnosticResponse,
)

router = APIRouter()


@router.get("/health")
async def health(request: Request) -> dict:
    cfg = request.app.state.config
    return {
        "status": "ok",
        "version": __version__,
        "embedding_dim": cfg.embedding_dim,
        "embedding_model": cfg.embedding_model,
    }


@router.get("/health/diagnostic", response_model=DiagnosticResponse)
async def diagnostic(request: Request) -> DiagnosticResponse:
    cfg = request.app.state.config
    driver = request.app.state.driver
    ollama = request.app.state.ollama

    db_path = cfg.data_dir / "db.sqlite"
    db_size = db_path.stat().st_size if db_path.exists() else 0

    ping = await ollama.ping()

    return DiagnosticResponse(
        daemon=DaemonHealth(
            status="ok",
            version=__version__,
            data_dir=str(cfg.data_dir),
            db_size_bytes=db_size,
        ),
        ollama=OllamaHealth(
            status=ping["status"],
            latency_ms=ping["latency_ms"],
            generate_model=cfg.generate_model,
            embedding_model=cfg.embedding_model,
            embedding_dim=cfg.embedding_dim,
        ),
        workers={
            kind: WorkerStatus(**stats)
            for kind, stats in driver.worker_stats().items()
        },
        counts=DiagnosticCounts(
            memories=driver.count_memories(),
            cognitions=driver.count_cognitions(),
            entities=driver.count_entities(),
            skills=driver.count_skills(),
        ),
        dead_letter=[DeadLetterEntry(**row) for row in driver.list_dead_letters(limit=20)],
    )
