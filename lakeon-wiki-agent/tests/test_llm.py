"""LlmClient wraps openai SDK and normalizes the response."""
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.agent.llm import LlmClient


def _fake_openai_response(content: str = "ok", tool_calls=None, finish_reason: str = "stop"):
    resp = MagicMock()
    resp.choices = [MagicMock()]
    resp.choices[0].message.model_dump.return_value = {
        "role": "assistant",
        "content": content,
        "tool_calls": tool_calls,
    }
    resp.choices[0].finish_reason = finish_reason
    resp.usage = MagicMock()
    resp.usage.prompt_tokens = 10
    resp.usage.completion_tokens = 5
    return resp


def _build_client_with_fake_openai(fake_openai):
    """Bypass __init__ so we don't need a real API key."""
    client = LlmClient.__new__(LlmClient)
    client._client = fake_openai
    client._model = "deepseek-v3.2"
    client._temperature = 0.1
    client._max_tokens = 1000
    return client


@pytest.mark.asyncio
async def test_chat_passes_tools_to_openai():
    fake_openai = MagicMock()
    fake_openai.chat.completions.create = AsyncMock(return_value=_fake_openai_response())

    client = _build_client_with_fake_openai(fake_openai)

    result = await client.chat(
        messages=[{"role": "user", "content": "hi"}],
        tools=[{"type": "function", "function": {"name": "list_pages", "parameters": {}}}],
    )

    fake_openai.chat.completions.create.assert_awaited_once()
    call = fake_openai.chat.completions.create.call_args
    assert call.kwargs["model"] == "deepseek-v3.2"
    assert call.kwargs["messages"] == [{"role": "user", "content": "hi"}]
    assert call.kwargs["tools"][0]["function"]["name"] == "list_pages"
    assert call.kwargs["tool_choice"] == "auto"
    assert call.kwargs["temperature"] == 0.1
    assert call.kwargs["max_tokens"] == 1000
    # DeepSeek V3.2 thinking-mode disable
    assert call.kwargs["extra_body"] == {"chat_template_kwargs": {"enable_thinking": False}}

    # Normalized return shape
    assert result["message"]["content"] == "ok"
    assert result["finish_reason"] == "stop"
    assert result["usage"]["prompt"] == 10
    assert result["usage"]["completion"] == 5
    assert result["usage"]["total"] == 15


@pytest.mark.asyncio
async def test_chat_without_tools_omits_tools_and_tool_choice():
    fake_openai = MagicMock()
    fake_openai.chat.completions.create = AsyncMock(return_value=_fake_openai_response())

    client = _build_client_with_fake_openai(fake_openai)

    await client.chat(messages=[{"role": "user", "content": "hi"}], tools=None)

    call = fake_openai.chat.completions.create.call_args
    assert "tools" not in call.kwargs
    assert "tool_choice" not in call.kwargs


@pytest.mark.asyncio
async def test_chat_handles_missing_usage_gracefully():
    fake_openai = MagicMock()
    resp = _fake_openai_response()
    resp.usage = None  # some providers omit usage
    fake_openai.chat.completions.create = AsyncMock(return_value=resp)

    client = _build_client_with_fake_openai(fake_openai)

    result = await client.chat(messages=[{"role": "user", "content": "hi"}], tools=None)

    assert result["usage"]["prompt"] == 0
    assert result["usage"]["completion"] == 0
    assert result["usage"]["total"] == 0


@pytest.mark.asyncio
async def test_chat_surfaces_tool_calls_in_message():
    fake_openai = MagicMock()
    tool_call = [{
        "id": "call_1",
        "type": "function",
        "function": {"name": "list_pages", "arguments": "{}"},
    }]
    fake_openai.chat.completions.create = AsyncMock(
        return_value=_fake_openai_response(content=None, tool_calls=tool_call, finish_reason="tool_calls")
    )

    client = _build_client_with_fake_openai(fake_openai)

    result = await client.chat(
        messages=[{"role": "user", "content": "hi"}],
        tools=[{"type": "function", "function": {"name": "list_pages", "parameters": {}}}],
    )

    assert result["finish_reason"] == "tool_calls"
    assert result["message"]["tool_calls"] == tool_call
