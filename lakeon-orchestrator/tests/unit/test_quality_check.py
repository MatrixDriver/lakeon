"""Unit tests for quality_check component."""

from unittest.mock import MagicMock

import pytest

from components.video.quality_check import quality_check


@pytest.fixture
def mock_ctx():
    ctx = MagicMock()
    ctx.params = {"review_mode": "auto_approve", "thumbnail_count": 4}
    ctx.input = {"clips": ["/tmp/clip1.mp4", "/tmp/clip2.mp4", "/tmp/clip3.mp4"]}
    ctx.log = MagicMock()
    ctx.report = MagicMock()
    ctx.checkpoint = MagicMock()
    ctx.pause = MagicMock()
    return ctx


class TestQualityCheck:
    def test_auto_approve_passes_all(self, mock_ctx):
        result = quality_check(mock_ctx)

        assert result == {"clips": ["/tmp/clip1.mp4", "/tmp/clip2.mp4", "/tmp/clip3.mp4"]}
        mock_ctx.checkpoint.assert_not_called()
        mock_ctx.pause.assert_not_called()
        report = mock_ctx.report.call_args[0][0]
        assert report["approved"] == 3
        assert report["rejected"] == 0

    def test_manual_review_pauses_and_resumes(self, mock_ctx):
        mock_ctx.params["review_mode"] = "manual"
        mock_ctx.pause.return_value = {
            "approved_items": ["/tmp/clip1.mp4", "/tmp/clip3.mp4"],
        }

        result = quality_check(mock_ctx)

        mock_ctx.checkpoint.assert_called_once()
        mock_ctx.pause.assert_called_once()
        assert result == {"clips": ["/tmp/clip1.mp4", "/tmp/clip3.mp4"]}
        report = mock_ctx.report.call_args[0][0]
        assert report["approved"] == 2
        assert report["rejected"] == 1

    def test_text_input_passthrough(self, mock_ctx):
        mock_ctx.input = {"text": ["doc1", "doc2"]}
        result = quality_check(mock_ctx)

        assert result == {"text": ["doc1", "doc2"]}

    def test_auto_approve_single_item(self, mock_ctx):
        mock_ctx.input = {"clips": ["/tmp/single.mp4"]}
        result = quality_check(mock_ctx)

        assert result == {"clips": ["/tmp/single.mp4"]}
        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 1
