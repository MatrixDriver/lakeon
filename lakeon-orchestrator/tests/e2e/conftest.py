"""E2E test fixtures and helpers."""

import sys
from pathlib import Path

import pytest

# Add the components directory to sys.path so tests can import from it
_components_root = Path(__file__).resolve().parent.parent.parent / "components"
if str(_components_root.parent) not in sys.path:
    sys.path.insert(0, str(_components_root.parent))


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
