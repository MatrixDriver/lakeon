"""Unit tests for video_crop component."""

import json
from unittest.mock import MagicMock, patch

import pytest

from components.video.video_crop import detect_crop_params, video_crop


class TestDetectCropParams:
    @patch("components.video.video_crop.subprocess.run")
    def test_parses_cropdetect_output(self, mock_run):
        mock_run.return_value = MagicMock(
            stderr=(
                "[Parsed_cropdetect] crop=1904:1072:8:4\n"
                "[Parsed_cropdetect] crop=1904:1072:8:4\n"
                "[Parsed_cropdetect] crop=1904:1072:8:4\n"
            ),
            returncode=0,
        )
        result = detect_crop_params("/tmp/clip.mp4")
        assert result == (1904, 1072, 8, 4)

    @patch("components.video.video_crop.subprocess.run")
    def test_returns_none_when_no_crop(self, mock_run):
        mock_run.return_value = MagicMock(stderr="", returncode=0)
        result = detect_crop_params("/tmp/clip.mp4")
        assert result is None


class TestVideoCrop:
    def _make_ctx(self, target_ar=0):
        ctx = MagicMock()
        ctx.input = {"clip": "/tmp/clip.mp4"}
        ctx.params = {"target_aspect_ratio": target_ar}
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.classify = MagicMock(side_effect=lambda path, label: {"__branch__": label, "clip": path})
        return ctx

    @patch("components.video.video_crop.subprocess.run")
    @patch("components.video.video_crop.detect_crop_params")
    def test_passthrough_no_crop_needed(self, mock_detect, mock_run):
        mock_run.return_value = MagicMock(
            stdout=json.dumps({"streams": [{"codec_type": "video", "width": 1920, "height": 1080}]}),
            returncode=0,
        )
        mock_detect.return_value = None
        ctx = self._make_ctx(target_ar=0)

        result = video_crop(ctx)

        ctx.classify.assert_called_once_with("/tmp/clip.mp4", "passed")
        report = ctx.report.call_args[0][0]
        assert report["action"] == "passthrough"

    @patch("components.video.video_crop.subprocess.run")
    @patch("components.video.video_crop.apply_crop")
    def test_crops_to_target_aspect(self, mock_apply, mock_run):
        mock_run.return_value = MagicMock(
            stdout=json.dumps({"streams": [{"codec_type": "video", "width": 1920, "height": 1080}]}),
            returncode=0,
        )
        mock_apply.return_value = "/tmp/crop_out/clip_cropped.mp4"
        ctx = self._make_ctx(target_ar=1.0)  # Square crop

        result = video_crop(ctx)

        ctx.classify.assert_called_once()
        call_args = ctx.classify.call_args
        assert call_args[0][1] == "passed"
        report = ctx.report.call_args[0][0]
        assert report["action"] == "cropped"
