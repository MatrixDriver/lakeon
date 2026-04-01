"""dataset_publish: 写入 Lance/Parquet 数据集 + 创建 dataset_version 记录."""

import json
import os
import tempfile
import uuid
from datetime import datetime, timezone

try:
    import pyarrow as pa
    import pyarrow.parquet as pq
except ImportError:
    pa = None  # type: ignore[assignment]
    pq = None  # type: ignore[assignment]

from lakeon_orchestrator.component.context import ComponentContext
from lakeon_orchestrator.component.decorator import Component


def _build_video_table(clips: list) -> "pa.Table":
    """Build a PyArrow table from video clip data.

    Handles both simple paths (list[str]) and labeled clips (list[dict]).
    """
    if pa is None:
        raise ImportError("pyarrow is required for dataset publishing: pip install pyarrow")

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


def _build_text_table(texts: list[dict], text_key: str = "content") -> "pa.Table":
    """Build a PyArrow table from text records."""
    if pa is None:
        raise ImportError("pyarrow is required for dataset publishing: pip install pyarrow")

    if not texts:
        return pa.table({text_key: pa.array([], type=pa.string())})

    # Collect all keys across records
    all_keys: set[str] = set()
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


def write_parquet(table: "pa.Table", out_path: str) -> int:
    """Write PyArrow table to Parquet file. Returns file size in bytes."""
    if pq is None:
        raise ImportError("pyarrow is required for Parquet writing: pip install pyarrow")
    pq.write_table(table, out_path, compression="snappy")
    return os.path.getsize(out_path)


def write_lance(table: "pa.Table", out_path: str) -> int:
    """Write PyArrow table to Lance dataset. Returns approximate size in bytes."""
    try:
        import lance
    except ImportError:
        raise ImportError("pylance is required for Lance writing: pip install pylance")
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

    # Create dataset_version record
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
