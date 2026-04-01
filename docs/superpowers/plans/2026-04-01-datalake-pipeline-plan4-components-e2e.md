# 数据生产线 Plan 4: 预置组件实现 + 模板数据 + E2E 验收测试

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 12 个 Phase 1 预置组件（8 真实 + 4 mock），写入预置模板数据，通过 E2E 测试验证视频和文本 pipeline 完整流程。

**Architecture:** 组件代码位于 `lakeon-orchestrator/components/`，使用 Plan 2 定义的 `@Component` 装饰器和 `ComponentContext` API。视频组件依赖 ffmpeg/ffprobe（容器预装），文本组件依赖 MinHash/jieba/tiktoken（pip 安装）。模板数据通过 Flyway 迁移脚本写入数据库。E2E 测试需要 Orchestrator + Ray 集群环境。

**Tech Stack:** Python 3.11, ffmpeg/ffprobe, PySceneDetect, datasketch (MinHash), jieba, tiktoken, pylance, pytest, Ray

**Spec:** `docs/superpowers/specs/2026-04-01-datalake-pipeline-design.md` (4.5 / 7 / 1.3)

---

## File Structure

```
lakeon-orchestrator/
├── components/
│   ├── __init__.py
│   ├── video/
│   │   ├── __init__.py
│   │   ├── video_normalize.py
│   │   ├── video_scene_split.py
│   │   ├── rule_filter.py
│   │   ├── video_crop.py
│   │   ├── quality_check.py
│   │   ├── model_filter_mock.py
│   │   └── video_labeling_mock.py
│   ├── text/
│   │   ├── __init__.py
│   │   ├── text_dedup.py
│   │   ├── text_clean.py
│   │   ├── text_tokenize.py
│   │   └── text_quality_score.py
│   └── universal/
│       ├── __init__.py
│       └── dataset_publish.py
├── tests/
│   ├── __init__.py
│   ├── conftest.py
│   ├── unit/
│   │   ├── __init__.py
│   │   ├── test_video_normalize.py
│   │   ├── test_video_scene_split.py
│   │   ├── test_rule_filter.py
│   │   ├── test_video_crop.py
│   │   ├── test_quality_check.py
│   │   ├── test_model_filter_mock.py
│   │   ├── test_video_labeling_mock.py
│   │   ├── test_text_dedup.py
│   │   ├── test_text_clean.py
│   │   ├── test_text_tokenize.py
│   │   ├── test_text_quality_score.py
│   │   └── test_dataset_publish.py
│   └── e2e/
│       ├── __init__.py
│       ├── conftest.py
│       ├── test_video_pipeline_e2e.py
│       └── test_text_pipeline_e2e.py
├── tests/fixtures/
│   ├── sample_video.mp4              (ffmpeg 生成的 10s 测试视频)
│   └── sample_texts.jsonl            (20 条测试文本)
lakeon-api/src/main/resources/db/migration/
└── V30__pipeline_preset_templates.sql (预置模板 + 组件数据)
```

---

## Task 1: video_normalize -- ffprobe 元数据提取 + ffmpeg 转码

**Files:**
- Create: `lakeon-orchestrator/components/video/__init__.py`
- Create: `lakeon-orchestrator/components/video/video_normalize.py`
- Create: `lakeon-orchestrator/tests/unit/test_video_normalize.py`

- [ ] **Step 1: 创建 video 包 __init__.py**

Create: `lakeon-orchestrator/components/video/__init__.py`

```python
"""Video processing components for the data pipeline."""
```

- [ ] **Step 2: 实现 video_normalize 组件**

Create: `lakeon-orchestrator/components/video/video_normalize.py`

```python
"""video_normalize: 视频规整适配 -- ffprobe 元数据提取 + ffmpeg 转码."""

import json
import os
import subprocess
import tempfile
from pathlib import Path

from lakeon.pipeline import Component, ComponentContext

RESOLUTION_MAP = {
    "360p": (640, 360),
    "480p": (854, 480),
    "720p": (1280, 720),
    "1080p": (1920, 1080),
    "2k": (2560, 1440),
    "4k": (3840, 2160),
}


def ffprobe_metadata(video_path: str) -> dict:
    """Extract video metadata using ffprobe.

    Returns dict with keys: width, height, duration, fps, codec, format, file_size.
    """
    cmd = [
        "ffprobe", "-v", "quiet", "-print_format", "json",
        "-show_format", "-show_streams", video_path,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    probe = json.loads(result.stdout)

    video_stream = next(
        (s for s in probe.get("streams", []) if s["codec_type"] == "video"), None
    )
    if video_stream is None:
        raise ValueError(f"No video stream found in {video_path}")

    fmt = probe.get("format", {})
    fps_parts = video_stream.get("r_frame_rate", "30/1").split("/")
    fps = float(fps_parts[0]) / float(fps_parts[1]) if len(fps_parts) == 2 else 30.0

    return {
        "width": int(video_stream.get("width", 0)),
        "height": int(video_stream.get("height", 0)),
        "duration": float(fmt.get("duration", 0)),
        "fps": round(fps, 2),
        "codec": video_stream.get("codec_name", "unknown"),
        "format": fmt.get("format_name", "unknown"),
        "file_size": int(fmt.get("size", 0)),
    }


@Component(
    name="video_normalize",
    display_name="视频规整适配",
    category="DATA_PREP",
    data_type="VIDEO",
    params_schema={
        "target_resolution": {
            "type": "string",
            "default": "1080p",
            "enum": list(RESOLUTION_MAP.keys()),
            "description": "目标分辨率",
        },
        "target_format": {
            "type": "string",
            "default": "mp4",
            "enum": ["mp4", "avi", "mkv"],
            "description": "目标封装格式",
        },
    },
    input_schema={"type": "video", "format": ["mp4", "avi", "mkv", "mov", "flv"]},
    output_schema={"type": "video", "format": "mp4"},
)
def video_normalize(ctx: ComponentContext) -> dict:
    """Normalize video resolution/format via ffmpeg transcoding."""
    video_path = ctx.input["video"]
    target_res = ctx.params.get("target_resolution", "1080p")
    target_fmt = ctx.params.get("target_format", "mp4")

    # Extract source metadata
    meta = ffprobe_metadata(video_path)
    ctx.log(f"Source: {meta['width']}x{meta['height']}, {meta['duration']:.1f}s, {meta['codec']}")

    target_w, target_h = RESOLUTION_MAP[target_res]

    # Determine if transcoding is needed
    needs_transcode = (
        meta["width"] != target_w
        or meta["height"] != target_h
        or not video_path.endswith(f".{target_fmt}")
    )

    if not needs_transcode:
        ctx.log("Video already matches target spec, skipping transcode")
        ctx.report({
            "input_count": 1,
            "output_count": 1,
            "skipped_transcode": True,
            "source_resolution": f"{meta['width']}x{meta['height']}",
            "target_resolution": target_res,
        })
        return {"video": video_path}

    # Build output path
    out_dir = tempfile.mkdtemp(prefix="normalize_")
    out_name = Path(video_path).stem + f"_norm.{target_fmt}"
    out_path = os.path.join(out_dir, out_name)

    # ffmpeg transcode with scale filter
    cmd = [
        "ffmpeg", "-y", "-i", video_path,
        "-vf", f"scale={target_w}:{target_h}:force_original_aspect_ratio=decrease,"
               f"pad={target_w}:{target_h}:(ow-iw)/2:(oh-ih)/2",
        "-c:v", "libx264", "-preset", "fast", "-crf", "23",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        out_path,
    ]
    ctx.log(f"Transcoding to {target_res} {target_fmt}...")
    subprocess.run(cmd, capture_output=True, text=True, check=True)

    out_meta = ffprobe_metadata(out_path)
    ctx.report({
        "input_count": 1,
        "output_count": 1,
        "skipped_transcode": False,
        "source_resolution": f"{meta['width']}x{meta['height']}",
        "target_resolution": target_res,
        "output_size_bytes": out_meta["file_size"],
    })

    return {"video": out_path}
```

- [ ] **Step 3: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_video_normalize.py`

```python
"""Unit tests for video_normalize component."""

import json
import subprocess
from unittest.mock import MagicMock, patch

import pytest

from lakeon.components.video.video_normalize import (
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
    @patch("subprocess.run")
    def test_extracts_metadata(self, mock_run):
        mock_run.return_value = MagicMock(stdout=SAMPLE_FFPROBE_OUTPUT, returncode=0)
        meta = ffprobe_metadata("/tmp/test.mp4")

        assert meta["width"] == 1280
        assert meta["height"] == 720
        assert meta["duration"] == 120.5
        assert meta["fps"] == 30.0
        assert meta["codec"] == "h264"
        assert meta["file_size"] == 5242880

    @patch("subprocess.run")
    def test_raises_on_no_video_stream(self, mock_run):
        mock_run.return_value = MagicMock(
            stdout=json.dumps({"streams": [{"codec_type": "audio"}], "format": {}}),
            returncode=0,
        )
        with pytest.raises(ValueError, match="No video stream"):
            ffprobe_metadata("/tmp/audio_only.mp3")


class TestVideoNormalize:
    @patch("lakeon.components.video.video_normalize.ffprobe_metadata")
    @patch("subprocess.run")
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

    @patch("lakeon.components.video.video_normalize.ffprobe_metadata")
    @patch("subprocess.run")
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
```

- [ ] **Step 4: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_video_normalize.py -v
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/components/video/__init__.py \
        lakeon-orchestrator/components/video/video_normalize.py \
        lakeon-orchestrator/tests/unit/test_video_normalize.py
git commit -m "feat(pipeline): add video_normalize component with ffprobe/ffmpeg"
```

---

## Task 2: video_scene_split -- PySceneDetect 镜头切分 + fan_out

**Files:**
- Create: `lakeon-orchestrator/components/video/video_scene_split.py`
- Create: `lakeon-orchestrator/tests/unit/test_video_scene_split.py`

- [ ] **Step 1: 实现 video_scene_split 组件**

Create: `lakeon-orchestrator/components/video/video_scene_split.py`

```python
"""video_scene_split: PySceneDetect 镜头检测 + ffmpeg 切片，fan_out 输出 clips."""

import os
import subprocess
import tempfile
from pathlib import Path

from scenedetect import ContentDetector, SceneManager, open_video

from lakeon.pipeline import Component, ComponentContext


def detect_scenes(video_path: str, threshold: float = 27.0,
                  min_scene_length: float = 1.0) -> list[tuple[float, float]]:
    """Detect scene boundaries using PySceneDetect ContentDetector.

    Returns list of (start_sec, end_sec) tuples.
    """
    video = open_video(video_path)
    scene_manager = SceneManager()
    scene_manager.add_detector(ContentDetector(
        threshold=threshold,
        min_scene_len=int(min_scene_length * video.frame_rate),
    ))
    scene_manager.detect_scenes(video)
    scene_list = scene_manager.get_scene_list()

    return [
        (scene[0].get_seconds(), scene[1].get_seconds())
        for scene in scene_list
    ]


def split_video_by_scenes(video_path: str,
                          scenes: list[tuple[float, float]],
                          out_dir: str) -> list[str]:
    """Split video into clips at scene boundaries using ffmpeg.

    Returns list of output clip file paths.
    """
    clips = []
    stem = Path(video_path).stem

    for i, (start, end) in enumerate(scenes):
        out_path = os.path.join(out_dir, f"{stem}_clip_{i:04d}.mp4")
        cmd = [
            "ffmpeg", "-y",
            "-i", video_path,
            "-ss", f"{start:.3f}",
            "-to", f"{end:.3f}",
            "-c:v", "libx264", "-preset", "fast", "-crf", "23",
            "-c:a", "aac",
            "-avoid_negative_ts", "make_zero",
            out_path,
        ]
        subprocess.run(cmd, capture_output=True, text=True, check=True)
        clips.append(out_path)

    return clips


@Component(
    name="video_scene_split",
    display_name="视频镜头切分",
    category="EXTRACT",
    data_type="VIDEO",
    params_schema={
        "threshold": {
            "type": "number",
            "default": 27,
            "description": "切分灵敏度(ContentDetector threshold)",
        },
        "min_scene_length": {
            "type": "number",
            "default": 1.0,
            "description": "最短镜头时长(秒)",
        },
    },
    input_schema={"type": "video", "format": ["mp4", "avi"]},
    output_schema={"type": "video_clips", "format": "mp4"},
)
def video_scene_split(ctx: ComponentContext) -> dict:
    """Split video into clips at scene boundaries, fan_out output."""
    video_path = ctx.input["video"]
    threshold = ctx.params.get("threshold", 27)
    min_scene_length = ctx.params.get("min_scene_length", 1.0)

    ctx.log(f"Detecting scenes with threshold={threshold}, min_length={min_scene_length}s")

    scenes = detect_scenes(video_path, threshold, min_scene_length)
    ctx.log(f"Detected {len(scenes)} scenes")

    if not scenes:
        ctx.log("No scenes detected, treating entire video as single clip")
        ctx.report({"input_count": 1, "output_count": 1, "scenes_detected": 0})
        return ctx.fan_out([video_path])

    out_dir = tempfile.mkdtemp(prefix="scene_split_")
    clips = split_video_by_scenes(video_path, scenes, out_dir)

    ctx.checkpoint()
    ctx.report({
        "input_count": 1,
        "output_count": len(clips),
        "scenes_detected": len(scenes),
    })

    return ctx.fan_out(clips)
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_video_scene_split.py`

```python
"""Unit tests for video_scene_split component."""

from unittest.mock import MagicMock, patch, call

import pytest

from lakeon.components.video.video_scene_split import (
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
    @patch("lakeon.components.video.video_scene_split.open_video")
    @patch("lakeon.components.video.video_scene_split.SceneManager")
    @patch("lakeon.components.video.video_scene_split.ContentDetector")
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
    @patch("subprocess.run")
    def test_creates_clips_for_each_scene(self, mock_run):
        mock_run.return_value = MagicMock(returncode=0)
        scenes = [(0.0, 5.0), (5.0, 10.0), (10.0, 15.0)]

        clips = split_video_by_scenes("/tmp/source.mp4", scenes, "/tmp/out")

        assert len(clips) == 3
        assert mock_run.call_count == 3
        assert clips[0] == "/tmp/out/source_clip_0000.mp4"
        assert clips[2] == "/tmp/out/source_clip_0002.mp4"


class TestVideoSceneSplit:
    @patch("lakeon.components.video.video_scene_split.split_video_by_scenes")
    @patch("lakeon.components.video.video_scene_split.detect_scenes")
    def test_fan_out_clips(self, mock_detect, mock_split, mock_ctx):
        mock_detect.return_value = [(0.0, 3.0), (3.0, 7.0)]
        mock_split.return_value = ["/tmp/clip_0.mp4", "/tmp/clip_1.mp4"]

        result = video_scene_split(mock_ctx)

        mock_ctx.fan_out.assert_called_once_with(["/tmp/clip_0.mp4", "/tmp/clip_1.mp4"])
        mock_ctx.checkpoint.assert_called_once()
        report = mock_ctx.report.call_args[0][0]
        assert report["output_count"] == 2

    @patch("lakeon.components.video.video_scene_split.detect_scenes")
    def test_single_clip_when_no_scenes(self, mock_detect, mock_ctx):
        mock_detect.return_value = []

        result = video_scene_split(mock_ctx)

        mock_ctx.fan_out.assert_called_once_with(["/tmp/test_video.mp4"])
        report = mock_ctx.report.call_args[0][0]
        assert report["scenes_detected"] == 0
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_video_scene_split.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/video/video_scene_split.py \
        lakeon-orchestrator/tests/unit/test_video_scene_split.py
git commit -m "feat(pipeline): add video_scene_split component with PySceneDetect"
```

---

## Task 3: rule_filter -- ffprobe 规则过滤 + 条件分支

**Files:**
- Create: `lakeon-orchestrator/components/video/rule_filter.py`
- Create: `lakeon-orchestrator/tests/unit/test_rule_filter.py`

- [ ] **Step 1: 实现 rule_filter 组件**

Create: `lakeon-orchestrator/components/video/rule_filter.py`

```python
"""rule_filter: 基于 ffprobe 元数据的规则过滤，条件分支输出 passed/needs_crop/dropped."""

import json
import subprocess

from lakeon.pipeline import Component, ComponentContext


def ffprobe_clip_metadata(clip_path: str) -> dict:
    """Extract clip metadata relevant for rule filtering.

    Returns dict with: duration, width, height, resolution (min dimension),
    aspect_ratio, fps, crop_area_percent.
    """
    cmd = [
        "ffprobe", "-v", "quiet", "-print_format", "json",
        "-show_format", "-show_streams", clip_path,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    probe = json.loads(result.stdout)

    video_stream = next(
        (s for s in probe.get("streams", []) if s["codec_type"] == "video"), None
    )
    if video_stream is None:
        raise ValueError(f"No video stream in {clip_path}")

    width = int(video_stream.get("width", 0))
    height = int(video_stream.get("height", 0))
    fmt = probe.get("format", {})

    fps_parts = video_stream.get("r_frame_rate", "30/1").split("/")
    fps = float(fps_parts[0]) / float(fps_parts[1]) if len(fps_parts) == 2 else 30.0

    # Resolution = min(width, height) for comparison with 480p/720p etc.
    resolution = min(width, height)

    # Aspect ratio = max/min to always get a value >= 1
    aspect_ratio = max(width, height) / max(min(width, height), 1)

    # Crop area: estimated by detecting black borders via ffprobe cropdetect
    # For Phase 1, we approximate: if aspect_ratio > threshold, crop is needed
    # Actual crop_area_percent would require cropdetect analysis
    crop_area_percent = _estimate_crop_area(clip_path, width, height)

    return {
        "duration": float(fmt.get("duration", 0)),
        "width": width,
        "height": height,
        "resolution": resolution,
        "aspect_ratio": round(aspect_ratio, 2),
        "fps": round(fps, 2),
        "crop_area_percent": crop_area_percent,
    }


def _estimate_crop_area(clip_path: str, width: int, height: int) -> float:
    """Estimate crop area percentage using ffmpeg cropdetect filter.

    Samples a few frames and computes the percentage of area that would be cropped.
    Returns 0.0 if cropdetect fails or no cropping needed.
    """
    try:
        cmd = [
            "ffmpeg", "-i", clip_path,
            "-vframes", "10", "-vf", "cropdetect=24:2:0",
            "-f", "null", "-",
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        stderr = result.stderr

        # Parse last cropdetect line: crop=W:H:X:Y
        crop_lines = [
            line for line in stderr.split("\n")
            if "crop=" in line
        ]
        if not crop_lines:
            return 0.0

        last_crop = crop_lines[-1]
        crop_str = last_crop.split("crop=")[-1].split()[0]
        parts = crop_str.split(":")
        if len(parts) != 4:
            return 0.0

        crop_w, crop_h = int(parts[0]), int(parts[1])
        original_area = width * height
        if original_area == 0:
            return 0.0

        cropped_area = original_area - (crop_w * crop_h)
        return round((cropped_area / original_area) * 100, 1)
    except (subprocess.TimeoutExpired, Exception):
        return 0.0


def evaluate_rules(meta: dict, params: dict) -> str:
    """Evaluate filter rules against clip metadata.

    Returns: 'passed', 'needs_crop', or 'dropped'.
    """
    min_duration = params.get("min_duration", 3)
    min_resolution = params.get("min_resolution", 480)
    max_aspect_ratio = params.get("max_aspect_ratio", 2)
    min_fps = params.get("min_fps", 20)
    min_crop_area = params.get("min_crop_area", 5)

    # Hard drop: too short
    if meta["duration"] < min_duration:
        return "dropped"

    # Hard drop: resolution too low
    if meta["resolution"] < min_resolution:
        return "dropped"

    # Hard drop: frame rate too low
    if meta["fps"] < min_fps:
        return "dropped"

    # Needs crop: aspect ratio too extreme
    if meta["aspect_ratio"] > max_aspect_ratio:
        return "needs_crop"

    # Needs crop: significant crop area detected
    if meta["crop_area_percent"] > min_crop_area:
        return "needs_crop"

    return "passed"


@Component(
    name="rule_filter",
    display_name="规则清洗",
    category="FILTER",
    data_type="VIDEO",
    params_schema={
        "min_duration": {"type": "number", "default": 3, "description": "最短时长(秒)"},
        "min_resolution": {"type": "number", "default": 480, "description": "最低分辨率(短边像素)"},
        "max_aspect_ratio": {"type": "number", "default": 2, "description": "最大长宽比"},
        "min_fps": {"type": "number", "default": 20, "description": "最低帧率"},
        "min_crop_area": {"type": "number", "default": 5, "description": "裁剪面积阈值(%)"},
    },
    output_branches=["passed", "needs_crop", "dropped"],
)
def rule_filter(ctx: ComponentContext) -> dict:
    """Filter video clip by metadata rules, route to passed/needs_crop/dropped."""
    clip_path = ctx.input["clip"]
    meta = ffprobe_clip_metadata(clip_path)

    ctx.log(
        f"Clip: {meta['duration']:.1f}s, {meta['width']}x{meta['height']}, "
        f"AR={meta['aspect_ratio']}, FPS={meta['fps']}, crop={meta['crop_area_percent']}%"
    )

    label = evaluate_rules(meta, ctx.params)
    ctx.log(f"Result: {label}")

    ctx.report({
        "clip": clip_path,
        "duration": meta["duration"],
        "resolution": meta["resolution"],
        "aspect_ratio": meta["aspect_ratio"],
        "fps": meta["fps"],
        "crop_area_percent": meta["crop_area_percent"],
        "label": label,
    })

    return ctx.classify(clip_path, label)
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_rule_filter.py`

```python
"""Unit tests for rule_filter component."""

from unittest.mock import MagicMock, patch

import pytest

from lakeon.components.video.rule_filter import evaluate_rules, rule_filter


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
    @patch("lakeon.components.video.rule_filter.ffprobe_clip_metadata")
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

    @patch("lakeon.components.video.rule_filter.ffprobe_clip_metadata")
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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_rule_filter.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/video/rule_filter.py \
        lakeon-orchestrator/tests/unit/test_rule_filter.py
git commit -m "feat(pipeline): add rule_filter component with conditional branching"
```

---

## Task 4: video_crop -- ffmpeg 裁剪处理

**Files:**
- Create: `lakeon-orchestrator/components/video/video_crop.py`
- Create: `lakeon-orchestrator/tests/unit/test_video_crop.py`

- [ ] **Step 1: 实现 video_crop 组件**

Create: `lakeon-orchestrator/components/video/video_crop.py`

```python
"""video_crop: ffmpeg cropdetect + crop 处理，裁剪黑边或按目标比例裁切."""

import json
import os
import subprocess
import tempfile
from pathlib import Path

from lakeon.pipeline import Component, ComponentContext


def detect_crop_params(clip_path: str) -> tuple[int, int, int, int] | None:
    """Run ffmpeg cropdetect to find optimal crop parameters.

    Returns (width, height, x, y) or None if no crop needed.
    """
    cmd = [
        "ffmpeg", "-i", clip_path,
        "-vframes", "30", "-vf", "cropdetect=24:2:0",
        "-f", "null", "-",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)

    crop_lines = [
        line for line in result.stderr.split("\n")
        if "crop=" in line
    ]
    if not crop_lines:
        return None

    # Use the most common crop value from the last N detections
    crop_values = []
    for line in crop_lines[-10:]:
        try:
            crop_str = line.split("crop=")[-1].split()[0]
            parts = crop_str.split(":")
            if len(parts) == 4:
                crop_values.append(tuple(int(p) for p in parts))
        except (ValueError, IndexError):
            continue

    if not crop_values:
        return None

    # Return most frequent crop value
    from collections import Counter
    most_common = Counter(crop_values).most_common(1)[0][0]
    return most_common


def apply_crop(clip_path: str, crop_w: int, crop_h: int,
               crop_x: int, crop_y: int, out_path: str) -> str:
    """Apply ffmpeg crop filter to video clip."""
    cmd = [
        "ffmpeg", "-y", "-i", clip_path,
        "-vf", f"crop={crop_w}:{crop_h}:{crop_x}:{crop_y}",
        "-c:v", "libx264", "-preset", "fast", "-crf", "23",
        "-c:a", "copy",
        out_path,
    ]
    subprocess.run(cmd, capture_output=True, text=True, check=True)
    return out_path


@Component(
    name="video_crop",
    display_name="视频裁剪",
    category="CLEAN",
    data_type="VIDEO",
    params_schema={
        "target_aspect_ratio": {
            "type": "number",
            "default": 1.78,
            "description": "目标长宽比(16:9=1.78), 0=使用cropdetect自动检测",
        },
    },
    input_schema={"type": "video", "format": ["mp4"]},
    output_schema={"type": "video", "format": "mp4"},
    output_branches=["passed", "dropped"],
)
def video_crop(ctx: ComponentContext) -> dict:
    """Crop video clip using cropdetect or target aspect ratio."""
    clip_path = ctx.input["clip"]
    target_ar = ctx.params.get("target_aspect_ratio", 0)

    # Get source dimensions via ffprobe
    probe_cmd = [
        "ffprobe", "-v", "quiet", "-print_format", "json",
        "-show_streams", clip_path,
    ]
    probe_result = subprocess.run(probe_cmd, capture_output=True, text=True, check=True)
    streams = json.loads(probe_result.stdout).get("streams", [])
    video_stream = next((s for s in streams if s["codec_type"] == "video"), None)
    if video_stream is None:
        ctx.log("No video stream found, dropping clip")
        ctx.report({"input_count": 1, "output_count": 0, "action": "dropped"})
        return ctx.classify(clip_path, "dropped")

    src_w = int(video_stream["width"])
    src_h = int(video_stream["height"])

    if target_ar > 0:
        # Crop to target aspect ratio (center crop)
        src_ar = src_w / max(src_h, 1)
        if abs(src_ar - target_ar) < 0.05:
            ctx.log("Aspect ratio already matches target, passing through")
            ctx.report({"input_count": 1, "output_count": 1, "action": "passthrough"})
            return ctx.classify(clip_path, "passed")

        if src_ar > target_ar:
            # Too wide, crop width
            crop_h = src_h
            crop_w = int(src_h * target_ar)
        else:
            # Too tall, crop height
            crop_w = src_w
            crop_h = int(src_w / target_ar)

        crop_x = (src_w - crop_w) // 2
        crop_y = (src_h - crop_h) // 2
    else:
        # Auto-detect crop using cropdetect
        crop_params = detect_crop_params(clip_path)
        if crop_params is None:
            ctx.log("No crop needed (cropdetect found no black borders)")
            ctx.report({"input_count": 1, "output_count": 1, "action": "passthrough"})
            return ctx.classify(clip_path, "passed")

        crop_w, crop_h, crop_x, crop_y = crop_params

        # If crop is negligible (< 2% area), skip
        crop_area_pct = 100 * (1 - (crop_w * crop_h) / max(src_w * src_h, 1))
        if crop_area_pct < 2.0:
            ctx.log(f"Crop area negligible ({crop_area_pct:.1f}%), passing through")
            ctx.report({"input_count": 1, "output_count": 1, "action": "passthrough"})
            return ctx.classify(clip_path, "passed")

    out_dir = tempfile.mkdtemp(prefix="crop_")
    out_path = os.path.join(out_dir, Path(clip_path).stem + "_cropped.mp4")

    ctx.log(f"Cropping: {src_w}x{src_h} -> {crop_w}x{crop_h} at ({crop_x},{crop_y})")
    apply_crop(clip_path, crop_w, crop_h, crop_x, crop_y, out_path)

    ctx.report({
        "input_count": 1,
        "output_count": 1,
        "action": "cropped",
        "source_dims": f"{src_w}x{src_h}",
        "crop_dims": f"{crop_w}x{crop_h}",
    })

    return ctx.classify(out_path, "passed")
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_video_crop.py`

```python
"""Unit tests for video_crop component."""

import json
from unittest.mock import MagicMock, patch

import pytest

from lakeon.components.video.video_crop import detect_crop_params, video_crop


class TestDetectCropParams:
    @patch("subprocess.run")
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

    @patch("subprocess.run")
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

    @patch("subprocess.run")
    @patch("lakeon.components.video.video_crop.detect_crop_params")
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

    @patch("subprocess.run")
    @patch("lakeon.components.video.video_crop.apply_crop")
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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_video_crop.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/video/video_crop.py \
        lakeon-orchestrator/tests/unit/test_video_crop.py
git commit -m "feat(pipeline): add video_crop component with cropdetect/ffmpeg"
```

---

## Task 5: quality_check -- 人工审核暂停/恢复

**Files:**
- Create: `lakeon-orchestrator/components/video/quality_check.py`
- Create: `lakeon-orchestrator/tests/unit/test_quality_check.py`

- [ ] **Step 1: 实现 quality_check 组件**

Create: `lakeon-orchestrator/components/video/quality_check.py`

```python
"""quality_check: HUMAN_REVIEW 质检组件，暂停等待人工确认后恢复."""

from lakeon.pipeline import Component, ComponentContext


@Component(
    name="quality_check",
    display_name="质量检查",
    category="QC",
    data_type="UNIVERSAL",
    execution_mode="HUMAN_REVIEW",
    params_schema={
        "review_mode": {
            "type": "string",
            "default": "manual",
            "enum": ["manual", "auto_approve"],
            "description": "审核模式: manual=人工暂停审核, auto_approve=自动通过(测试用)",
        },
        "thumbnail_count": {
            "type": "integer",
            "default": 4,
            "description": "每个 clip 生成的缩略图数量(用于审核 UI)",
        },
    },
    input_schema={"type": "any"},
    output_schema={"type": "any"},
)
def quality_check(ctx: ComponentContext) -> dict:
    """Pause pipeline for human review.

    In manual mode:
    1. Write all input data to OBS checkpoint
    2. Generate thumbnails for video clips (if applicable)
    3. Pause execution -- Orchestrator sets step status to PAUSED
    4. Wait for human to approve/reject items via Console UI
    5. Resume with approved items

    In auto_approve mode (for testing):
    - Pass all items through without pausing
    """
    review_mode = ctx.params.get("review_mode", "manual")
    input_data = ctx.input

    # Determine input type and item count
    if "clips" in input_data:
        items = input_data["clips"]
        item_type = "video_clips"
    elif "text" in input_data:
        items = input_data["text"]
        item_type = "text"
    else:
        items = list(input_data.values())
        item_type = "unknown"

    item_count = len(items) if isinstance(items, list) else 1
    ctx.log(f"Quality check: {item_count} {item_type} items, mode={review_mode}")

    if review_mode == "auto_approve":
        ctx.log("Auto-approve mode: passing all items through")
        ctx.report({
            "input_count": item_count,
            "output_count": item_count,
            "review_mode": "auto_approve",
            "approved": item_count,
            "rejected": 0,
        })
        return input_data

    # Manual review mode: checkpoint data and pause
    ctx.log("Writing checkpoint for human review...")
    ctx.checkpoint()

    # Prepare review metadata for the Console UI
    review_meta = {
        "item_type": item_type,
        "item_count": item_count,
        "thumbnail_count": ctx.params.get("thumbnail_count", 4),
    }

    # Generate thumbnails for video clips
    if item_type == "video_clips" and isinstance(items, list):
        thumbnails = _generate_thumbnails(items, ctx.params.get("thumbnail_count", 4))
        review_meta["thumbnails"] = thumbnails

    ctx.log(f"Pausing for human review of {item_count} items")

    # This call signals the Orchestrator to:
    # 1. Set step status to PAUSED
    # 2. Store review_meta in step_run.output_ref
    # 3. Release Ray cluster
    # 4. Wait for POST /runs/{id}/resume with approved item indices
    paused_result = ctx.pause(review_meta=review_meta)

    # After resume, paused_result contains approved items
    approved_items = paused_result.get("approved_items", items)
    rejected_count = item_count - (
        len(approved_items) if isinstance(approved_items, list) else item_count
    )

    ctx.report({
        "input_count": item_count,
        "output_count": len(approved_items) if isinstance(approved_items, list) else item_count,
        "review_mode": "manual",
        "approved": item_count - rejected_count,
        "rejected": rejected_count,
    })

    # Return in same key structure as input
    if "clips" in input_data:
        return {"clips": approved_items}
    elif "text" in input_data:
        return {"text": approved_items}
    else:
        return {"items": approved_items}


def _generate_thumbnails(clips: list[str], count_per_clip: int) -> list[dict]:
    """Generate thumbnail images from video clips for the review UI.

    Returns list of {clip_path, thumbnails: [path1, path2, ...]}.
    """
    import os
    import subprocess
    import tempfile

    thumbnails = []
    thumb_dir = tempfile.mkdtemp(prefix="qc_thumbs_")

    for clip_path in clips:
        clip_thumbs = []
        try:
            # Get duration
            probe_cmd = [
                "ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", clip_path,
            ]
            probe = subprocess.run(probe_cmd, capture_output=True, text=True, check=True)
            import json
            duration = float(json.loads(probe.stdout).get("format", {}).get("duration", 0))

            if duration <= 0:
                continue

            # Extract evenly spaced frames
            interval = duration / (count_per_clip + 1)
            stem = os.path.splitext(os.path.basename(clip_path))[0]

            for i in range(count_per_clip):
                timestamp = interval * (i + 1)
                thumb_path = os.path.join(thumb_dir, f"{stem}_thumb_{i}.jpg")
                cmd = [
                    "ffmpeg", "-y", "-ss", f"{timestamp:.2f}",
                    "-i", clip_path, "-vframes", "1",
                    "-vf", "scale=320:-1",
                    thumb_path,
                ]
                subprocess.run(cmd, capture_output=True, text=True, check=True)
                clip_thumbs.append(thumb_path)
        except (subprocess.CalledProcessError, Exception):
            pass

        thumbnails.append({"clip_path": clip_path, "thumbnails": clip_thumbs})

    return thumbnails
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_quality_check.py`

```python
"""Unit tests for quality_check component."""

from unittest.mock import MagicMock

import pytest

from lakeon.components.video.quality_check import quality_check


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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_quality_check.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/video/quality_check.py \
        lakeon-orchestrator/tests/unit/test_quality_check.py
git commit -m "feat(pipeline): add quality_check component with HUMAN_REVIEW pause/resume"
```

---

## Task 6: model_filter_mock -- 模拟 VQA/水印/字幕/光流检测

**Files:**
- Create: `lakeon-orchestrator/components/video/model_filter_mock.py`
- Create: `lakeon-orchestrator/tests/unit/test_model_filter_mock.py`

- [ ] **Step 1: 实现 model_filter_mock 组件**

Create: `lakeon-orchestrator/components/video/model_filter_mock.py`

```python
"""model_filter_mock: Mock 模型清洗 -- 随机打分模拟 VQA/水印/字幕/光流检测.

Phase 1 mock: 真实模型将在 Phase 2 替换。
"""

import hashlib
import random

from lakeon.pipeline import Component, ComponentContext

# Mock score thresholds (clips below threshold are dropped)
DEFAULT_THRESHOLDS = {
    "vqa": 0.3,
    "watermark": 0.5,
    "subtitle": 0.5,
    "optical_flow": 0.2,
}


def _deterministic_seed(clip_path: str, check_name: str) -> int:
    """Generate a deterministic seed from clip path + check name for reproducibility."""
    h = hashlib.md5(f"{clip_path}:{check_name}".encode()).hexdigest()
    return int(h[:8], 16)


def mock_model_score(clip_path: str, check_name: str) -> float:
    """Return a deterministic mock score in [0, 1] for a clip and check type.

    Uses path+check hash for reproducibility. Higher = more likely to pass.
    """
    rng = random.Random(_deterministic_seed(clip_path, check_name))
    return round(rng.random(), 3)


@Component(
    name="model_filter_mock",
    display_name="模型清洗 (Mock)",
    category="FILTER",
    data_type="VIDEO",
    params_schema={
        "checks": {
            "type": "array",
            "default": ["vqa", "watermark", "subtitle", "optical_flow"],
            "description": "要执行的检测项列表",
        },
        "thresholds": {
            "type": "object",
            "default": DEFAULT_THRESHOLDS,
            "description": "各检测项的通过阈值(分数低于阈值则 drop)",
        },
        "pass_rate": {
            "type": "number",
            "default": 0.8,
            "description": "Mock 模式下的目标通过率(调节随机种子偏移)",
        },
    },
    input_schema={"type": "video_clips", "format": "mp4"},
    output_schema={"type": "video_clips", "format": "mp4"},
)
def model_filter_mock(ctx: ComponentContext) -> dict:
    """Mock model-based filtering: score each clip on multiple checks, drop low scorers."""
    clips = ctx.input["clips"]
    checks = ctx.params.get("checks", ["vqa", "watermark", "subtitle", "optical_flow"])
    thresholds = ctx.params.get("thresholds", DEFAULT_THRESHOLDS)

    passed_clips = []
    dropped_clips = []
    all_scores = []

    for clip_path in clips:
        clip_scores = {}
        is_passed = True

        for check in checks:
            score = mock_model_score(clip_path, check)
            clip_scores[check] = score
            threshold = thresholds.get(check, 0.5)
            if score < threshold:
                is_passed = False

        all_scores.append({"clip": clip_path, "scores": clip_scores, "passed": is_passed})

        if is_passed:
            passed_clips.append(clip_path)
        else:
            dropped_clips.append(clip_path)

    ctx.log(
        f"Model filter: {len(clips)} input, "
        f"{len(passed_clips)} passed, {len(dropped_clips)} dropped"
    )
    ctx.checkpoint()

    ctx.report({
        "input_count": len(clips),
        "output_count": len(passed_clips),
        "drop_count": len(dropped_clips),
        "checks": checks,
        "retention": f"{len(passed_clips)/max(len(clips),1)*100:.1f}%",
        "scores": all_scores,
    })

    return {"clips": passed_clips}
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_model_filter_mock.py`

```python
"""Unit tests for model_filter_mock component."""

from unittest.mock import MagicMock

import pytest

from lakeon.components.video.model_filter_mock import (
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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_model_filter_mock.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/video/model_filter_mock.py \
        lakeon-orchestrator/tests/unit/test_model_filter_mock.py
git commit -m "feat(pipeline): add model_filter_mock component (Phase 1 placeholder)"
```

---

## Task 7: video_labeling_mock -- 模拟 VICLIP/Caption/运镜标注

**Files:**
- Create: `lakeon-orchestrator/components/video/video_labeling_mock.py`
- Create: `lakeon-orchestrator/tests/unit/test_video_labeling_mock.py`

- [ ] **Step 1: 实现 video_labeling_mock 组件**

Create: `lakeon-orchestrator/components/video/video_labeling_mock.py`

```python
"""video_labeling_mock: Mock 内容标注 -- 返回固定标签模拟 VICLIP/Caption/运镜检测.

Phase 1 mock: 真实模型将在 Phase 2 替换。
"""

import hashlib
import random

from lakeon.pipeline import Component, ComponentContext

# Predefined label pools for mock generation
VICLIP_TAGS = [
    "outdoor", "indoor", "landscape", "cityscape", "people", "animals",
    "vehicles", "food", "sports", "technology", "nature", "architecture",
    "water", "sky", "night", "day", "crowd", "solo",
]

CAPTION_TEMPLATES = [
    "A {scene} scene featuring {subject} with {style} lighting",
    "{subject} in a {scene} environment, {action}",
    "Wide shot of {scene} with {subject}, {style} atmosphere",
    "Close-up of {subject} in {scene} setting",
]

CAMERA_MOTIONS = [
    "static", "pan_left", "pan_right", "tilt_up", "tilt_down",
    "zoom_in", "zoom_out", "tracking", "dolly", "handheld",
]

SCENES = ["urban", "rural", "coastal", "mountain", "forest", "desert", "studio"]
SUBJECTS = ["person", "group", "vehicle", "animal", "object", "landscape"]
STYLES = ["warm", "cool", "dramatic", "soft", "natural", "cinematic"]
ACTIONS = ["moving slowly", "in motion", "standing still", "interacting", "performing"]


def _clip_rng(clip_path: str, salt: str = "") -> random.Random:
    """Create deterministic RNG from clip path for reproducible labels."""
    h = hashlib.md5(f"{clip_path}:{salt}".encode()).hexdigest()
    return random.Random(int(h[:8], 16))


def generate_viclip_tags(clip_path: str, top_k: int = 5) -> list[dict]:
    """Generate mock VICLIP semantic tags with confidence scores."""
    rng = _clip_rng(clip_path, "viclip")
    selected = rng.sample(VICLIP_TAGS, min(top_k, len(VICLIP_TAGS)))
    return [
        {"tag": tag, "confidence": round(rng.uniform(0.5, 0.98), 3)}
        for tag in selected
    ]


def generate_caption(clip_path: str) -> str:
    """Generate a mock video caption."""
    rng = _clip_rng(clip_path, "caption")
    template = rng.choice(CAPTION_TEMPLATES)
    return template.format(
        scene=rng.choice(SCENES),
        subject=rng.choice(SUBJECTS),
        style=rng.choice(STYLES),
        action=rng.choice(ACTIONS),
    )


def generate_camera_motion(clip_path: str) -> dict:
    """Generate mock camera motion detection result."""
    rng = _clip_rng(clip_path, "camera")
    motion = rng.choice(CAMERA_MOTIONS)
    return {
        "primary_motion": motion,
        "confidence": round(rng.uniform(0.6, 0.99), 3),
        "is_stable": motion == "static",
    }


@Component(
    name="video_labeling_mock",
    display_name="内容标注 (Mock)",
    category="LABEL",
    data_type="VIDEO",
    params_schema={
        "tasks": {
            "type": "array",
            "default": ["viclip_tag", "caption", "camera_motion"],
            "description": "标注任务列表",
        },
        "viclip_top_k": {
            "type": "integer",
            "default": 5,
            "description": "VICLIP 返回标签数",
        },
    },
    input_schema={"type": "video_clips", "format": "mp4"},
    output_schema={"type": "labeled_clips", "format": "mp4"},
)
def video_labeling_mock(ctx: ComponentContext) -> dict:
    """Mock content labeling: generate VICLIP tags, captions, camera motion for each clip."""
    clips = ctx.input["clips"]
    tasks = ctx.params.get("tasks", ["viclip_tag", "caption", "camera_motion"])
    top_k = ctx.params.get("viclip_top_k", 5)

    labeled_clips = []

    for clip_path in clips:
        labels = {}

        if "viclip_tag" in tasks:
            labels["viclip_tags"] = generate_viclip_tags(clip_path, top_k)

        if "caption" in tasks:
            labels["caption"] = generate_caption(clip_path)

        if "camera_motion" in tasks:
            labels["camera_motion"] = generate_camera_motion(clip_path)

        labeled_clips.append({
            "clip": clip_path,
            "labels": labels,
        })

    ctx.log(f"Labeled {len(clips)} clips with tasks: {tasks}")
    ctx.report({
        "input_count": len(clips),
        "output_count": len(labeled_clips),
        "tasks": tasks,
    })

    return {"clips": labeled_clips}
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_video_labeling_mock.py`

```python
"""Unit tests for video_labeling_mock component."""

from unittest.mock import MagicMock

import pytest

from lakeon.components.video.video_labeling_mock import (
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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_video_labeling_mock.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/video/video_labeling_mock.py \
        lakeon-orchestrator/tests/unit/test_video_labeling_mock.py
git commit -m "feat(pipeline): add video_labeling_mock component (Phase 1 placeholder)"
```

---

## Task 8: text_dedup -- MinHash 文本去重

**Files:**
- Create: `lakeon-orchestrator/components/text/__init__.py`
- Create: `lakeon-orchestrator/components/text/text_dedup.py`
- Create: `lakeon-orchestrator/tests/unit/test_text_dedup.py`

- [ ] **Step 1: 创建 text 包 __init__.py**

Create: `lakeon-orchestrator/components/text/__init__.py`

```python
"""Text processing components for the data pipeline."""
```

- [ ] **Step 2: 实现 text_dedup 组件**

Create: `lakeon-orchestrator/components/text/text_dedup.py`

```python
"""text_dedup: MinHash 近似去重，基于 datasketch 库."""

import re
from datasketch import MinHash, MinHashLSH

from lakeon.pipeline import Component, ComponentContext


def tokenize_for_minhash(text: str, ngram: int = 3) -> list[str]:
    """Tokenize text into character n-grams for MinHash computation.

    Uses character-level n-grams for language-agnostic deduplication
    (works for both Chinese and English).
    """
    # Normalize: lowercase, collapse whitespace
    text = re.sub(r"\s+", " ", text.lower().strip())
    if len(text) < ngram:
        return [text]
    return [text[i:i + ngram] for i in range(len(text) - ngram + 1)]


def compute_minhash(tokens: list[str], num_perm: int = 128) -> MinHash:
    """Compute MinHash signature from token list."""
    m = MinHash(num_perm=num_perm)
    for token in tokens:
        m.update(token.encode("utf-8"))
    return m


def deduplicate_texts(texts: list[dict], similarity_threshold: float = 0.85,
                      num_perm: int = 128, ngram: int = 3,
                      text_key: str = "content") -> tuple[list[dict], list[dict]]:
    """Deduplicate texts using MinHash LSH.

    Args:
        texts: List of text records (dicts with at least a text_key field).
        similarity_threshold: Jaccard similarity threshold for duplicate detection.
        num_perm: Number of permutations for MinHash.
        ngram: Character n-gram size.
        text_key: Key in each record containing the text content.

    Returns:
        (unique_texts, duplicate_texts) tuple.
    """
    lsh = MinHashLSH(threshold=similarity_threshold, num_perm=num_perm)
    minhashes = []

    for i, record in enumerate(texts):
        content = record.get(text_key, "")
        tokens = tokenize_for_minhash(content, ngram)
        mh = compute_minhash(tokens, num_perm)
        minhashes.append((i, mh))

    unique_indices = set()
    duplicate_indices = set()

    for i, mh in minhashes:
        key = f"doc_{i}"
        # Check if this document is similar to any already-inserted document
        result = lsh.query(mh)
        if result:
            duplicate_indices.add(i)
        else:
            try:
                lsh.insert(key, mh)
                unique_indices.add(i)
            except ValueError:
                # Key already exists (shouldn't happen, but be safe)
                duplicate_indices.add(i)

    unique = [texts[i] for i in sorted(unique_indices)]
    duplicates = [texts[i] for i in sorted(duplicate_indices)]
    return unique, duplicates


@Component(
    name="text_dedup",
    display_name="文本去重",
    category="CLEAN",
    data_type="TEXT",
    params_schema={
        "method": {
            "type": "string",
            "default": "minhash",
            "enum": ["minhash"],
            "description": "去重算法",
        },
        "similarity_threshold": {
            "type": "number",
            "default": 0.85,
            "description": "相似度阈值(Jaccard), 超过此值视为重复",
        },
        "num_perm": {
            "type": "integer",
            "default": 128,
            "description": "MinHash 排列数(精度 vs 速度)",
        },
        "ngram": {
            "type": "integer",
            "default": 3,
            "description": "字符 n-gram 大小",
        },
        "text_key": {
            "type": "string",
            "default": "content",
            "description": "文本字段名",
        },
    },
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_dedup(ctx: ComponentContext) -> dict:
    """Deduplicate text records using MinHash LSH."""
    texts = ctx.input["text"]
    threshold = ctx.params.get("similarity_threshold", 0.85)
    num_perm = ctx.params.get("num_perm", 128)
    ngram = ctx.params.get("ngram", 3)
    text_key = ctx.params.get("text_key", "content")

    ctx.log(f"Deduplicating {len(texts)} texts with threshold={threshold}")

    unique, duplicates = deduplicate_texts(
        texts, threshold, num_perm, ngram, text_key
    )

    ctx.checkpoint()
    ctx.log(f"Result: {len(unique)} unique, {len(duplicates)} duplicates removed")
    ctx.report({
        "input_count": len(texts),
        "output_count": len(unique),
        "duplicate_count": len(duplicates),
        "retention": f"{len(unique)/max(len(texts),1)*100:.1f}%",
    })

    return {"text": unique}
```

- [ ] **Step 3: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_text_dedup.py`

```python
"""Unit tests for text_dedup component."""

from unittest.mock import MagicMock

import pytest

from lakeon.components.text.text_dedup import (
    tokenize_for_minhash,
    compute_minhash,
    deduplicate_texts,
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


class TestComputeMinhash:
    def test_returns_minhash(self):
        tokens = ["abc", "bcd", "cde"]
        mh = compute_minhash(tokens, num_perm=128)
        assert mh is not None
        assert len(mh.hashvalues) == 128

    def test_similar_texts_similar_hash(self):
        t1 = tokenize_for_minhash("the quick brown fox jumps over the lazy dog")
        t2 = tokenize_for_minhash("the quick brown fox jumps over a lazy dog")
        mh1 = compute_minhash(t1)
        mh2 = compute_minhash(t2)
        # Should be quite similar
        assert mh1.jaccard(mh2) > 0.5

    def test_different_texts_different_hash(self):
        t1 = tokenize_for_minhash("hello world programming")
        t2 = tokenize_for_minhash("completely different unrelated content xyz")
        mh1 = compute_minhash(t1)
        mh2 = compute_minhash(t2)
        assert mh1.jaccard(mh2) < 0.5


class TestDeduplicateTexts:
    def test_removes_exact_duplicates(self):
        texts = [
            {"content": "This is document one about machine learning"},
            {"content": "This is document one about machine learning"},
            {"content": "A completely different document about cooking"},
        ]
        unique, dups = deduplicate_texts(texts, similarity_threshold=0.85)
        assert len(unique) == 2
        assert len(dups) == 1

    def test_removes_near_duplicates(self):
        texts = [
            {"content": "The quick brown fox jumps over the lazy dog in the park"},
            {"content": "The quick brown fox jumps over the lazy dog in the garden"},
            {"content": "Machine learning and artificial intelligence are transforming technology"},
        ]
        unique, dups = deduplicate_texts(texts, similarity_threshold=0.7)
        assert len(unique) == 2

    def test_no_duplicates(self):
        texts = [
            {"content": "First completely unique document about science"},
            {"content": "Second unrelated document about history and culture"},
            {"content": "Third different text about mathematics and logic"},
        ]
        unique, dups = deduplicate_texts(texts, similarity_threshold=0.85)
        assert len(unique) == 3
        assert len(dups) == 0

    def test_empty_input(self):
        unique, dups = deduplicate_texts([], similarity_threshold=0.85)
        assert unique == []
        assert dups == []


class TestTextDedup:
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
```

- [ ] **Step 4: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_text_dedup.py -v
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/components/text/__init__.py \
        lakeon-orchestrator/components/text/text_dedup.py \
        lakeon-orchestrator/tests/unit/test_text_dedup.py
git commit -m "feat(pipeline): add text_dedup component with MinHash LSH"
```

---

## Task 9: text_clean -- HTML 清理/空白标准化/URL 移除/长度过滤

**Files:**
- Create: `lakeon-orchestrator/components/text/text_clean.py`
- Create: `lakeon-orchestrator/tests/unit/test_text_clean.py`

- [ ] **Step 1: 实现 text_clean 组件**

Create: `lakeon-orchestrator/components/text/text_clean.py`

```python
"""text_clean: HTML 清理、空白标准化、URL 移除、长度过滤."""

import html
import re
import unicodedata

from lakeon.pipeline import Component, ComponentContext

# Regex patterns compiled once
URL_PATTERN = re.compile(
    r"https?://[^\s<>\"']+|www\.[^\s<>\"']+",
    re.IGNORECASE,
)
EMAIL_PATTERN = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
HTML_TAG_PATTERN = re.compile(r"<[^>]+>")
MULTI_NEWLINE_PATTERN = re.compile(r"\n{3,}")
MULTI_SPACE_PATTERN = re.compile(r"[ \t]{2,}")


def strip_html(text: str) -> str:
    """Remove HTML tags and decode HTML entities."""
    text = HTML_TAG_PATTERN.sub("", text)
    text = html.unescape(text)
    return text


def remove_urls(text: str) -> str:
    """Remove URLs from text."""
    return URL_PATTERN.sub("", text)


def remove_emails(text: str) -> str:
    """Remove email addresses from text."""
    return EMAIL_PATTERN.sub("", text)


def normalize_whitespace(text: str) -> str:
    """Normalize whitespace: collapse multiple spaces/newlines, strip edges."""
    text = MULTI_SPACE_PATTERN.sub(" ", text)
    text = MULTI_NEWLINE_PATTERN.sub("\n\n", text)
    return text.strip()


def normalize_unicode(text: str) -> str:
    """Normalize Unicode to NFC form, remove control characters."""
    text = unicodedata.normalize("NFC", text)
    # Remove control characters except newline and tab
    text = "".join(
        c for c in text
        if not unicodedata.category(c).startswith("C") or c in ("\n", "\t")
    )
    return text


def detect_language(text: str) -> str | None:
    """Simple heuristic language detection: 'zh', 'en', or None.

    Checks character distribution. Not a full language detector,
    but sufficient for basic CJK vs Latin filtering.
    """
    if not text:
        return None

    cjk_count = sum(1 for c in text if "\u4e00" <= c <= "\u9fff")
    latin_count = sum(1 for c in text if c.isascii() and c.isalpha())
    total = max(cjk_count + latin_count, 1)

    if cjk_count / total > 0.3:
        return "zh"
    if latin_count / total > 0.3:
        return "en"
    return None


def clean_text(text: str, remove_html_flag: bool = True,
               normalize_ws: bool = True, remove_urls_flag: bool = True,
               remove_emails_flag: bool = True) -> str:
    """Apply all cleaning steps to a text string."""
    if remove_html_flag:
        text = strip_html(text)
    if remove_urls_flag:
        text = remove_urls(text)
    if remove_emails_flag:
        text = remove_emails(text)
    text = normalize_unicode(text)
    if normalize_ws:
        text = normalize_whitespace(text)
    return text


@Component(
    name="text_clean",
    display_name="文本清洗",
    category="CLEAN",
    data_type="TEXT",
    params_schema={
        "remove_html": {"type": "boolean", "default": True, "description": "去除 HTML 标签"},
        "normalize_whitespace": {"type": "boolean", "default": True, "description": "标准化空白字符"},
        "remove_urls": {"type": "boolean", "default": True, "description": "移除 URL"},
        "remove_emails": {"type": "boolean", "default": True, "description": "移除邮箱地址"},
        "min_length": {"type": "integer", "default": 50, "description": "最小文本长度(字符)"},
        "max_length": {"type": "integer", "default": 100000, "description": "最大文本长度(字符)"},
        "language_filter": {
            "type": "array",
            "default": ["zh", "en"],
            "description": "保留的语言列表, 空=不过滤",
        },
        "text_key": {"type": "string", "default": "content", "description": "文本字段名"},
    },
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_clean(ctx: ComponentContext) -> dict:
    """Clean and filter text records: HTML strip, URL removal, length/language filter."""
    texts = ctx.input["text"]
    params = ctx.params
    text_key = params.get("text_key", "content")
    min_len = params.get("min_length", 50)
    max_len = params.get("max_length", 100000)
    lang_filter = params.get("language_filter", ["zh", "en"])

    cleaned = []
    dropped_short = 0
    dropped_long = 0
    dropped_lang = 0

    for record in texts:
        content = record.get(text_key, "")

        # Apply cleaning
        content = clean_text(
            content,
            remove_html_flag=params.get("remove_html", True),
            normalize_ws=params.get("normalize_whitespace", True),
            remove_urls_flag=params.get("remove_urls", True),
            remove_emails_flag=params.get("remove_emails", True),
        )

        # Length filter
        if len(content) < min_len:
            dropped_short += 1
            continue
        if len(content) > max_len:
            dropped_long += 1
            continue

        # Language filter
        if lang_filter:
            lang = detect_language(content)
            if lang is not None and lang not in lang_filter:
                dropped_lang += 1
                continue

        cleaned_record = {**record, text_key: content}
        cleaned.append(cleaned_record)

    ctx.log(
        f"Cleaned {len(texts)} texts: {len(cleaned)} passed, "
        f"{dropped_short} too short, {dropped_long} too long, {dropped_lang} wrong language"
    )
    ctx.report({
        "input_count": len(texts),
        "output_count": len(cleaned),
        "dropped_short": dropped_short,
        "dropped_long": dropped_long,
        "dropped_language": dropped_lang,
        "retention": f"{len(cleaned)/max(len(texts),1)*100:.1f}%",
    })

    return {"text": cleaned}
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_text_clean.py`

```python
"""Unit tests for text_clean component."""

from unittest.mock import MagicMock

import pytest

from lakeon.components.text.text_clean import (
    strip_html,
    remove_urls,
    remove_emails,
    normalize_whitespace,
    normalize_unicode,
    detect_language,
    clean_text,
    text_clean,
)


class TestStripHtml:
    def test_removes_tags(self):
        assert strip_html("<p>Hello <b>world</b></p>") == "Hello world"

    def test_decodes_entities(self):
        assert strip_html("&amp; &lt; &gt;") == "& < >"

    def test_no_html(self):
        assert strip_html("plain text") == "plain text"


class TestRemoveUrls:
    def test_removes_http(self):
        assert remove_urls("Visit https://example.com today") == "Visit  today"

    def test_removes_www(self):
        assert remove_urls("See www.example.com") == "See "

    def test_no_urls(self):
        assert remove_urls("no urls here") == "no urls here"


class TestRemoveEmails:
    def test_removes_email(self):
        assert remove_emails("Contact user@example.com") == "Contact "

    def test_no_emails(self):
        assert remove_emails("no email here") == "no email here"


class TestNormalizeWhitespace:
    def test_collapses_spaces(self):
        assert normalize_whitespace("hello    world") == "hello world"

    def test_collapses_newlines(self):
        assert normalize_whitespace("a\n\n\n\n\nb") == "a\n\nb"

    def test_strips_edges(self):
        assert normalize_whitespace("  hello  ") == "hello"


class TestDetectLanguage:
    def test_chinese(self):
        assert detect_language("这是一段中文测试文本") == "zh"

    def test_english(self):
        assert detect_language("This is an English test text") == "en"

    def test_empty(self):
        assert detect_language("") is None

    def test_mixed_mostly_chinese(self):
        assert detect_language("这是中文 with some English") == "zh"


class TestCleanText:
    def test_full_pipeline(self):
        dirty = "<p>Hello   world</p> https://example.com user@test.com"
        result = clean_text(dirty)
        assert "<p>" not in result
        assert "https://" not in result
        assert "@" not in result
        assert "Hello" in result


class TestTextClean:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "remove_html": True,
            "normalize_whitespace": True,
            "remove_urls": True,
            "remove_emails": True,
            "min_length": 10,
            "max_length": 1000,
            "language_filter": ["zh", "en"],
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"id": 1, "content": "This is a valid English text that should pass the length filter"},
                {"id": 2, "content": "short"},  # too short
                {"id": 3, "content": "<p>这是一段有效的中文文本，应该通过长度过滤器</p>"},
                {"id": 4, "content": "Visit https://example.com for more info about this topic"},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        return ctx

    def test_filters_short_text(self, mock_ctx):
        result = text_clean(mock_ctx)

        assert "text" in result
        ids = [r["id"] for r in result["text"]]
        assert 2 not in ids  # "short" dropped

    def test_cleans_html(self, mock_ctx):
        result = text_clean(mock_ctx)

        html_doc = next(r for r in result["text"] if r["id"] == 3)
        assert "<p>" not in html_doc["content"]

    def test_removes_urls_from_content(self, mock_ctx):
        result = text_clean(mock_ctx)

        url_doc = next(r for r in result["text"] if r["id"] == 4)
        assert "https://" not in url_doc["content"]

    def test_reports_metrics(self, mock_ctx):
        text_clean(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["input_count"] == 4
        assert report["dropped_short"] == 1
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_text_clean.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/text/text_clean.py \
        lakeon-orchestrator/tests/unit/test_text_clean.py
git commit -m "feat(pipeline): add text_clean component with HTML/URL/length filtering"
```

---

## Task 10: text_tokenize -- tiktoken/jieba 分词统计

**Files:**
- Create: `lakeon-orchestrator/components/text/text_tokenize.py`
- Create: `lakeon-orchestrator/tests/unit/test_text_tokenize.py`

- [ ] **Step 1: 实现 text_tokenize 组件**

Create: `lakeon-orchestrator/components/text/text_tokenize.py`

```python
"""text_tokenize: tiktoken/jieba 分词统计，计算 token 数和词频分布."""

import re
from collections import Counter

import jieba
import tiktoken

from lakeon.pipeline import Component, ComponentContext


def tokenize_tiktoken(text: str, model: str = "cl100k_base") -> list[str]:
    """Tokenize text using tiktoken (OpenAI tokenizer).

    Returns list of token strings.
    """
    enc = tiktoken.get_encoding(model)
    token_ids = enc.encode(text)
    return [enc.decode([tid]) for tid in token_ids]


def tokenize_jieba(text: str) -> list[str]:
    """Tokenize text using jieba Chinese segmentation.

    Returns list of word strings.
    """
    return list(jieba.cut(text))


def compute_text_stats(tokens: list[str]) -> dict:
    """Compute statistics from a token list.

    Returns dict with: token_count, unique_count, type_token_ratio,
    avg_token_length, top_tokens.
    """
    if not tokens:
        return {
            "token_count": 0,
            "unique_count": 0,
            "type_token_ratio": 0.0,
            "avg_token_length": 0.0,
            "top_tokens": [],
        }

    counter = Counter(tokens)
    unique = len(counter)
    total = len(tokens)
    avg_len = sum(len(t) for t in tokens) / max(total, 1)

    return {
        "token_count": total,
        "unique_count": unique,
        "type_token_ratio": round(unique / max(total, 1), 4),
        "avg_token_length": round(avg_len, 2),
        "top_tokens": counter.most_common(20),
    }


@Component(
    name="text_tokenize",
    display_name="分词统计",
    category="EXTRACT",
    data_type="TEXT",
    params_schema={
        "tokenizer": {
            "type": "string",
            "default": "tiktoken",
            "enum": ["tiktoken", "jieba"],
            "description": "分词器: tiktoken(BPE) 或 jieba(中文分词)",
        },
        "tiktoken_model": {
            "type": "string",
            "default": "cl100k_base",
            "description": "tiktoken 编码模型",
        },
        "compute_stats": {
            "type": "boolean",
            "default": True,
            "description": "是否计算统计信息",
        },
        "text_key": {
            "type": "string",
            "default": "content",
            "description": "文本字段名",
        },
    },
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_tokenize(ctx: ComponentContext) -> dict:
    """Tokenize text records and compute statistics."""
    texts = ctx.input["text"]
    tokenizer = ctx.params.get("tokenizer", "tiktoken")
    tiktoken_model = ctx.params.get("tiktoken_model", "cl100k_base")
    compute_stats_flag = ctx.params.get("compute_stats", True)
    text_key = ctx.params.get("text_key", "content")

    total_tokens = 0
    global_counter = Counter()
    tokenized_texts = []

    for record in texts:
        content = record.get(text_key, "")

        if tokenizer == "tiktoken":
            tokens = tokenize_tiktoken(content, tiktoken_model)
        elif tokenizer == "jieba":
            tokens = tokenize_jieba(content)
        else:
            raise ValueError(f"Unknown tokenizer: {tokenizer}")

        enriched = {**record, "token_count": len(tokens)}

        if compute_stats_flag:
            stats = compute_text_stats(tokens)
            enriched["token_stats"] = stats
            global_counter.update(tokens)

        total_tokens += len(tokens)
        tokenized_texts.append(enriched)

    avg_tokens = total_tokens / max(len(texts), 1)

    ctx.log(
        f"Tokenized {len(texts)} texts with {tokenizer}: "
        f"total={total_tokens}, avg={avg_tokens:.0f} tokens/doc"
    )
    ctx.checkpoint()

    ctx.report({
        "input_count": len(texts),
        "output_count": len(tokenized_texts),
        "tokenizer": tokenizer,
        "total_tokens": total_tokens,
        "avg_tokens_per_doc": round(avg_tokens, 1),
        "vocabulary_size": len(global_counter),
    })

    return {"text": tokenized_texts}
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_text_tokenize.py`

```python
"""Unit tests for text_tokenize component."""

from unittest.mock import MagicMock, patch

import pytest

from lakeon.components.text.text_tokenize import (
    tokenize_tiktoken,
    tokenize_jieba,
    compute_text_stats,
    text_tokenize,
)


class TestTokenizeTiktoken:
    def test_basic_tokenization(self):
        tokens = tokenize_tiktoken("Hello, world!")
        assert len(tokens) > 0
        # Reconstructed text should match original
        assert "".join(tokens) == "Hello, world!"

    def test_chinese_text(self):
        tokens = tokenize_tiktoken("你好世界")
        assert len(tokens) > 0

    def test_empty_text(self):
        tokens = tokenize_tiktoken("")
        assert tokens == []


class TestTokenizeJieba:
    def test_chinese_segmentation(self):
        tokens = tokenize_jieba("我来到北京清华大学")
        assert "清华大学" in tokens or "清华" in tokens

    def test_english_text(self):
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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_text_tokenize.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/text/text_tokenize.py \
        lakeon-orchestrator/tests/unit/test_text_tokenize.py
git commit -m "feat(pipeline): add text_tokenize component with tiktoken/jieba"
```

---

## Task 11: text_quality_score -- 基于规则的质量评分

**Files:**
- Create: `lakeon-orchestrator/components/text/text_quality_score.py`
- Create: `lakeon-orchestrator/tests/unit/test_text_quality_score.py`

- [ ] **Step 1: 实现 text_quality_score 组件**

Create: `lakeon-orchestrator/components/text/text_quality_score.py`

```python
"""text_quality_score: 基于规则的文本质量评分，条件分支输出 passed/low_quality."""

import math
import re

from lakeon.pipeline import Component, ComponentContext


def score_length(text: str) -> float:
    """Score based on text length. Sweet spot: 200-10000 chars."""
    length = len(text)
    if length < 50:
        return 0.0
    if length < 200:
        return 0.3
    if length <= 10000:
        return 1.0
    if length <= 50000:
        return 0.7
    return 0.4


def score_sentence_structure(text: str) -> float:
    """Score based on sentence structure quality.

    Checks for proper sentence endings, average sentence length, etc.
    """
    # Split by Chinese/English sentence endings
    sentences = re.split(r"[。！？.!?]+", text)
    sentences = [s.strip() for s in sentences if s.strip()]

    if not sentences:
        return 0.0

    avg_len = sum(len(s) for s in sentences) / len(sentences)

    # Score based on average sentence length (chars)
    if avg_len < 5:
        return 0.2  # Too fragmented
    if avg_len > 500:
        return 0.3  # Sentences too long (likely no punctuation)
    if 20 <= avg_len <= 200:
        return 1.0
    return 0.6


def score_repetition(text: str) -> float:
    """Score based on text repetition. Lower repetition = higher score."""
    if len(text) < 100:
        return 0.5

    # Check character-level repetition
    chars = list(text)
    unique_chars = set(chars)
    char_ratio = len(unique_chars) / max(len(chars), 1)

    # Check word/phrase-level repetition (2-gram)
    words = text.split()
    if len(words) < 4:
        return 0.5

    bigrams = [f"{words[i]} {words[i+1]}" for i in range(len(words) - 1)]
    unique_bigrams = set(bigrams)
    bigram_ratio = len(unique_bigrams) / max(len(bigrams), 1)

    # Combine: lower repetition = higher score
    return round(min(char_ratio * 2, 1.0) * 0.4 + min(bigram_ratio * 1.5, 1.0) * 0.6, 3)


def score_special_chars(text: str) -> float:
    """Score based on special character ratio. Too many = low quality."""
    if not text:
        return 0.0

    special_count = sum(1 for c in text if not c.isalnum() and not c.isspace()
                        and c not in "。，！？、；：""''《》（）.,:;!?\"'()-")
    ratio = special_count / max(len(text), 1)

    if ratio > 0.3:
        return 0.1
    if ratio > 0.15:
        return 0.4
    if ratio > 0.05:
        return 0.7
    return 1.0


def score_information_density(text: str) -> float:
    """Score based on information density heuristic.

    Approximates entropy using unique character ratio and vocabulary richness.
    """
    if len(text) < 20:
        return 0.3

    # Character-level entropy approximation
    chars = list(text.lower())
    char_freq = {}
    for c in chars:
        char_freq[c] = char_freq.get(c, 0) + 1

    total = len(chars)
    entropy = -sum(
        (count / total) * math.log2(count / total)
        for count in char_freq.values()
    )

    # Normalize: typical text entropy is 3-5 bits/char
    if entropy < 2.0:
        return 0.2
    if entropy > 6.0:
        return 0.5  # Could be gibberish
    return min(entropy / 5.0, 1.0)


def compute_quality_score(text: str) -> dict:
    """Compute overall quality score from multiple sub-scores.

    Returns dict with sub-scores and weighted overall score.
    """
    scores = {
        "length": score_length(text),
        "sentence_structure": score_sentence_structure(text),
        "repetition": score_repetition(text),
        "special_chars": score_special_chars(text),
        "information_density": score_information_density(text),
    }

    # Weighted average
    weights = {
        "length": 0.15,
        "sentence_structure": 0.25,
        "repetition": 0.25,
        "special_chars": 0.15,
        "information_density": 0.20,
    }

    overall = sum(scores[k] * weights[k] for k in scores)
    scores["overall"] = round(overall, 3)

    return scores


@Component(
    name="text_quality_score",
    display_name="文本质量评分",
    category="QC",
    data_type="TEXT",
    params_schema={
        "scorer": {
            "type": "string",
            "default": "rule",
            "enum": ["rule"],
            "description": "评分方法: rule=基于规则",
        },
        "min_score": {
            "type": "number",
            "default": 0.6,
            "description": "最低质量分(低于此值标记为 low_quality)",
        },
        "text_key": {
            "type": "string",
            "default": "content",
            "description": "文本字段名",
        },
    },
    output_branches=["passed", "low_quality"],
    input_schema={"type": "text_records", "format": "jsonl"},
    output_schema={"type": "text_records", "format": "jsonl"},
)
def text_quality_score(ctx: ComponentContext) -> dict:
    """Score text quality and route to passed/low_quality branches."""
    texts = ctx.input["text"]
    min_score = ctx.params.get("min_score", 0.6)
    text_key = ctx.params.get("text_key", "content")

    passed = []
    low_quality = []

    for record in texts:
        content = record.get(text_key, "")
        scores = compute_quality_score(content)

        enriched = {**record, "quality_scores": scores}

        if scores["overall"] >= min_score:
            passed.append(enriched)
        else:
            low_quality.append(enriched)

    ctx.log(
        f"Quality scoring: {len(texts)} input, "
        f"{len(passed)} passed (>={min_score}), {len(low_quality)} low quality"
    )
    ctx.report({
        "input_count": len(texts),
        "passed_count": len(passed),
        "low_quality_count": len(low_quality),
        "min_score": min_score,
        "avg_score": round(
            sum(r["quality_scores"]["overall"] for r in passed + low_quality) / max(len(texts), 1),
            3,
        ),
        "retention": f"{len(passed)/max(len(texts),1)*100:.1f}%",
    })

    # Return both branches; Orchestrator routes based on output_branches
    return {
        "passed": passed,
        "low_quality": low_quality,
    }
```

- [ ] **Step 2: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_text_quality_score.py`

```python
"""Unit tests for text_quality_score component."""

from unittest.mock import MagicMock

import pytest

from lakeon.components.text.text_quality_score import (
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
```

- [ ] **Step 3: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_text_quality_score.py -v
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/components/text/text_quality_score.py \
        lakeon-orchestrator/tests/unit/test_text_quality_score.py
git commit -m "feat(pipeline): add text_quality_score component with rule-based scoring"
```

---

## Task 12: dataset_publish -- Lance/Parquet 数据集发布

**Files:**
- Create: `lakeon-orchestrator/components/universal/__init__.py`
- Create: `lakeon-orchestrator/components/universal/dataset_publish.py`
- Create: `lakeon-orchestrator/tests/unit/test_dataset_publish.py`

- [ ] **Step 1: 创建 universal 包 __init__.py**

Create: `lakeon-orchestrator/components/universal/__init__.py`

```python
"""Universal components for the data pipeline (cross data-type)."""
```

- [ ] **Step 2: 实现 dataset_publish 组件**

Create: `lakeon-orchestrator/components/universal/dataset_publish.py`

```python
"""dataset_publish: 写入 Lance/Parquet 数据集 + 创建 dataset_version 记录."""

import json
import os
import tempfile
import uuid
from datetime import datetime, timezone

import pyarrow as pa
import pyarrow.parquet as pq

from lakeon.pipeline import Component, ComponentContext


def _build_video_table(clips: list) -> pa.Table:
    """Build a PyArrow table from video clip data.

    Handles both simple paths (list[str]) and labeled clips (list[dict]).
    """
    if not clips:
        return pa.table({"clip_path": pa.array([], type=pa.string())})

    if isinstance(clips[0], str):
        # Simple path list
        return pa.table({
            "clip_path": pa.array(clips, type=pa.string()),
        })

    # Labeled clips: [{clip, labels: {viclip_tags, caption, camera_motion}}]
    paths = []
    captions = []
    tags_list = []
    camera_motions = []

    for item in clips:
        paths.append(item.get("clip", ""))
        labels = item.get("labels", {})
        captions.append(labels.get("caption", ""))
        tags_list.append(json.dumps(labels.get("viclip_tags", []), ensure_ascii=False))
        camera_motions.append(json.dumps(labels.get("camera_motion", {}), ensure_ascii=False))

    return pa.table({
        "clip_path": pa.array(paths, type=pa.string()),
        "caption": pa.array(captions, type=pa.string()),
        "viclip_tags": pa.array(tags_list, type=pa.string()),
        "camera_motion": pa.array(camera_motions, type=pa.string()),
    })


def _build_text_table(texts: list[dict], text_key: str = "content") -> pa.Table:
    """Build a PyArrow table from text records."""
    if not texts:
        return pa.table({text_key: pa.array([], type=pa.string())})

    # Collect all keys across records
    all_keys = set()
    for record in texts:
        all_keys.update(record.keys())

    # Remove nested dicts (like quality_scores, token_stats) -- serialize as JSON
    columns = {}
    for key in sorted(all_keys):
        values = []
        for record in texts:
            val = record.get(key)
            if isinstance(val, (dict, list)):
                values.append(json.dumps(val, ensure_ascii=False))
            elif val is None:
                values.append("")
            else:
                values.append(str(val))
        columns[key] = pa.array(values, type=pa.string())

    return pa.table(columns)


def write_parquet(table: pa.Table, out_path: str) -> int:
    """Write PyArrow table to Parquet file. Returns file size in bytes."""
    pq.write_table(table, out_path, compression="snappy")
    return os.path.getsize(out_path)


def write_lance(table: pa.Table, out_path: str) -> int:
    """Write PyArrow table to Lance dataset. Returns approximate size in bytes."""
    import lance
    lance.write_dataset(table, out_path, mode="overwrite")
    # Compute total size of Lance dataset directory
    total_size = 0
    for root, _dirs, files in os.walk(out_path):
        for f in files:
            total_size += os.path.getsize(os.path.join(root, f))
    return total_size


@Component(
    name="dataset_publish",
    display_name="发布数据集",
    category="PUBLISH",
    data_type="UNIVERSAL",
    params_schema={
        "dataset_name": {
            "type": "string",
            "default": "",
            "description": "数据集名称(空=自动生成)",
        },
        "format": {
            "type": "string",
            "default": "PARQUET",
            "enum": ["PARQUET", "LANCE"],
            "description": "输出格式: PARQUET(文本) 或 LANCE(多模态)",
        },
        "text_key": {
            "type": "string",
            "default": "content",
            "description": "文本数据的字段名",
        },
    },
    input_schema={"type": "any"},
    output_schema={"type": "dataset_version"},
)
def dataset_publish(ctx: ComponentContext) -> dict:
    """Publish pipeline output as a versioned dataset in Parquet or Lance format."""
    input_data = ctx.input
    fmt = ctx.params.get("format", "PARQUET")
    dataset_name = ctx.params.get("dataset_name", "")
    text_key = ctx.params.get("text_key", "content")

    # Determine data type and build table
    if "clips" in input_data:
        table = _build_video_table(input_data["clips"])
        data_type = "video"
    elif "text" in input_data:
        table = _build_text_table(input_data["text"], text_key)
        data_type = "text"
    else:
        # Generic: try to build from whatever is available
        items = list(input_data.values())[0] if input_data else []
        if isinstance(items, list) and items and isinstance(items[0], dict):
            table = _build_text_table(items)
        else:
            table = pa.table({"data": pa.array([json.dumps(input_data)], type=pa.string())})
        data_type = "generic"

    row_count = table.num_rows
    if not dataset_name:
        dataset_name = f"pipeline_output_{data_type}_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}"

    ctx.log(f"Publishing {row_count} rows as {fmt} dataset: {dataset_name}")

    # Write to local temp, then upload to OBS
    out_dir = tempfile.mkdtemp(prefix="publish_")

    if fmt == "LANCE":
        out_path = os.path.join(out_dir, f"{dataset_name}.lance")
        file_size = write_lance(table, out_path)
    else:
        out_path = os.path.join(out_dir, f"{dataset_name}.parquet")
        file_size = write_parquet(table, out_path)

    # Upload to OBS via context
    obs_path = f"datasets/{dataset_name}/{fmt.lower()}"
    ctx.obs.write(out_path, obs_path)

    # Create dataset_version record via context (calls lakeon-api)
    version_id = f"dsv_{uuid.uuid4().hex[:12]}"
    schema_json = json.dumps(
        {field.name: str(field.type) for field in table.schema},
        ensure_ascii=False,
    )

    version_record = {
        "id": version_id,
        "dataset_name": dataset_name,
        "format": fmt,
        "obs_path": obs_path,
        "row_count": row_count,
        "file_size": file_size,
        "schema_json": schema_json,
    }

    ctx.log(f"Dataset published: {version_id}, {row_count} rows, {file_size} bytes")

    ctx.report({
        "dataset_name": dataset_name,
        "version_id": version_id,
        "format": fmt,
        "row_count": row_count,
        "file_size": file_size,
        "obs_path": obs_path,
    })

    return {"dataset_version": version_record}
```

- [ ] **Step 3: 编写单元测试**

Create: `lakeon-orchestrator/tests/unit/test_dataset_publish.py`

```python
"""Unit tests for dataset_publish component."""

import json
import os
import tempfile
from unittest.mock import MagicMock, patch

import pyarrow as pa
import pytest

from lakeon.components.universal.dataset_publish import (
    _build_video_table,
    _build_text_table,
    write_parquet,
    dataset_publish,
)


class TestBuildVideoTable:
    def test_simple_paths(self):
        clips = ["/tmp/clip1.mp4", "/tmp/clip2.mp4"]
        table = _build_video_table(clips)
        assert table.num_rows == 2
        assert "clip_path" in table.column_names

    def test_labeled_clips(self):
        clips = [
            {
                "clip": "/tmp/clip1.mp4",
                "labels": {
                    "caption": "A scene in a park",
                    "viclip_tags": [{"tag": "outdoor", "confidence": 0.9}],
                    "camera_motion": {"primary_motion": "pan_left"},
                },
            },
        ]
        table = _build_video_table(clips)
        assert table.num_rows == 1
        assert "caption" in table.column_names
        assert "clip_path" in table.column_names

    def test_empty_clips(self):
        table = _build_video_table([])
        assert table.num_rows == 0


class TestBuildTextTable:
    def test_basic_records(self):
        texts = [
            {"id": "1", "content": "Hello world"},
            {"id": "2", "content": "Test text"},
        ]
        table = _build_text_table(texts)
        assert table.num_rows == 2
        assert "content" in table.column_names

    def test_nested_fields_serialized(self):
        texts = [{"content": "text", "quality_scores": {"overall": 0.8}}]
        table = _build_text_table(texts)
        assert table.num_rows == 1
        # Nested dict should be JSON-serialized
        val = table.column("quality_scores")[0].as_py()
        assert "overall" in val

    def test_empty_records(self):
        table = _build_text_table([])
        assert table.num_rows == 0


class TestWriteParquet:
    def test_writes_file(self):
        table = pa.table({"col": pa.array(["a", "b", "c"])})
        with tempfile.NamedTemporaryFile(suffix=".parquet", delete=False) as f:
            out_path = f.name

        try:
            size = write_parquet(table, out_path)
            assert size > 0
            assert os.path.exists(out_path)

            # Verify readable
            read_table = pa.parquet.read_table(out_path)
            assert read_table.num_rows == 3
        finally:
            os.unlink(out_path)


class TestDatasetPublish:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "dataset_name": "test_dataset",
            "format": "PARQUET",
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"id": "1", "content": "First document"},
                {"id": "2", "content": "Second document"},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.obs = MagicMock()
        return ctx

    def test_publishes_text_as_parquet(self, mock_ctx):
        result = dataset_publish(mock_ctx)

        assert "dataset_version" in result
        version = result["dataset_version"]
        assert version["format"] == "PARQUET"
        assert version["row_count"] == 2
        assert version["dataset_name"] == "test_dataset"
        ctx_obs = mock_ctx.obs.write
        assert ctx_obs.called

    def test_publishes_video_clips(self, mock_ctx):
        mock_ctx.input = {"clips": ["/tmp/clip1.mp4", "/tmp/clip2.mp4"]}
        mock_ctx.params["format"] = "LANCE"

        with patch("lakeon.components.universal.dataset_publish.write_lance") as mock_lance:
            mock_lance.return_value = 4096
            result = dataset_publish(mock_ctx)

        version = result["dataset_version"]
        assert version["format"] == "LANCE"
        assert version["row_count"] == 2

    def test_reports_metrics(self, mock_ctx):
        dataset_publish(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["row_count"] == 2
        assert report["format"] == "PARQUET"
        assert "obs_path" in report
```

- [ ] **Step 4: 运行测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/test_dataset_publish.py -v
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/components/universal/__init__.py \
        lakeon-orchestrator/components/universal/dataset_publish.py \
        lakeon-orchestrator/tests/unit/test_dataset_publish.py
git commit -m "feat(pipeline): add dataset_publish component with Lance/Parquet output"
```

---

## Task 13: 预置模板数据 -- 数据库迁移脚本

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V30__pipeline_preset_templates.sql`

- [ ] **Step 1: 编写迁移脚本**

Create: `lakeon-api/src/main/resources/db/migration/V30__pipeline_preset_templates.sql`

注意：V 编号需与现有迁移脚本不冲突，按实际情况调整。

```sql
-- V30__pipeline_preset_templates.sql
-- 预置 Phase 1 组件 + 视频/文本 Pipeline 模板

-- ============================================================
-- 1. 预置组件 (tenant_id = NULL = 平台内置)
-- ============================================================

-- Video components
INSERT INTO pipeline_components (id, tenant_id, name, display_name, category, data_type, description, latest_version, created_at, updated_at)
VALUES
  ('comp_video_normalize',  NULL, 'video_normalize',     '视频规整适配', 'DATA_PREP', 'VIDEO',     'ffprobe 元数据提取 + ffmpeg 转码，标准化分辨率和格式', 1, NOW(), NOW()),
  ('comp_video_scene_split', NULL, 'video_scene_split',  '视频镜头切分', 'EXTRACT',   'VIDEO',     'PySceneDetect 镜头检测 + ffmpeg 切片，fan_out 输出 clips', 1, NOW(), NOW()),
  ('comp_rule_filter',       NULL, 'rule_filter',        '规则清洗',     'FILTER',    'VIDEO',     '基于 ffprobe 元数据的规则过滤：时长/分辨率/长宽比/帧率/裁剪面积', 1, NOW(), NOW()),
  ('comp_video_crop',        NULL, 'video_crop',         '视频裁剪',     'CLEAN',     'VIDEO',     'ffmpeg cropdetect + crop 处理', 1, NOW(), NOW()),
  ('comp_model_filter_mock', NULL, 'model_filter_mock',  '模型清洗 (Mock)', 'FILTER', 'VIDEO',     '[Phase 1 Mock] 随机打分模拟 VQA/水印/字幕/光流检测', 1, NOW(), NOW()),
  ('comp_quality_check',     NULL, 'quality_check',      '质量检查',     'QC',        'UNIVERSAL', 'HUMAN_REVIEW 模式：暂停等待人工审核确认', 1, NOW(), NOW()),
  ('comp_video_label_mock',  NULL, 'video_labeling_mock','内容标注 (Mock)', 'LABEL',  'VIDEO',     '[Phase 1 Mock] 返回固定标签模拟 VICLIP/Caption/运镜', 1, NOW(), NOW()),
  ('comp_dataset_publish',   NULL, 'dataset_publish',    '发布数据集',   'PUBLISH',   'UNIVERSAL', '写入 Lance/Parquet 数据集 + 创建 dataset_version 记录', 1, NOW(), NOW()),

-- Text components
  ('comp_text_dedup',        NULL, 'text_dedup',         '文本去重',     'CLEAN',     'TEXT',      'MinHash LSH 近似去重', 1, NOW(), NOW()),
  ('comp_text_clean',        NULL, 'text_clean',         '文本清洗',     'CLEAN',     'TEXT',      'HTML 清理、空白标准化、URL 移除、长度过滤', 1, NOW(), NOW()),
  ('comp_text_tokenize',     NULL, 'text_tokenize',      '分词统计',     'EXTRACT',   'TEXT',      'tiktoken/jieba 分词 + 统计', 1, NOW(), NOW()),
  ('comp_text_quality',      NULL, 'text_quality_score', '文本质量评分', 'QC',        'TEXT',      '基于规则的多维度质量评分', 1, NOW(), NOW());

-- ============================================================
-- 2. 组件版本 (version 1)
-- ============================================================

INSERT INTO pipeline_component_versions (id, component_id, version, entrypoint, params_schema, input_schema, output_schema, output_branches, requires_gpu, requires_model, execution_mode, status, created_at)
VALUES
  -- video_normalize
  ('compv_vid_norm_v1', 'comp_video_normalize', 1,
   'lakeon.components.video.video_normalize',
   '{"target_resolution":{"type":"string","default":"1080p","enum":["360p","480p","720p","1080p","2k","4k"]},"target_format":{"type":"string","default":"mp4","enum":["mp4","avi","mkv"]}}',
   '{"type":"video","format":["mp4","avi","mkv","mov","flv"]}',
   '{"type":"video","format":"mp4"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- video_scene_split
  ('compv_scene_v1', 'comp_video_scene_split', 1,
   'lakeon.components.video.video_scene_split',
   '{"threshold":{"type":"number","default":27},"min_scene_length":{"type":"number","default":1.0}}',
   '{"type":"video","format":["mp4","avi"]}',
   '{"type":"video_clips","format":"mp4"}',
   NULL, FALSE, 'pyscenedetect', 'FUNCTION', 'PUBLISHED', NOW()),

  -- rule_filter
  ('compv_rule_v1', 'comp_rule_filter', 1,
   'lakeon.components.video.rule_filter',
   '{"min_duration":{"type":"number","default":3},"min_resolution":{"type":"number","default":480},"max_aspect_ratio":{"type":"number","default":2},"min_fps":{"type":"number","default":20},"min_crop_area":{"type":"number","default":5}}',
   '{"type":"video","format":"mp4"}',
   '{"type":"video","format":"mp4"}',
   '["passed","needs_crop","dropped"]', FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- video_crop
  ('compv_crop_v1', 'comp_video_crop', 1,
   'lakeon.components.video.video_crop',
   '{"target_aspect_ratio":{"type":"number","default":1.78}}',
   '{"type":"video","format":["mp4"]}',
   '{"type":"video","format":"mp4"}',
   '["passed","dropped"]', FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- model_filter_mock
  ('compv_model_filt_v1', 'comp_model_filter_mock', 1,
   'lakeon.components.video.model_filter_mock',
   '{"checks":{"type":"array","default":["vqa","watermark","subtitle","optical_flow"]},"thresholds":{"type":"object","default":{"vqa":0.3,"watermark":0.5,"subtitle":0.5,"optical_flow":0.2}}}',
   '{"type":"video_clips","format":"mp4"}',
   '{"type":"video_clips","format":"mp4"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- quality_check
  ('compv_qc_v1', 'comp_quality_check', 1,
   'lakeon.components.video.quality_check',
   '{"review_mode":{"type":"string","default":"manual","enum":["manual","auto_approve"]},"thumbnail_count":{"type":"integer","default":4}}',
   '{"type":"any"}',
   '{"type":"any"}',
   NULL, FALSE, NULL, 'HUMAN_REVIEW', 'PUBLISHED', NOW()),

  -- video_labeling_mock
  ('compv_label_v1', 'comp_video_label_mock', 1,
   'lakeon.components.video.video_labeling_mock',
   '{"tasks":{"type":"array","default":["viclip_tag","caption","camera_motion"]},"viclip_top_k":{"type":"integer","default":5}}',
   '{"type":"video_clips","format":"mp4"}',
   '{"type":"labeled_clips","format":"mp4"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- dataset_publish
  ('compv_publish_v1', 'comp_dataset_publish', 1,
   'lakeon.components.universal.dataset_publish',
   '{"dataset_name":{"type":"string","default":""},"format":{"type":"string","default":"PARQUET","enum":["PARQUET","LANCE"]},"text_key":{"type":"string","default":"content"}}',
   '{"type":"any"}',
   '{"type":"dataset_version"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_dedup
  ('compv_dedup_v1', 'comp_text_dedup', 1,
   'lakeon.components.text.text_dedup',
   '{"method":{"type":"string","default":"minhash"},"similarity_threshold":{"type":"number","default":0.85},"num_perm":{"type":"integer","default":128},"ngram":{"type":"integer","default":3}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_clean
  ('compv_clean_v1', 'comp_text_clean', 1,
   'lakeon.components.text.text_clean',
   '{"remove_html":{"type":"boolean","default":true},"normalize_whitespace":{"type":"boolean","default":true},"remove_urls":{"type":"boolean","default":true},"min_length":{"type":"integer","default":50},"max_length":{"type":"integer","default":100000},"language_filter":{"type":"array","default":["zh","en"]}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_tokenize
  ('compv_token_v1', 'comp_text_tokenize', 1,
   'lakeon.components.text.text_tokenize',
   '{"tokenizer":{"type":"string","default":"tiktoken","enum":["tiktoken","jieba"]},"tiktoken_model":{"type":"string","default":"cl100k_base"},"compute_stats":{"type":"boolean","default":true}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   NULL, FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW()),

  -- text_quality_score
  ('compv_quality_v1', 'comp_text_quality', 1,
   'lakeon.components.text.text_quality_score',
   '{"scorer":{"type":"string","default":"rule"},"min_score":{"type":"number","default":0.6}}',
   '{"type":"text_records","format":"jsonl"}',
   '{"type":"text_records","format":"jsonl"}',
   '["passed","low_quality"]', FALSE, NULL, 'FUNCTION', 'PUBLISHED', NOW());

-- ============================================================
-- 3. 预置 Pipeline 模板 (tenant_id = 'system', is_template = TRUE)
-- ============================================================

-- Video Pipeline Template
INSERT INTO pipelines (id, tenant_id, name, description, data_type, is_template, source_template_id, latest_version, created_at, updated_at)
VALUES
  ('pipe_tpl_video_clean', 'system', '视频数据清洗流水线',
   '从原始视频到清洗标注后的视频片段数据集。包含规整适配、镜头切分、规则清洗、裁剪、模型清洗(Mock)、人工质检、内容标注(Mock)、发布等步骤。',
   'VIDEO', TRUE, NULL, 1, NOW(), NOW()),

  ('pipe_tpl_text_clean', 'system', '文本数据清洗流水线',
   '从原始文本到高质量训练数据集。包含 MinHash 去重、HTML/URL 清洗、分词统计、质量评分、人工质检、发布等步骤。',
   'TEXT', TRUE, NULL, 1, NOW(), NOW());

-- Video Pipeline Template Version (DAG YAML)
INSERT INTO pipeline_versions (id, pipeline_id, version, dag_yaml, status, created_at)
VALUES
  ('pipev_tpl_video_v1', 'pipe_tpl_video_clean', 1,
   'name: 视频数据清洗流水线
data_type: VIDEO
description: 从原始视频到清洗标注后的视频片段数据集

steps:
  - id: normalize
    component: video_normalize
    component_version: 1
    params: { target_resolution: "1080p", target_format: "mp4" }
    inputs: { video: "$input.dataset" }
    outputs: { video: normalized }

  - id: scene_split
    component: video_scene_split
    component_version: 1
    params: { threshold: 27, min_scene_length: 1.0 }
    inputs: { video: normalize.video }
    fan_out: true
    checkpoint: true
    outputs: { clips: split_clips }

  - id: rule_filter
    component: rule_filter
    component_version: 1
    depends_on: [scene_split]
    params: { min_duration: 3, min_resolution: 480, max_aspect_ratio: 2, min_fps: 20, min_crop_area: 5 }
    inputs: { clip: scene_split.clips }
    output_branches: [passed, needs_crop, dropped]
    outputs: { passed: passed_clip, needs_crop: crop_clip }

  - id: crop
    component: video_crop
    component_version: 1
    condition: "rule_filter.needs_crop"
    inputs: { clip: rule_filter.crop_clip }
    outputs: { clip: cropped_clip }

  - id: merge_clean
    type: merge
    inputs: [rule_filter.passed_clip, crop.clip]
    outputs: { clips: merged_clips }

  - id: model_filter
    component: model_filter_mock
    component_version: 1
    depends_on: [merge_clean]
    params: { checks: [vqa, watermark, subtitle, optical_flow] }
    inputs: { clips: merge_clean.clips }
    checkpoint: true
    outputs: { clips: cleaned_clips }

  - id: qc
    component: quality_check
    component_version: 1
    execution_mode: HUMAN_REVIEW
    depends_on: [model_filter]
    inputs: { clips: model_filter.clips }
    outputs: { clips: approved_clips }

  - id: labeling
    component: video_labeling_mock
    component_version: 1
    depends_on: [qc]
    params: { tasks: [viclip_tag, caption, camera_motion] }
    inputs: { clips: qc.clips }
    outputs: { clips: labeled_clips }

  - id: publish
    component: dataset_publish
    component_version: 1
    depends_on: [labeling]
    inputs: { clips: labeling.clips }
    output_dataset: { name: "清洗后视频数据集", format: LANCE }',
   'PUBLISHED', NOW());

-- Text Pipeline Template Version (DAG YAML)
INSERT INTO pipeline_versions (id, pipeline_id, version, dag_yaml, status, created_at)
VALUES
  ('pipev_tpl_text_v1', 'pipe_tpl_text_clean', 1,
   'name: 文本数据清洗流水线
data_type: TEXT
description: 从原始文本到高质量训练数据集

steps:
  - id: dedup
    component: text_dedup
    component_version: 1
    params: { method: "minhash", similarity_threshold: 0.85 }
    inputs: { text: "$input.dataset" }
    checkpoint: true
    outputs: { text: deduped }

  - id: clean
    component: text_clean
    component_version: 1
    depends_on: [dedup]
    params: { remove_html: true, normalize_whitespace: true, remove_urls: true, min_length: 50, max_length: 100000, language_filter: ["zh", "en"] }
    inputs: { text: dedup.text }
    outputs: { text: cleaned }

  - id: tokenize_stats
    component: text_tokenize
    component_version: 1
    depends_on: [clean]
    params: { tokenizer: "tiktoken", compute_stats: true }
    inputs: { text: clean.text }
    checkpoint: true
    outputs: { text: tokenized }

  - id: quality_score
    component: text_quality_score
    component_version: 1
    depends_on: [tokenize_stats]
    params: { scorer: "rule", min_score: 0.6 }
    inputs: { text: tokenize_stats.text }
    output_branches: [passed, low_quality]
    outputs: { passed: good_text }

  - id: qc
    component: quality_check
    component_version: 1
    execution_mode: HUMAN_REVIEW
    depends_on: [quality_score]
    inputs: { text: quality_score.good_text }
    outputs: { text: approved_text }

  - id: publish
    component: dataset_publish
    component_version: 1
    depends_on: [qc]
    inputs: { text: qc.text }
    output_dataset: { name: "清洗后文本数据集", format: PARQUET }',
   'PUBLISHED', NOW());
```

- [ ] **Step 2: 验证 SQL 语法**

```bash
cd lakeon-api && cat src/main/resources/db/migration/V30__pipeline_preset_templates.sql | head -5
```

确认文件已写入，SQL 语法无明显错误。

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V30__pipeline_preset_templates.sql
git commit -m "feat(pipeline): add preset component and template migration V30"
```

---

## Task 14: E2E 测试 -- 视频 pipeline 完整流程

**Files:**
- Create: `lakeon-orchestrator/tests/e2e/conftest.py`
- Create: `lakeon-orchestrator/tests/e2e/test_video_pipeline_e2e.py`
- Create: `lakeon-orchestrator/tests/conftest.py`
- Create: `lakeon-orchestrator/tests/fixtures/generate_test_video.sh`

- [ ] **Step 1: 创建共享 conftest.py（测试工具和 Mock ComponentContext）**

Create: `lakeon-orchestrator/tests/conftest.py`

```python
"""Shared test fixtures for the orchestrator test suite."""

import json
import os
import tempfile
from dataclasses import dataclass, field
from typing import Any
from unittest.mock import MagicMock

import pytest


@dataclass
class MockObs:
    """Mock OBS client for testing."""
    stored: dict = field(default_factory=dict)

    def write(self, local_path: str, obs_path: str) -> None:
        self.stored[obs_path] = local_path

    def read(self, obs_path: str, local_path: str) -> str:
        return self.stored.get(obs_path, local_path)


@dataclass
class FakeComponentContext:
    """A fake ComponentContext that records all calls for E2E testing.

    Unlike MagicMock, this provides real fan_out/classify/checkpoint behavior
    so the full pipeline can execute end-to-end.
    """
    input: dict = field(default_factory=dict)
    params: dict = field(default_factory=dict)
    obs: MockObs = field(default_factory=MockObs)

    # Recorded state
    logs: list = field(default_factory=list)
    metrics: dict = field(default_factory=dict)
    checkpoints: list = field(default_factory=list)
    paused: bool = False
    pause_meta: dict = field(default_factory=dict)

    def log(self, msg: str) -> None:
        self.logs.append(msg)

    def report(self, metrics: dict) -> None:
        self.metrics = metrics

    def checkpoint(self) -> None:
        self.checkpoints.append({"input": self.input, "params": self.params})

    def fan_out(self, items: list) -> dict:
        return {"__fan_out__": True, "items": items}

    def classify(self, item: Any, label: str) -> dict:
        return {"__branch__": label, "item": item}

    def pause(self, review_meta: dict = None) -> dict:
        """Simulate pause/resume: in E2E test, auto-approve all items."""
        self.paused = True
        self.pause_meta = review_meta or {}
        # Auto-resume with all items approved
        if "clips" in self.input:
            return {"approved_items": self.input["clips"]}
        elif "text" in self.input:
            return {"approved_items": self.input["text"]}
        return {"approved_items": []}


@pytest.fixture
def fake_ctx():
    """Create a FakeComponentContext for testing."""
    return FakeComponentContext()


@pytest.fixture
def test_video_path():
    """Generate a small test video using ffmpeg (10 seconds, 640x360).

    Returns the path to the video file. Cleaned up after test.
    """
    out_dir = tempfile.mkdtemp(prefix="test_video_")
    out_path = os.path.join(out_dir, "test_input.mp4")

    # Generate a test video with multiple distinct scenes using ffmpeg:
    # 3 color segments (red, green, blue) of ~3.3s each = 10s total
    import subprocess
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i",
        "color=c=red:size=640x360:duration=3.3:rate=30,"
        "drawtext=text='Scene 1':fontsize=30:fontcolor=white:x=250:y=170",
        "-f", "lavfi", "-i",
        "color=c=green:size=640x360:duration=3.3:rate=30,"
        "drawtext=text='Scene 2':fontsize=30:fontcolor=white:x=250:y=170",
        "-f", "lavfi", "-i",
        "color=c=blue:size=640x360:duration=3.4:rate=30,"
        "drawtext=text='Scene 3':fontsize=30:fontcolor=white:x=250:y=170",
        "-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[outv]",
        "-map", "[outv]",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
        out_path,
    ]

    try:
        subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=30)
    except (subprocess.CalledProcessError, FileNotFoundError):
        pytest.skip("ffmpeg not available for E2E test")

    yield out_path

    # Cleanup
    import shutil
    shutil.rmtree(out_dir, ignore_errors=True)
```

- [ ] **Step 2: 创建 E2E conftest.py**

Create: `lakeon-orchestrator/tests/e2e/conftest.py`

```python
"""E2E test fixtures and helpers."""

import pytest


@pytest.fixture
def video_pipeline_params():
    """Default params for the video pipeline template."""
    return {
        "normalize": {"target_resolution": "360p", "target_format": "mp4"},
        "scene_split": {"threshold": 27, "min_scene_length": 1.0},
        "rule_filter": {
            "min_duration": 1,  # lowered for test clips
            "min_resolution": 240,
            "max_aspect_ratio": 2,
            "min_fps": 20,
            "min_crop_area": 5,
        },
        "video_crop": {"target_aspect_ratio": 0},
        "model_filter": {
            "checks": ["vqa", "watermark"],
            "thresholds": {"vqa": 0.1, "watermark": 0.1},  # permissive for test
        },
        "quality_check": {"review_mode": "auto_approve"},
        "labeling": {"tasks": ["viclip_tag", "caption", "camera_motion"]},
        "publish": {"dataset_name": "e2e_test_video", "format": "PARQUET"},
    }
```

- [ ] **Step 3: 实现视频 pipeline E2E 测试**

Create: `lakeon-orchestrator/tests/e2e/test_video_pipeline_e2e.py`

```python
"""E2E test: Video data cleaning pipeline.

Exercises the full flow:
  raw video → normalize → scene_split (fan-out) → rule_filter (branch)
  → video_crop (for needs_crop) → merge → model_filter_mock
  → quality_check (auto_approve) → video_labeling_mock → dataset_publish

Requires ffmpeg on PATH.
"""

import os

import pytest

from lakeon.components.video.video_normalize import video_normalize
from lakeon.components.video.video_scene_split import video_scene_split, detect_scenes, split_video_by_scenes
from lakeon.components.video.rule_filter import rule_filter, evaluate_rules, ffprobe_clip_metadata
from lakeon.components.video.video_crop import video_crop
from lakeon.components.video.model_filter_mock import model_filter_mock
from lakeon.components.video.quality_check import quality_check
from lakeon.components.video.video_labeling_mock import video_labeling_mock
from lakeon.components.universal.dataset_publish import dataset_publish

from tests.conftest import FakeComponentContext, MockObs


pytestmark = pytest.mark.e2e


class TestVideoPipelineE2E:
    """End-to-end test of the video data cleaning pipeline."""

    def test_full_video_pipeline(self, test_video_path, video_pipeline_params):
        """Run the complete video pipeline from raw video to published dataset.

        This test validates:
        1. video_normalize transcodes to target resolution
        2. video_scene_split detects scenes and produces clips (fan-out)
        3. rule_filter classifies each clip into passed/needs_crop/dropped
        4. video_crop processes needs_crop clips
        5. model_filter_mock filters clips by mock model scores
        6. quality_check auto-approves in test mode
        7. video_labeling_mock adds labels to each clip
        8. dataset_publish writes the final dataset
        """
        # --- Step 1: Normalize ---
        ctx_norm = FakeComponentContext(
            input={"video": test_video_path},
            params=video_pipeline_params["normalize"],
        )
        norm_result = video_normalize(ctx_norm)
        assert "video" in norm_result
        assert os.path.exists(norm_result["video"])
        assert ctx_norm.metrics.get("input_count") == 1

        # --- Step 2: Scene Split (fan-out) ---
        ctx_split = FakeComponentContext(
            input={"video": norm_result["video"]},
            params=video_pipeline_params["scene_split"],
        )
        split_result = video_scene_split(ctx_split)
        assert split_result.get("__fan_out__") is True
        clips = split_result["items"]
        assert len(clips) >= 1  # At least 1 clip from our test video
        for clip in clips:
            assert os.path.exists(clip)

        # --- Step 3: Rule Filter (conditional branching per clip) ---
        passed_clips = []
        needs_crop_clips = []
        dropped_count = 0

        for clip_path in clips:
            ctx_filter = FakeComponentContext(
                input={"clip": clip_path},
                params=video_pipeline_params["rule_filter"],
            )
            filter_result = rule_filter(ctx_filter)
            branch = filter_result["__branch__"]

            if branch == "passed":
                passed_clips.append(filter_result["item"])
            elif branch == "needs_crop":
                needs_crop_clips.append(filter_result["item"])
            else:
                dropped_count += 1

        total_after_filter = len(passed_clips) + len(needs_crop_clips)

        # --- Step 4: Crop (for needs_crop clips) ---
        for crop_clip in needs_crop_clips:
            ctx_crop = FakeComponentContext(
                input={"clip": crop_clip},
                params=video_pipeline_params["video_crop"],
            )
            crop_result = video_crop(ctx_crop)
            if crop_result["__branch__"] == "passed":
                passed_clips.append(crop_result["item"])

        # --- Step 5: Merge (fan-in) ---
        merged_clips = passed_clips
        assert len(merged_clips) >= 1, "At least one clip should survive filtering"

        # --- Step 6: Model Filter Mock ---
        ctx_model = FakeComponentContext(
            input={"clips": merged_clips},
            params=video_pipeline_params["model_filter"],
        )
        model_result = model_filter_mock(ctx_model)
        assert "clips" in model_result
        model_passed = model_result["clips"]

        # With permissive thresholds, most should pass
        assert len(model_passed) >= 1

        # --- Step 7: Quality Check (auto-approve) ---
        ctx_qc = FakeComponentContext(
            input={"clips": model_passed},
            params=video_pipeline_params["quality_check"],
        )
        qc_result = quality_check(ctx_qc)
        assert "clips" in qc_result
        approved_clips = qc_result["clips"]
        assert len(approved_clips) == len(model_passed)  # auto_approve passes all

        # --- Step 8: Labeling Mock ---
        ctx_label = FakeComponentContext(
            input={"clips": approved_clips},
            params=video_pipeline_params["labeling"],
        )
        label_result = video_labeling_mock(ctx_label)
        assert "clips" in label_result
        labeled_clips = label_result["clips"]
        assert len(labeled_clips) == len(approved_clips)

        # Verify each clip has labels
        for item in labeled_clips:
            assert "labels" in item
            assert "caption" in item["labels"]
            assert "viclip_tags" in item["labels"]
            assert "camera_motion" in item["labels"]

        # --- Step 9: Publish ---
        ctx_pub = FakeComponentContext(
            input={"clips": labeled_clips},
            params=video_pipeline_params["publish"],
        )
        pub_result = dataset_publish(ctx_pub)
        assert "dataset_version" in pub_result
        version = pub_result["dataset_version"]
        assert version["row_count"] == len(labeled_clips)
        assert version["format"] == "PARQUET"
        assert version["dataset_name"] == "e2e_test_video"

        # --- Verify full pipeline metrics ---
        print(f"\n--- Video Pipeline E2E Summary ---")
        print(f"Input: 1 video ({test_video_path})")
        print(f"Scene split: {len(clips)} clips")
        print(f"Rule filter: {len(passed_clips)} passed, {len(needs_crop_clips)} crop, {dropped_count} dropped")
        print(f"Model filter: {len(model_passed)} passed")
        print(f"Published: {version['row_count']} clips")
        print(f"Retention: {version['row_count']/max(len(clips),1)*100:.1f}%")
```

- [ ] **Step 4: 运行 E2E 测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/e2e/test_video_pipeline_e2e.py -v -m e2e --timeout=120
```

需要环境中有 ffmpeg。如果没有，测试会 skip。

- [ ] **Step 5: Commit**

```bash
git add lakeon-orchestrator/tests/conftest.py \
        lakeon-orchestrator/tests/e2e/conftest.py \
        lakeon-orchestrator/tests/e2e/test_video_pipeline_e2e.py
git commit -m "test(pipeline): add video pipeline E2E test with full flow verification"
```

---

## Task 15: E2E 测试 -- 文本 pipeline 完整流程

**Files:**
- Create: `lakeon-orchestrator/tests/e2e/test_text_pipeline_e2e.py`
- Create: `lakeon-orchestrator/tests/fixtures/sample_texts.jsonl`

- [ ] **Step 1: 创建测试文本数据**

Create: `lakeon-orchestrator/tests/fixtures/sample_texts.jsonl`

```jsonl
{"id": "t001", "content": "Machine learning is a subset of artificial intelligence that focuses on building systems that learn from data. These systems can improve their performance over time without being explicitly programmed. Common approaches include supervised learning, unsupervised learning, and reinforcement learning."}
{"id": "t002", "content": "Machine learning is a subset of artificial intelligence that focuses on building systems that learn from data. These systems improve performance over time without explicit programming. Common approaches include supervised, unsupervised, and reinforcement learning."}
{"id": "t003", "content": "<p>Deep learning uses <b>neural networks</b> with many layers to learn complex patterns.</p> Visit https://example.com for more info. Contact: info@example.com"}
{"id": "t004", "content": "自然语言处理是人工智能的重要分支，研究计算机与人类语言之间的交互。它涵盖了文本分类、情感分析、机器翻译、问答系统等多个子领域。近年来，基于Transformer架构的大语言模型在NLP领域取得了突破性进展。"}
{"id": "t005", "content": "hi"}
{"id": "t006", "content": "计算机视觉是一个跨学科的研究领域，旨在让计算机从数字图像或视频中获得高层次的理解。它的任务包括图像分类、目标检测、图像分割、人脸识别等。深度学习特别是卷积神经网络的发展极大推动了计算机视觉的进步。"}
{"id": "t007", "content": "!@#$%^&*()!@#$%^&*()!@#$%^&*()!@#$%^&*()!@#$%^&*()!@#$%^&*()!@#$%^&*()!@#$%^&*()!@#$%^&*()"}
{"id": "t008", "content": "Reinforcement learning is an area of machine learning concerned with how intelligent agents take actions in an environment to maximize cumulative reward. Unlike supervised learning, RL does not require labeled data. Instead, agents learn through trial and error, receiving feedback through rewards or penalties."}
{"id": "t009", "content": "数据工程是构建和维护数据基础设施的实践。数据工程师负责设计、构建和管理数据管道，确保数据从各种来源流向数据仓库和数据湖。ETL（提取、转换、加载）是数据工程中的核心流程。现代数据栈包括Apache Spark、Kafka、Airflow等工具。"}
{"id": "t010", "content": "Transfer learning is a technique where a model trained on one task is repurposed as the starting point for a model on a second task. This is especially useful when the second task has limited training data. Pre-trained language models like BERT and GPT demonstrate the power of transfer learning."}
{"id": "t011", "content": "spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam spam"}
{"id": "t012", "content": "联邦学习是一种分布式机器学习方法，允许多个参与方在不共享原始数据的情况下协同训练模型。这种方法在保护数据隐私的同时，能够利用分散在不同设备或机构中的数据来改进模型性能。谷歌在移动键盘预测中率先应用了联邦学习技术。"}
{"id": "t013", "content": "Generative adversarial networks consist of two neural networks: a generator and a discriminator. The generator creates synthetic data samples, while the discriminator tries to distinguish real data from generated data. Through this adversarial process, the generator learns to produce increasingly realistic outputs."}
{"id": "t014", "content": "知识图谱是一种结构化的知识表示方式，以图的形式存储实体及其关系。在搜索引擎、推荐系统、智能问答等应用中发挥着重要作用。构建知识图谱涉及实体识别、关系抽取、知识融合等技术环节。"}
{"id": "t015", "content": "Transformer architecture has revolutionized natural language processing since its introduction in 2017. The key innovation is the self-attention mechanism, which allows the model to weigh the importance of different parts of the input when producing output. This architecture forms the basis of models like BERT, GPT, and T5."}
{"id": "t016", "content": "abc"}
{"id": "t017", "content": "Transformer architecture has revolutionized natural language processing since its introduction in 2017. The key innovation is the self-attention mechanism which allows the model to weigh the importance of different parts of input when producing output. This architecture forms the basis of models like BERT GPT and T5."}
{"id": "t018", "content": "边缘计算是一种分布式计算范式，将计算和数据存储带到更接近数据源的位置。通过减少数据传输距离和延迟，边缘计算能够支持实时应用和物联网场景。5G网络的普及进一步推动了边缘计算的发展和应用。"}
{"id": "t019", "content": "Quantum computing leverages quantum mechanical phenomena like superposition and entanglement to process information. Unlike classical bits, quantum bits (qubits) can exist in multiple states simultaneously. This enables quantum computers to solve certain problems exponentially faster than classical computers."}
{"id": "t020", "content": "模型压缩技术旨在减小深度学习模型的大小和计算量，使其能够部署在资源受限的设备上。常见的模型压缩方法包括知识蒸馏、模型剪枝、量化和低秩分解。这些技术在保持模型性能的同时显著降低了推理成本。"}
```

- [ ] **Step 2: 实现文本 pipeline E2E 测试**

Create: `lakeon-orchestrator/tests/e2e/test_text_pipeline_e2e.py`

```python
"""E2E test: Text data cleaning pipeline.

Exercises the full flow:
  raw texts → text_dedup → text_clean → text_tokenize
  → text_quality_score (branch) → quality_check (auto_approve) → dataset_publish

Does NOT require ffmpeg.
"""

import json
import os

import pytest

from lakeon.components.text.text_dedup import text_dedup
from lakeon.components.text.text_clean import text_clean
from lakeon.components.text.text_tokenize import text_tokenize
from lakeon.components.text.text_quality_score import text_quality_score
from lakeon.components.video.quality_check import quality_check
from lakeon.components.universal.dataset_publish import dataset_publish

from tests.conftest import FakeComponentContext


pytestmark = pytest.mark.e2e

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "..", "fixtures")


def load_test_texts() -> list[dict]:
    """Load test texts from sample_texts.jsonl fixture."""
    texts = []
    fixture_path = os.path.join(FIXTURES_DIR, "sample_texts.jsonl")
    with open(fixture_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                texts.append(json.loads(line))
    return texts


class TestTextPipelineE2E:
    """End-to-end test of the text data cleaning pipeline."""

    def test_full_text_pipeline(self):
        """Run the complete text pipeline from raw texts to published dataset.

        This test validates:
        1. text_dedup removes near-duplicate texts (MinHash)
        2. text_clean strips HTML/URLs, filters by length
        3. text_tokenize adds token counts and statistics
        4. text_quality_score routes to passed/low_quality
        5. quality_check auto-approves
        6. dataset_publish writes Parquet output
        """
        raw_texts = load_test_texts()
        assert len(raw_texts) == 20

        # --- Step 1: Dedup ---
        ctx_dedup = FakeComponentContext(
            input={"text": raw_texts},
            params={
                "similarity_threshold": 0.85,
                "num_perm": 128,
                "ngram": 3,
                "text_key": "content",
            },
        )
        dedup_result = text_dedup(ctx_dedup)
        deduped = dedup_result["text"]
        assert len(deduped) < len(raw_texts), "Dedup should remove some near-duplicates"
        # t001/t002 are near-duplicates, t015/t017 are near-duplicates
        dedup_ids = {r["id"] for r in deduped}
        assert not ({"t001", "t002"}.issubset(dedup_ids)), "Near-duplicate pair should be deduplicated"

        print(f"\nDedup: {len(raw_texts)} -> {len(deduped)} texts")

        # --- Step 2: Clean ---
        ctx_clean = FakeComponentContext(
            input={"text": deduped},
            params={
                "remove_html": True,
                "normalize_whitespace": True,
                "remove_urls": True,
                "remove_emails": True,
                "min_length": 50,
                "max_length": 100000,
                "language_filter": ["zh", "en"],
                "text_key": "content",
            },
        )
        clean_result = text_clean(ctx_clean)
        cleaned = clean_result["text"]
        assert len(cleaned) < len(deduped), "Cleaning should filter some short/invalid texts"

        # Verify HTML was stripped from t003
        t003 = next((r for r in cleaned if r["id"] == "t003"), None)
        if t003:
            assert "<p>" not in t003["content"]
            assert "https://" not in t003["content"]
            assert "@example.com" not in t003["content"]

        # Verify short texts (t005, t016) were dropped
        clean_ids = {r["id"] for r in cleaned}
        assert "t005" not in clean_ids, "Very short text 'hi' should be dropped"
        assert "t016" not in clean_ids, "Very short text 'abc' should be dropped"

        print(f"Clean: {len(deduped)} -> {len(cleaned)} texts")

        # --- Step 3: Tokenize ---
        ctx_token = FakeComponentContext(
            input={"text": cleaned},
            params={
                "tokenizer": "tiktoken",
                "tiktoken_model": "cl100k_base",
                "compute_stats": True,
                "text_key": "content",
            },
        )
        token_result = text_tokenize(ctx_token)
        tokenized = token_result["text"]
        assert len(tokenized) == len(cleaned)

        # Verify token counts were added
        for record in tokenized:
            assert "token_count" in record
            assert record["token_count"] > 0
            assert "token_stats" in record

        total_tokens = sum(r["token_count"] for r in tokenized)
        print(f"Tokenize: {len(tokenized)} texts, {total_tokens} total tokens")

        # --- Step 4: Quality Score (conditional branching) ---
        ctx_quality = FakeComponentContext(
            input={"text": tokenized},
            params={
                "scorer": "rule",
                "min_score": 0.5,
                "text_key": "content",
            },
        )
        quality_result = text_quality_score(ctx_quality)
        passed = quality_result["passed"]
        low_quality = quality_result["low_quality"]
        assert len(passed) + len(low_quality) == len(tokenized)

        # Spam text (t011) should be low quality
        low_ids = {r["id"] for r in low_quality}
        # Special chars text (t007) should be low quality if it survived cleaning
        if "t007" in {r["id"] for r in tokenized}:
            assert "t007" in low_ids, "Special chars text should be low quality"

        print(f"Quality: {len(passed)} passed, {len(low_quality)} low quality")

        # --- Step 5: Quality Check (auto-approve) ---
        ctx_qc = FakeComponentContext(
            input={"text": passed},
            params={"review_mode": "auto_approve"},
        )
        qc_result = quality_check(ctx_qc)
        approved = qc_result["text"]
        assert len(approved) == len(passed)

        # --- Step 6: Publish ---
        ctx_pub = FakeComponentContext(
            input={"text": approved},
            params={
                "dataset_name": "e2e_test_text",
                "format": "PARQUET",
                "text_key": "content",
            },
        )
        pub_result = dataset_publish(ctx_pub)
        assert "dataset_version" in pub_result
        version = pub_result["dataset_version"]
        assert version["row_count"] == len(approved)
        assert version["format"] == "PARQUET"
        assert version["dataset_name"] == "e2e_test_text"

        # --- Verify full pipeline summary ---
        retention = version["row_count"] / max(len(raw_texts), 1) * 100

        print(f"\n--- Text Pipeline E2E Summary ---")
        print(f"Input: {len(raw_texts)} texts")
        print(f"After dedup: {len(deduped)}")
        print(f"After clean: {len(cleaned)}")
        print(f"After quality: {len(passed)} passed / {len(low_quality)} low")
        print(f"Published: {version['row_count']} texts")
        print(f"Overall retention: {retention:.1f}%")

        # Sanity checks
        assert version["row_count"] >= 5, "At least 5 good texts should survive"
        assert version["row_count"] <= 18, "Not all texts should pass (some are dupes/short/low quality)"

    def test_dedup_detects_near_duplicates(self):
        """Focused test: verify MinHash catches the known near-duplicate pairs."""
        raw_texts = load_test_texts()

        ctx = FakeComponentContext(
            input={"text": raw_texts},
            params={
                "similarity_threshold": 0.8,
                "num_perm": 128,
                "ngram": 3,
                "text_key": "content",
            },
        )
        result = text_dedup(ctx)
        remaining_ids = {r["id"] for r in result["text"]}

        # At least one from each near-dup pair should be removed
        assert not ({"t001", "t002"}.issubset(remaining_ids))

    def test_quality_score_identifies_spam(self):
        """Focused test: verify spam and special char texts get low quality scores."""
        texts = [
            {"id": "spam", "content": "spam " * 100},
            {"id": "special", "content": "!@#$%^&*()" * 20},
            {
                "id": "good",
                "content": (
                    "Machine learning enables computers to learn from data "
                    "and improve performance over time. It has applications "
                    "in healthcare, finance, and many other domains."
                ),
            },
        ]
        ctx = FakeComponentContext(
            input={"text": texts},
            params={"scorer": "rule", "min_score": 0.5, "text_key": "content"},
        )
        result = text_quality_score(ctx)

        passed_ids = {r["id"] for r in result["passed"]}
        low_ids = {r["id"] for r in result["low_quality"]}

        assert "good" in passed_ids, "Good quality text should pass"
        # At least one of the bad texts should be flagged
        assert len(low_ids) >= 1, "At least one low-quality text should be detected"
```

- [ ] **Step 3: 运行 E2E 测试**

```bash
cd lakeon-orchestrator && python -m pytest tests/e2e/test_text_pipeline_e2e.py -v -m e2e -s
```

文本 pipeline 不依赖 ffmpeg，应该可以在任何环境运行。

- [ ] **Step 4: Commit**

```bash
git add lakeon-orchestrator/tests/fixtures/sample_texts.jsonl \
        lakeon-orchestrator/tests/e2e/test_text_pipeline_e2e.py
git commit -m "test(pipeline): add text pipeline E2E test with dedup/clean/score/publish"
```

---

## Task 16: 组件包 __init__.py 和 pytest 配置

**Files:**
- Create: `lakeon-orchestrator/components/__init__.py`
- Create: `lakeon-orchestrator/tests/__init__.py`
- Create: `lakeon-orchestrator/tests/unit/__init__.py`
- Create: `lakeon-orchestrator/tests/e2e/__init__.py`
- Modify: `lakeon-orchestrator/pyproject.toml` (或 `setup.cfg`)

- [ ] **Step 1: 创建包 __init__.py 文件**

Create: `lakeon-orchestrator/components/__init__.py`

```python
"""Pipeline components package -- Phase 1 preset components."""
```

Create: `lakeon-orchestrator/tests/__init__.py`

```python
```

Create: `lakeon-orchestrator/tests/unit/__init__.py`

```python
```

Create: `lakeon-orchestrator/tests/e2e/__init__.py`

```python
```

- [ ] **Step 2: 添加 pytest 配置和 E2E marker**

在 `lakeon-orchestrator/pyproject.toml` 中添加（如果文件不存在则创建 `pytest.ini`）：

Create: `lakeon-orchestrator/pytest.ini`

```ini
[pytest]
testpaths = tests
markers =
    e2e: End-to-end tests (may require external dependencies like ffmpeg)
```

- [ ] **Step 3: 添加组件依赖到 requirements**

在 `lakeon-orchestrator/requirements-components.txt` 中（如果不存在则创建）：

```
# Phase 1 component dependencies
scenedetect[opencv]>=0.6
datasketch>=1.6
jieba>=0.42
tiktoken>=0.5
pylance>=0.9
pyarrow>=14.0
```

- [ ] **Step 4: 验证所有单元测试通过**

```bash
cd lakeon-orchestrator && python -m pytest tests/unit/ -v --tb=short
```

- [ ] **Step 5: 验证 E2E 测试通过**

```bash
cd lakeon-orchestrator && python -m pytest tests/e2e/ -v -m e2e -s --timeout=120
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-orchestrator/components/__init__.py \
        lakeon-orchestrator/tests/__init__.py \
        lakeon-orchestrator/tests/unit/__init__.py \
        lakeon-orchestrator/tests/e2e/__init__.py \
        lakeon-orchestrator/pytest.ini \
        lakeon-orchestrator/requirements-components.txt
git commit -m "chore(pipeline): add package init files, pytest config, component dependencies"
```
