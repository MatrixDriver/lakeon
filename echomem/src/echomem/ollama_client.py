from __future__ import annotations

import httpx


class OllamaClient:
    def __init__(self, base_url: str, timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(base_url=self.base_url, timeout=timeout)

    async def embed(self, text: str, *, model: str) -> list[float]:
        resp = await self._client.post(
            "/api/embeddings", json={"model": model, "prompt": text}
        )
        resp.raise_for_status()
        return resp.json()["embedding"]

    async def generate(
        self, prompt: str, *, model: str, options: dict | None = None
    ) -> str:
        body: dict = {"model": model, "prompt": prompt, "stream": False}
        if options:
            body["options"] = options
        resp = await self._client.post("/api/generate", json=body)
        resp.raise_for_status()
        return resp.json()["response"]

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> "OllamaClient":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.aclose()
