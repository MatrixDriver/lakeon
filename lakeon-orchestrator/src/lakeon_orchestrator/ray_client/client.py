from __future__ import annotations

import asyncio
import base64
import json
import logging
from typing import Any, Optional

from kubernetes_asyncio import client as k8s_client, config as k8s_config
from kubernetes_asyncio.client import ApiException

logger = logging.getLogger(__name__)

# RayJob status constants (from KubeRay operator)
STATUS_PENDING = "PENDING"
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"
STATUS_STOPPED = "STOPPED"

TERMINAL_STATUSES = {STATUS_SUCCEEDED, STATUS_FAILED, STATUS_STOPPED}

# CRD coordinates
RAY_JOB_GROUP = "ray.io"
RAY_JOB_VERSION = "v1"
RAY_JOB_PLURAL = "rayjobs"


class RayClient:
    """Submit pipeline steps as RayJob CRDs via Kubernetes API.

    Instead of connecting directly to a Ray cluster, this client:
    1. Builds a RayJob CRD spec (entrypoint, resources, env vars)
    2. Creates the RayJob via K8s API — KubeRay Operator provisions a temporary cluster
    3. Polls RayJob status until completion
    4. Cleans up finished RayJob resources

    The Orchestrator calls submit_pipeline_step() for each DAG node.
    Inter-step data is passed via OBS (S3-compatible object storage).
    """

    def __init__(
        self,
        ray_image: str = "swr.cn-north-4.myhuaweicloud.com/flex/ray:2.44-py311-data",
        k8s_namespace: str = "lakeon-pipeline",
        image_pull_secrets: Optional[list[str]] = None,
        obs_endpoint: str = "",
        obs_access_key: str = "",
        obs_secret_key: str = "",
        obs_bucket: str = "lakeon-data",
        obs_region: str = "cn-north-4",
        vk_node_selector_key: str = "type",
        vk_node_selector_value: str = "virtual-kubelet",
    ):
        self._ray_image = ray_image
        self._namespace = k8s_namespace
        self._image_pull_secrets = image_pull_secrets or []
        self._obs_endpoint = obs_endpoint
        self._obs_access_key = obs_access_key
        self._obs_secret_key = obs_secret_key
        self._obs_bucket = obs_bucket
        self._obs_region = obs_region
        self._vk_node_selector_key = vk_node_selector_key
        self._vk_node_selector_value = vk_node_selector_value
        self._api: Optional[k8s_client.CustomObjectsApi] = None
        self._core_api: Optional[k8s_client.CoreV1Api] = None
        self._initialized = False

    async def _ensure_k8s(self) -> None:
        """Load K8s config (in-cluster or kubeconfig) and create API clients."""
        if self._initialized:
            return
        try:
            k8s_config.load_incluster_config()
            logger.info("Loaded in-cluster Kubernetes config")
        except k8s_config.ConfigException:
            await k8s_config.load_kube_config()
            logger.info("Loaded kubeconfig from default location")
        self._api = k8s_client.CustomObjectsApi()
        self._core_api = k8s_client.CoreV1Api()
        self._initialized = True

    @property
    def is_connected(self) -> bool:
        return self._initialized

    async def connect(self) -> None:
        """Initialize K8s API clients. Idempotent."""
        await self._ensure_k8s()

    async def disconnect(self) -> None:
        """Clean up API client. Safe to call multiple times."""
        if self._initialized:
            await k8s_client.ApiClient().close()
            self._api = None
            self._core_api = None
            self._initialized = False
            logger.info("Disconnected Kubernetes API client")

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def submit_pipeline_step(
        self,
        run_id: str,
        step_id: str,
        component_entrypoint: str,
        params: dict,
        input_data: dict,
        resources: Optional[dict] = None,
        tenant_id: str = "default",
        env_vars: Optional[dict[str, str]] = None,
        pip_packages: Optional[list[str]] = None,
        namespace_override: Optional[str] = None,
    ) -> str:
        """Create a RayJob CRD to execute a pipeline step.

        Args:
            run_id: Pipeline run identifier.
            step_id: Step identifier within the DAG.
            component_entrypoint: Python module path (e.g. "lakeon_orchestrator.components.normalize.run").
            params: Step parameters dict (serialized to JSON for the entrypoint).
            input_data: Input data references (serialized to JSON for the entrypoint).
            resources: Optional resource overrides {"head_cpu", "head_memory", "worker_cpu", "worker_memory", "worker_count"}.
            tenant_id: Tenant ID, used for namespace and OBS paths.
            env_vars: Additional environment variables for the Ray runtime.
            pip_packages: Additional pip packages to install in Ray runtime_env.
            namespace_override: Override the target namespace (default: datalake-tn-{tenant_id}).

        Returns:
            The RayJob name (used to query status / wait / delete).
        """
        await self._ensure_k8s()

        ns = namespace_override or f"datalake-{tenant_id.replace('_', '-')}"
        ray_job_name = self._make_job_name(run_id, step_id)
        res = resources or {}

        # Serialize step context to JSON -> base64 for the entrypoint wrapper
        step_context = {
            "run_id": run_id,
            "step_id": step_id,
            "entrypoint": component_entrypoint,
            "params": params,
            "input_data": input_data,
        }
        context_b64 = base64.b64encode(json.dumps(step_context).encode()).decode()

        # The entrypoint invokes the component function with deserialized context.
        # Results are written to OBS at a well-known path.
        output_obs_key = f"pipeline-runs/{run_id}/{step_id}/output.json"
        log_obs_key = f"pipeline-runs/{run_id}/{step_id}/output.log"

        wrapper_script = _build_wrapper_script(context_b64, output_obs_key, log_obs_key)
        wrapper_b64 = base64.b64encode(wrapper_script.encode()).decode()

        entrypoint = (
            f"python -c \""
            f"import base64,os; "
            f"os.makedirs('/tmp',exist_ok=True); "
            f"open('/tmp/_step.py','w').write(base64.b64decode('{wrapper_b64}').decode()); "
            f"\" && python /tmp/_step.py"
        )

        # Build runtime_env YAML
        runtime_env_yaml = self._build_runtime_env_yaml(
            env_vars=env_vars, pip_packages=pip_packages,
            output_obs_key=output_obs_key,
        )

        # Build the RayJob CRD body
        body = self._build_rayjob_crd(
            name=ray_job_name,
            namespace=ns,
            entrypoint=entrypoint,
            runtime_env_yaml=runtime_env_yaml,
            tenant_id=tenant_id,
            run_id=run_id,
            step_id=step_id,
            res=res,
        )

        await self._api.create_namespaced_custom_object(
            group=RAY_JOB_GROUP,
            version=RAY_JOB_VERSION,
            namespace=ns,
            plural=RAY_JOB_PLURAL,
            body=body,
        )
        logger.info("Created RayJob %s/%s", ns, ray_job_name)
        return ray_job_name

    async def get_job_status(self, ray_job_name: str, namespace: Optional[str] = None) -> str:
        """Query a RayJob's current status.

        Returns one of: PENDING, RUNNING, SUCCEEDED, FAILED, STOPPED.
        """
        await self._ensure_k8s()
        ns = namespace or self._namespace
        obj = await self._api.get_namespaced_custom_object(
            group=RAY_JOB_GROUP,
            version=RAY_JOB_VERSION,
            namespace=ns,
            plural=RAY_JOB_PLURAL,
            name=ray_job_name,
        )
        status = (obj.get("status") or {}).get("jobStatus", STATUS_PENDING)
        return status

    async def wait_for_completion(
        self,
        ray_job_name: str,
        namespace: Optional[str] = None,
        timeout: int = 3600,
        poll_interval: int = 5,
    ) -> dict:
        """Poll until a RayJob reaches a terminal status.

        Returns:
            {"status": "SUCCEEDED"|"FAILED"|"STOPPED", "ray_job_name": ..., "message": ...}

        Raises:
            TimeoutError if the job doesn't finish within *timeout* seconds.
        """
        await self._ensure_k8s()
        ns = namespace or self._namespace
        elapsed = 0

        while elapsed < timeout:
            obj = await self._api.get_namespaced_custom_object(
                group=RAY_JOB_GROUP,
                version=RAY_JOB_VERSION,
                namespace=ns,
                plural=RAY_JOB_PLURAL,
                name=ray_job_name,
            )
            status_block = obj.get("status") or {}
            job_status = status_block.get("jobStatus", STATUS_PENDING)
            message = status_block.get("message", "")

            if job_status in TERMINAL_STATUSES:
                logger.info("RayJob %s/%s finished: %s", ns, ray_job_name, job_status)
                return {
                    "status": job_status,
                    "ray_job_name": ray_job_name,
                    "message": message,
                }

            await asyncio.sleep(poll_interval)
            elapsed += poll_interval

        raise TimeoutError(
            f"RayJob {ns}/{ray_job_name} did not complete within {timeout}s"
        )

    async def delete_job(self, ray_job_name: str, namespace: Optional[str] = None) -> None:
        """Delete a completed RayJob CRD."""
        await self._ensure_k8s()
        ns = namespace or self._namespace
        try:
            await self._api.delete_namespaced_custom_object(
                group=RAY_JOB_GROUP,
                version=RAY_JOB_VERSION,
                namespace=ns,
                plural=RAY_JOB_PLURAL,
                name=ray_job_name,
            )
            logger.info("Deleted RayJob %s/%s", ns, ray_job_name)
        except ApiException as e:
            if e.status == 404:
                logger.warning("RayJob %s/%s already deleted", ns, ray_job_name)
            else:
                raise

    # ------------------------------------------------------------------
    # CRD construction helpers
    # ------------------------------------------------------------------

    def _make_job_name(self, run_id: str, step_id: str) -> str:
        """Generate a K8s-safe RayJob name (max 63 chars)."""
        raw = f"pl-{run_id}-{step_id}".replace("_", "-").lower()
        return raw[:63]

    def _build_rayjob_crd(
        self,
        name: str,
        namespace: str,
        entrypoint: str,
        runtime_env_yaml: str,
        tenant_id: str,
        run_id: str,
        step_id: str,
        res: dict,
    ) -> dict:
        """Build the full RayJob CRD dict following ray.io/v1 schema."""

        head_cpu = _enforce_cci_min_cpu(res.get("head_cpu", "2"))
        head_memory = res.get("head_memory", "4Gi")
        worker_cpu = _enforce_cci_min_cpu(res.get("worker_cpu", "2"))
        worker_memory = res.get("worker_memory", "4Gi")
        worker_count = int(res.get("worker_count", 2))

        node_selector = {self._vk_node_selector_key: self._vk_node_selector_value}
        tolerations = [
            {"key": "virtual-kubelet.io/provider", "operator": "Exists", "effect": "NoSchedule"}
        ]
        image_pull_secrets = [{"name": s} for s in self._image_pull_secrets if s]

        pod_security_context = {
            "runAsNonRoot": True,
            "runAsUser": 1000,
            "runAsGroup": 1000,
        }
        container_security_context = {
            "allowPrivilegeEscalation": False,
            "readOnlyRootFilesystem": False,  # Ray needs writable /tmp
            "capabilities": {"drop": ["ALL"]},
        }

        # OBS env vars for the head container (entrypoint log upload)
        head_env = [
            {"name": "OBS_ACCESS_KEY_ID", "value": self._obs_access_key},
            {"name": "OBS_SECRET_ACCESS_KEY", "value": self._obs_secret_key},
            {"name": "OBS_ENDPOINT", "value": self._obs_endpoint},
            {"name": "OBS_BUCKET", "value": self._obs_bucket},
            {"name": "OBS_REGION", "value": self._obs_region},
        ]

        head_container = {
            "name": "ray-head",
            "image": self._ray_image,
            "resources": {
                "requests": {"cpu": head_cpu, "memory": head_memory},
                "limits": {"cpu": head_cpu, "memory": head_memory},
            },
            "env": head_env,
            "securityContext": container_security_context,
        }

        worker_container = {
            "name": "ray-worker",
            "image": self._ray_image,
            "resources": {
                "requests": {"cpu": worker_cpu, "memory": worker_memory},
                "limits": {"cpu": worker_cpu, "memory": worker_memory},
            },
            "securityContext": container_security_context,
        }

        head_pod_spec = {
            "serviceAccountName": "ray-head",
            "securityContext": pod_security_context,
            "nodeSelector": node_selector,
            "tolerations": tolerations,
            "imagePullSecrets": image_pull_secrets,
            "containers": [head_container],
        }

        worker_pod_spec = {
            "automountServiceAccountToken": False,
            "securityContext": pod_security_context,
            "nodeSelector": node_selector,
            "tolerations": tolerations,
            "imagePullSecrets": image_pull_secrets,
            "containers": [worker_container],
        }

        # Submitter pod (lightweight, just submits the entrypoint to head)
        submitter_container = {
            "name": "ray-job-submitter",
            "image": self._ray_image,
            "resources": {
                "requests": {"cpu": "250m", "memory": "512Mi"},
                "limits": {"cpu": "250m", "memory": "512Mi"},
            },
            "securityContext": container_security_context,
        }
        submitter_pod_spec = {
            "automountServiceAccountToken": False,
            "securityContext": pod_security_context,
            "restartPolicy": "Never",
            "imagePullSecrets": image_pull_secrets,
            "nodeSelector": node_selector,
            "tolerations": tolerations,
            "containers": [submitter_container],
        }

        spec = {
            "entrypoint": entrypoint,
            "shutdownAfterJobFinishes": True,
            "ttlSecondsAfterFinished": 300,
            "runtimeEnvYAML": runtime_env_yaml,
            "submitterPodTemplate": {"spec": submitter_pod_spec},
            "rayClusterSpec": {
                "headGroupSpec": {
                    "rayStartParams": {"dashboard-host": "0.0.0.0"},
                    "headService": {"spec": {"clusterIP": "None"}},
                    "template": {"spec": head_pod_spec},
                },
                "workerGroupSpecs": [
                    {
                        "replicas": worker_count,
                        "minReplicas": worker_count,
                        "maxReplicas": worker_count,
                        "groupName": "worker-group",
                        "rayStartParams": {},
                        "template": {"spec": worker_pod_spec},
                    }
                ],
            },
        }

        return {
            "apiVersion": "ray.io/v1",
            "kind": "RayJob",
            "metadata": {
                "name": name,
                "namespace": namespace,
                "labels": {
                    "lakeon.io/tenant-id": tenant_id,
                    "lakeon.io/run-id": run_id,
                    "lakeon.io/step-id": step_id,
                },
            },
            "spec": spec,
        }

    def _build_runtime_env_yaml(
        self,
        env_vars: Optional[dict[str, str]] = None,
        pip_packages: Optional[list[str]] = None,
        output_obs_key: str = "",
    ) -> str:
        """Build a YAML string for Ray runtime_env (pip + env_vars)."""
        lines: list[str] = []

        # pip packages
        lines.append("pip:")
        lines.append("  - pyobsfs")
        for pkg in (pip_packages or []):
            if pkg.strip():
                lines.append(f"  - {pkg.strip()}")

        # env_vars
        lines.append("env_vars:")
        lines.append(f'  OBS_ACCESS_KEY_ID: "{_yaml_escape(self._obs_access_key)}"')
        lines.append(f'  OBS_SECRET_ACCESS_KEY: "{_yaml_escape(self._obs_secret_key)}"')
        lines.append(f'  OBS_ENDPOINT: "{_yaml_escape(self._obs_endpoint)}"')
        lines.append(f'  OBS_BUCKET: "{_yaml_escape(self._obs_bucket)}"')
        lines.append(f'  OBS_REGION: "{_yaml_escape(self._obs_region)}"')
        if output_obs_key:
            lines.append(f'  OUTPUT_OBS_KEY: "{_yaml_escape(output_obs_key)}"')
        for k, v in (env_vars or {}).items():
            lines.append(f'  {k}: "{_yaml_escape(v)}"')

        return "\n".join(lines) + "\n"


# ------------------------------------------------------------------
# Module-level helper functions
# ------------------------------------------------------------------

def _build_wrapper_script(context_b64: str, output_obs_key: str, log_obs_key: str) -> str:
    """Build the Python wrapper that runs on the Ray head node.

    It deserializes the step context, dynamically imports and calls the component
    entrypoint function, and uploads results + logs to OBS.
    """
    return f"""\
import base64, importlib, json, os, sys, traceback

# Decode step context
_ctx = json.loads(base64.b64decode("{context_b64}").decode())
entrypoint = _ctx["entrypoint"]
params = _ctx["params"]
input_data = _ctx["input_data"]
run_id = _ctx["run_id"]
step_id = _ctx["step_id"]

output = None
error = None
try:
    # Import the component function: "pkg.module.func"
    mod_path, func_name = entrypoint.rsplit(".", 1)
    mod = importlib.import_module(mod_path)
    func = getattr(mod, func_name)
    output = func(params=params, input_data=input_data, run_id=run_id, step_id=step_id)
except Exception:
    error = traceback.format_exc()
    print(error, file=sys.stderr)

# Write result to local file
result = {{"status": "FAILED" if error else "SUCCEEDED", "output": output, "error": error}}
open("/tmp/_result.json", "w").write(json.dumps(result))

# Upload result + log to OBS via boto3/S3
try:
    import boto3
    from botocore.config import Config
    s3 = boto3.client(
        "s3",
        endpoint_url=os.environ.get("OBS_ENDPOINT", ""),
        aws_access_key_id=os.environ.get("OBS_ACCESS_KEY_ID", ""),
        aws_secret_access_key=os.environ.get("OBS_SECRET_ACCESS_KEY", ""),
        aws_session_token=os.environ.get("OBS_SECURITY_TOKEN"),
        region_name=os.environ.get("OBS_REGION", "cn-north-4"),
        config=Config(signature_version="s3", s3={{"addressing_style": "virtual"}}),
    )
    bucket = os.environ.get("OBS_BUCKET", "")
    s3.upload_file("/tmp/_result.json", bucket, "{output_obs_key}")
except Exception:
    traceback.print_exc()

if error:
    sys.exit(1)
"""


def _yaml_escape(value: str) -> str:
    """Escape double-quotes and backslashes for YAML string values."""
    if not value:
        return ""
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _enforce_cci_min_cpu(cpu: str) -> str:
    """Enforce CCI minimum CPU of 250m."""
    if not cpu:
        return "250m"
    if cpu.endswith("m"):
        try:
            millis = int(cpu[:-1])
            if millis < 250:
                return "250m"
        except ValueError:
            pass
    return cpu
