"""
E2E tests for Text Pipeline execution with real datasets.

Tests the full text pipeline flow:
  1. Upload JSONL text data as dataset (via OBS)
  2. Create text pipeline from template DAG
  3. Trigger pipeline run with dataset
  4. Poll for completion → verify step results

Requires: orchestrator service running.
Skip completion checks with: PIPELINE_SKIP_COMPLETION=1
"""
import os
import time

import pytest

from conftest import poll_until
from dbay_cli.client import DbayApiError

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")
TEXT_FIXTURES_DIR = os.path.join(FIXTURES_DIR, "text")
SKIP_COMPLETION = os.environ.get("PIPELINE_SKIP_COMPLETION", "0") == "1"
PIPELINE_TIMEOUT = int(os.environ.get("PIPELINE_TIMEOUT", "300"))


def _read_fixture(name: str) -> str:
    with open(os.path.join(FIXTURES_DIR, name)) as f:
        return f.read()


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def news_dataset(e2e_client):
    """Upload news articles JSONL as dataset."""
    records_path = os.path.join(TEXT_FIXTURES_DIR, "news_articles.jsonl")
    ds = e2e_client.upload_dataset(
        name=f"e2e-news-{int(time.time())}",
        path=records_path,
        description="E2E news articles",
    )
    assert ds["status"] == "READY"
    assert ds["file_count"] == 1

    yield ds

    try:
        e2e_client.delete_dataset(ds["id"])
    except Exception:
        pass


@pytest.fixture(scope="module")
def review_dataset(e2e_client):
    """Upload movie reviews JSONL as dataset."""
    records_path = os.path.join(TEXT_FIXTURES_DIR, "movie_reviews.jsonl")
    ds = e2e_client.upload_dataset(
        name=f"e2e-reviews-{int(time.time())}",
        path=records_path,
        description="E2E movie reviews",
    )
    assert ds["status"] == "READY"
    assert ds["file_count"] == 1

    yield ds

    try:
        e2e_client.delete_dataset(ds["id"])
    except Exception:
        pass


@pytest.fixture(scope="module")
def chinese_dataset(e2e_client):
    """Upload Chinese abstracts JSONL as dataset."""
    records_path = os.path.join(TEXT_FIXTURES_DIR, "chinese_abstracts.jsonl")
    ds = e2e_client.upload_dataset(
        name=f"e2e-cn-abs-{int(time.time())}",
        path=records_path,
        description="E2E Chinese abstracts",
    )
    assert ds["status"] == "READY"
    assert ds["file_count"] == 1

    yield ds

    try:
        e2e_client.delete_dataset(ds["id"])
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Helper: run pipeline and verify
# ---------------------------------------------------------------------------

def _run_pipeline_and_verify(e2e_client, pipeline_id, version, dataset_id,
                              expected_steps, min_passed=0):
    """Trigger a pipeline run, poll for completion, verify step results."""
    run = e2e_client.trigger_pipeline_run(
        pipeline_id=pipeline_id,
        version=version,
        input_dataset_id=dataset_id,
    )
    assert run["pipeline_id"] == pipeline_id
    assert run["pipeline_version"] == version
    assert run["input_dataset_id"] == dataset_id
    assert run["status"] in ("PENDING", "RUNNING")

    if SKIP_COMPLETION:
        pytest.skip("PIPELINE_SKIP_COMPLETION=1, skipping completion check")

    # Poll for completion
    run = poll_until(
        lambda: e2e_client.get_pipeline_run(run["id"]),
        condition=lambda r: r["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"),
        timeout=PIPELINE_TIMEOUT,
        interval=5,
    )
    assert run["status"] == "SUCCEEDED", f"Pipeline run failed: {run}"

    # Verify step runs
    step_runs = e2e_client.list_step_runs(run["id"])
    assert len(step_runs) >= expected_steps

    succeeded = [s for s in step_runs if s["status"] == "SUCCEEDED"]
    assert len(succeeded) >= min_passed, (
        f"Expected >= {min_passed} succeeded steps, got {len(succeeded)}: "
        f"{[(s.get('step_id'), s['status']) for s in step_runs]}"
    )

    return run, step_runs


# ---------------------------------------------------------------------------
# Tests: Text Pipeline with News Articles
# ---------------------------------------------------------------------------

class TestTextPipelineNews:
    """Run text pipeline template on English news articles."""

    @pytest.fixture(scope="class")
    def text_pipeline(self, e2e_client):
        """Create a text pipeline from template DAG."""
        dag_yaml = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-txt-news-{int(time.time())}",
            data_type="TEXT",
            description="E2E text pipeline with news articles",
            dag_yaml=dag_yaml,
        )
        assert pipeline["latest_version"] == 1

        yield pipeline

        try:
            e2e_client.delete_pipeline(pipeline["id"])
        except Exception:
            pass

    def test_run_news_pipeline(self, e2e_client, text_pipeline, news_dataset):
        """Full pipeline: dedup → clean → tokenize → quality_score → publish."""
        run, step_runs = _run_pipeline_and_verify(
            e2e_client,
            pipeline_id=text_pipeline["id"],
            version=1,
            dataset_id=news_dataset["id"],
            expected_steps=5,
            min_passed=4,  # at least dedup, clean, tokenize, quality_score
        )
        step_ids = {s.get("step_id") for s in step_runs}
        assert "dedup" in step_ids
        assert "clean" in step_ids
        assert "tokenize_stats" in step_ids
        assert "quality_score" in step_ids
        assert "publish" in step_ids


# ---------------------------------------------------------------------------
# Tests: Text Pipeline with Movie Reviews (HTML content)
# ---------------------------------------------------------------------------

class TestTextPipelineReviews:
    """Run text pipeline on movie reviews — tests HTML cleaning."""

    @pytest.fixture(scope="class")
    def review_pipeline(self, e2e_client):
        """Create pipeline with more aggressive cleaning params."""
        dag_yaml = _read_fixture("text_pipeline_dag.yaml")
        # Reviews are longer, lower the min_length threshold
        dag_yaml = dag_yaml.replace("min_length: 50", "min_length: 30")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-txt-review-{int(time.time())}",
            data_type="TEXT",
            description="E2E text pipeline with movie reviews",
            dag_yaml=dag_yaml,
        )

        yield pipeline

        try:
            e2e_client.delete_pipeline(pipeline["id"])
        except Exception:
            pass

    def test_run_review_pipeline(self, e2e_client, review_pipeline, review_dataset):
        """Movie reviews contain HTML tags — pipeline should clean them."""
        run, step_runs = _run_pipeline_and_verify(
            e2e_client,
            pipeline_id=review_pipeline["id"],
            version=1,
            dataset_id=review_dataset["id"],
            expected_steps=5,
            min_passed=4,
        )
        step_ids = {s.get("step_id") for s in step_runs}
        assert "clean" in step_ids


# ---------------------------------------------------------------------------
# Tests: Text Pipeline with Chinese Abstracts
# ---------------------------------------------------------------------------

class TestTextPipelineChinese:
    """Run text pipeline on Chinese academic abstracts."""

    @pytest.fixture(scope="class")
    def chinese_pipeline(self, e2e_client):
        """Create pipeline tuned for Chinese text."""
        dag_yaml = _read_fixture("text_pipeline_dag.yaml")
        # Use jieba tokenizer for Chinese
        dag_yaml = dag_yaml.replace('tokenizer: "tiktoken"', 'tokenizer: "jieba"')
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-txt-chinese-{int(time.time())}",
            data_type="TEXT",
            description="E2E text pipeline with Chinese abstracts",
            dag_yaml=dag_yaml,
        )

        yield pipeline

        try:
            e2e_client.delete_pipeline(pipeline["id"])
        except Exception:
            pass

    def test_run_chinese_pipeline(self, e2e_client, chinese_pipeline, chinese_dataset):
        """Chinese abstracts — tests jieba tokenizer and CJK quality scoring."""
        run, step_runs = _run_pipeline_and_verify(
            e2e_client,
            pipeline_id=chinese_pipeline["id"],
            version=1,
            dataset_id=chinese_dataset["id"],
            expected_steps=5,
            min_passed=4,
        )
        step_ids = {s.get("step_id") for s in step_runs}
        assert "dedup" in step_ids
        assert "tokenize_stats" in step_ids


# ---------------------------------------------------------------------------
# Tests: Pipeline with modified threshold versions
# ---------------------------------------------------------------------------

class TestTextPipelineVersions:
    """Test running different pipeline versions on the same dataset."""

    @pytest.fixture(scope="class")
    def versioned_pipeline(self, e2e_client):
        """Create pipeline with two versions: strict vs relaxed quality."""
        dag_v1 = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-txt-ver-{int(time.time())}",
            data_type="TEXT",
            description="E2E versioned text pipeline",
            dag_yaml=dag_v1,
        )

        # v2: stricter quality threshold
        dag_v2 = dag_v1.replace("min_score: 0.6", "min_score: 0.8")
        dag_v2 = dag_v2.replace("similarity_threshold: 0.85", "similarity_threshold: 0.95")
        e2e_client.create_pipeline_version(
            pipeline["id"], dag_v2,
            changelog="Stricter quality and dedup thresholds",
        )

        yield pipeline

        try:
            e2e_client.delete_pipeline(pipeline["id"])
        except Exception:
            pass

    def test_run_v1_relaxed(self, e2e_client, versioned_pipeline, news_dataset):
        """V1 (relaxed threshold) should pass more records."""
        run = e2e_client.trigger_pipeline_run(
            pipeline_id=versioned_pipeline["id"],
            version=1,
            input_dataset_id=news_dataset["id"],
        )
        assert run["pipeline_version"] == 1
        assert run["status"] in ("PENDING", "RUNNING")

        if SKIP_COMPLETION:
            pytest.skip("PIPELINE_SKIP_COMPLETION=1")

        run = poll_until(
            lambda: e2e_client.get_pipeline_run(run["id"]),
            condition=lambda r: r["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"),
            timeout=PIPELINE_TIMEOUT,
            interval=5,
        )
        assert run["status"] == "SUCCEEDED"

    def test_run_v2_strict(self, e2e_client, versioned_pipeline, news_dataset):
        """V2 (strict threshold) — should also succeed but filter more."""
        run = e2e_client.trigger_pipeline_run(
            pipeline_id=versioned_pipeline["id"],
            version=2,
            input_dataset_id=news_dataset["id"],
        )
        assert run["pipeline_version"] == 2
        assert run["status"] in ("PENDING", "RUNNING")

        if SKIP_COMPLETION:
            pytest.skip("PIPELINE_SKIP_COMPLETION=1")

        run = poll_until(
            lambda: e2e_client.get_pipeline_run(run["id"]),
            condition=lambda r: r["status"] in ("SUCCEEDED", "FAILED", "CANCELLED"),
            timeout=PIPELINE_TIMEOUT,
            interval=5,
        )
        assert run["status"] == "SUCCEEDED"
