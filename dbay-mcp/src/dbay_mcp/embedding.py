"""
Local embedding generation for encrypted memory bases.

Supports three providers:
- "dbay"     — call DBay's embedding API (uses user's apikey)
- "external" — user-provided embedding API endpoint (OpenAI-compatible)
- "local"    — not yet supported
"""

from __future__ import annotations

import httpx

from dbay_mcp.crypto import load_encrypted_bases


def _get_embedding_config(mem_id: str) -> dict:
    """Get embedding config for a memory base from encrypted_bases.json."""
    bases = load_encrypted_bases()
    if mem_id not in bases:
        raise RuntimeError(f"No encryption config found for memory base '{mem_id}'")
    return bases[mem_id]


def generate_embedding(
    mem_id: str,
    text: str,
    api_key: str | None = None,
    endpoint: str | None = None,
) -> list[float]:
    """Generate embedding vector for text using the configured provider."""
    config = _get_embedding_config(mem_id)
    provider = config.get("embedding_provider", "dbay")

    if provider == "dbay":
        if not api_key:
            raise RuntimeError("api_key is required for dbay embedding provider")
        if not endpoint:
            raise RuntimeError("endpoint is required for dbay embedding provider")
        return _embed_dbay(text, api_key, endpoint)

    if provider == "external":
        return _embed_external(text, config)

    if provider == "local":
        raise RuntimeError(
            "Local embedding provider is not yet supported. "
            "Use 'dbay' or 'external' provider instead."
        )

    raise RuntimeError(f"Unknown embedding provider: {provider}")


def _embed_dbay(text: str, api_key: str, endpoint: str) -> list[float]:
    """Call DBay's embedding API at {endpoint}/api/v1/embedding."""
    url = f"{endpoint}/api/v1/embedding"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    resp = httpx.post(url, json={"input": text}, headers=headers, verify=False, timeout=30)
    resp.raise_for_status()
    return resp.json()["data"][0]["embedding"]


def _embed_external(text: str, config: dict) -> list[float]:
    """Call user-provided external embedding API (OpenAI-compatible)."""
    ep = config.get("embedding_endpoint")
    model = config.get("embedding_model")
    key = config.get("embedding_api_key")

    if not ep:
        raise RuntimeError("embedding_endpoint is required for external provider")
    if not model:
        raise RuntimeError("embedding_model is required for external provider")

    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"

    resp = httpx.post(ep, json={"model": model, "input": text}, headers=headers, verify=False, timeout=30)
    resp.raise_for_status()
    return resp.json()["data"][0]["embedding"]


def probe_embedding_dim(
    mem_id: str,
    api_key: str | None = None,
    endpoint: str | None = None,
) -> int:
    """Probe the embedding dimension by sending a test text."""
    vec = generate_embedding(mem_id, "dimension probe", api_key, endpoint)
    return len(vec)
