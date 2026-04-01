"""video_crop: ffmpeg cropdetect + crop 处理，裁剪黑边或按目标比例裁切."""

import json
import os
import subprocess
import tempfile
from collections import Counter
from pathlib import Path

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


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
