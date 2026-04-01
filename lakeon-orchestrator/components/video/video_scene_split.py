"""video_scene_split: PySceneDetect 镜头检测 + ffmpeg 切片，fan_out 输出 clips."""

import os
import subprocess
import tempfile
from pathlib import Path

from scenedetect import ContentDetector, SceneManager, open_video

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


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

    ctx.checkpoint(clips)
    ctx.report({
        "input_count": 1,
        "output_count": len(clips),
        "scenes_detected": len(scenes),
    })

    return ctx.fan_out(clips)
