"""Unit tests for video_scene_split component."""

import sys
from unittest.mock import MagicMock, patch

import pytest

# Mock scenedetect before importing the component module
_mock_scenedetect = MagicMock()
sys.modules["scenedetect"] = _mock_scenedetect

from components.video.video_scene_split import (  # noqa: E402
    detect_scenes,
    split_video_by_scenes,
    video_scene_split,
)


@pytest.fixture
def mock_ctx():
    ctx = MagicMock()
    ctx.params = {"threshold": 27, "min_scene_length": 1.0}
    ctx.input = {"video": "/tmp/test_video.mp4"}
    ctx.log = MagicMock()
    ctx.report = MagicMock()
    ctx.checkpoint = MagicMock()
    ctx.fan_out = MagicMock(side_effect=lambda clips: {"clips": clips, "__fan_out__": True})
    return ctx


class TestDetectScenes:
    @patch("components.video.video_scene_split.open_video")
    @patch("components.video.video_scene_split.SceneManager")
    @patch("components.video.video_scene_split.ContentDetector")
    def test_returns_scene_boundaries(self, mock_detector_cls, mock_sm_cls, mock_open):
        mock_video = MagicMock()
        mock_video.frame_rate = 30.0
        mock_open.return_value = mock_video

        # Create mock FrameTimecode objects
        scene_start = MagicMock()
        scene_start.get_seconds.return_value = 0.0
        scene_end = MagicMock()
        scene_end.get_seconds.return_value = 5.3
        scene2_start = MagicMock()
        scene2_start.get_seconds.return_value = 5.3
        scene2_end = MagicMock()
        scene2_end.get_seconds.return_value = 12.1

        mock_sm = MagicMock()
        mock_sm.get_scene_list.return_value = [
            (scene_start, scene_end),
            (scene2_start, scene2_end),
        ]
        mock_sm_cls.return_value = mock_sm

        scenes = detect_scenes("/tmp/video.mp4", threshold=27, min_scene_length=1.0)

        assert len(scenes) == 2
        assert scenes[0] == (0.0, 5.3)
        assert scenes[1] == (5.3, 12.1)


class TestSplitVideoByScenes:
    @patch("components.video.video_scene_split.subprocess.run")
    def test_creates_clips_for_each_scene(self, mock_run):
        mock_run.return_value = MagicMock(returncode=0)
        scenes = [(0.0, 5.0), (5.0, 10.0), (10.0, 15.0)]

        clips = split_video_by_scenes("/tmp/source.mp4", scenes, "/tmp/out")

        assert len(clips) == 3
        assert mock_run.call_count == 3
        assert clips[0] == "/tmp/out/source_clip_0000.mp4"
        assert clips[2] == "/tmp/out/source_clip_0002.mp4"


class TestVideoSceneSplit:
    @patch("components.video.video_scene_split.split_video_by_scenes")
    @patch("components.video.video_scene_split.detect_scenes")
    def test_fan_out_clips(self, mock_detect, mock_split, mock_ctx):
        mock_detect.return_value = [(0.0, 3.0), (3.0, 7.0)]
        mock_split.return_value = ["/tmp/clip_0.mp4", "/tmp/clip_1.mp4"]

        result = video_scene_split(mock_ctx)

        mock_ctx.fan_out.assert_called_once_with(["/tmp/clip_0.mp4", "/tmp/clip_1.mp4"])
        mock_ctx.checkpoint.assert_called_once()
        report = mock_ctx.report.call_args[0][0]
        assert report["output_count"] == 2

    @patch("components.video.video_scene_split.detect_scenes")
    def test_single_clip_when_no_scenes(self, mock_detect, mock_ctx):
        mock_detect.return_value = []

        result = video_scene_split(mock_ctx)

        mock_ctx.fan_out.assert_called_once_with(["/tmp/test_video.mp4"])
        report = mock_ctx.report.call_args[0][0]
        assert report["scenes_detected"] == 0
