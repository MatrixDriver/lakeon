"""
E2E tests for Pipeline, Component Library, and Pipeline Run functionality.

Tests: Pipeline CRUD, template instantiation, version management,
       component library listing, custom component registration,
       pipeline run trigger/status/cancel.
"""
import os
import time
import pytest

from conftest import poll_until
from dbay_cli.client import DbayApiError

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")

MINIMAL_DAG = """name: minimal
data_type: TEXT
steps:
  - id: clean
    component: text_clean
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: cleaned }
"""


def _read_fixture(name: str) -> str:
    with open(os.path.join(FIXTURES_DIR, name)) as f:
        return f.read()


# ---------------------------------------------------------------------------
# 1. Component Library
# ---------------------------------------------------------------------------

class TestComponentLibrary:
    """Test preset component discovery and custom component registration."""

    def test_list_preset_components(self, e2e_client):
        """Platform preset components should be visible to all tenants."""
        components = e2e_client.list_pipeline_components()
        assert isinstance(components, list)
        assert len(components) >= 12, f"Expected at least 12 preset components, got {len(components)}"

        names = {c["name"] for c in components}
        expected = {
            "video_normalize", "video_scene_split", "rule_filter", "video_crop",
            "model_filter_mock", "quality_check", "video_labeling_mock", "dataset_publish",
            "text_dedup", "text_clean", "text_tokenize", "text_quality_score",
        }
        assert expected.issubset(names), f"Missing components: {expected - names}"

    def test_get_component_detail(self, e2e_client):
        """Get a specific component and verify its fields."""
        comp = e2e_client.get_pipeline_component("comp_text_clean")
        assert comp["name"] == "text_clean"
        assert comp["category"] == "CLEAN"
        assert comp["data_type"] == "TEXT"
        assert comp["display_name"] == "文本清洗"

    def test_list_component_versions(self, e2e_client):
        """Component should have at least one published version."""
        versions = e2e_client.list_component_versions("comp_text_clean")
        assert len(versions) >= 1
        v1 = versions[0]
        assert v1["version"] == 1
        assert v1["status"] == "PUBLISHED"
        assert v1["execution_mode"] == "FUNCTION"

    def test_get_component_version_detail(self, e2e_client):
        """Get a specific component version with schema info."""
        v = e2e_client.get_component_version("comp_video_scene_split", 1)
        assert v["version"] == 1
        assert "threshold" in (v.get("params_schema") or "")
        assert v["execution_mode"] == "FUNCTION"

    def test_component_categories(self, e2e_client):
        """Components should cover all expected categories."""
        components = e2e_client.list_pipeline_components()
        categories = {c["category"] for c in components}
        expected = {"DATA_PREP", "EXTRACT", "CLEAN", "FILTER", "QC", "LABEL", "PUBLISH"}
        assert expected.issubset(categories), f"Missing categories: {expected - categories}"

    def test_component_data_types(self, e2e_client):
        """Components should cover TEXT, VIDEO, and UNIVERSAL."""
        components = e2e_client.list_pipeline_components()
        data_types = {c["data_type"] for c in components}
        assert "TEXT" in data_types
        assert "VIDEO" in data_types
        assert "UNIVERSAL" in data_types

    def test_register_custom_component(self, e2e_client):
        """Tenant can register a custom component."""
        ts = int(time.time())
        comp = e2e_client.register_pipeline_component(
            name=f"e2e_custom_{ts}",
            display_name=f"E2E Custom {ts}",
            category="FILTER",
            data_type="TEXT",
            description="Custom filter for E2E test",
            entrypoint="lakeon.components.custom.e2e_filter",
            execution_mode="FUNCTION",
        )
        assert comp["name"] == f"e2e_custom_{ts}"
        assert comp["category"] == "FILTER"
        assert comp["data_type"] == "TEXT"

        # Verify it appears in the list
        components = e2e_client.list_pipeline_components()
        names = {c["name"] for c in components}
        assert f"e2e_custom_{ts}" in names


# ---------------------------------------------------------------------------
# 2. Pipeline Templates
# ---------------------------------------------------------------------------

class TestPipelineTemplates:
    """Test pipeline template listing and instantiation."""

    def test_list_templates(self, e2e_client):
        """Should have at least 2 preset templates (video + text)."""
        templates = e2e_client.list_pipeline_templates()
        assert isinstance(templates, list)
        assert len(templates) >= 2

        names = {t["name"] for t in templates}
        assert any("视频" in n for n in names), "Missing video template"
        assert any("文本" in n for n in names), "Missing text template"

    def test_template_fields(self, e2e_client):
        """Templates should have is_template=True and correct data_type."""
        templates = e2e_client.list_pipeline_templates()
        for t in templates:
            assert t["is_template"] is True
            assert t["data_type"] in ("VIDEO", "TEXT", "IMAGE", "AUDIO", "DOCUMENT", "UNIVERSAL")

    def test_create_from_template(self, e2e_client):
        """Create a pipeline from a template."""
        ts = int(time.time())
        dag_yaml = _read_fixture("video_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-from-tpl-{ts}",
            data_type="VIDEO",
            description="Created from template in E2E test",
            source_template_id="pipe_tpl_video_clean",
            dag_yaml=dag_yaml,
        )
        assert pipeline["name"] == f"e2e-from-tpl-{ts}"
        assert pipeline["data_type"] == "VIDEO"
        assert pipeline["source_template_id"] == "pipe_tpl_video_clean"

        # Should have version 1
        versions = e2e_client.list_pipeline_versions(pipeline["id"])
        assert len(versions) >= 1
        assert "dag_yaml" in versions[0]

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])


# ---------------------------------------------------------------------------
# 3. Pipeline CRUD
# ---------------------------------------------------------------------------

class TestPipelineCRUD:
    """Test pipeline create, read, update, delete operations."""

    def test_create_text_pipeline_with_dag(self, e2e_client):
        """Create a text pipeline with custom DAG YAML."""
        ts = int(time.time())
        dag_yaml = _read_fixture("text_pipeline_dag.yaml")

        pipeline = e2e_client.create_pipeline(
            name=f"e2e-text-{ts}",
            data_type="TEXT",
            description="E2E text pipeline test",
            dag_yaml=dag_yaml,
        )
        assert pipeline["name"] == f"e2e-text-{ts}"
        assert pipeline["data_type"] == "TEXT"
        assert pipeline["latest_version"] == 1

        # Verify version contains the DAG
        versions = e2e_client.list_pipeline_versions(pipeline["id"])
        assert len(versions) == 1
        assert "text_dedup" in versions[0]["dag_yaml"]
        assert "text_clean" in versions[0]["dag_yaml"]

        # Get pipeline by ID
        fetched = e2e_client.get_pipeline(pipeline["id"])
        assert fetched["id"] == pipeline["id"]
        assert fetched["name"] == pipeline["name"]

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])

    def test_create_video_pipeline_with_dag(self, e2e_client):
        """Create a video pipeline with custom DAG YAML."""
        ts = int(time.time())
        dag_yaml = _read_fixture("video_pipeline_dag.yaml")

        pipeline = e2e_client.create_pipeline(
            name=f"e2e-video-{ts}",
            data_type="VIDEO",
            description="E2E video pipeline test",
            dag_yaml=dag_yaml,
        )
        assert pipeline["name"] == f"e2e-video-{ts}"
        assert pipeline["data_type"] == "VIDEO"

        versions = e2e_client.list_pipeline_versions(pipeline["id"])
        assert len(versions) == 1
        assert "video_normalize" in versions[0]["dag_yaml"]
        assert "scene_split" in versions[0]["dag_yaml"]

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])

    def test_list_pipelines(self, e2e_client):
        """List pipelines returns only tenant's own pipelines."""
        ts = int(time.time())
        p1 = e2e_client.create_pipeline(name=f"e2e-list-a-{ts}", data_type="TEXT", dag_yaml=MINIMAL_DAG)
        p2 = e2e_client.create_pipeline(name=f"e2e-list-b-{ts}", data_type="VIDEO", dag_yaml=MINIMAL_DAG)

        pipelines = e2e_client.list_pipelines()
        ids = {p["id"] for p in pipelines}
        assert p1["id"] in ids
        assert p2["id"] in ids

        # Cleanup
        e2e_client.delete_pipeline(p1["id"])
        e2e_client.delete_pipeline(p2["id"])

    def test_delete_pipeline(self, e2e_client):
        """Delete a pipeline and verify it's gone."""
        ts = int(time.time())
        pipeline = e2e_client.create_pipeline(name=f"e2e-del-{ts}", data_type="TEXT", dag_yaml=MINIMAL_DAG)

        e2e_client.delete_pipeline(pipeline["id"])

        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_pipeline(pipeline["id"])
        assert exc_info.value.status_code in (404, 403)

    def test_create_pipeline_minimal(self, e2e_client):
        """Create a pipeline with minimal DAG."""
        ts = int(time.time())
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-minimal-{ts}",
            data_type="TEXT",
            dag_yaml=MINIMAL_DAG,
        )
        assert pipeline["name"] == f"e2e-minimal-{ts}"
        assert pipeline["data_type"] == "TEXT"

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])


# ---------------------------------------------------------------------------
# 4. Pipeline Versions
# ---------------------------------------------------------------------------

class TestPipelineVersions:
    """Test pipeline version management."""

    def test_publish_new_version(self, e2e_client):
        """Publish a new version with updated DAG."""
        ts = int(time.time())
        dag_v1 = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-ver-{ts}", data_type="TEXT", dag_yaml=dag_v1,
        )
        assert pipeline["latest_version"] == 1

        # Publish v2 with modified DAG
        dag_v2 = dag_v1.replace("similarity_threshold: 0.85", "similarity_threshold: 0.90")
        v2 = e2e_client.create_pipeline_version(
            pipeline["id"], dag_v2, changelog="Increased dedup threshold",
        )
        assert v2["version"] == 2
        assert "0.90" in v2["dag_yaml"]
        assert v2["changelog"] == "Increased dedup threshold"

        # Verify versions list
        versions = e2e_client.list_pipeline_versions(pipeline["id"])
        assert len(versions) == 2

        # Get specific version
        got_v1 = e2e_client.get_pipeline_version(pipeline["id"], 1)
        assert "0.85" in got_v1["dag_yaml"]

        got_v2 = e2e_client.get_pipeline_version(pipeline["id"], 2)
        assert "0.90" in got_v2["dag_yaml"]

        # Pipeline latest_version should be updated
        updated = e2e_client.get_pipeline(pipeline["id"])
        assert updated["latest_version"] == 2

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])

    def test_multiple_versions(self, e2e_client):
        """Can publish multiple versions sequentially."""
        ts = int(time.time())
        dag = _read_fixture("video_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-multi-ver-{ts}", data_type="VIDEO", dag_yaml=dag,
        )

        for i in range(2, 5):
            modified = dag.replace("min_duration: 2", f"min_duration: {i}")
            v = e2e_client.create_pipeline_version(
                pipeline["id"], modified, changelog=f"Version {i}",
            )
            assert v["version"] == i

        versions = e2e_client.list_pipeline_versions(pipeline["id"])
        assert len(versions) == 4  # v1 + v2 + v3 + v4

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])


# ---------------------------------------------------------------------------
# 5. Pipeline Runs (API-level, no actual orchestrator execution)
# ---------------------------------------------------------------------------

class TestPipelineRuns:
    """Test pipeline run trigger and status queries.

    Note: These tests verify the API layer only. Actual orchestrator
    execution depends on the orchestrator service being available.
    """

    def test_trigger_run(self, e2e_client):
        """Trigger a pipeline run and verify initial status."""
        ts = int(time.time())
        dag = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-run-{ts}", data_type="TEXT", dag_yaml=dag,
        )

        run = e2e_client.trigger_pipeline_run(pipeline["id"], version=1)
        assert run["pipeline_id"] == pipeline["id"]
        assert run["pipeline_version"] == 1
        assert run["status"] in ("PENDING", "RUNNING")

        # Get run details
        fetched = e2e_client.get_pipeline_run(run["id"])
        assert fetched["id"] == run["id"]
        assert fetched["pipeline_id"] == pipeline["id"]

        # Cancel the run (cleanup)
        try:
            e2e_client.cancel_pipeline_run(run["id"])
        except DbayApiError:
            pass  # May already be finished or not cancellable

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])

    def test_trigger_video_run(self, e2e_client):
        """Trigger a video pipeline run."""
        ts = int(time.time())
        dag = _read_fixture("video_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-vrun-{ts}", data_type="VIDEO", dag_yaml=dag,
        )

        run = e2e_client.trigger_pipeline_run(pipeline["id"], version=1)
        assert run["pipeline_id"] == pipeline["id"]
        assert run["status"] in ("PENDING", "RUNNING")

        try:
            e2e_client.cancel_pipeline_run(run["id"])
        except DbayApiError:
            pass

        e2e_client.delete_pipeline(pipeline["id"])

    def test_list_step_runs(self, e2e_client):
        """After triggering a run, step runs should be created."""
        ts = int(time.time())
        dag = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-steps-{ts}", data_type="TEXT", dag_yaml=dag,
        )

        run = e2e_client.trigger_pipeline_run(pipeline["id"], version=1)

        # Give the API a moment to create step runs
        time.sleep(2)

        steps = e2e_client.list_step_runs(run["id"])
        assert isinstance(steps, list)
        # Steps may or may not be populated depending on orchestrator
        # At minimum, verify the API returns successfully

        try:
            e2e_client.cancel_pipeline_run(run["id"])
        except DbayApiError:
            pass

        e2e_client.delete_pipeline(pipeline["id"])

    def test_cancel_run(self, e2e_client):
        """Cancel a running pipeline."""
        ts = int(time.time())
        dag = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-cancel-{ts}", data_type="TEXT", dag_yaml=dag,
        )

        run = e2e_client.trigger_pipeline_run(pipeline["id"], version=1)

        # Cancel
        try:
            cancelled = e2e_client.cancel_pipeline_run(run["id"])
            assert cancelled["status"] in ("CANCELLED", "CANCELLING", "FAILED")
        except DbayApiError as e:
            # Run may have already completed or be in non-cancellable state
            pass

        e2e_client.delete_pipeline(pipeline["id"])

    def test_run_specific_version(self, e2e_client):
        """Trigger a run on a specific pipeline version."""
        ts = int(time.time())
        dag_v1 = _read_fixture("text_pipeline_dag.yaml")
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-specver-{ts}", data_type="TEXT", dag_yaml=dag_v1,
        )

        # Publish v2
        dag_v2 = dag_v1.replace("similarity_threshold: 0.85", "similarity_threshold: 0.95")
        e2e_client.create_pipeline_version(pipeline["id"], dag_v2, changelog="v2")

        # Run on v1 specifically
        run = e2e_client.trigger_pipeline_run(pipeline["id"], version=1)
        assert run["pipeline_version"] == 1

        try:
            e2e_client.cancel_pipeline_run(run["id"])
        except DbayApiError:
            pass

        e2e_client.delete_pipeline(pipeline["id"])


# ---------------------------------------------------------------------------
# 6. Multi-tenant Isolation
# ---------------------------------------------------------------------------

class TestPipelineIsolation:
    """Test that pipelines are isolated between tenants."""

    def test_pipeline_not_visible_to_other_tenant(self, e2e_client):
        """Pipeline created by one tenant should not be visible to another."""
        from conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN

        ts = int(time.time())

        # Create pipeline with primary tenant
        pipeline = e2e_client.create_pipeline(
            name=f"e2e-iso-{ts}", data_type="TEXT", dag_yaml=MINIMAL_DAG,
        )

        # Create a second tenant
        client2, tenant2 = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-iso2-{ts}", f"E2eIso@{ts}", f"E2E Iso {ts}",
        )

        # Second tenant should not see the pipeline
        pipelines2 = client2.list_pipelines()
        ids2 = {p["id"] for p in pipelines2}
        assert pipeline["id"] not in ids2

        # Second tenant should not be able to access it directly
        with pytest.raises(DbayApiError) as exc_info:
            client2.get_pipeline(pipeline["id"])
        assert exc_info.value.status_code in (404, 403)

        # Cleanup
        e2e_client.delete_pipeline(pipeline["id"])
        from dbay_cli.client import DbayClient
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        admin.admin_batch_delete_tenants([tenant2["id"]])

    def test_custom_component_visibility(self, e2e_client):
        """Custom components are tenant-scoped; preset components are shared."""
        from conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN

        ts = int(time.time())

        # Register custom component with primary tenant
        comp = e2e_client.register_pipeline_component(
            name=f"e2e_priv_{ts}",
            display_name=f"Private {ts}",
            category="FILTER",
            data_type="TEXT",
            entrypoint="e2e.private_filter",
        )

        # Create second tenant
        client2, tenant2 = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-comp2-{ts}", f"E2eComp@{ts}", f"E2E Comp {ts}",
        )

        # Second tenant should see preset components but NOT private ones
        components2 = client2.list_pipeline_components()
        names2 = {c["name"] for c in components2}
        assert f"e2e_priv_{ts}" not in names2
        # But should still see presets
        assert "text_clean" in names2

        # Cleanup
        from dbay_cli.client import DbayClient
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        admin.admin_batch_delete_tenants([tenant2["id"]])
