"""E2E: two clients writing the same file → second hits precondition_failed.

Simulates cross-device editing without actually launching the FUSE daemon —
we exercise the server's per-op precondition_failed behaviour via direct
HTTP calls, which is what dbay-fuse's uplink would do.

NOTE: requires lakeon-api deployed with T1-T3 changes (AgentFSService.append
accepts ifMatch; controller wires if_match through /files/append + batch
append; batch demotes BadRequestException("precondition_failed: ...") to
per-op status:"precondition_failed").
"""
import base64
import time

import requests


def _b64(s: bytes) -> str:
    return base64.b64encode(s).decode()


def _b64url(s: bytes) -> str:
    return base64.urlsafe_b64encode(s).rstrip(b"=").decode()


def _put(endpoint, key, path, data, if_match=None, retries=10, delay=4):
    """POST /files/put; first call on a fresh tenant retries while base provisions."""
    last_err = None
    for _ in range(retries):
        body = {"path": path, "data_base64": _b64(data)}
        if if_match is not None:
            body["if_match"] = if_match
        r = requests.post(
            f"{endpoint}/api/v1/agentfs/files/put",
            json=body,
            headers={"Authorization": f"Bearer {key}"},
            verify=False, timeout=120,
        )
        if r.status_code in (200, 400):
            return r
        last_err = (r.status_code, r.text[:200])
        time.sleep(delay)
    raise RuntimeError(f"PUT never succeeded: {last_err}")


def _head(endpoint, key, path):
    r = requests.get(
        f"{endpoint}/api/v1/agentfs/files/head",
        params={"path": _b64url(path.encode())},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=30,
    )
    r.raise_for_status()
    return r.json()


def test_put_with_stale_if_match_returns_precondition_failed(e2e_client):
    endpoint = e2e_client.endpoint
    key = e2e_client.api_key

    r1 = _put(endpoint, key, "/cross-dev.txt", b"v1")
    assert r1.status_code == 200, r1.text
    etag_v1 = r1.json()["etag"]

    r2 = _put(endpoint, key, "/cross-dev.txt", b"v2")
    assert r2.status_code == 200, r2.text
    etag_v2 = r2.json()["etag"]
    assert etag_v2 != etag_v1

    # Third writer thinks it's based on v1 → should fail.
    r3 = _put(endpoint, key, "/cross-dev.txt", b"v3", if_match=etag_v1)
    assert r3.status_code == 400, r3.text
    assert "precondition_failed" in r3.text


def test_append_with_stale_if_match_returns_precondition_failed(e2e_client):
    endpoint = e2e_client.endpoint
    key = e2e_client.api_key

    r1 = _put(endpoint, key, "/cross-dev-append.log", b"hello")
    assert r1.status_code == 200
    etag_v1 = r1.json()["etag"]

    # Concurrent append from another client — succeeds, etag advances.
    r2 = requests.post(
        f"{endpoint}/api/v1/agentfs/files/append",
        json={"path": "/cross-dev-append.log", "data_base64": _b64(b" world")},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=60,
    )
    assert r2.status_code == 200, r2.text

    # Stale appender using the original etag_v1.
    r3 = requests.post(
        f"{endpoint}/api/v1/agentfs/files/append",
        json={"path": "/cross-dev-append.log",
              "data_base64": _b64(b"!"),
              "if_match": etag_v1},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=60,
    )
    assert r3.status_code == 400, r3.text
    assert "precondition_failed" in r3.text


def test_batch_one_precondition_fails_others_succeed(e2e_client):
    endpoint = e2e_client.endpoint
    key = e2e_client.api_key

    _put(endpoint, key, "/batch-a.txt", b"1")
    _put(endpoint, key, "/batch-b.txt", b"1")
    etag_b = _head(endpoint, key, "/batch-b.txt")["etag"]

    body = {"ops": [
        {"op": "put", "path": "/batch-a.txt",
         "data_base64": _b64(b"2"), "if_match": "wrong-etag"},
        {"op": "put", "path": "/batch-b.txt",
         "data_base64": _b64(b"2"), "if_match": etag_b},
    ]}
    r = requests.post(f"{endpoint}/api/v1/agentfs/batch", json=body,
                      headers={"Authorization": f"Bearer {key}"},
                      verify=False, timeout=60)
    assert r.status_code == 200, r.text
    results = r.json()["results"]
    assert len(results) == 2
    assert results[0]["status"] == "precondition_failed"
    assert results[0]["path"] == "/batch-a.txt"
    assert results[1]["status"] == "ok"
    assert results[1]["path"] == "/batch-b.txt"
