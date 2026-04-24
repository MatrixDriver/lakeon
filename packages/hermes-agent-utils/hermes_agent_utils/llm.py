"""DeepseekLLMClient — OpenAI-compat HTTP client.

Works against api.deepseek.com or HWC MaaS (set DEEPSEEK_BASE_URL accordingly).
"""
from __future__ import annotations

import os
from typing import Any

import httpx


class DeepseekLLMClient:
    """Thin OpenAI-compatible client for Deepseek / HWC MaaS."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str = "deepseek-chat",
        timeout: float = 120.0,
    ) -> None:
        self._api_key = api_key or os.environ["DEEPSEEK_API_KEY"]
        self._base_url = (
            base_url or os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
        ).rstrip("/")
        self._model = model
        self._timeout = timeout

    def complete(
        self, *, system: str, user: str, tools: list[dict] | None = None
    ) -> dict:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        if tools:
            payload["tools"] = tools

        with httpx.Client(timeout=self._timeout) as client:
            resp = client.post(
                f"{self._base_url}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self._api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
        resp.raise_for_status()
        data = resp.json()
        choice = data["choices"][0]
        text = choice.get("message", {}).get("content") or ""
        usage = data.get("usage", {})
        return {
            "text": text,
            "model": data.get("model", self._model),
            "tokens_in": usage.get("prompt_tokens"),
            "tokens_out": usage.get("completion_tokens"),
            "cost_usd": None,
        }
