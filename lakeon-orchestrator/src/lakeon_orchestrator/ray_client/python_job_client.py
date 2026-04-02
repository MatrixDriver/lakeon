from __future__ import annotations

import asyncio
import base64
import json
import logging
from typing import Any, Optional

from kubernetes_asyncio import client as k8s_client, config as k8s_config
from kubernetes_asyncio.client import ApiException

logger = logging.getLogger(__name__)

# Job status constants (mirroring K8s Job conditions)
STATUS_PENDING = "PENDING"
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"

TERMINAL_STATUSES = {STATUS_SUCCEEDED, STATUS_FAILED}


class PythonJobClient:
    """Submit pipeline steps as single-Pod K8s Jobs for lightweight Python execution.

    Compared to RayClient (which creates a full Ray cluster via RayJob CRD),
    PythonJobClient creates a simple batch/v1 Job with a single Pod.
    This is faster to start (~5s vs ~30-60s for Ray) and suitable for
    single-node components that don't need distributed execution.

    The interface mirrors RayClient so the Orchestrator can switch between
    the two based on the step's execution_engine setting.
    """

    def __init__(
        self,
        python_image: str = "swr.cn-north-4.myhuaweicloud.com/flex/python:3.11-slim",
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
        self._python_image = python_image
        self._namespace = k8s_namespace
        self._image_pull_secrets = image_pull_secrets or []
        self._obs_endpoint = obs_endpoint
        self._obs_access_key = obs_access_key
        self._obs_secret_key = obs_secret_key
        self._obs_bucket = obs_bucket
        self._obs_region = obs_region
        self._vk_node_selector_key = vk_node_selector_key
        self._vk_node_selector_value = vk_node_selector_value
        self._batch_api: Optional[k8s_client.BatchV1Api] = None
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
        self._batch_api = k8s_client.BatchV1Api()
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
            self._batch_api = None
            self._core_api = None
            self._initialized = False
            logger.info("Disconnected Kubernetes API client (PythonJobClient)")

    # ------------------------------------------------------------------
    # Public API (mirrors RayClient interface)
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
        """Create a K8s Job to execute a pipeline step in a single Pod.

        Args:
            run_id: Pipeline run identifier.
            step_id: Step identifier within the DAG.
            component_entrypoint: Python module path (e.g. "lakeon_orchestrator.components.normalize.run").
            params: Step parameters dict (serialized to JSON for the entrypoint).
            input_data: Input data references (serialized to JSON for the entrypoint).
            resources: Optional resource overrides {"cpu", "memory"}.
            tenant_id: Tenant ID, used for namespace and OBS paths.
            env_vars: Additional environment variables.
            pip_packages: Additional pip packages to install before execution.
            namespace_override: Override the target namespace (default: datalake-tn-{tenant_id}).

        Returns:
            The K8s Job name (used to query status / wait / delete).
        """
        await self._ensure_k8s()

        ns = namespace_override or f"datalake-{tenant_id.replace('_', '-')}"
        job_name = self._make_job_name(run_id, step_id)
        res = resources or {}

        # Serialize step context to JSON -> base64
        step_context = {
            "run_id": run_id,
            "step_id": step_id,
            "entrypoint": component_entrypoint,
            "params": params,
            "input_data": input_data,
        }
        context_b64 = base64.b64encode(json.dumps(step_context).encode()).decode()

        output_obs_key = f"pipeline-runs/{run_id}/{step_id}/output.json"
        log_obs_key = f"pipeline-runs/{run_id}/{step_id}/output.log"

        wrapper_script = _build_wrapper_script(context_b64, output_obs_key, log_obs_key)
        wrapper_b64 = base64.b64encode(wrapper_script.encode()).decode()

        # Build pip install prefix
        pip_install = ""
        all_packages = ["boto3"]
        if pip_packages:
            all_packages.extend(p.strip() for p in pip_packages if p.strip())
        pip_install = "pip install --no-cache-dir " + " ".join(all_packages) + " && "

        command = [
            "/bin/sh", "-c",
            (
                f"set -o pipefail; "
                f"({pip_install}"
                f"python -c \""
                f"import base64,os; "
                f"os.makedirs('/tmp',exist_ok=True); "
                f"open('/tmp/_step.py','w').write(base64.b64decode('{wrapper_b64}').decode()); "
                f"\" && python /tmp/_step.py) 2>&1 | tee /tmp/job.log"
            ),
        ]

        # Build the K8s Job body
        body = self._build_job(
            name=job_name,
            namespace=ns,
            command=command,
            tenant_id=tenant_id,
            run_id=run_id,
            step_id=step_id,
            res=res,
            env_vars=env_vars,
        )

        await self._batch_api.create_namespaced_job(namespace=ns, body=body)
        logger.info("Created K8s Job %s/%s (python engine)", ns, job_name)
        return job_name

    async def get_job_status(self, job_name: str, namespace: Optional[str] = None) -> str:
        """Query a K8s Job's current status.

        Returns one of: PENDING, RUNNING, SUCCEEDED, FAILED.
        """
        await self._ensure_k8s()
        ns = namespace or self._namespace
        job = await self._batch_api.read_namespaced_job_status(name=job_name, namespace=ns)
        return self._extract_status(job)

    async def wait_for_completion(
        self,
        job_name: str,
        namespace: Optional[str] = None,
        timeout: int = 3600,
        poll_interval: int = 5,
    ) -> dict:
        """Poll until a K8s Job reaches a terminal status.

        Returns:
            {"status": "SUCCEEDED"|"FAILED", "job_name": ..., "message": ...}

        Raises:
            TimeoutError if the job doesn't finish within *timeout* seconds.
        """
        await self._ensure_k8s()
        ns = namespace or self._namespace
        elapsed = 0

        while elapsed < timeout:
            job = await self._batch_api.read_namespaced_job_status(name=job_name, namespace=ns)
            status = self._extract_status(job)

            if status in TERMINAL_STATUSES:
                message = ""
                if job.status and job.status.conditions:
                    message = job.status.conditions[-1].message or ""
                logger.info("K8s Job %s/%s finished: %s", ns, job_name, status)
                return {
                    "status": status,
                    "job_name": job_name,
                    "message": message,
                }

            await asyncio.sleep(poll_interval)
            elapsed += poll_interval

        raise TimeoutError(
            f"K8s Job {ns}/{job_name} did not complete within {timeout}s"
        )

    async def delete_job(self, job_name: str, namespace: Optional[str] = None) -> None:
        """Delete a completed K8s Job (with propagation to clean up pods)."""
        await self._ensure_k8s()
        ns = namespace or self._namespace
        try:
            await self._batch_api.delete_namespaced_job(
                name=job_name,
                namespace=ns,
                body=k8s_client.V1DeleteOptions(propagation_policy="Background"),
            )
            logger.info("Deleted K8s Job %s/%s", ns, job_name)
        except ApiException as e:
            if e.status == 404:
                logger.warning("K8s Job %s/%s already deleted", ns, job_name)
            else:
                raise

    # ------------------------------------------------------------------
    # Job construction helpers
    # ------------------------------------------------------------------

    def _make_job_name(self, run_id: str, step_id: str) -> str:
        """Generate a K8s-safe Job name (max 63 chars)."""
        raw = f"pl-py-{run_id}-{step_id}".replace("_", "-").lower()
        return raw[:63]

    def _build_job(
        self,
        name: str,
        namespace: str,
        command: list[str],
        tenant_id: str,
        run_id: str,
        step_id: str,
        res: dict,
        env_vars: Optional[dict[str, str]] = None,
    ) -> dict:
        """Build the K8s batch/v1 Job dict."""
        cpu = res.get("cpu", "1")
        memory = res.get("memory", "2Gi")

        # Enforce CCI minimum CPU of 250m
        cpu = _enforce_cci_min_cpu(cpu)

        node_selector = {self._vk_node_selector_key: self._vk_node_selector_value}
        tolerations = [
            {
                "key": "virtual-kubelet.io/provider",
                "operator": "Exists",
                "effect": "NoSchedule",
            }
        ]
        image_pull_secrets = [{"name": s} for s in self._image_pull_secrets if s]

        # OBS + user env vars
        env = [
            {"name": "OBS_ACCESS_KEY_ID", "value": self._obs_access_key},
            {"name": "OBS_SECRET_ACCESS_KEY", "value": self._obs_secret_key},
            {"name": "OBS_ENDPOINT", "value": self._obs_endpoint},
            {"name": "OBS_BUCKET", "value": self._obs_bucket},
            {"name": "OBS_REGION", "value": self._obs_region},
        ]
        for k, v in (env_vars or {}).items():
            env.append({"name": k, "value": v})

        container = {
            "name": "python-job",
            "image": self._python_image,
            "command": command,
            "resources": {
                "requests": {"cpu": cpu, "memory": memory},
                "limits": {"cpu": cpu, "memory": memory},
            },
            "env": env,
            "securityContext": {
                "allowPrivilegeEscalation": False,
                "readOnlyRootFilesystem": False,
                "capabilities": {"drop": ["ALL"]},
            },
        }

        pod_spec = {
            "automountServiceAccountToken": False,
            "securityContext": {
                "runAsNonRoot": True,
                "runAsUser": 1000,
                "runAsGroup": 1000,
            },
            "restartPolicy": "Never",
            "nodeSelector": node_selector,
            "tolerations": tolerations,
            "imagePullSecrets": image_pull_secrets,
            "containers": [container],
        }

        return {
            "apiVersion": "batch/v1",
            "kind": "Job",
            "metadata": {
                "name": name,
                "namespace": namespace,
                "labels": {
                    "lakeon.io/engine": "python",
                    "lakeon.io/tenant-id": tenant_id,
                    "lakeon.io/run-id": run_id,
                    "lakeon.io/step-id": step_id,
                },
            },
            "spec": {
                "backoffLimit": 0,
                "ttlSecondsAfterFinished": 300,
                "template": {
                    "metadata": {
                        "labels": {
                            "lakeon.io/engine": "python",
                            "lakeon.io/tenant-id": tenant_id,
                            "lakeon.io/run-id": run_id,
                            "lakeon.io/step-id": step_id,
                        },
                    },
                    "spec": pod_spec,
                },
            },
        }

    @staticmethod
    def _extract_status(job) -> str:
        """Extract a simplified status from a K8s Job object."""
        if not job.status:
            return STATUS_PENDING
        if job.status.succeeded and job.status.succeeded > 0:
            return STATUS_SUCCEEDED
        if job.status.failed and job.status.failed > 0:
            return STATUS_FAILED
        if job.status.active and job.status.active > 0:
            return STATUS_RUNNING
        return STATUS_PENDING


# ------------------------------------------------------------------
# Module-level helper functions
# ------------------------------------------------------------------

def _build_wrapper_script(context_b64: str, output_obs_key: str, log_obs_key: str) -> str:
    """Build the Python wrapper that runs in the Job Pod.

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

# Upload result to OBS via boto3/S3
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
