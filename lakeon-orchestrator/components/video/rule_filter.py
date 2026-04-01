"""rule_filter: 基于 ffprobe 元数据的规则过滤，条件分支输出 passed/needs_crop/dropped."""

import json
import subprocess

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


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
