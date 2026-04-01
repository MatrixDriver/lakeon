"""Unit tests for dataset_publish component."""

import json
import os
import tempfile
from unittest.mock import MagicMock, patch

import pytest

try:
    import pyarrow as pa
    import pyarrow.parquet
    HAS_PYARROW = True
except ImportError:
    HAS_PYARROW = False

pytestmark = pytest.mark.skipif(not HAS_PYARROW, reason="pyarrow not installed")


from components.universal.dataset_publish import (
    _build_video_table,
    _build_text_table,
    write_parquet,
    dataset_publish,
)


class TestBuildVideoTable:
    def test_simple_paths(self):
        clips = ["/tmp/clip1.mp4", "/tmp/clip2.mp4"]
        table = _build_video_table(clips)
        assert table.num_rows == 2
        assert "clip_path" in table.column_names

    def test_labeled_clips(self):
        clips = [
            {
                "clip": "/tmp/clip1.mp4",
                "labels": {
                    "caption": "A scene in a park",
                    "viclip_tags": [{"tag": "outdoor", "confidence": 0.9}],
                    "camera_motion": {"primary_motion": "pan_left"},
                },
            },
        ]
        table = _build_video_table(clips)
        assert table.num_rows == 1
        assert "caption" in table.column_names
        assert "clip_path" in table.column_names

    def test_empty_clips(self):
        table = _build_video_table([])
        assert table.num_rows == 0


class TestBuildTextTable:
    def test_basic_records(self):
        texts = [
            {"id": "1", "content": "Hello world"},
            {"id": "2", "content": "Test text"},
        ]
        table = _build_text_table(texts)
        assert table.num_rows == 2
        assert "content" in table.column_names

    def test_nested_fields_serialized(self):
        texts = [{"content": "text", "quality_scores": {"overall": 0.8}}]
        table = _build_text_table(texts)
        assert table.num_rows == 1
        # Nested dict should be JSON-serialized
        val = table.column("quality_scores")[0].as_py()
        assert "overall" in val

    def test_empty_records(self):
        table = _build_text_table([])
        assert table.num_rows == 0


class TestWriteParquet:
    def test_writes_file(self):
        table = pa.table({"col": pa.array(["a", "b", "c"])})
        with tempfile.NamedTemporaryFile(suffix=".parquet", delete=False) as f:
            out_path = f.name

        try:
            size = write_parquet(table, out_path)
            assert size > 0
            assert os.path.exists(out_path)

            # Verify readable
            read_table = pa.parquet.read_table(out_path)
            assert read_table.num_rows == 3
        finally:
            os.unlink(out_path)


class TestDatasetPublish:
    @pytest.fixture
    def mock_ctx(self):
        ctx = MagicMock()
        ctx.params = {
            "dataset_name": "test_dataset",
            "format": "PARQUET",
            "text_key": "content",
        }
        ctx.input = {
            "text": [
                {"id": "1", "content": "First document"},
                {"id": "2", "content": "Second document"},
            ]
        }
        ctx.log = MagicMock()
        ctx.report = MagicMock()
        ctx.obs = MagicMock()
        return ctx

    def test_publishes_text_as_parquet(self, mock_ctx):
        result = dataset_publish(mock_ctx)

        assert "dataset_version" in result
        version = result["dataset_version"]
        assert version["format"] == "PARQUET"
        assert version["row_count"] == 2
        assert version["dataset_name"] == "test_dataset"
        assert mock_ctx.obs.write.called

    def test_publishes_video_clips(self, mock_ctx):
        mock_ctx.input = {"clips": ["/tmp/clip1.mp4", "/tmp/clip2.mp4"]}
        mock_ctx.params["format"] = "LANCE"

        with patch("components.universal.dataset_publish.write_lance") as mock_lance:
            mock_lance.return_value = 4096
            result = dataset_publish(mock_ctx)

        version = result["dataset_version"]
        assert version["format"] == "LANCE"
        assert version["row_count"] == 2

    def test_reports_metrics(self, mock_ctx):
        dataset_publish(mock_ctx)

        report = mock_ctx.report.call_args[0][0]
        assert report["row_count"] == 2
        assert report["format"] == "PARQUET"
        assert "obs_path" in report
