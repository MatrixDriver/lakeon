"""video_normalize: 视频规整适配 -- ffprobe 元数据提取 + ffmpeg 转码."""

import json
import os
import subprocess
import tempfile
from pathlib import Path

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component

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
