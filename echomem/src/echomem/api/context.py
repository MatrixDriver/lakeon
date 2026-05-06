from __future__ import annotations

import time
from fastapi import APIRouter, HTTPException, Request

from echomem.api.schemas import AddUrlRequest, BlobOut, WriteRequest, MoveRequest, LsResponse
from echomem.context.fetcher import fetch_url
from echomem.drivers.base import BlobRef

router = APIRouter(prefix="/context")


@router.post("/add_url", response_model=BlobOut)
async def add_url(req: AddUrlRequest, request: Request) -> BlobOut:
    driver = request.app.state.driver
    blob_store = request.app.state.blob_store
    orchestrator = request.app.state.orchestrator

    doc = await fetch_url(req.url)
    sha = blob_store.write(doc.raw_bytes)
    now = int(time.time() * 1000)
    driver.upsert_blob_ref(BlobRef(
        sha256=sha, mime=doc.mime, byte_size=doc.byte_size,
        origin_url=doc.url, meta=None, created_at=now,
    ))
    if req.path:
        driver.set_path_alias(req.path, sha, now)
    if doc.text:
        await orchestrator.on_blob_ingested(sha)
    return BlobOut(sha256=sha, mime=doc.mime, byte_size=doc.byte_size,
                   origin_url=doc.url, path=req.path, created_at=now)


@router.get("/ls", response_model=LsResponse)
async def ls(request: Request, prefix: str | None = None, limit: int = 100) -> LsResponse:
    driver = request.app.state.driver
    if prefix is not None:
        return LsResponse(items=driver.list_paths(prefix=prefix, limit=limit))
    # No prefix → list blobs by created_at
    refs = driver.list_blob_refs(limit=limit)
    return LsResponse(items=[
        {"sha256": r.sha256, "mime": r.mime, "byte_size": r.byte_size,
         "origin_url": r.origin_url, "created_at": r.created_at}
        for r in refs
    ])


@router.get("/read")
async def read(request: Request, path: str | None = None, sha256: str | None = None) -> dict:
    driver = request.app.state.driver
    blob_store = request.app.state.blob_store
    if sha256 is None and path is None:
        raise HTTPException(400, "must specify ?path= or ?sha256=")
    if sha256 is None:
        sha256 = driver.resolve_path(path)
        if sha256 is None:
            raise HTTPException(404, f"path not found: {path}")
    try:
        content = blob_store.read(sha256)
    except FileNotFoundError:
        raise HTTPException(404, f"blob not found: {sha256}")
    ref = driver.get_blob_ref(sha256)
    return {
        "sha256": sha256, "path": path,
        "mime": ref.mime if ref else "application/octet-stream",
        "byte_size": len(content),
        "content": content.decode("utf-8", errors="replace"),
    }


@router.post("/write", response_model=BlobOut)
async def write(req: WriteRequest, request: Request) -> BlobOut:
    driver = request.app.state.driver
    blob_store = request.app.state.blob_store
    orchestrator = request.app.state.orchestrator

    raw = req.content.encode("utf-8")
    sha = blob_store.write(raw)
    now = int(time.time() * 1000)
    driver.upsert_blob_ref(BlobRef(
        sha256=sha, mime=req.mime, byte_size=len(raw),
        origin_url=None, meta=None, created_at=now,
    ))
    driver.set_path_alias(req.path, sha, now)
    await orchestrator.on_blob_ingested(sha)
    return BlobOut(sha256=sha, mime=req.mime, byte_size=len(raw),
                   origin_url=None, path=req.path, created_at=now)


@router.post("/mv")
async def mv(req: MoveRequest, request: Request) -> dict:
    driver = request.app.state.driver
    ok = driver.move_path_alias(old=req.old, new=req.new)
    if not ok:
        raise HTTPException(404, f"path not found: {req.old}")
    return {"old": req.old, "new": req.new, "moved": True}
