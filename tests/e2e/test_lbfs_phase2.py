"""LakebaseFS Phase 2 mechanism E2E tests.

Exercises the end-to-end derivation pipeline:
  CC writes file -> FUSE -> /lbfs/put -> agent_files + event row
  -> forwarder -> memory-svc /lbfs/derive -> memories table
  -> memory_recall returns it

These tests will not pass until Phase F has rolled out the forwarder
and /lbfs/derive endpoint. Use after deploy to verify mechanism.
"""
import base64
import time

import requests
import urllib3

from conftest import poll_until

# The e2e suite hits an HTTPS endpoint with a valid cert but we keep
# verify=False to match existing lbfs tests; silence the warning spam.
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


# 3 minutes to cover: forwarder 30s x ~2 cycles + base auto-provision ~30s
DERIVE_TIMEOUT = 180


def _lbfs_put(endpoint, api_key, path, data: bytes):
    r = requests.post(
        f"{endpoint}/api/v1/lbfs/files/put",
        json={"path": path, "data_base64": base64.b64encode(data).decode()},
        headers={"Authorization": f"Bearer {api_key}"},
        verify=False,
        timeout=60,
    )
    r.raise_for_status()
    return r.json()


def _lbfs_delete(endpoint, api_key, path):
    r = requests.post(
        f"{endpoint}/api/v1/lbfs/files/delete",
        json={"path": path},
        headers={"Authorization": f"Bearer {api_key}"},
        verify=False,
        timeout=60,
    )
    r.raise_for_status()


def _memory_target(endpoint, api_key):
    r = requests.get(
        f"{endpoint}/api/v1/lbfs/memory-target",
        headers={"Authorization": f"Bearer {api_key}"},
        verify=False,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


def _memory_recall(endpoint, api_key, base_id, query, top_k=3):
    """POST /memory/bases/{id}/recall — endpoint is nested under the base id."""
    r = requests.post(
        f"{endpoint}/api/v1/memory/bases/{base_id}/recall",
        json={"query": query, "top_k": top_k},
        headers={"Authorization": f"Bearer {api_key}"},
        verify=False,
        timeout=60,
    )
    r.raise_for_status()
    return r.json()


def _query_memories(endpoint, api_key, base_id, source_path):
    """Direct memories list filtered by source_path for deterministic assertions."""
    r = requests.get(
        f"{endpoint}/api/v1/memory/bases/{base_id}/memories",
        params={"limit": 100},
        headers={"Authorization": f"Bearer {api_key}"},
        verify=False,
        timeout=60,
    )
    r.raise_for_status()
    payload = r.json()
    # memory-svc may return {"memories": [...]} or a bare list — handle both.
    mems = payload.get("memories", payload) if isinstance(payload, dict) else payload
    return [
        m for m in mems
        if (m.get("metadata") or {}).get("source_path") == source_path
    ]


def _warm_up_lbfs(endpoint, api_key):
    """First PUT for a fresh tenant triggers LakebaseFS DB provision (~30s).
    Absorb that here so subsequent tests run on a warm DB."""
    for attempt in range(24):  # 24 x 5s = 120s (provision can take up to 90s)
        try:
            _lbfs_put(endpoint, api_key, "/memory/__warmup__.md", b"warmup")
            return
        except Exception:
            if attempt == 23:
                raise
            time.sleep(5)


def _safe_recall(endpoint, api_key, base_id, query, top_k=3):
    """Recall that tolerates transient 400/404 while base provisioning.
    Returns None on transient error, dict on success."""
    try:
        r = requests.post(
            f"{endpoint}/api/v1/memory/bases/{base_id}/recall",
            json={"query": query, "top_k": top_k},
            headers={"Authorization": f"Bearer {api_key}"},
            verify=False,
            timeout=60,
        )
        if r.status_code >= 500 or r.status_code == 400 or r.status_code == 404:
            return None
        r.raise_for_status()
        return r.json()
    except Exception:
        return None


# --- Test cases ---------------------------------------------------------


def test_phase2_create_derives_memory(e2e_client):
    """PUT feedback_*.md -> recall hits it."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up_lbfs(ep, key)

    path = "/memory/feedback_no_emoji.md"
    body = b"---\ntype: feedback\nname: no_emoji\n---\n\nUser prefers no emoji in output\n"
    _lbfs_put(ep, key, path, body)

    def found_in_recall():
        target = _memory_target(ep, key)
        if not target.get("base_id"):
            return False
        r = _safe_recall(ep, key, target["base_id"], "emoji")
        if r is None:
            return False  # base not yet ready
        return any(
            "no emoji" in (m.get("content") or "").lower()
            for m in r.get("memories", [])
        )

    assert poll_until(
        found_in_recall, lambda x: x,
        timeout=DERIVE_TIMEOUT, interval=10,
    )


def test_phase2_update_refreshes_etag(e2e_client):
    """Updating file content produces a new memory row with new source_etag."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up_lbfs(ep, key)

    path = "/memory/feedback_edit_me.md"
    v1 = b"---\ntype: feedback\n---\n\nversion 1 content\n"
    v2 = b"---\ntype: feedback\n---\n\nversion 2 content\n"

    _lbfs_put(ep, key, path, v1)
    target = poll_until(
        lambda: _memory_target(ep, key),
        lambda t: t.get("base_id"),
        timeout=DERIVE_TIMEOUT, interval=10,
    )
    base_id = target["base_id"]

    def v1_present():
        rows = _query_memories(ep, key, base_id, path)
        return rows and any("version 1" in (r.get("content") or "") for r in rows)
    poll_until(v1_present, lambda x: x, timeout=DERIVE_TIMEOUT, interval=10)

    _lbfs_put(ep, key, path, v2)

    def v2_present():
        rows = _query_memories(ep, key, base_id, path)
        return rows and any("version 2" in (r.get("content") or "") for r in rows)
    poll_until(v2_present, lambda x: x, timeout=DERIVE_TIMEOUT, interval=10)


def test_phase2_delete_removes_memory(e2e_client):
    """Deleting LakebaseFS file removes corresponding memory row."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up_lbfs(ep, key)

    path = "/memory/feedback_to_delete.md"
    body = b"---\ntype: feedback\n---\n\nwill be deleted\n"
    _lbfs_put(ep, key, path, body)

    target = poll_until(
        lambda: _memory_target(ep, key),
        lambda t: t.get("base_id"),
        timeout=DERIVE_TIMEOUT, interval=10,
    )
    base_id = target["base_id"]
    poll_until(
        lambda: _query_memories(ep, key, base_id, path),
        lambda rows: len(rows) == 1,
        timeout=DERIVE_TIMEOUT, interval=10,
    )

    _lbfs_delete(ep, key, path)
    poll_until(
        lambda: _query_memories(ep, key, base_id, path),
        lambda rows: len(rows) == 0,
        timeout=DERIVE_TIMEOUT, interval=10,
    )


def test_phase2_memory_md_is_not_derived(e2e_client):
    """MEMORY.md is a view file and MUST NOT be derived into memories."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up_lbfs(ep, key)

    # First create any other file so the target base exists.
    _lbfs_put(
        ep, key, "/memory/feedback_any.md",
        b"---\ntype: feedback\n---\nany content\n",
    )
    target = poll_until(
        lambda: _memory_target(ep, key),
        lambda t: t.get("base_id"),
        timeout=DERIVE_TIMEOUT, interval=10,
    )
    base_id = target["base_id"]

    # Now put MEMORY.md.
    memory_md_path = "/memory/MEMORY.md"
    _lbfs_put(ep, key, memory_md_path, b"# Index\n- entry 1\n")
    # Give forwarder one full cycle to process.
    time.sleep(60)

    rows = _query_memories(ep, key, base_id, memory_md_path)
    assert rows == [], f"MEMORY.md should not be derived, got: {rows}"


def test_phase2_projects_memory_is_derived(e2e_client):
    """/projects/<id>/memory/*.md also matches whitelist."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up_lbfs(ep, key)

    path = "/projects/test-proj/memory/project_my_decision.md"
    body = b"---\ntype: project\n---\n\nproject decision content\n"
    _lbfs_put(ep, key, path, body)

    target = poll_until(
        lambda: _memory_target(ep, key),
        lambda t: t.get("base_id"),
        timeout=DERIVE_TIMEOUT, interval=10,
    )
    base_id = target["base_id"]

    poll_until(
        lambda: _query_memories(ep, key, base_id, path),
        lambda rows: len(rows) == 1,
        timeout=DERIVE_TIMEOUT, interval=10,
    )


def test_phase2_switch_target_routes_new_events(e2e_client):
    """When user switches target base, new events go to the new base;
    old base retains previously derived entries (no automatic migration)."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up_lbfs(ep, key)

    # Seed one file -> auto-created base.
    _lbfs_put(
        ep, key, "/memory/feedback_before.md",
        b"---\ntype: feedback\n---\nbefore switch\n",
    )
    auto_target = poll_until(
        lambda: _memory_target(ep, key),
        lambda t: t.get("base_id"),
        timeout=DERIVE_TIMEOUT, interval=10,
    )
    auto_base_id = auto_target["base_id"]
    poll_until(
        lambda: _query_memories(ep, key, auto_base_id, "/memory/feedback_before.md"),
        lambda rows: len(rows) == 1,
        timeout=DERIVE_TIMEOUT, interval=10,
    )

    # Create a second memory base manually + switch target.
    new_base_resp = requests.post(
        f"{ep}/api/v1/memory/bases",
        json={"name": "manual-target", "type": "BUILTIN"},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=60,
    )
    new_base_resp.raise_for_status()
    new_base = new_base_resp.json()
    new_base_id = new_base["id"]

    # Wait for the new base to be READY (Python memory-svc needs to provision).
    poll_until(
        lambda: requests.get(
            f"{ep}/api/v1/memory/bases/{new_base_id}",
            headers={"Authorization": f"Bearer {key}"},
            verify=False, timeout=30,
        ).json(),
        lambda mb: mb.get("status") == "READY",
        timeout=120, interval=5,
    )

    # Switch target.
    switch = requests.post(
        f"{ep}/api/v1/lbfs/memory-target",
        json={"base_id": new_base_id},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=30,
    )
    switch.raise_for_status()

    # Put a new file -> should land in the new base.
    _lbfs_put(
        ep, key, "/memory/feedback_after.md",
        b"---\ntype: feedback\n---\nafter switch\n",
    )
    poll_until(
        lambda: _query_memories(ep, key, new_base_id, "/memory/feedback_after.md"),
        lambda rows: len(rows) == 1,
        timeout=DERIVE_TIMEOUT, interval=10,
    )

    # Old file remains in old base (no auto-migration per spec).
    old_rows = _query_memories(ep, key, auto_base_id, "/memory/feedback_before.md")
    assert len(old_rows) == 1, "no automatic migration — old entries stay"
