import asyncio
import json

import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from lakeon_orchestrator.ray_client.client import (
    RayClient,
    _enforce_cci_min_cpu,
    _yaml_escape,
    _build_wrapper_script,
    RAY_JOB_GROUP,
    RAY_JOB_VERSION,
    RAY_JOB_PLURAL,
    STATUS_SUCCEEDED,
    STATUS_FAILED,
    STATUS_PENDING,
    STATUS_RUNNING,
)


@pytest.fixture
def ray_client():
    return RayClient(
        ray_image="test-image:latest",
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
    with patch("lakeon_orchestrator.ray_client.client.k8s_config") as mock_config, \
         patch("lakeon_orchestrator.ray_client.client.k8s_client") as mock_client_mod:

        # load_incluster_config raises -> falls back to load_kube_config
        mock_config.load_incluster_config.side_effect = Exception("not in cluster")
        mock_config.ConfigException = Exception
        mock_config.load_kube_config = AsyncMock()

        mock_custom_api = AsyncMock()
        mock_core_api = AsyncMock()
        mock_client_mod.CustomObjectsApi.return_value = mock_custom_api
        mock_client_mod.CoreV1Api.return_value = mock_core_api
        mock_client_mod.ApiClient.return_value = AsyncMock()

        yield {
            "config": mock_config,
            "client_mod": mock_client_mod,
            "custom_api": mock_custom_api,
            "core_api": mock_core_api,
        }


class TestRayClientInit:
    def test_default_values(self):
        c = RayClient()
        assert c._ray_image == "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data"
        assert c._namespace == "lakeon-pipeline"
        assert c._initialized is False

    def test_custom_values(self, ray_client):
        assert ray_client._ray_image == "test-image:latest"
        assert ray_client._namespace == "test-ns"
        assert ray_client._image_pull_secrets == ["my-secret"]
        assert ray_client._obs_bucket == "test-bucket"

    def test_is_connected_false_initially(self, ray_client):
        assert ray_client.is_connected is False


class TestConnect:
    @pytest.mark.asyncio
    async def test_connect_loads_kubeconfig(self, ray_client, mock_k8s_api):
        await ray_client.connect()
        assert ray_client.is_connected is True
        mock_k8s_api["config"].load_kube_config.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_connect_idempotent(self, ray_client, mock_k8s_api):
        await ray_client.connect()
        await ray_client.connect()
        # load_kube_config called only once
        mock_k8s_api["config"].load_kube_config.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_disconnect(self, ray_client, mock_k8s_api):
        await ray_client.connect()
        assert ray_client.is_connected is True
        await ray_client.disconnect()
        assert ray_client.is_connected is False


class TestSubmitPipelineStep:
    @pytest.mark.asyncio
    async def test_creates_rayjob_crd(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.create_namespaced_custom_object = AsyncMock(return_value={})

        job_name = await ray_client.submit_pipeline_step(
            run_id="run-001",
            step_id="normalize",
            component_entrypoint="mymod.normalize.run",
            params={"quality": "high"},
            input_data={"dataset": "ds-1"},
            tenant_id="tenant-abc",
        )

        assert job_name == "pl-run-001-normalize"
        custom_api.create_namespaced_custom_object.assert_awaited_once()

        call_kwargs = custom_api.create_namespaced_custom_object.call_args
        assert call_kwargs.kwargs["group"] == RAY_JOB_GROUP
        assert call_kwargs.kwargs["version"] == RAY_JOB_VERSION
        assert call_kwargs.kwargs["namespace"] == "datalake-tn-tenant-abc"
        assert call_kwargs.kwargs["plural"] == RAY_JOB_PLURAL

        body = call_kwargs.kwargs["body"]
        assert body["apiVersion"] == "ray.io/v1"
        assert body["kind"] == "RayJob"
        assert body["metadata"]["name"] == "pl-run-001-normalize"
        assert body["metadata"]["labels"]["lakeon.io/tenant-id"] == "tenant-abc"

        spec = body["spec"]
        assert spec["shutdownAfterJobFinishes"] is True
        assert spec["ttlSecondsAfterFinished"] == 300
        assert "rayClusterSpec" in spec
        assert "submitterPodTemplate" in spec

    @pytest.mark.asyncio
    async def test_namespace_override(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.create_namespaced_custom_object = AsyncMock(return_value={})

        await ray_client.submit_pipeline_step(
            run_id="run-002",
            step_id="step-a",
            component_entrypoint="mod.func",
            params={},
            input_data={},
            namespace_override="custom-ns",
        )

        call_kwargs = custom_api.create_namespaced_custom_object.call_args
        assert call_kwargs.kwargs["namespace"] == "custom-ns"

    @pytest.mark.asyncio
    async def test_job_name_truncation(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.create_namespaced_custom_object = AsyncMock(return_value={})

        long_run_id = "a" * 50
        long_step_id = "b" * 50
        job_name = await ray_client.submit_pipeline_step(
            run_id=long_run_id,
            step_id=long_step_id,
            component_entrypoint="mod.func",
            params={},
            input_data={},
        )
        assert len(job_name) <= 63


class TestGetJobStatus:
    @pytest.mark.asyncio
    async def test_returns_status(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.get_namespaced_custom_object = AsyncMock(return_value={
            "status": {"jobStatus": "RUNNING"}
        })

        status = await ray_client.get_job_status("my-job", namespace="ns-1")
        assert status == "RUNNING"

    @pytest.mark.asyncio
    async def test_returns_pending_when_no_status(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.get_namespaced_custom_object = AsyncMock(return_value={})

        status = await ray_client.get_job_status("my-job", namespace="ns-1")
        assert status == STATUS_PENDING


class TestWaitForCompletion:
    @pytest.mark.asyncio
    async def test_wait_succeeds(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        # First call: RUNNING, second call: SUCCEEDED
        custom_api.get_namespaced_custom_object = AsyncMock(side_effect=[
            {"status": {"jobStatus": "RUNNING"}},
            {"status": {"jobStatus": "SUCCEEDED", "message": "done"}},
        ])

        with patch("lakeon_orchestrator.ray_client.client.asyncio.sleep", new_callable=AsyncMock):
            result = await ray_client.wait_for_completion(
                "my-job", namespace="ns-1", poll_interval=1
            )

        assert result["status"] == STATUS_SUCCEEDED
        assert result["ray_job_name"] == "my-job"

    @pytest.mark.asyncio
    async def test_wait_timeout(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.get_namespaced_custom_object = AsyncMock(return_value={
            "status": {"jobStatus": "RUNNING"}
        })

        with patch("lakeon_orchestrator.ray_client.client.asyncio.sleep", new_callable=AsyncMock):
            with pytest.raises(TimeoutError, match="did not complete"):
                await ray_client.wait_for_completion(
                    "my-job", namespace="ns-1", timeout=3, poll_interval=1
                )

    @pytest.mark.asyncio
    async def test_wait_failed(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.get_namespaced_custom_object = AsyncMock(return_value={
            "status": {"jobStatus": "FAILED", "message": "OOM"}
        })

        with patch("lakeon_orchestrator.ray_client.client.asyncio.sleep", new_callable=AsyncMock):
            result = await ray_client.wait_for_completion("my-job", namespace="ns-1")

        assert result["status"] == STATUS_FAILED
        assert result["message"] == "OOM"


class TestDeleteJob:
    @pytest.mark.asyncio
    async def test_delete_success(self, ray_client, mock_k8s_api):
        custom_api = mock_k8s_api["custom_api"]
        custom_api.delete_namespaced_custom_object = AsyncMock(return_value={})

        await ray_client.delete_job("my-job", namespace="ns-1")
        custom_api.delete_namespaced_custom_object.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_delete_already_gone(self, ray_client, mock_k8s_api):
        from kubernetes_asyncio.client import ApiException
        custom_api = mock_k8s_api["custom_api"]
        custom_api.delete_namespaced_custom_object = AsyncMock(
            side_effect=ApiException(status=404, reason="Not Found")
        )

        # Should not raise
        await ray_client.delete_job("my-job", namespace="ns-1")


class TestCrdConstruction:
    def test_build_rayjob_crd_structure(self, ray_client):
        """Verify the CRD dict has the expected KubeRay structure."""
        # Force init without K8s
        crd = ray_client._build_rayjob_crd(
            name="test-job",
            namespace="ns-1",
            entrypoint="python /tmp/test.py",
            runtime_env_yaml="pip:\n  - pyobsfs\n",
            tenant_id="t1",
            run_id="r1",
            step_id="s1",
            res={},
        )

        assert crd["apiVersion"] == "ray.io/v1"
        assert crd["kind"] == "RayJob"
        assert crd["metadata"]["name"] == "test-job"

        spec = crd["spec"]
        assert spec["shutdownAfterJobFinishes"] is True
        assert spec["ttlSecondsAfterFinished"] == 300
        assert "entrypoint" in spec
        assert "runtimeEnvYAML" in spec

        cluster = spec["rayClusterSpec"]
        head = cluster["headGroupSpec"]
        assert head["headService"]["spec"]["clusterIP"] == "None"
        assert head["template"]["spec"]["serviceAccountName"] == "ray-head"

        workers = cluster["workerGroupSpecs"]
        assert len(workers) == 1
        assert workers[0]["replicas"] == 2
        assert workers[0]["groupName"] == "worker-group"

        submitter = spec["submitterPodTemplate"]["spec"]
        assert submitter["restartPolicy"] == "Never"

    def test_build_rayjob_crd_custom_resources(self, ray_client):
        crd = ray_client._build_rayjob_crd(
            name="test-job",
            namespace="ns-1",
            entrypoint="echo hi",
            runtime_env_yaml="",
            tenant_id="t1",
            run_id="r1",
            step_id="s1",
            res={"head_cpu": "4", "head_memory": "8Gi", "worker_count": 4},
        )

        head_container = crd["spec"]["rayClusterSpec"]["headGroupSpec"]["template"]["spec"]["containers"][0]
        assert head_container["resources"]["requests"]["cpu"] == "4"
        assert head_container["resources"]["requests"]["memory"] == "8Gi"

        worker_specs = crd["spec"]["rayClusterSpec"]["workerGroupSpecs"][0]
        assert worker_specs["replicas"] == 4

    def test_security_contexts(self, ray_client):
        crd = ray_client._build_rayjob_crd(
            name="test-job", namespace="ns-1", entrypoint="echo hi",
            runtime_env_yaml="", tenant_id="t1", run_id="r1", step_id="s1", res={},
        )

        head_pod = crd["spec"]["rayClusterSpec"]["headGroupSpec"]["template"]["spec"]
        assert head_pod["securityContext"]["runAsNonRoot"] is True
        assert head_pod["securityContext"]["runAsUser"] == 1000

        head_container = head_pod["containers"][0]
        assert head_container["securityContext"]["allowPrivilegeEscalation"] is False
        assert head_container["securityContext"]["capabilities"]["drop"] == ["ALL"]

    def test_node_selector_and_tolerations(self, ray_client):
        crd = ray_client._build_rayjob_crd(
            name="test-job", namespace="ns-1", entrypoint="echo hi",
            runtime_env_yaml="", tenant_id="t1", run_id="r1", step_id="s1", res={},
        )

        head_pod = crd["spec"]["rayClusterSpec"]["headGroupSpec"]["template"]["spec"]
        assert head_pod["nodeSelector"] == {"type": "virtual-kubelet"}
        assert any(
            t["key"] == "virtual-kubelet.io/provider"
            for t in head_pod["tolerations"]
        )


class TestRuntimeEnvYaml:
    def test_basic_runtime_env(self, ray_client):
        yaml = ray_client._build_runtime_env_yaml()
        assert "pip:" in yaml
        assert "  - pyobsfs" in yaml
        assert "env_vars:" in yaml
        assert "OBS_ACCESS_KEY_ID" in yaml

    def test_extra_pip_packages(self, ray_client):
        yaml = ray_client._build_runtime_env_yaml(pip_packages=["pandas", "numpy"])
        assert "  - pandas" in yaml
        assert "  - numpy" in yaml

    def test_extra_env_vars(self, ray_client):
        yaml = ray_client._build_runtime_env_yaml(env_vars={"MY_VAR": "hello"})
        assert 'MY_VAR: "hello"' in yaml


class TestHelperFunctions:
    def test_enforce_cci_min_cpu(self):
        assert _enforce_cci_min_cpu("100m") == "250m"
        assert _enforce_cci_min_cpu("250m") == "250m"
        assert _enforce_cci_min_cpu("500m") == "500m"
        assert _enforce_cci_min_cpu("2") == "2"
        assert _enforce_cci_min_cpu("") == "250m"
        assert _enforce_cci_min_cpu(None) == "250m"

    def test_yaml_escape(self):
        assert _yaml_escape('hello "world"') == 'hello \\"world\\"'
        assert _yaml_escape("back\\slash") == "back\\\\slash"
        assert _yaml_escape("") == ""
        assert _yaml_escape(None) == ""

    def test_build_wrapper_script(self):
        script = _build_wrapper_script("dGVzdA==", "out.json", "out.log")
        assert "import base64" in script
        assert "importlib.import_module" in script
        assert "out.json" in script

    def test_make_job_name(self, ray_client):
        name = ray_client._make_job_name("run_123", "step_a")
        assert name == "pl-run-123-step-a"
        assert "_" not in name
