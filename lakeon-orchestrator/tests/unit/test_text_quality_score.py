"""Unit tests for text_quality_score component."""

from unittest.mock import MagicMock

import pytest

from components.text.text_quality_score import (
    score_length,
    score_sentence_structure,
    score_repetition,
    score_special_chars,
    score_information_density,
    compute_quality_score,
    text_quality_score,
)


class TestScoreLength:
    def test_very_short(self):
        assert score_length("hi") == 0.0

    def test_short(self):
        assert score_length("x" * 100) == 0.3

    def test_optimal(self):
        assert score_length("x" * 500) == 1.0

    def test_long(self):
        assert score_length("x" * 30000) == 0.7

    def test_very_long(self):
        assert score_length("x" * 100000) == 0.4


class TestScoreSentenceStructure:
    def test_good_sentences(self):
        text = "This is a good sentence. Another one follows. And a third."
        score = score_sentence_structure(text)
        assert score > 0.5

    def test_no_sentences(self):
        assert score_sentence_structure("") == 0.0

    def test_single_long_sentence(self):
        text = "word " * 300
        score = score_sentence_structure(text)
        assert score < 0.5  # No sentence boundaries


class TestScoreRepetition:
    def test_normal_text(self):
        text = "The quick brown fox jumps over the lazy dog in the beautiful garden."
        score = score_repetition(text)
        assert score > 0.3

    def test_highly_repetitive(self):
        text = "spam spam spam " * 50
        score = score_repetition(text)
        # Very repetitive bigrams
        assert score < 0.8


class TestScoreSpecialChars:
    def test_normal_text(self):
        assert score_special_chars("Hello, world! This is a test.") == 1.0

    def test_too_many_special(self):
        text = "###$$$%%%&&&***!!!" * 10
        score = score_special_chars(text)
        assert score < 0.5

    def test_empty(self):
        assert score_special_chars("") == 0.0


class TestComputeQualityScore:
    def test_good_text(self):
        text = (
            "Machine learning is a subset of artificial intelligence that focuses "
            "on the development of algorithms and statistical models. These models "
            "enable computers to perform tasks without explicit programming. "
            "Deep learning, a further subset, uses neural networks with many layers."
        )
        scores = compute_quality_score(text)
        assert "overall" in scores
        assert scores["overall"] > 0.4

    def test_empty_text(self):
        scores = compute_quality_score("")
        assert scores["overall"] < 0.3


class TestTextQualityScore:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "scorer": "rule",
            "min_score": 0.5,
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {
                    "id": 1,
                    "content": (
                        "Machine learning is transforming industries worldwide. "
                        "From healthcare to finance, AI applications are growing rapidly. "
                        "Natural language processing enables machines to understand human text. "
                        "Computer vision allows automated image analysis at scale."
                    ),
                },
                {
                    "id": 2,
                    "content": "hi",  # Very short, low quality
                },
                {
                    "id": 3,
                    "content": (
                        "这是一段关于人工智能的中文文本。深度学习是机器学习的一个子集，"
                        "它使用多层神经网络来学习数据的复杂表示。近年来，大语言模型取得了"
                        "显著的进展，在自然语言处理的多个任务上达到了人类水平的性能。"
                    ),
                },
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        return ctx

    def test_separates_passed_and_low_quality(self, mock_ctx):
        result = text_quality_score(mock_ctx)

        assert "passed" in result
        assert "low_quality" in result
        assert len(result["passed"]) + len(result["low_quality"]) == 3

    def test_low_quality_includes_short(self, mock_ctx):
        result = text_quality_score(mock_ctx)

        low_ids = [r["id"] for r in result["low_quality"]]
        assert 2 in low_ids  # "hi" should be low quality

    def test_enriches_with_scores(self, mock_ctx):
        result = text_quality_score(mock_ctx)

        for record in result["passed"] + result["low_quality"]:
            assert "quality_scores" in record
            assert "overall" in record["quality_scores"]

    def test_reports_metrics(self, mock_ctx):
        text_quality_score(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 3
        assert "avg_score" in report
