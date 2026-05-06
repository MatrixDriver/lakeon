from __future__ import annotations

import time
import httpx


class OllamaClient:
    def __init__(self, base_url: str, timeout: float = 300.0):
        self.base_url = base_url.rstrip("/")
        # trust_env=False: do NOT read HTTP_PROXY / HTTPS_PROXY / ALL_PROXY env vars.
        # Ollama is always a local service; routing it through a system proxy
        # (e.g. Clash on 7890) breaks every LLM call. Bypass the env entirely.
        self._client = httpx.AsyncClient(
            base_url=self.base_url, timeout=timeout, trust_env=False
        )

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

    async def ping(self) -> dict:
        """Probe Ollama base URL. Returns {status, latency_ms}.

        status in {"ok", "unreachable", "timeout"}.
        """
        start = time.perf_counter()
        try:
            resp = await self._client.get("/", timeout=2.0)
        except httpx.TimeoutException:
            return {"status": "timeout", "latency_ms": None}
        except httpx.HTTPError:
            return {"status": "unreachable", "latency_ms": None}
        elapsed_ms = int((time.perf_counter() - start) * 1000)
        if resp.status_code != 200:
            return {"status": "unreachable", "latency_ms": elapsed_ms}
        return {"status": "ok", "latency_ms": elapsed_ms}

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self) -> "OllamaClient":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.aclose()
