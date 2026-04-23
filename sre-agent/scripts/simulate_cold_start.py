"""Force a compute cold start on dbay.cloud by creating a new DB and connecting.

Run from the user's Mac after deploy, to generate a real cold-start event
that the watcher should catch within ~2 minutes.

Requires:
    pip install psycopg2-binary httpx
    DBAY_ADMIN_TOKEN env var (e.g. lakeon-sre-2026 for test envs)
    DBAY_API_URL env var (default https://api.dbay.cloud:8443/api/v1)
"""
from __future__ import annotations

import os
import sys
import time

import httpx
import psycopg2


def main() -> int:
    api = os.environ.get("DBAY_API_URL", "https://api.dbay.cloud:8443/api/v1")
    token = os.environ.get("DBAY_ADMIN_TOKEN", "lakeon-sre-2026")

    print(f"[simulate] creating tenant + db on {api}")
    t = httpx.post(
        f"{api}/admin/tenants",
        headers={"Admin-Token": token},
        json={"name": f"coldstart-test-{int(time.time())}"},
        timeout=30,
    ).json()
    tenant_id = t["id"]
    d = httpx.post(
        f"{api}/admin/databases",
        headers={"Admin-Token": token},
        json={"tenant_id": tenant_id, "name": "test"},
        timeout=120,
    ).json()
    db_id = d["id"]
    dsn = d.get("dsn")
    if not dsn:
        print(f"FAIL: no dsn returned: {d}")
        return 1
    print(f"[simulate] created tenant={tenant_id} db={db_id}")

    # Wait for idle auto-suspend (actual window depends on deploy config;
    # the default ComputeLifecycleService idle timeout may be 5-10 min)
    idle_wait = int(os.environ.get("SIMULATE_IDLE_WAIT_SEC", "600"))
    print(f"[simulate] waiting {idle_wait}s for auto-suspend...")
    time.sleep(idle_wait)

    print("[simulate] connecting (first connect = cold start)...")
    t0 = time.time()
    conn = psycopg2.connect(dsn, connect_timeout=120)
    dt = int((time.time() - t0) * 1000)
    conn.close()
    print(f"[simulate] cold start took {dt}ms (watcher should pick up if >5000)")

    if dt > 5000:
        print("[simulate] SUCCESS — watcher should fire within 2 min")
        return 0
    print("[simulate] cold start was fast; watcher will correctly NOT fire")
    return 0


if __name__ == "__main__":
    sys.exit(main())
