"""Force a compute cold start on dbay.cloud by creating a new DB and connecting.

Run from the user's Mac after deploy, to generate a real cold-start event
that the watcher should catch within ~2 minutes.

Requires:
    pip install psycopg2-binary httpx
    DBAY_ADMIN_TOKEN env var (e.g. lakeon-sre-2026 for test envs) —
        Used as Bearer token against /api/v1/admin/*
    DBAY_API_URL env var (default https://api.dbay.cloud:8443/api/v1)

Flow:
    1. Create a throwaway tenant via admin API.
    2. Create a DB under it (fresh compute spin-up).
    3. Wait for auto-suspend (default 10 min).
    4. Reconnect → cold start.
    5. Print duration so the operator can compare against the 5s threshold.
"""
from __future__ import annotations

import os
import sys
import time

import httpx
import psycopg2


def _admin_headers(token: str) -> dict:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def main() -> int:
    api = os.environ.get("DBAY_API_URL", "https://api.dbay.cloud:8443/api/v1")
    token = os.environ.get("DBAY_ADMIN_TOKEN", "lakeon-sre-2026")

    # Reuse an existing tenant rather than signing up a new one (sign-up
    # requires invite codes + pollutes the tenant table). We pick the
    # `system` tenant which already exists and has its api_key available
    # via admin list. Override with DBAY_SIMULATE_TENANT_API_KEY if you
    # prefer a different tenant.
    suffix = int(time.time())
    tenant_api_key = os.environ.get("DBAY_SIMULATE_TENANT_API_KEY")
    if not tenant_api_key:
        print("[simulate] fetching system tenant api_key via admin API")
        ts = httpx.get(
            f"{api}/admin/tenants",
            headers=_admin_headers(token),
            timeout=15,
            verify=False,
        ).json()
        sys_tenant = next((t for t in ts if t.get("name") == "system"), None)
        if not sys_tenant or not sys_tenant.get("api_key"):
            print(f"FAIL: could not find system tenant (or api_key missing)")
            return 1
        tenant_api_key = sys_tenant["api_key"]
        tenant_id = sys_tenant["id"]
    else:
        tenant_id = "(via env override)"
    print(f"[simulate] using tenant: id={tenant_id}")

    # --- create DB via regular user API using this tenant's api_key ------
    # (admin /databases is for listing; regular /databases POST is the create path)
    d_resp = httpx.post(
        f"{api}/databases",
        headers={
            "Authorization": f"Bearer {tenant_api_key}",
            "Content-Type": "application/json",
        },
        json={"name": f"coldstarttest{suffix}", "compute_size": "1cu"},
        timeout=180,
        verify=False,
    )
    if d_resp.status_code >= 300:
        print(f"FAIL: create db: HTTP {d_resp.status_code}: {d_resp.text}")
        return 1
    d_json = d_resp.json()
    db_id = d_json.get("id")
    password = d_json.get("password")
    if not db_id:
        print(f"FAIL: unexpected db response: {d_json}")
        return 1

    # wait for RUNNING + fetch dsn
    for i in range(30):
        time.sleep(3)
        g = httpx.get(
            f"{api}/databases/{db_id}",
            headers={"Authorization": f"Bearer {tenant_api_key}"},
            timeout=15,
            verify=False,
        ).json()
        if g.get("status") == "RUNNING":
            dsn_tpl = g.get("connection_uri")
            break
    else:
        print(f"FAIL: db never became RUNNING: last status={g.get('status')}")
        return 1

    # inject password into dsn
    if "postgres://" in dsn_tpl and "@" in dsn_tpl:
        dsn = dsn_tpl.replace("@", f":{password}@", 1) + "&sslmode=require"
    else:
        print(f"FAIL: unexpected dsn: {dsn_tpl}")
        return 1
    print(f"[simulate] db={db_id} ready; dsn built")

    # --- sanity first connect (should be fast; compute already running) --
    t0 = time.time()
    conn = psycopg2.connect(dsn, connect_timeout=30)
    conn.close()
    first = int((time.time() - t0) * 1000)
    print(f"[simulate] first connect (warm compute) took {first}ms")

    # --- force cold start by deleting the compute pod via admin API ----
    #     /suspend alone keeps the pod warm in the pool; we need an actual
    #     pod teardown so the next connect has to go through compute_ctl
    #     sync-safekeepers + full PG startup.
    # Admin API exposes compute_pod_name; user API doesn't.
    ad = httpx.get(
        f"{api}/admin/databases/{db_id}",
        headers=_admin_headers(token),
        timeout=15,
        verify=False,
    ).json()
    pod_name = ad.get("compute_pod_name")
    if not pod_name:
        print(f"FAIL: admin response missing compute_pod_name: {ad}")
        return 1
    print(f"[simulate] POST /admin/infra/restart-pod/{pod_name} to force teardown")
    rresp = httpx.post(
        f"{api}/admin/infra/restart-pod/{pod_name}",
        headers=_admin_headers(token),
        timeout=30,
        verify=False,
    )
    if rresp.status_code >= 300:
        print(f"FAIL: restart-pod: HTTP {rresp.status_code}: {rresp.text}")
        return 1
    settle_wait = int(os.environ.get("SIMULATE_SUSPEND_SETTLE_SEC", "15"))
    print(f"[simulate] waiting {settle_wait}s for pod termination...")
    time.sleep(settle_wait)

    # --- reconnect → cold start -----------------------------------------
    print("[simulate] reconnecting (this should trigger a real cold start)...")
    t0 = time.time()
    conn = psycopg2.connect(dsn, connect_timeout=120)
    dt = int((time.time() - t0) * 1000)
    conn.close()
    print(f"[simulate] cold start took {dt}ms (watcher threshold = 5000ms)")

    if dt > 5000:
        print("[simulate] SUCCESS — watcher should fire within 2 min")
    else:
        print("[simulate] NOTE: cold start was <5s; watcher will correctly NOT fire")
        print("           (could mean compute re-warmed too quickly; try rerunning)")

    print(f"[simulate] cleanup: tenant={tenant_id} db={db_id} (purge via admin API if desired)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
