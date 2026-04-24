import pytest

from hermes_agent_utils.llm import DeepseekLLMClient


def test_init_requires_api_key(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    with pytest.raises(KeyError):
        DeepseekLLMClient()


def test_init_with_explicit_api_key():
    client = DeepseekLLMClient(api_key="test-key")
    assert client._api_key == "test-key"
    assert client._model == "deepseek-chat"
    assert client._base_url == "https://api.deepseek.com"


def test_init_strips_trailing_slash():
    client = DeepseekLLMClient(api_key="k", base_url="https://maas.example/v1/")
    assert client._base_url == "https://maas.example/v1"


def test_complete_returns_shape(monkeypatch):
    """Mock httpx and verify the dict shape we return."""
    import httpx

    class FakeClient:
        def __init__(self, *a, **kw): pass
        def __enter__(self): return self
        def __exit__(self, *a): pass
        def post(self, url, headers=None, json=None):
            class R:
                def raise_for_status(self): pass
                def json(self):
                    return {
                        "choices": [{"message": {"content": "hello"}}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5},
                        "model": "deepseek-chat",
                    }
            return R()

    monkeypatch.setattr(httpx, "Client", FakeClient)
    client = DeepseekLLMClient(api_key="k")
    out = client.complete(system="s", user="u")
    assert out["text"] == "hello"
    assert out["tokens_in"] == 10
    assert out["tokens_out"] == 5
    assert out["model"] == "deepseek-chat"
    assert out["cost_usd"] is None
