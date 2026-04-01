"""Unit tests for video_normalize component."""

import json
from unittest.mock import MagicMock, patch

import pytest

from components.video.video_normalize import (
    RESOLUTION_MAP,
    ffprobe_metadata,
    video_normalize,
)


@pytest.fixture
def mock_ctx():
    ctx = MagicMock()
    ctx.params = {"target_resolution": "1080p", "target_format": "mp4"}
    ctx.input = {"video": "/tmp/test_input.avi"}
    ctx.log = MagicMock()
    ctx.report = MagicMock()
    return ctx


SAMPLE_FFPROBE_OUTPUT = json.dumps({
    "streams": [{
        "codec_type": "video",
        "codec_name": "h264",
        "width": 1280,
        "height": 720,
        "r_frame_rate": "30/1",
    }],
    "format": {
        "duration": "120.5",
        "format_name": "matroska,webm",
        "size": "5242880",
    },
})


class TestFfprobeMetadata:
    @patch("components.video.video_normalize.subprocess.run")
    def test_extracts_metadata(self, mock_run):
        mock_run.return_value = MagicMock(stdout=SAMPLE_FFPROBE_OUTPUT, returncode=0)
        meta = ffprobe_metadata("/tmp/test.mp4")

        assert meta["width"] == 1280
        assert meta["height"] == 720
        assert meta["duration"] == 120.5
        assert meta["fps"] == 30.0
        assert meta["codec"] == "h264"
        assert meta["file_size"] == 5242880

    @patch("components.video.video_normalize.subprocess.run")
    def test_raises_on_no_video_stream(self, mock_run):
        mock_run.return_value = MagicMock(
            stdout=json.dumps({"streams": [{"codec_type": "audio"}], "format": {}}),
            returncode=0,
        )
        with pytest.raises(ValueError, match="No video stream"):
            ffprobe_metadata("/tmp/audio_only.mp3")


class TestVideoNormalize:
    @patch("components.video.video_normalize.ffprobe_metadata")
    @patch("components.video.video_normalize.subprocess.run")
    def test_skip_when_already_matches(self, mock_run, mock_probe, mock_ctx):
        mock_ctx.input = {"video": "/tmp/test.mp4"}
        mock_probe.return_value = {
            "width": 1920, "height": 1080, "duration": 60.0,
            "fps": 30.0, "codec": "h264", "format": "mp4", "file_size": 1000,
        }

        result = video_normalize(mock_ctx)

        assert result["video"] == "/tmp/test.mp4"
        mock_ctx.report.assert_called_once()
        report = mock_ctx.report.call_args[0][0]
        assert report["skipped_transcode"] is True

    @patch("components.video.video_normalize.ffprobe_metadata")
    @patch("components.video.video_normalize.subprocess.run")
    def test_transcodes_when_resolution_differs(self, mock_run, mock_probe, mock_ctx):
        mock_probe.side_effect = [
            {  # source metadata
                "width": 1280, "height": 720, "duration": 60.0,
                "fps": 30.0, "codec": "h264", "format": "mp4", "file_size": 1000,
            },
            {  # output metadata
                "width": 1920, "height": 1080, "duration": 60.0,
                "fps": 30.0, "codec": "h264", "format": "mp4", "file_size": 2000,
            },
        ]
        mock_ctx.input = {"video": "/tmp/test.avi"}
        mock_run.return_value = MagicMock(returncode=0)

        result = video_normalize(mock_ctx)

        assert result["video"].endswith("_norm.mp4")
        mock_ctx.report.assert_called_once()
        report = mock_ctx.report.call_args[0][0]
        assert report["skipped_transcode"] is False
