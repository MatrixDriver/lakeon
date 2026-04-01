"""Unit tests for video_labeling_mock component."""

from unittest.mock import MagicMock

import pytest

from components.video.video_labeling_mock import (
    generate_viclip_tags,
    generate_caption,
    generate_camera_motion,
    video_labeling_mock,
)


class TestGenerateViclipTags:
    def test_returns_correct_count(self):
        tags = generate_viclip_tags("/tmp/clip.mp4", top_k=5)
        assert len(tags) == 5

    def test_deterministic(self):
        t1 = generate_viclip_tags("/tmp/clip.mp4", top_k=3)
        t2 = generate_viclip_tags("/tmp/clip.mp4", top_k=3)
        assert t1 == t2

    def test_has_tag_and_confidence(self):
        tags = generate_viclip_tags("/tmp/clip.mp4", top_k=1)
        assert "tag" in tags[0]
        assert "confidence" in tags[0]
        assert 0 <= tags[0]["confidence"] <= 1


class TestGenerateCaption:
    def test_returns_string(self):
        cap = generate_caption("/tmp/clip.mp4")
        assert isinstance(cap, str)
        assert len(cap) > 10

    def test_deterministic(self):
        c1 = generate_caption("/tmp/clip.mp4")
        c2 = generate_caption("/tmp/clip.mp4")
        assert c1 == c2


class TestGenerateCameraMotion:
    def test_returns_motion_dict(self):
        motion = generate_camera_motion("/tmp/clip.mp4")
        assert "primary_motion" in motion
        assert "confidence" in motion
        assert "is_stable" in motion

    def test_deterministic(self):
        m1 = generate_camera_motion("/tmp/clip.mp4")
        m2 = generate_camera_motion("/tmp/clip.mp4")
        assert m1 == m2


class TestVideoLabelingMock:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "tasks": ["viclip_tag", "caption", "camera_motion"],
            "viclip_top_k": 5,
        }
        ctx.input = {"clips": ["/tmp/clip1.mp4", "/tmp/clip2.mp4"]}
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        return ctx

    def test_labels_all_clips(self, mock_ctx):
        result = video_labeling_mock(mock_ctx)

        assert "clips" in result
        assert len(result["clips"]) == 2

    def test_each_clip_has_all_labels(self, mock_ctx):
        result = video_labeling_mock(mock_ctx)

        for item in result["clips"]:
            assert "clip" in item
            assert "labels" in item
            assert "viclip_tags" in item["labels"]
            assert "caption" in item["labels"]
            assert "camera_motion" in item["labels"]

    def test_subset_of_tasks(self, mock_ctx):
        mock_ctx.params["tasks"] = ["caption"]
        result = video_labeling_mock(mock_ctx)

        for item in result["clips"]:
            assert "caption" in item["labels"]
            assert "viclip_tags" not in item["labels"]

    def test_reports_metrics(self, mock_ctx):
        video_labeling_mock(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 2
        assert report["output_count"] == 2
