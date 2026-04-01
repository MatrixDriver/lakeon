"""quality_check: HUMAN_REVIEW 质检组件，暂停等待人工确认后恢复."""

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


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
    import json
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
