"""Unit tests for text_dedup component."""

from unittest.mock import MagicMock, patch

import pytest

try:
    from datasketch import MinHash, MinHashLSH
    HAS_DATASKETCH = True
except ImportError:
    HAS_DATASKETCH = False

from components.text.text_dedup import (
    tokenize_for_minhash,
    text_dedup,
)


class TestTokenizeForMinhash:
    def test_basic_ngrams(self):
        tokens = tokenize_for_minhash("hello", ngram=3)
        assert tokens == ["hel", "ell", "llo"]

    def test_short_text(self):
        tokens = tokenize_for_minhash("hi", ngram=3)
        assert tokens == ["hi"]

    def test_normalizes_whitespace(self):
        tokens = tokenize_for_minhash("  hello   world  ", ngram=5)
        # Normalized to "hello world"
        assert "hello" in tokens
        assert "ello " in tokens

    def test_chinese_text(self):
        tokens = tokenize_for_minhash("你好世界测试", ngram=3)
        assert len(tokens) == 4  # 6 chars - 3 + 1


@pytest.mark.skipif(not HAS_DATASKETCH, reason="datasketch not installed")
class TestComputeMinhash:
    def test_returns_minhash(self):
        from components.text.text_dedup import compute_minhash
        tokens = ["abc", "bcd", "cde"]
        mh = compute_minhash(tokens, num_perm=128)
        assert mh is not None
        assert len(mh.hashvalues) == 128

    def test_similar_texts_similar_hash(self):
        from components.text.text_dedup import compute_minhash
        t1 = tokenize_for_minhash("the quick brown fox jumps over the lazy dog")
        t2 = tokenize_for_minhash("the quick brown fox jumps over a lazy dog")
        mh1 = compute_minhash(t1)
        mh2 = compute_minhash(t2)
        # Should be quite similar
        assert mh1.jaccard(mh2) > 0.5

    def test_different_texts_different_hash(self):
        from components.text.text_dedup import compute_minhash
        t1 = tokenize_for_minhash("hello world programming")
        t2 = tokenize_for_minhash("completely different unrelated content xyz")
        mh1 = compute_minhash(t1)
        mh2 = compute_minhash(t2)
        assert mh1.jaccard(mh2) < 0.5


@pytest.mark.skipif(not HAS_DATASKETCH, reason="datasketch not installed")
class TestDeduplicateTexts:
    def test_removes_exact_duplicates(self):
        from components.text.text_dedup import deduplicate_texts
        texts = [
            {"content": "This is document one about machine learning"},
            {"content": "This is document one about machine learning"},
            {"content": "A completely different document about cooking"},
        ]
        unique, dups = deduplicate_texts(texts, similarity_threshold=0.85)
        assert len(unique) == 2
        assert len(dups) == 1

    def test_removes_near_duplicates(self):
        from components.text.text_dedup import deduplicate_texts
        texts = [
            {"content": "The quick brown fox jumps over the lazy dog in the park"},
            {"content": "The quick brown fox jumps over the lazy dog in the garden"},
            {"content": "Machine learning and artificial intelligence are transforming technology"},
        ]
        unique, dups = deduplicate_texts(texts, similarity_threshold=0.7)
        assert len(unique) == 2

    def test_no_duplicates(self):
        from components.text.text_dedup import deduplicate_texts
        texts = [
            {"content": "First completely unique document about science"},
            {"content": "Second unrelated document about history and culture"},
            {"content": "Third different text about mathematics and logic"},
        ]
        unique, dups = deduplicate_texts(texts, similarity_threshold=0.85)
        assert len(unique) == 3
        assert len(dups) == 0

    def test_empty_input(self):
        from components.text.text_dedup import deduplicate_texts
        unique, dups = deduplicate_texts([], similarity_threshold=0.85)
        assert unique == []
        assert dups == []


class TestTextDedupWithMock:
    """Test text_dedup component with mocked deduplicate_texts."""

    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "similarity_threshold": 0.85,
            "num_perm": 128,
            "ngram": 3,
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"content": "Document about artificial intelligence and deep learning"},
                {"content": "Document about artificial intelligence and deep learning"},
                {"content": "A recipe for chocolate cake with cream frosting"},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.checkpoint = MagicMock()
        return ctx

    @patch("components.text.text_dedup.deduplicate_texts")
    def test_dedup_calls_and_returns(self, mock_dedup, mock_ctx):
        unique = [
            {"content": "Document about artificial intelligence and deep learning"},
            {"content": "A recipe for chocolate cake with cream frosting"},
        ]
        duplicates = [
            {"content": "Document about artificial intelligence and deep learning"},
        ]
        mock_dedup.return_value = (unique, duplicates)

        result = text_dedup(mock_ctx)

        assert "text" in result
        assert len(result["text"]) == 2
        mock_ctx.checkpoint.assert_called_once()

    @patch("components.text.text_dedup.deduplicate_texts")
    def test_reports_metrics(self, mock_dedup, mock_ctx):
        mock_dedup.return_value = (
            [{"content": "a"}, {"content": "b"}],
            [{"content": "c"}],
        )

        text_dedup(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 3
        assert report["output_count"] == 2
        assert report["duplicate_count"] == 1


@pytest.mark.skipif(not HAS_DATASKETCH, reason="datasketch not installed")
class TestTextDedupIntegration:
    """Integration test with real datasketch (when available)."""

    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "similarity_threshold": 0.85,
            "num_perm": 128,
            "ngram": 3,
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"content": "Document about artificial intelligence and deep learning"},
                {"content": "Document about artificial intelligence and deep learning"},
                {"content": "A recipe for chocolate cake with cream frosting"},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.checkpoint = MagicMock()
        return ctx

    def test_dedup_integration(self, mock_ctx):
        result = text_dedup(mock_ctx)

        assert "text" in result
        assert len(result["text"]) == 2
        mock_ctx.checkpoint.assert_called_once()

    def test_reports_metrics(self, mock_ctx):
        text_dedup(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 3
        assert report["output_count"] == 2
        assert report["duplicate_count"] == 1
