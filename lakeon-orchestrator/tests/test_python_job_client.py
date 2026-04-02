import asyncio
import json

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from lakeon_orchestrator.ray_client.python_job_client import (
    PythonJobClient,
    _enforce_cci_min_cpu,
    _build_wrapper_script,
    STATUS_SUCCEEDED,
    STATUS_FAILED,
    STATUS_PENDING,
    STATUS_RUNNING,
)


@pytest.fixture
def python_client():
    return PythonJobClient(
        python_image="test-python:3.11",
        k8s_namespace="test-ns",
        image_pull_secrets=["my-secret"],
        obs_endpoint="https://obs.test.com",
        obs_access_key="ak",
        obs_secret_key="sk",
        obs_bucket="test-bucket",
    )


@pytest.fixture
def mock_k8s_api():
    """Patch kubernetes_asyncio config and return mock API instances."""
    with patch("lakeon_orchestrator.ray_client.python_job_client.k8s_config") as mock_config, \
         patch("lakeon_orchestrator.ray_client.python_job_client.k8s_client") as mock_client_mod:

        mock_config.load_incluster_config.side_effect = Exception("not in cluster")
        mock_config.ConfigException = Exception
        mock_config.load_kube_config = AsyncMock()

        mock_batch_api = AsyncMock()
        mock_core_api = AsyncMock()
        mock_client_mod.BatchV1Api.return_value = mock_batch_api
        mock_client_mod.CoreV1Api.return_value = mock_core_api
        mock_client_mod.ApiClient.return_value = AsyncMock()
        mock_client_mod.V1DeleteOptions.return_value = {"propagationPolicy": "Background"}

        yield {
            "config": mock_config,
            "client_mod": mock_client_mod,
            "batch_api": mock_batch_api,
            "core_api": mock_core_api,
        }


class TestPythonJobClientInit:
    def test_default_values(self):
        c = PythonJobClient()
        assert c._python_image == "swr.cn-north-4.myhuaweicloud.com/flex/python:3.11-slim"
        assert c._namespace == "lakeon-pipeline"
        assert c._initialized is False

    def test_custom_values(self, python_client):
        assert python_client._python_image == "test-python:3.11"
        assert python_client._namespace == "test-ns"
        assert python_client._image_pull_secrets == ["my-secret"]
        assert python_client._obs_bucket == "test-bucket"

    def test_is_connected_false_initially(self, python_client):
        assert python_client.is_connected is False


class TestConnect:
    @pytest.mark.asyncio
    async def test_connect_loads_kubeconfig(self, python_client, mock_k8s_api):
        await python_client.connect()
        assert python_client.is_connected is True
        mock_k8s_api["config"].load_kube_config.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_connect_idempotent(self, python_client, mock_k8s_api):
        await python_client.connect()
        await python_client.connect()
        mock_k8s_api["config"].load_kube_config.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_disconnect(self, python_client, mock_k8s_api):
        await python_client.connect()
        assert python_client.is_connected is True
        await python_client.disconnect()
        assert python_client.is_connected is False


class TestSubmitPipelineStep:
    @pytest.mark.asyncio
    async def test_creates_k8s_job(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        batch_api.create_namespaced_job = AsyncMock(return_value={})

        job_name = await python_client.submit_pipeline_step(
            run_id="run-001",
            step_id="normalize",
            component_entrypoint="mymod.normalize.run",
            params={"quality": "high"},
            input_data={"dataset": "ds-1"},
            tenant_id="tenant-abc",
        )

        assert job_name == "pl-py-run-001-normalize"
        batch_api.create_namespaced_job.assert_awaited_once()

        call_kwargs = batch_api.create_namespaced_job.call_args
        assert call_kwargs.kwargs["namespace"] == "datalake-tn-tenant-abc"

        body = call_kwargs.kwargs["body"]
        assert body["apiVersion"] == "batch/v1"
        assert body["kind"] == "Job"
        assert body["metadata"]["name"] == "pl-py-run-001-normalize"
        assert body["metadata"]["labels"]["lakeon.io/engine"] == "python"
        assert body["metadata"]["labels"]["lakeon.io/tenant-id"] == "tenant-abc"

        spec = body["spec"]
        assert spec["backoffLimit"] == 0
        assert spec["ttlSecondsAfterFinished"] == 300
        assert spec["template"]["spec"]["restartPolicy"] == "Never"

        container = spec["template"]["spec"]["containers"][0]
        assert container["name"] == "python-job"
        assert container["image"] == "test-python:3.11"

    @pytest.mark.asyncio
    async def test_namespace_override(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        batch_api.create_namespaced_job = AsyncMock(return_value={})

        await python_client.submit_pipeline_step(
            run_id="run-002",
            step_id="step-a",
            component_entrypoint="mod.func",
            params={},
            input_data={},
            namespace_override="custom-ns",
        )

        call_kwargs = batch_api.create_namespaced_job.call_args
        assert call_kwargs.kwargs["namespace"] == "custom-ns"

    @pytest.mark.asyncio
    async def test_job_name_truncation(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        batch_api.create_namespaced_job = AsyncMock(return_value={})

        long_run_id = "a" * 50
        long_step_id = "b" * 50
        job_name = await python_client.submit_pipeline_step(
            run_id=long_run_id,
            step_id=long_step_id,
            component_entrypoint="mod.func",
            params={},
            input_data={},
        )
        assert len(job_name) <= 63


class TestGetJobStatus:
    @pytest.mark.asyncio
    async def test_returns_running(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        job_mock = MagicMock()
        job_mock.status.succeeded = None
        job_mock.status.failed = None
        job_mock.status.active = 1
        batch_api.read_namespaced_job_status = AsyncMock(return_value=job_mock)

        status = await python_client.get_job_status("my-job", namespace="ns-1")
        assert status == STATUS_RUNNING

    @pytest.mark.asyncio
    async def test_returns_succeeded(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        job_mock = MagicMock()
        job_mock.status.succeeded = 1
        job_mock.status.failed = None
        job_mock.status.active = None
        batch_api.read_namespaced_job_status = AsyncMock(return_value=job_mock)

        status = await python_client.get_job_status("my-job", namespace="ns-1")
        assert status == STATUS_SUCCEEDED

    @pytest.mark.asyncio
    async def test_returns_failed(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        job_mock = MagicMock()
        job_mock.status.succeeded = None
        job_mock.status.failed = 1
        job_mock.status.active = None
        batch_api.read_namespaced_job_status = AsyncMock(return_value=job_mock)

        status = await python_client.get_job_status("my-job", namespace="ns-1")
        assert status == STATUS_FAILED

    @pytest.mark.asyncio
    async def test_returns_pending_no_status(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        job_mock = MagicMock()
        job_mock.status = None
        batch_api.read_namespaced_job_status = AsyncMock(return_value=job_mock)

        status = await python_client.get_job_status("my-job", namespace="ns-1")
        assert status == STATUS_PENDING


class TestWaitForCompletion:
    @pytest.mark.asyncio
    async def test_wait_succeeds(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        running_job = MagicMock()
        running_job.status.succeeded = None
        running_job.status.failed = None
        running_job.status.active = 1
        running_job.status.conditions = None

        succeeded_job = MagicMock()
        succeeded_job.status.succeeded = 1
        succeeded_job.status.failed = None
        succeeded_job.status.active = None
        succeeded_job.status.conditions = [MagicMock(message="completed")]

        batch_api.read_namespaced_job_status = AsyncMock(
            side_effect=[running_job, succeeded_job]
        )

        with patch("lakeon_orchestrator.ray_client.python_job_client.asyncio.sleep", new_callable=AsyncMock):
            result = await python_client.wait_for_completion(
                "my-job", namespace="ns-1", poll_interval=1
            )

        assert result["status"] == STATUS_SUCCEEDED
        assert result["job_name"] == "my-job"

    @pytest.mark.asyncio
    async def test_wait_timeout(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        running_job = MagicMock()
        running_job.status.succeeded = None
        running_job.status.failed = None
        running_job.status.active = 1

        batch_api.read_namespaced_job_status = AsyncMock(return_value=running_job)

        with patch("lakeon_orchestrator.ray_client.python_job_client.asyncio.sleep", new_callable=AsyncMock):
            with pytest.raises(TimeoutError, match="did not complete"):
                await python_client.wait_for_completion(
                    "my-job", namespace="ns-1", timeout=3, poll_interval=1
                )


class TestDeleteJob:
    @pytest.mark.asyncio
    async def test_delete_success(self, python_client, mock_k8s_api):
        batch_api = mock_k8s_api["batch_api"]
        batch_api.delete_namespaced_job = AsyncMock(return_value={})

        await python_client.delete_job("my-job", namespace="ns-1")
        batch_api.delete_namespaced_job.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_delete_already_gone(self, python_client, mock_k8s_api):
        from kubernetes_asyncio.client import ApiException
        batch_api = mock_k8s_api["batch_api"]
        batch_api.delete_namespaced_job = AsyncMock(
            side_effect=ApiException(status=404, reason="Not Found")
        )

        # Should not raise
        await python_client.delete_job("my-job", namespace="ns-1")


class TestJobConstruction:
    def test_build_job_structure(self, python_client):
        job = python_client._build_job(
            name="test-job",
            namespace="ns-1",
            command=["/bin/sh", "-c", "echo hi"],
            tenant_id="t1",
            run_id="r1",
            step_id="s1",
            res={},
        )

        assert job["apiVersion"] == "batch/v1"
        assert job["kind"] == "Job"
        assert job["metadata"]["name"] == "test-job"
        assert job["metadata"]["labels"]["lakeon.io/engine"] == "python"

        spec = job["spec"]
        assert spec["backoffLimit"] == 0
        assert spec["ttlSecondsAfterFinished"] == 300

        pod_spec = spec["template"]["spec"]
        assert pod_spec["restartPolicy"] == "Never"
        assert pod_spec["securityContext"]["runAsNonRoot"] is True
        assert pod_spec["securityContext"]["runAsUser"] == 1000

        container = pod_spec["containers"][0]
        assert container["name"] == "python-job"
        assert container["securityContext"]["allowPrivilegeEscalation"] is False
        assert container["securityContext"]["capabilities"]["drop"] == ["ALL"]

    def test_build_job_custom_resources(self, python_client):
        job = python_client._build_job(
            name="test-job",
            namespace="ns-1",
            command=["echo", "hi"],
            tenant_id="t1",
            run_id="r1",
            step_id="s1",
            res={"cpu": "4", "memory": "8Gi"},
        )

        container = job["spec"]["template"]["spec"]["containers"][0]
        assert container["resources"]["requests"]["cpu"] == "4"
        assert container["resources"]["requests"]["memory"] == "8Gi"

    def test_node_selector_and_tolerations(self, python_client):
        job = python_client._build_job(
            name="test-job", namespace="ns-1", command=["echo"],
            tenant_id="t1", run_id="r1", step_id="s1", res={},
        )

        pod_spec = job["spec"]["template"]["spec"]
        assert pod_spec["nodeSelector"] == {"type": "virtual-kubelet"}
        assert any(
            t["key"] == "virtual-kubelet.io/provider"
            for t in pod_spec["tolerations"]
        )

    def test_env_vars_include_obs(self, python_client):
        job = python_client._build_job(
            name="test-job", namespace="ns-1", command=["echo"],
            tenant_id="t1", run_id="r1", step_id="s1", res={},
            env_vars={"MY_CUSTOM": "val"},
        )

        container = job["spec"]["template"]["spec"]["containers"][0]
        env_names = [e["name"] for e in container["env"]]
        assert "OBS_ACCESS_KEY_ID" in env_names
        assert "OBS_BUCKET" in env_names
        assert "MY_CUSTOM" in env_names


class TestHelperFunctions:
    def test_enforce_cci_min_cpu(self):
        assert _enforce_cci_min_cpu("100m") == "250m"
        assert _enforce_cci_min_cpu("250m") == "250m"
        assert _enforce_cci_min_cpu("500m") == "500m"
        assert _enforce_cci_min_cpu("2") == "2"
        assert _enforce_cci_min_cpu("") == "250m"
        assert _enforce_cci_min_cpu(None) == "250m"

    def test_build_wrapper_script(self):
        script = _build_wrapper_script("dGVzdA==", "out.json", "out.log")
        assert "import base64" in script
        assert "importlib.import_module" in script
        assert "out.json" in script

    def test_make_job_name(self, python_client):
        name = python_client._make_job_name("run_123", "step_a")
        assert name == "pl-py-run-123-step-a"
        assert "_" not in name

    def test_extract_status_succeeded(self):
        job = MagicMock()
        job.status.succeeded = 1
        job.status.failed = None
        job.status.active = None
        assert PythonJobClient._extract_status(job) == STATUS_SUCCEEDED

    def test_extract_status_no_status(self):
        job = MagicMock()
        job.status = None
        assert PythonJobClient._extract_status(job) == STATUS_PENDING
