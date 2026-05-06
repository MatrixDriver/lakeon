from __future__ import annotations

import io
from dataclasses import dataclass

import httpx


@dataclass(slots=True)
class ExtractedDoc:
    url: str
    mime: str
    text: str
    raw_bytes: bytes
    byte_size: int


async def fetch_url(url: str, *, timeout: float = 30.0) -> ExtractedDoc:
    """Fetch URL and return main text + raw bytes. Routes by Content-Type:
    - text/html → trafilatura main-content extraction
    - application/pdf → pypdf text extraction
    - text/* → raw text passthrough
    - other → text=""; caller decides what to do with raw_bytes
    """
    async with httpx.AsyncClient(timeout=timeout, trust_env=False, follow_redirects=True) as c:
        resp = await c.get(url)
        resp.raise_for_status()
        raw = resp.content
        mime = (resp.headers.get("content-type") or "application/octet-stream").split(";")[0].strip()

    text = ""
    if mime.startswith("text/html"):
        try:
            import trafilatura
            extracted = trafilatura.extract(raw.decode("utf-8", errors="replace"))
            text = extracted or ""
        except Exception:
            text = ""
    elif mime == "application/pdf":
        try:
            from pypdf import PdfReader
            reader = PdfReader(io.BytesIO(raw))
            text = "\n".join(p.extract_text() or "" for p in reader.pages)
        except Exception:
            text = ""
    elif mime.startswith("text/"):
        text = raw.decode("utf-8", errors="replace")

    return ExtractedDoc(
        url=url, mime=mime, text=text,
        raw_bytes=raw, byte_size=len(raw),
    )
