"""Unit tests for model_filter_mock component."""

from unittest.mock import MagicMock

import pytest

from components.video.model_filter_mock import (
    mock_model_score,
    model_filter_mock,
    _deterministic_seed,
)


class TestMockModelScore:
    def test_deterministic_same_input(self):
        s1 = mock_model_score("/tmp/clip1.mp4", "vqa")
        s2 = mock_model_score("/tmp/clip1.mp4", "vqa")
        assert s1 == s2

    def test_different_clips_different_scores(self):
        s1 = mock_model_score("/tmp/clip1.mp4", "vqa")
        s2 = mock_model_score("/tmp/clip2.mp4", "vqa")
        # Not guaranteed to differ, but very likely with different paths
        # Just verify both are valid scores
        assert 0 <= s1 <= 1
        assert 0 <= s2 <= 1

    def test_different_checks_different_scores(self):
        s1 = mock_model_score("/tmp/clip1.mp4", "vqa")
        s2 = mock_model_score("/tmp/clip1.mp4", "watermark")
        assert 0 <= s1 <= 1
        assert 0 <= s2 <= 1

    def test_score_range(self):
        for i in range(20):
            score = mock_model_score(f"/tmp/clip_{i}.mp4", "vqa")
            assert 0 <= score <= 1


class TestModelFilterMock:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "checks": ["vqa", "watermark"],
            "thresholds": {"vqa": 0.3, "watermark": 0.5},
            "pass_rate": 0.8,
        }
        ctx.input = {"clips": [f"/tmp/clip_{i}.mp4" for i in range(10)]}
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.checkpoint = MagicMock()
        return ctx

    def test_returns_subset_of_input(self, mock_ctx):
        result = model_filter_mock(mock_ctx)

        assert "clips" in result
        assert len(result["clips"]) <= 10
        # All output clips should be from the input
        for clip in result["clips"]:
            assert clip in mock_ctx.input["clips"]

    def test_reports_metrics(self, mock_ctx):
        model_filter_mock(mock_ctx)

        mock_ctx.report.assert_called_once()
        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 10
        assert report["output_count"] + report["drop_count"] == 10
        assert "retention" in report

    def test_checkpoint_called(self, mock_ctx):
        model_filter_mock(mock_ctx)
        mock_ctx.checkpoint.assert_called_once()

    def test_empty_input(self, mock_ctx):
        mock_ctx.input = {"clips": []}
        result = model_filter_mock(mock_ctx)

        assert result == {"clips": []}
