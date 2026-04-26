"""E2E tests for POST /api/v1/ray-jobs — the xscale/pyscaler CLI contract.

Requires a running lakeon-api with:
- DBAY_ENDPOINT (defaults to hwstaff)
- A valid tenant API key (lk_...)

Creates a temp tenant, submits a tiny Ray job (single-script that prints hello),
polls until done, fetches logs, cleans up.
"""
import io
import os
import time

import httpx
import pytest

from conftest import ENDPOINT, ADMIN_TOKEN, poll_until, _create_tenant_with_invite
from dbay_cli.client import DbayClient


SCRIPT = b'''
import os, sys, time
print("pyscaler hello from Ray, input=", os.environ.get("INPUT_PATH"))
print("output=", os.environ.get("OUTPUT_PATH"))
time.sleep(2)
print("done")
'''


@pytest.fixture(scope="module")
def tenant_key():
    """Create a temp tenant via invite flow, yield its API key."""
    ts = int(time.time())
    username = f"rayjobs-e2e-{ts}"
    password = f"E2eTest@{ts}"
    name = f"pyscaler-e2e-{ts}"
    _client, tenant = _create_tenant_with_invite(
        ENDPOINT, ADMIN_TOKEN, username, password, name
    )
    yield tenant["api_key"]

    try:
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        admin.admin_batch_delete_tenants([tenant["id"]])
    except Exception as e:
        print(f"tenant teardown failed: {e}")


def test_submit_ray_job_and_get_status(tenant_key):
    headers = {"Authorization": f"Bearer {tenant_key}"}
    with httpx.Client(verify=False, timeout=120.0) as c:
        r = c.post(
            f"{ENDPOINT}/api/v1/ray-jobs",
            headers=headers,
            files={"script": ("hello.py", SCRIPT, "text/x-python")},
            data={"input": "obs://test/in/", "output": "obs://test/out/",
                  "name": "pyscaler-hello", "workers": "2"},
        )
        assert r.status_code == 202, r.text
        job_id = r.json()["job_id"]
        assert job_id.startswith("dlj_")

        # Poll for status
        def get_status():
            s = c.get(f"{ENDPOINT}/api/v1/ray-jobs/{job_id}", headers=headers)
            s.raise_for_status()
            return s.json()

        final = poll_until(get_status,
                           lambda x: x["state"] in ("succeeded", "failed"),
                           timeout=300, interval=5)
        assert final["state"] == "succeeded", final
        assert final["returncode"] == 0

        # Fetch logs
        lr = c.get(f"{ENDPOINT}/api/v1/ray-jobs/{job_id}/logs?tail=50", headers=headers)
        assert lr.status_code == 200
        lines = lr.json()["lines"]
        # Script prints these; allow for some pod overhead lines
        joined = "\n".join(lines)
        assert "pyscaler hello" in joined or "done" in joined, joined[:500]
