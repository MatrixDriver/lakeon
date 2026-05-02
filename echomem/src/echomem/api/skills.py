from __future__ import annotations

from pathlib import Path
from fastapi import APIRouter, HTTPException, Request

from echomem.skills.importer import import_skills_from_directory

router = APIRouter(prefix="/skills")


@router.post("/import")
async def import_skills(request: Request, path: str, agent_scope: str | None = None) -> dict:
    p = Path(path).expanduser().resolve()
    if not p.is_dir():
        raise HTTPException(status_code=400, detail=f"not a directory: {p}")
    cfg = request.app.state.config
    n = await import_skills_from_directory(
        request.app.state.driver, request.app.state.ollama,
        directory=p, embedding_model=cfg.embedding_model, agent_scope=agent_scope,
    )
    return {"imported": n, "directory": str(p)}
