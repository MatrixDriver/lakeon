"""model_filter_mock: Mock 模型清洗 -- 随机打分模拟 VQA/水印/字幕/光流检测.

Phase 1 mock: 真实模型将在 Phase 2 替换。
"""

import hashlib
import random

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component

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
