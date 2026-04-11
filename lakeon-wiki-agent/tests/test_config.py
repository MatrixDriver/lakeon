"""Settings loads from environment."""
from app.config import Settings


def test_settings_loads_from_env(monkeypatch):
    monkeypatch.setenv("WIKI_AGENT_INTERNAL_TOKEN", "t1")
    monkeypatch.setenv("LAKEON_API_URL", "http://api:8080")
    monkeypatch.setenv("LAKEON_API_INTERNAL_TOKEN", "t2")
    monkeypatch.setenv("LLM_BASE_URL", "https://api.modelarts-maas.com/openai/v1")
    monkeypatch.setenv("LLM_API_KEY", "k")

    s = Settings()

    assert s.wiki_agent_internal_token == "t1"
    assert s.lakeon_api_url == "http://api:8080"
    assert s.lakeon_api_internal_token == "t2"
    assert s.llm_base_url == "https://api.modelarts-maas.com/openai/v1"
    assert s.llm_api_key == "k"
    # Defaults
    assert s.llm_model == "deepseek-v3.2"
    assert s.max_tool_rounds == 20
    assert s.max_concurrent_agents == 8


def test_settings_defaults_when_env_missing(monkeypatch):
    # Clear any inherited env that would pollute test
    for k in (
        "WIKI_AGENT_INTERNAL_TOKEN", "LAKEON_API_URL", "LAKEON_API_INTERNAL_TOKEN",
        "LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL",
    ):
        monkeypatch.delenv(k, raising=False)

    s = Settings(_env_file=None)  # don't load .env if present

    assert s.wiki_agent_internal_token == "lakeon-wiki-agent-2026"
    assert s.llm_model == "deepseek-v3.2"
    assert s.max_tool_rounds == 20
    assert s.max_concurrent_agents == 8
    assert s.llm_temperature == 0.1
    assert s.max_tool_result_chars == 6000
