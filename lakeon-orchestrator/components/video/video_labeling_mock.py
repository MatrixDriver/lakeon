"""video_labeling_mock: Mock 内容标注 -- 返回固定标签模拟 VICLIP/Caption/运镜检测.

Phase 1 mock: 真实模型将在 Phase 2 替换。
"""

import hashlib
import random

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component

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
