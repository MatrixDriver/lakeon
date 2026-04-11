"""Settings loaded from environment / .env file."""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ── Auth tokens ────────────────────────────────────────────
    # Token that lakeon-api uses to call THIS agent (as bearer on inbound requests)
    wiki_agent_internal_token: str = "lakeon-wiki-agent-2026"
    # Base URL + token for calling lakeon-api's /api/v1/internal/wiki/* endpoints
    lakeon_api_url: str = "http://lakeon-api.lakeon.svc:8080"
    lakeon_api_internal_token: str = "lakeon-wiki-agent-2026"

    # ── LLM (DeepSeek V3.2 via 华为云 MaaS) ────────────────────
    llm_base_url: str = "https://api.modelarts-maas.com/openai/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-v3.2"
    llm_temperature: float = 0.1
    llm_max_tokens: int = 4000
    llm_request_timeout: int = 90

    # ── Agent loop ─────────────────────────────────────────────
    max_tool_rounds: int = 20
    max_concurrent_agents: int = 8
    max_tool_result_chars: int = 6000

    # ── Service ────────────────────────────────────────────────
    host: str = "0.0.0.0"
    port: int = 8090
    log_level: str = "INFO"


settings = Settings()
