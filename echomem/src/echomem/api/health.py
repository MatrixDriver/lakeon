from fastapi import APIRouter, Request

from echomem import __version__

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
