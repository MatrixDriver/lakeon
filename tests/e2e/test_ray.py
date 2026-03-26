"""
E2E tests for Ray distributed job on CCI.

Suites:
  1. Ray Job Completion — submit, wait for SUCCEEDED, verify output
  2. Ray with pip requirements — runtime_env installs extra packages
  3. Ray Job Cancel — submit then cancel
  4. Ray Tenant Isolation — cross-tenant access denied
"""
import os
import time

import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import ENDPOINT, ADMIN_TOKEN, poll_until, _create_tenant_with_invite

SKIP_RAY = os.environ.get("RAY_SKIP", "0") == "1"
RAY_TIMEOUT = int(os.environ.get("RAY_TIMEOUT", "300"))

TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED"}

pytestmark = pytest.mark.skipif(SKIP_RAY, reason="RAY_SKIP=1 — set to 0 to run Ray tests")


def _wait_terminal(client: DbayClient, job_id: str, timeout: int = RAY_TIMEOUT) -> dict:
    """Poll until job reaches a terminal status. Returns the full job dict."""
    return poll_until(
        lambda: client.get_datalake_job(job_id),
        condition=lambda j: j["status"] in TERMINAL_STATUSES,
        timeout=timeout,
        interval=10,
    )


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 1: Ray Job Completion
# ═══════════════════════════════════════════════════════════════════════════════

class TestRayJobCompletion:
    """Submit a Ray distributed job on CCI and verify it succeeds."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-ray-{ts}", f"Ray@{ts}", f"Ray Test {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def completed_ray_job(self, client):
        """Submit a simple Ray job that distributes work to workers."""
        ts = int(time.time())
        job = client.submit_datalake_job({
            "name": f"e2e-ray-hello-{ts}",
            "type": "RAY",
            "inline_script": (
                "import ray\n"
                "ray.init()\n"
                "\n"
                "@ray.remote\n"
                "def hello(x):\n"
                "    return f'hello from worker {x}'\n"
                "\n"
                "results = ray.get([hello.remote(i) for i in range(4)])\n"
                "for r in results:\n"
                "    print(r)\n"
                "print('E2E_RAY_SUCCESS')\n"
            ),
            "head": {"cpu": "1", "memory": "2Gi"},
            "workers": {"replicas": 1, "cpu": "1", "memory": "2Gi"},
            "timeout_seconds": RAY_TIMEOUT,
        })
        assert job.get("id"), "Expected non-empty job id"
        assert job["type"] == "RAY"
        final = _wait_terminal(client, job["id"])
        yield final

    # ── RAY-E2E-001: Ray job reaches SUCCEEDED ────────────────────────────────

    def test_ray_job_succeeds(self, completed_ray_job):
        """RAY-E2E-001: A simple Ray distributed job reaches SUCCEEDED."""
        assert completed_ray_job["status"] == "SUCCEEDED", (
            f"Expected SUCCEEDED, got {completed_ray_job['status']}. "
            f"Error: {completed_ray_job.get('errorMessage')}"
        )

    # ── RAY-E2E-002: finishedAt is populated ──────────────────────────────────

    def test_finished_at_set(self, completed_ray_job):
        """RAY-E2E-002: finishedAt is set after Ray job completes."""
        assert completed_ray_job.get("finishedAt") is not None

    # ── RAY-E2E-003: rayJobName is populated ──────────────────────────────────

    def test_ray_job_name_set(self, completed_ray_job):
        """RAY-E2E-003: rayJobName is populated for Ray jobs."""
        assert completed_ray_job.get("rayJobName"), "rayJobName should be set"
        assert completed_ray_job["rayJobName"].startswith("ray-")

    # ── RAY-E2E-004: cciNamespace is populated ────────────────────────────────

    def test_cci_namespace_set(self, completed_ray_job):
        """RAY-E2E-004: cciNamespace is set for CCI jobs."""
        assert completed_ray_job.get("cciNamespace"), "cciNamespace should be set"
        assert completed_ray_job["cciNamespace"].startswith("datalake-")


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 2: Ray with pip requirements
# ═══════════════════════════════════════════════════════════════════════════════

class TestRayWithRequirements:
    """Ray job with runtime_env pip packages."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-ray-req-{ts}", f"RayReq@{ts}", f"Ray Req {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def pip_job(self, client):
        """Submit a Ray job that uses a pip-installed package."""
        ts = int(time.time())
        job = client.submit_datalake_job({
            "name": f"e2e-ray-pip-{ts}",
            "type": "RAY",
            "inline_script": (
                "import ray\n"
                "ray.init()\n"
                "import humanize\n"
                "print(humanize.intcomma(1000000))\n"
                "print('E2E_RAY_PIP_SUCCESS')\n"
            ),
            "requirements": "humanize",
            "head": {"cpu": "1", "memory": "2Gi"},
            "workers": {"replicas": 1, "cpu": "1", "memory": "2Gi"},
            "timeout_seconds": RAY_TIMEOUT,
        })
        final = _wait_terminal(client, job["id"])
        yield final

    # ── RAY-E2E-010: Ray job with pip requirements succeeds ───────────────────

    def test_ray_pip_job_succeeds(self, pip_job):
        """RAY-E2E-010: Ray job with pip requirements reaches SUCCEEDED."""
        assert pip_job["status"] == "SUCCEEDED", (
            f"Expected SUCCEEDED, got {pip_job['status']}. "
            f"Error: {pip_job.get('errorMessage')}"
        )


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 3: Ray Job Cancel
# ═══════════════════════════════════════════════════════════════════════════════

class TestRayJobCancel:
    """Cancel a running Ray job."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-ray-cancel-{ts}", f"RayCancel@{ts}", f"Ray Cancel {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    # ── RAY-E2E-020: Cancel a running Ray job ─────────────────────────────────

    def test_cancel_ray_job(self, client):
        """RAY-E2E-020: Cancel a running Ray job transitions to CANCELLED."""
        ts = int(time.time())
        job = client.submit_datalake_job({
            "name": f"e2e-ray-cancel-{ts}",
            "type": "RAY",
            "inline_script": (
                "import ray, time\n"
                "ray.init()\n"
                "time.sleep(600)\n"
            ),
            "head": {"cpu": "1", "memory": "2Gi"},
            "workers": {"replicas": 1, "cpu": "1", "memory": "2Gi"},
            "timeout_seconds": 600,
        })
        job_id = job["id"]

        # Wait a bit for the job to start
        time.sleep(15)

        current = client.get_datalake_job(job_id)["status"]
        if current in TERMINAL_STATUSES:
            pytest.skip(f"Job already terminal ({current}) before cancel")

        client.cancel_datalake_job(job_id)
        time.sleep(5)

        after = client.get_datalake_job(job_id)["status"]
        assert after == "CANCELLED", f"Expected CANCELLED, got {after}"


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 4: Tenant Isolation
# ═══════════════════════════════════════════════════════════════════════════════

class TestRayTenantIsolation:
    """Cross-tenant access to Ray jobs must be denied."""

    @pytest.fixture(scope="class")
    def tenant_a(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-ray-ta-{ts}", f"RayTa@{ts}", f"Ray-A {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def tenant_b(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-ray-tb-{ts}", f"RayTb@{ts}", f"Ray-B {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def ray_job_a(self, tenant_a):
        """Tenant A submits a Ray job."""
        ts = int(time.time())
        job = tenant_a.submit_datalake_job({
            "name": f"e2e-ray-iso-a-{ts}",
            "type": "RAY",
            "inline_script": "import ray, time\nray.init()\ntime.sleep(300)\n",
            "head": {"cpu": "1", "memory": "2Gi"},
            "workers": {"replicas": 1, "cpu": "1", "memory": "2Gi"},
            "timeout_seconds": 600,
        })
        yield job
        try:
            tenant_a.cancel_datalake_job(job["id"])
        except Exception:
            pass

    # ── RAY-E2E-030: Cross-tenant GET denied ──────────────────────────────────

    def test_tenant_b_cannot_get_tenant_a_ray_job(self, tenant_b, ray_job_a):
        """RAY-E2E-030: Tenant B cannot GET Tenant A's Ray job."""
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b.get_datalake_job(ray_job_a["id"])
        assert exc_info.value.status_code in (403, 404)

    # ── RAY-E2E-031: Cross-tenant cancel denied ──────────────────────────────

    def test_tenant_b_cannot_cancel_tenant_a_ray_job(self, tenant_b, ray_job_a):
        """RAY-E2E-031: Tenant B cannot cancel Tenant A's Ray job."""
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b.cancel_datalake_job(ray_job_a["id"])
        assert exc_info.value.status_code in (403, 404)

    # ── RAY-E2E-032: Cross-tenant list isolation ──────────────────────────────

    def test_tenant_b_list_excludes_tenant_a_ray_job(self, tenant_b, ray_job_a):
        """RAY-E2E-032: Tenant B's job list does not contain Tenant A's Ray job."""
        ids = [j["id"] for j in tenant_b.list_datalake_jobs()]
        assert ray_job_a["id"] not in ids
