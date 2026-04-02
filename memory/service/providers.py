import httpx
import os
from typing import Optional

EMBEDDING_API_URL = os.getenv("EMBEDDING_API_URL", "https://api.siliconflow.cn/v1/embeddings")
EMBEDDING_API_KEY = os.getenv("EMBEDDING_API_KEY", "")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")

_client: Optional[httpx.AsyncClient] = None


def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient(timeout=30)
    return _client


async def get_embedding(text: str) -> list[float]:
    client = _get_client()
    headers = {}
    if EMBEDDING_API_KEY:
        headers["Authorization"] = f"Bearer {EMBEDDING_API_KEY}"
    resp = await client.post(
        EMBEDDING_API_URL,
        json={"model": EMBEDDING_MODEL, "input": text},
        headers=headers,
    )
    resp.raise_for_status()
    return resp.json()["data"][0]["embedding"]
