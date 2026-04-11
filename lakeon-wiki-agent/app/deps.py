"""Shared singletons and request-level dependencies."""
from functools import lru_cache

from fastapi import Header, HTTPException, status

from app.agent.llm import LlmClient
from app.agent.loop import AgentRunner
from app.clients.lakeon_api import LakeonApiClient
from app.config import settings
from app.tasks import TaskRegistry


@lru_cache(maxsize=1)
def get_llm() -> LlmClient:
    return LlmClient(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
        model=settings.llm_model,
        temperature=settings.llm_temperature,
        max_tokens=settings.llm_max_tokens,
        timeout=settings.llm_request_timeout,
    )


@lru_cache(maxsize=1)
def get_api() -> LakeonApiClient:
    return LakeonApiClient(
        base_url=settings.lakeon_api_url,
        token=settings.lakeon_api_internal_token,
    )


@lru_cache(maxsize=1)
def get_registry() -> TaskRegistry:
    return TaskRegistry(max_concurrent=settings.max_concurrent_agents)


@lru_cache(maxsize=1)
def get_runner() -> AgentRunner:
    return AgentRunner(
        llm=get_llm(),
        api=get_api(),
        max_rounds=settings.max_tool_rounds,
        max_tool_result_chars=settings.max_tool_result_chars,
    )


async def require_token(authorization: str | None = Header(None)) -> None:
    """Enforce bearer-token auth for internal routes."""
    expected = f"Bearer {settings.wiki_agent_internal_token}"
    if authorization != expected:
        raise HTTPException(
            status.HTTP_403_FORBIDDEN,
            detail="invalid wiki agent token",
        )
