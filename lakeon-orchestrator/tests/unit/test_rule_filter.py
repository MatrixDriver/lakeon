"""Unit tests for rule_filter component."""

from unittest.mock import MagicMock, patch

import pytest

from components.video.rule_filter import evaluate_rules, rule_filter


DEFAULT_PARAMS = {
    "min_duration": 3,
    "min_resolution": 480,
    "max_aspect_ratio": 2,
    "min_fps": 20,
    "min_crop_area": 5,
}


class TestEvaluateRules:
    def test_passed_good_clip(self):
        meta = {
            "duration": 5.0, "resolution": 720, "aspect_ratio": 1.78,
            "fps": 30.0, "crop_area_percent": 2.0,
        }
        assert evaluate_rules(meta, DEFAULT_PARAMS) == "passed"

    def test_dropped_too_short(self):
        meta = {
            "duration": 1.5, "resolution": 720, "aspect_ratio": 1.78,
            "fps": 30.0, "crop_area_percent": 0.0,
        }
        assert evaluate_rules(meta, DEFAULT_PARAMS) == "dropped"

    def test_dropped_low_resolution(self):
        meta = {
            "duration": 5.0, "resolution": 320, "aspect_ratio": 1.78,
            "fps": 30.0, "crop_area_percent": 0.0,
        }
        assert evaluate_rules(meta, DEFAULT_PARAMS) == "dropped"

    def test_dropped_low_fps(self):
        meta = {
            "duration": 5.0, "resolution": 720, "aspect_ratio": 1.78,
            "fps": 15.0, "crop_area_percent": 0.0,
        }
        assert evaluate_rules(meta, DEFAULT_PARAMS) == "dropped"

    def test_needs_crop_extreme_aspect(self):
        meta = {
            "duration": 5.0, "resolution": 720, "aspect_ratio": 2.5,
            "fps": 30.0, "crop_area_percent": 0.0,
        }
        assert evaluate_rules(meta, DEFAULT_PARAMS) == "needs_crop"

    def test_needs_crop_large_crop_area(self):
        meta = {
            "duration": 5.0, "resolution": 720, "aspect_ratio": 1.5,
            "fps": 30.0, "crop_area_percent": 8.0,
        }
        assert evaluate_rules(meta, DEFAULT_PARAMS) == "needs_crop"

    def test_custom_thresholds(self):
        meta = {
            "duration": 2.0, "resolution": 720, "aspect_ratio": 1.5,
            "fps": 30.0, "crop_area_percent": 0.0,
        }
        # With min_duration=1, this clip should pass
        custom = {**DEFAULT_PARAMS, "min_duration": 1}
        assert evaluate_rules(meta, custom) == "passed"


class TestRuleFilter:
    @patch("components.video.rule_filter.ffprobe_clip_metadata")
    def test_routes_to_passed(self, mock_probe):
        mock_probe.return_value = {
            "duration": 5.0, "width": 1920, "height": 1080,
            "resolution": 1080, "aspect_ratio": 1.78, "fps": 30.0,
            "crop_area_percent": 1.0,
        }

        ctx = MagicMock()
        ctx.input = {"clip": "/tmp/clip.mp4"}
        ctx.params = DEFAULT_PARAMS
        ctx.classify = MagicMock(return_value={"clip": "/tmp/clip.mp4", "__branch__": "passed"})

        result = rule_filter(ctx)

        ctx.classify.assert_called_once_with("/tmp/clip.mp4", "passed")
        ctx.report.assert_called_once()

    @patch("components.video.rule_filter.ffprobe_clip_metadata")
    def test_routes_to_dropped(self, mock_probe):
        mock_probe.return_value = {
            "duration": 1.0, "width": 320, "height": 240,
            "resolution": 240, "aspect_ratio": 1.33, "fps": 30.0,
            "crop_area_percent": 0.0,
        }

        ctx = MagicMock()
        ctx.input = {"clip": "/tmp/short.mp4"}
        ctx.params = DEFAULT_PARAMS
        ctx.classify = MagicMock(return_value={"__branch__": "dropped"})

        result = rule_filter(ctx)

        ctx.classify.assert_called_once_with("/tmp/short.mp4", "dropped")
