"""Regression tests for AgentFS PUT idempotency.

Motivation: Phase 2 will consume agent_files change events to derive
memory_items. If PUT with unchanged content still bumps mtime_ns /
updated_at, downstream CDC consumers see a false change and re-derive
needlessly. The server-side upsert must be a true idempotent PUT.
"""
import base64
import time

import requests


def _b64url(path: str) -> str:
    return base64.urlsafe_b64encode(path.encode()).rstrip(b"=").decode()


def _agentfs_put(endpoint, api_key, path, data: bytes, retries=10, delay=4):
    """POST /files/put with retry — first call on a new tenant triggers
    AgentFS database provisioning and may return 500 'not READY yet'."""
    last_err = None
    for _ in range(retries):
        r = requests.post(
            f"{endpoint}/api/v1/agentfs/files/put",
            json={"path": path, "data_base64": base64.b64encode(data).decode()},
            headers={"Authorization": f"Bearer {api_key}"},
            verify=False,
            timeout=120,
        )
        if r.status_code == 200:
            return r.json()
        last_err = (r.status_code, r.text[:200])
        time.sleep(delay)
    raise RuntimeError(f"PUT never succeeded: {last_err}")


def _agentfs_head(endpoint, api_key, path):
    r = requests.get(
        f"{endpoint}/api/v1/agentfs/files/head",
        params={"path": _b64url(path)},
        headers={"Authorization": f"Bearer {api_key}"},
        verify=False,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def test_put_with_same_content_is_idempotent(e2e_client):
    """Two PUTs with identical content must leave mtime_ns unchanged.

    This guards the Phase 2 CDC pipeline against false-positive change
    events. Without the idempotency fix, each PUT re-stamps mtime_ns
    to nowNs() regardless of content.
    """
    endpoint = e2e_client.endpoint
    api_key = e2e_client.api_key
    path = f"/idem-test-{int(time.time()*1000)}.txt"
    data = b"deterministic content for idempotency check"

    # First write + readback (absorbs provisioning delay).
    _agentfs_put(endpoint, api_key, path, data)
    mt1 = _agentfs_head(endpoint, api_key, path)["mtime_ns"]

    # Ensure wall clock has moved so unconditional UPDATE would be visible.
    time.sleep(0.25)

    _agentfs_put(endpoint, api_key, path, data)
    mt2 = _agentfs_head(endpoint, api_key, path)["mtime_ns"]

    assert mt1 == mt2, (
        f"PUT with unchanged content must not bump mtime_ns: "
        f"first={mt1} second={mt2} delta_ns={mt2-mt1}"
    )


def test_put_with_changed_content_updates_mtime(e2e_client):
    """Content change still bumps mtime_ns and etag — negative control
    to ensure the idempotency fix didn't break normal writes."""
    endpoint = e2e_client.endpoint
    api_key = e2e_client.api_key
    path = f"/change-test-{int(time.time()*1000)}.txt"

    _agentfs_put(endpoint, api_key, path, b"initial")
    first = _agentfs_head(endpoint, api_key, path)
    mt1 = first["mtime_ns"]
    etag1 = first["etag"]

    time.sleep(0.25)

    _agentfs_put(endpoint, api_key, path, b"modified")
    second = _agentfs_head(endpoint, api_key, path)
    assert second["etag"] != etag1
    assert second["mtime_ns"] > mt1
