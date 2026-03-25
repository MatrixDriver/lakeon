"""
E2E tests for Datalake Job API.

Suites:
  1. Basic CRUD — submit, get, list, cancel, 404
  2. Tenant isolation — cross-tenant GET/list/cancel protection
  3. Auth & validation — 401, 400 responses
  4. Job completion — waits for SUCCEEDED (skipped if DATALAKE_SKIP_COMPLETION=1)
"""
import os
import time

import pytest

from dbay_cli.client import DbayClient, DbayApiError
from conftest import ENDPOINT, ADMIN_TOKEN, poll_until, _create_tenant_with_invite

SKIP_COMPLETION = os.environ.get("DATALAKE_SKIP_COMPLETION", "0") == "1"
JOB_COMPLETION_TIMEOUT = int(os.environ.get("JOB_COMPLETION_TIMEOUT", "180"))

VALID_INITIAL_STATUSES = {"PENDING", "STARTING", "RUNNING", "FAILED"}
TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELLED"}


def _submit_sleep_job(client: DbayClient, name: str, sleep_seconds: int = 300) -> dict:
    """Submit a PYTHON job that sleeps (stays alive long enough to cancel)."""
    return client.submit_datalake_job({
        "name": name,
        "type": "PYTHON",
        "entrypoint": f'python -c "import time; time.sleep({sleep_seconds})"',
        "resources": {"cpu": "0.1", "memory": "128Mi"},
        "timeout_seconds": 600,
    })


def _wait_terminal(client: DbayClient, job_id: str, timeout: int = JOB_COMPLETION_TIMEOUT) -> str:
    """Poll until job reaches a terminal status. Returns the final status string."""
    job = poll_until(
        lambda: client.get_datalake_job(job_id),
        condition=lambda j: j["status"] in TERMINAL_STATUSES,
        timeout=timeout,
        interval=5,
    )
    return job["status"]


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 1: Basic CRUD
# ═══════════════════════════════════════════════════════════════════════════════

class TestDatalakeCRUD:
    """Basic job CRUD operations."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-dl-crud-{ts}", f"DlCrud@{ts}", f"DL CRUD {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def submitted_job(self, client):
        """Submit a single long-running job reused across CRUD tests."""
        ts = int(time.time())
        job = _submit_sleep_job(client, f"e2e-crud-{ts}")
        yield job
        # Best-effort cleanup: cancel if still active
        try:
            client.cancel_datalake_job(job["id"])
        except Exception:
            pass

    # ── DL-E2E-001: Submit ────────────────────────────────────────────────────

    def test_submit_returns_id(self, submitted_job):
        """DL-E2E-001a: Submit returns a non-empty job id."""
        assert submitted_job.get("id"), "Expected non-empty id in submit response"

    def test_submit_initial_status(self, submitted_job):
        """DL-E2E-001b: Initial status is one of the expected values."""
        assert submitted_job["status"] in VALID_INITIAL_STATUSES, (
            f"Unexpected initial status: {submitted_job['status']}"
        )

    def test_submit_type(self, submitted_job):
        """DL-E2E-001c: Submitted job type is PYTHON."""
        assert submitted_job["type"] == "PYTHON"

    def test_submit_name(self, submitted_job):
        """DL-E2E-001d: Submitted job name is preserved."""
        assert "e2e-crud-" in submitted_job["name"]

    # ── DL-E2E-002: Get by ID ─────────────────────────────────────────────────

    def test_get_job_by_id(self, client, submitted_job):
        """DL-E2E-002a: GET returns the correct job."""
        job = client.get_datalake_job(submitted_job["id"])
        assert job["id"] == submitted_job["id"]
        assert job["type"] == "PYTHON"

    # ── DL-E2E-003: List ──────────────────────────────────────────────────────

    def test_list_jobs(self, client, submitted_job):
        """DL-E2E-003: List returns at least one job."""
        jobs = client.list_datalake_jobs()
        assert isinstance(jobs, list)
        assert len(jobs) >= 1
        ids = [j["id"] for j in jobs]
        assert submitted_job["id"] in ids

    # ── DL-E2E-004: List with status filter ───────────────────────────────────

    def test_list_with_status_filter(self, client, submitted_job):
        """DL-E2E-004: Filtering by the job's current status includes the job."""
        current = client.get_datalake_job(submitted_job["id"])["status"]
        filtered = client.list_datalake_jobs(status=current)
        ids = [j["id"] for j in filtered]
        assert submitted_job["id"] in ids

    # ── DL-E2E-005: Cancel ────────────────────────────────────────────────────

    def test_cancel_active_job(self, client):
        """DL-E2E-005a/b: Cancel an active job → 204, then status becomes CANCELLED."""
        ts = int(time.time())
        job = _submit_sleep_job(client, f"e2e-cancel-{ts}")
        job_id = job["id"]

        # If job is already terminal (e.g., FAILED due to no infra), skip cancel check
        current = client.get_datalake_job(job_id)["status"]
        if current in TERMINAL_STATUSES:
            pytest.skip(f"Job already in terminal state {current} — no active job to cancel")

        # Cancel should succeed (204 — no body raised)
        client.cancel_datalake_job(job_id)

        # Status should be CANCELLED
        time.sleep(2)
        after = client.get_datalake_job(job_id)["status"]
        assert after == "CANCELLED", f"Expected CANCELLED after cancel, got {after}"

    def test_cancel_terminal_job_returns_400(self, client):
        """DL-E2E-005c: Cancelling an already-terminal job returns 400."""
        ts = int(time.time())
        job = _submit_sleep_job(client, f"e2e-cancel2-{ts}")
        job_id = job["id"]

        current = client.get_datalake_job(job_id)["status"]
        if current not in TERMINAL_STATUSES:
            client.cancel_datalake_job(job_id)
            time.sleep(2)

        with pytest.raises(DbayApiError) as exc_info:
            client.cancel_datalake_job(job_id)
        assert exc_info.value.status_code == 400

    # ── DL-E2E-006: Non-existent job → 404 ───────────────────────────────────

    def test_get_nonexistent_job_returns_404(self, client):
        """DL-E2E-006: GET on a non-existent job id returns 404."""
        with pytest.raises(DbayApiError) as exc_info:
            client.get_datalake_job("non-existent-job-id-00000000")
        assert exc_info.value.status_code == 404


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 2: Tenant Isolation
# ═══════════════════════════════════════════════════════════════════════════════

class TestDatalakeTenantIsolation:
    """Cross-tenant access must be denied (403 or 404)."""

    @pytest.fixture(scope="class")
    def tenant_a(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-dl-ta-{ts}", f"DlTa@{ts}", f"DL-A {ts}",
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
            f"e2e-dl-tb-{ts}", f"DlTb@{ts}", f"DL-B {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def jobs(self, tenant_a, tenant_b):
        """Each tenant submits a job; return both job ids."""
        ts = int(time.time())
        job_a = _submit_sleep_job(tenant_a, f"e2e-iso-a-{ts}")
        job_b = _submit_sleep_job(tenant_b, f"e2e-iso-b-{ts}")
        yield job_a["id"], job_b["id"]
        for client, job_id in [(tenant_a, job_a["id"]), (tenant_b, job_b["id"])]:
            try:
                client.cancel_datalake_job(job_id)
            except Exception:
                pass

    # ── DL-E2E-010: Cross-tenant GET ──────────────────────────────────────────

    def test_tenant_a_cannot_get_tenant_b_job(self, tenant_a, jobs):
        """DL-E2E-010a: Tenant A gets 403/404 on Tenant B's job."""
        _, job_b_id = jobs
        with pytest.raises(DbayApiError) as exc_info:
            tenant_a.get_datalake_job(job_b_id)
        assert exc_info.value.status_code in (403, 404)

    def test_tenant_b_cannot_get_tenant_a_job(self, tenant_b, jobs):
        """DL-E2E-010b: Tenant B gets 403/404 on Tenant A's job."""
        job_a_id, _ = jobs
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b.get_datalake_job(job_a_id)
        assert exc_info.value.status_code in (403, 404)

    # ── DL-E2E-011: List isolation ────────────────────────────────────────────

    def test_tenant_a_list_excludes_tenant_b_job(self, tenant_a, jobs):
        """DL-E2E-011a: Tenant A's job list does not contain Tenant B's job."""
        _, job_b_id = jobs
        ids = [j["id"] for j in tenant_a.list_datalake_jobs()]
        assert job_b_id not in ids

    def test_tenant_b_list_excludes_tenant_a_job(self, tenant_b, jobs):
        """DL-E2E-011b: Tenant B's job list does not contain Tenant A's job."""
        job_a_id, _ = jobs
        ids = [j["id"] for j in tenant_b.list_datalake_jobs()]
        assert job_a_id not in ids

    # ── DL-E2E-012: Cross-tenant cancel ───────────────────────────────────────

    def test_tenant_a_cannot_cancel_tenant_b_job(self, tenant_a, jobs):
        """DL-E2E-012a: Tenant A gets 403/404 when cancelling Tenant B's job."""
        _, job_b_id = jobs
        with pytest.raises(DbayApiError) as exc_info:
            tenant_a.cancel_datalake_job(job_b_id)
        assert exc_info.value.status_code in (403, 404)

    def test_tenant_b_cannot_cancel_tenant_a_job(self, tenant_b, jobs):
        """DL-E2E-012b: Tenant B gets 403/404 when cancelling Tenant A's job."""
        job_a_id, _ = jobs
        with pytest.raises(DbayApiError) as exc_info:
            tenant_b.cancel_datalake_job(job_a_id)
        assert exc_info.value.status_code in (403, 404)


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 3: Auth & Validation
# ═══════════════════════════════════════════════════════════════════════════════

class TestDatalakeAuthAndValidation:
    """Auth errors and request validation."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-dl-val-{ts}", f"DlVal@{ts}", f"DL VAL {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    # ── DL-E2E-020: No auth → 401 ─────────────────────────────────────────────

    def test_no_auth_returns_401(self):
        """DL-E2E-020: Unauthenticated request returns 401."""
        anon = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc_info:
            anon.list_datalake_jobs()
        assert exc_info.value.status_code == 401

    # ── DL-E2E-021: Invalid API key → 401 ────────────────────────────────────

    def test_invalid_key_returns_401(self):
        """DL-E2E-021: Invalid API key returns 401."""
        bad = DbayClient(endpoint=ENDPOINT, api_key="invalid-key-xyz-12345")
        with pytest.raises(DbayApiError) as exc_info:
            bad.list_datalake_jobs()
        assert exc_info.value.status_code == 401

    # ── DL-E2E-022: Missing name → 400 ───────────────────────────────────────

    def test_submit_without_name_returns_400(self, client):
        """DL-E2E-022: Submitting without a name returns 400."""
        with pytest.raises(DbayApiError) as exc_info:
            client.submit_datalake_job({
                "type": "PYTHON",
                "entrypoint": 'python -c "print(1)"',
            })
        assert exc_info.value.status_code == 400

    # ── DL-E2E-023: Missing type → 400 ───────────────────────────────────────

    def test_submit_without_type_returns_400(self, client):
        """DL-E2E-023: Submitting without a type returns 400."""
        with pytest.raises(DbayApiError) as exc_info:
            client.submit_datalake_job({
                "name": f"no-type-{int(time.time())}",
                "entrypoint": 'python -c "print(1)"',
            })
        assert exc_info.value.status_code == 400

    # ── DL-E2E-024: Invalid status filter → 400 ──────────────────────────────

    def test_list_invalid_status_returns_400(self, client):
        """DL-E2E-024: Filtering by an invalid status value returns 400."""
        with pytest.raises(DbayApiError) as exc_info:
            client.list_datalake_jobs(status="BOGUS")
        assert exc_info.value.status_code == 400


# ═══════════════════════════════════════════════════════════════════════════════
#  Suite 4: Job Completion  (optional — set DATALAKE_SKIP_COMPLETION=0 to enable)
# ═══════════════════════════════════════════════════════════════════════════════

@pytest.mark.skipif(SKIP_COMPLETION, reason="DATALAKE_SKIP_COMPLETION=1 — set to 0 to run")
class TestDatalakeJobCompletion:
    """Verify a PYTHON job actually runs to SUCCEEDED (requires VK/CCI infra)."""

    @pytest.fixture(scope="class")
    def client(self):
        ts = int(time.time())
        c, t = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-dl-comp-{ts}", f"DlComp@{ts}", f"DL COMP {ts}",
        )
        yield c
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([t["id"]])
        except Exception:
            pass

    @pytest.fixture(scope="class")
    def completed_job(self, client):
        """Submit a fast-exiting job and wait for SUCCEEDED."""
        ts = int(time.time())
        job = client.submit_datalake_job({
            "name": f"e2e-success-{ts}",
            "type": "PYTHON",
            "entrypoint": 'python -c "print(\'hello datalake\')"',
            "resources": {"cpu": "0.1", "memory": "128Mi"},
            "timeout_seconds": 60,
        })
        final_status = _wait_terminal(client, job["id"], timeout=JOB_COMPLETION_TIMEOUT)
        job["_final_status"] = final_status
        job["_id"] = job["id"]
        yield job

    # ── DL-E2E-030: PYTHON job → SUCCEEDED ───────────────────────────────────

    def test_python_job_succeeds(self, completed_job):
        """DL-E2E-030: A simple PYTHON job reaches SUCCEEDED."""
        assert completed_job["_final_status"] == "SUCCEEDED", (
            f"Expected SUCCEEDED, got {completed_job['_final_status']}"
        )

    # ── DL-E2E-031: finishedAt is populated after completion ─────────────────

    def test_finished_at_set_on_completed_job(self, client, completed_job):
        """DL-E2E-031: finishedAt is non-null after job completes."""
        job = client.get_datalake_job(completed_job["_id"])
        assert job.get("finishedAt") is not None, "finishedAt should be set on completed job"
