"""E2E test: Video data cleaning pipeline.

Exercises the full flow:
  raw video -> normalize -> scene_split (fan-out) -> rule_filter (branch)
  -> video_crop (for needs_crop) -> merge -> model_filter_mock
  -> quality_check (auto_approve) -> video_labeling_mock -> dataset_publish

Requires ffmpeg on PATH and scenedetect + pyarrow packages.
"""

import os

import pytest

# Skip entire module if external deps are missing
pytest.importorskip("scenedetect", reason="scenedetect required for video E2E tests")
pytest.importorskip("pyarrow", reason="pyarrow required for dataset publishing")

from components.video.video_normalize import video_normalize
from components.video.video_scene_split import video_scene_split
from components.video.rule_filter import rule_filter
from components.video.video_crop import video_crop
from components.video.model_filter_mock import model_filter_mock
from components.video.quality_check import quality_check
from components.video.video_labeling_mock import video_labeling_mock
from components.universal.dataset_publish import dataset_publish

from tests.conftest import FakeComponentContext


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
            input_data={"video": test_video_path},
            params=video_pipeline_params["normalize"],
        )
        norm_result = video_normalize(ctx_norm)
        assert "video" in norm_result
        assert os.path.exists(norm_result["video"])
        assert ctx_norm.metrics.get("input_count") == 1

        # --- Step 2: Scene Split (fan-out) ---
        ctx_split = FakeComponentContext(
            input_data={"video": norm_result["video"]},
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
                input_data={"clip": clip_path},
                params=video_pipeline_params["rule_filter"],
            )
            filter_result = rule_filter(ctx_filter)
            branch = filter_result["__branch__"]

            if branch == "passed":
                passed_clips.append(filter_result["data"])
            elif branch == "needs_crop":
                needs_crop_clips.append(filter_result["data"])
            else:
                dropped_count += 1

        total_after_filter = len(passed_clips) + len(needs_crop_clips)

        # --- Step 4: Crop (for needs_crop clips) ---
        for crop_clip in needs_crop_clips:
            ctx_crop = FakeComponentContext(
                input_data={"clip": crop_clip},
                params=video_pipeline_params["video_crop"],
            )
            crop_result = video_crop(ctx_crop)
            if crop_result["__branch__"] == "passed":
                passed_clips.append(crop_result["data"])

        # --- Step 5: Merge (fan-in) ---
        merged_clips = passed_clips
        assert len(merged_clips) >= 1, "At least one clip should survive filtering"

        # --- Step 6: Model Filter Mock ---
        ctx_model = FakeComponentContext(
            input_data={"clips": merged_clips},
            params=video_pipeline_params["model_filter"],
        )
        model_result = model_filter_mock(ctx_model)
        assert "clips" in model_result
        model_passed = model_result["clips"]

        # With permissive thresholds, most should pass
        assert len(model_passed) >= 1

        # --- Step 7: Quality Check (auto-approve) ---
        ctx_qc = FakeComponentContext(
            input_data={"clips": model_passed},
            params=video_pipeline_params["quality_check"],
        )
        qc_result = quality_check(ctx_qc)
        assert "clips" in qc_result
        approved_clips = qc_result["clips"]
        assert len(approved_clips) == len(model_passed)  # auto_approve passes all

        # --- Step 8: Labeling Mock ---
        ctx_label = FakeComponentContext(
            input_data={"clips": approved_clips},
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
            input_data={"clips": labeled_clips},
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
