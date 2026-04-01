"""Unit tests for text_tokenize component."""

from unittest.mock import MagicMock, patch

import pytest

try:
    import tiktoken
    HAS_TIKTOKEN = True
except ImportError:
    HAS_TIKTOKEN = False

try:
    import jieba
    HAS_JIEBA = True
except ImportError:
    HAS_JIEBA = False

from components.text.text_tokenize import (
    compute_text_stats,
    text_tokenize,
)


@pytest.mark.skipif(not HAS_TIKTOKEN, reason="tiktoken not installed")
class TestTokenizeTiktoken:
    def test_basic_tokenization(self):
        from components.text.text_tokenize import tokenize_tiktoken
        tokens = tokenize_tiktoken("Hello, world!")
        assert len(tokens) > 0
        # Reconstructed text should match original
        assert "".join(tokens) == "Hello, world!"

    def test_chinese_text(self):
        from components.text.text_tokenize import tokenize_tiktoken
        tokens = tokenize_tiktoken("你好世界")
        assert len(tokens) > 0

    def test_empty_text(self):
        from components.text.text_tokenize import tokenize_tiktoken
        tokens = tokenize_tiktoken("")
        assert tokens == []


@pytest.mark.skipif(not HAS_JIEBA, reason="jieba not installed")
class TestTokenizeJieba:
    def test_chinese_segmentation(self):
        from components.text.text_tokenize import tokenize_jieba
        tokens = tokenize_jieba("我来到北京清华大学")
        assert "清华大学" in tokens or "清华" in tokens

    def test_english_text(self):
        from components.text.text_tokenize import tokenize_jieba
        tokens = tokenize_jieba("Hello World")
        assert len(tokens) > 0


class TestComputeTextStats:
    def test_basic_stats(self):
        tokens = ["hello", "world", "hello", "test"]
        stats = compute_text_stats(tokens)
        assert stats["token_count"] == 4
        assert stats["unique_count"] == 3
        assert 0 < stats["type_token_ratio"] < 1
        assert stats["avg_token_length"] > 0

    def test_empty_tokens(self):
        stats = compute_text_stats([])
        assert stats["token_count"] == 0
        assert stats["type_token_ratio"] == 0.0

    def test_top_tokens(self):
        tokens = ["a", "b", "a", "a", "b", "c"]
        stats = compute_text_stats(tokens)
        top = stats["top_tokens"]
        assert top[0] == ("a", 3)


@pytest.mark.skipif(not HAS_TIKTOKEN, reason="tiktoken not installed")
class TestTextTokenize:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "tokenizer": "tiktoken",
            "tiktoken_model": "cl100k_base",
            "compute_stats": True,
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"id": 1, "content": "Hello world, this is a test document."},
                {"id": 2, "content": "Another document with different content."},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.checkpoint = MagicMock()
        return ctx

    def test_adds_token_count(self, mock_ctx):
        result = text_tokenize(mock_ctx)

        assert "text" in result
        for record in result["text"]:
            assert "token_count" in record
            assert record["token_count"] > 0

    def test_adds_token_stats(self, mock_ctx):
        result = text_tokenize(mock_ctx)

        for record in result["text"]:
            assert "token_stats" in record
            assert "token_count" in record["token_stats"]

    @pytest.mark.skipif(not HAS_JIEBA, reason="jieba not installed")
    def test_jieba_tokenizer(self, mock_ctx):
        mock_ctx.params["tokenizer"] = "jieba"
        mock_ctx.input["text"] = [{"id": 1, "content": "我来到北京清华大学学习"}]

        result = text_tokenize(mock_ctx)

        assert result["text"][0]["token_count"] > 0

    def test_reports_metrics(self, mock_ctx):
        text_tokenize(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 2
        assert report["total_tokens"] > 0
        assert report["tokenizer"] == "tiktoken"

    def test_no_stats(self, mock_ctx):
        mock_ctx.params["compute_stats"] = False
        result = text_tokenize(mock_ctx)

        for record in result["text"]:
            assert "token_count" in record
            assert "token_stats" not in record
