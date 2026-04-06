"""URL fetching: download article HTML, extract text + images."""

import re
import base64
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter()


class UrlFetchRequest(BaseModel):
    url: str


class UrlFetchResponse(BaseModel):
    title: str
    content: str
    images: list[dict]


@router.post("/fetch", response_model=UrlFetchResponse)
async def fetch_url(req: UrlFetchRequest):
    """Fetch a URL, extract article text, download images."""
    try:
        from trafilatura import extract, bare_extraction
    except ImportError:
        raise HTTPException(status_code=500, detail="trafilatura not installed")

    # 1. Download HTML
    try:
        async with httpx.AsyncClient(timeout=30, follow_redirects=True) as client:
            resp = await client.get(req.url, headers={
                "User-Agent": "Mozilla/5.0 (compatible; DBay/1.0)"
            })
            resp.raise_for_status()
            html = resp.text
    except httpx.HTTPError as e:
        raise HTTPException(status_code=502, detail=f"Failed to fetch URL: {e}")

    # 2. Extract main content
    result = extract(html, include_links=True, output_format="txt")
    if not result:
        raise HTTPException(status_code=422, detail="Could not extract article content from URL")

    # 3. Get title
    extracted = bare_extraction(html)
    title = extracted.get("title", "Untitled") if extracted else "Untitled"

    # 4. Extract and download images
    images = []
    img_urls = re.findall(r'<img[^>]+src=["\']([^"\']+)["\']', html)
    async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
        for img_url in img_urls[:20]:
            try:
                if img_url.startswith("//"):
                    img_url = "https:" + img_url
                elif img_url.startswith("/"):
                    parsed = urlparse(req.url)
                    img_url = f"{parsed.scheme}://{parsed.netloc}{img_url}"
                elif not img_url.startswith("http"):
                    continue

                img_resp = await client.get(img_url)
                if img_resp.status_code == 200 and len(img_resp.content) < 5_000_000:
                    ext = img_url.rsplit(".", 1)[-1].split("?")[0][:4]
                    if ext not in ("png", "jpg", "jpeg", "gif", "webp", "svg"):
                        ext = "png"
                    filename = f"img_{len(images):03d}.{ext}"
                    images.append({
                        "url": img_url,
                        "filename": filename,
                        "data_base64": base64.b64encode(img_resp.content).decode()
                    })
            except Exception:
                continue

    return UrlFetchResponse(title=title, content=result, images=images)
