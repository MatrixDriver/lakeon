"""Thin async wrapper around the OpenAI SDK pointed at 华为云 MaaS.

The wrapper exposes a single `chat(messages, tools)` method that returns a
normalized dict. It sets the DeepSeek-V3.2-specific `enable_thinking=False`
extra body flag to keep token usage predictable.
"""
from typing import Any

from openai import AsyncOpenAI


class LlmClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str = "deepseek-v3.2",
        temperature: float = 0.1,
        max_tokens: int = 4000,
        timeout: int = 90,
    ) -> None:
        self._client = AsyncOpenAI(
            base_url=base_url,
            api_key=api_key,
            timeout=timeout,
        )
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens

    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        """Single LLM call. Returns a dict with `message`, `finish_reason`, `usage`."""
        kwargs: dict[str, Any] = {
            "model": self._model,
            "messages": messages,
            "temperature": self._temperature,
            "max_tokens": self._max_tokens,
            # MaaS-specific: disable DeepSeek V3.2 thinking mode
            "extra_body": {"chat_template_kwargs": {"enable_thinking": False}},
        }
        if tools:
            kwargs["tools"] = tools
            kwargs["tool_choice"] = "auto"

        resp = await self._client.chat.completions.create(**kwargs)
        choice = resp.choices[0]
        msg = choice.message.model_dump()

        if resp.usage is not None:
            prompt_tokens = resp.usage.prompt_tokens
            completion_tokens = resp.usage.completion_tokens
        else:
            prompt_tokens = 0
            completion_tokens = 0

        return {
            "message": msg,
            "finish_reason": choice.finish_reason,
            "usage": {
                "prompt": prompt_tokens,
                "completion": completion_tokens,
                "total": prompt_tokens + completion_tokens,
            },
        }
