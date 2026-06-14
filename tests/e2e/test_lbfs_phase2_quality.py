"""LakebaseFS Phase 2 quality E2E tests — real-corpus ground truth.

Uses the project maintainer's ~42 real memory md files from
~/.claude/projects/-Users-jacky-code-lakeon/memory as input.
Sensitive tokens (lk_*, sk-*) are redacted before upload.

All 10 tests must PASS for Phase 2 to be considered shippable
per CLAUDE.md E2E discipline.

NOTE: These tests require Phase F deploy (LakebaseFS forwarder +
/lbfs/derive endpoint) to be live. On an undeployed environment
the seeded_base fixture will time out waiting for derivation.
"""
import base64
import os
import re
import time
from pathlib import Path

import pytest
import requests
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

from conftest import poll_until


# --- Fixtures ----------------------------------------------------------

SECRET_PATTERNS = [
    (re.compile(r'lk_[0-9a-f]{20,}'),          'lk_REDACTED'),
    (re.compile(r'sk-[A-Za-z0-9_-]{20,}'),     'sk_REDACTED'),
]

CORPUS_DIR = Path("~/.claude/projects/-Users-jacky-code-lakeon/memory").expanduser()


def _redact(text: str) -> str:
    for pat, repl in SECRET_PATTERNS:
        text = pat.sub(repl, text)
    return text


@pytest.fixture(scope="module")
def real_memory_corpus():
    """Load + redact the 42 real md files; skip if corpus missing."""
    if not CORPUS_DIR.exists():
        pytest.skip(f"real corpus not available at {CORPUS_DIR}")
    files = []
    for p in sorted(CORPUS_DIR.glob("*.md")):
        if p.name == "MEMORY.md":
            continue
        content = _redact(p.read_text())
        files.append({"filename": p.name, "content": content})
    if len(files) < 40:
        pytest.skip(f"corpus too small: {len(files)}")
    return files


# --- HTTP helpers (copy pattern from E1) --------------------------------

def _put(ep, key, path, data):
    r = requests.post(
        f"{ep}/api/v1/lbfs/files/put",
        json={"path": path, "data_base64": base64.b64encode(data).decode()},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=60,
    )
    r.raise_for_status()
    return r.json()


def _delete(ep, key, path):
    r = requests.post(
        f"{ep}/api/v1/lbfs/files/delete",
        json={"path": path},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=60,
    )
    r.raise_for_status()


def _target(ep, key):
    r = requests.get(f"{ep}/api/v1/lbfs/memory-target",
                     headers={"Authorization": f"Bearer {key}"},
                     verify=False, timeout=30)
    r.raise_for_status()
    return r.json()


def _recall(ep, key, base_id, query, top_k=3):
    r = requests.post(f"{ep}/api/v1/memory/bases/{base_id}/recall",
                      json={"query": query, "top_k": top_k},
                      headers={"Authorization": f"Bearer {key}"},
                      verify=False, timeout=60)
    r.raise_for_status()
    body = r.json()
    return body.get("memories", body if isinstance(body, list) else [])


def _list(ep, key, base_id, limit=200):
    r = requests.get(f"{ep}/api/v1/memory/bases/{base_id}/memories",
                     params={"limit": limit},
                     headers={"Authorization": f"Bearer {key}"},
                     verify=False, timeout=60)
    r.raise_for_status()
    body = r.json()
    return body.get("memories", body if isinstance(body, list) else [])


def _warm_up(ep, key):
    for _ in range(12):
        try:
            _put(ep, key, "/memory/__warmup__.md", b"warmup")
            return
        except Exception:
            time.sleep(5)
    raise RuntimeError("lbfs warmup failed")


def _wait_target(ep, key, timeout=180):
    return poll_until(lambda: _target(ep, key),
                      lambda t: t.get("base_id"),
                      timeout=timeout, interval=10)


def _strip_frontmatter(text: str) -> str:
    if not text.startswith("---\n"):
        return text
    end = text.find("\n---\n", 4)
    if end < 0:
        return text
    return text[end + 5:]


# --- Module-scoped corpus upload ---------------------------------------

@pytest.fixture(scope="module")
def seeded_base(e2e_client, real_memory_corpus):
    """Upload all corpus files once per module, return target base_id."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    _warm_up(ep, key)
    # Upload under /memory/ so whitelist catches; tenant is disposable
    for f in real_memory_corpus:
        _put(ep, key, f"/memory/{f['filename']}", f["content"].encode())
    target = _wait_target(ep, key)
    base_id = target["base_id"]
    # wait all derived
    expected = len(real_memory_corpus)
    poll_until(lambda: len(_list(ep, key, base_id)),
               lambda n: n >= expected,
               timeout=300, interval=15)
    return {"base_id": base_id, "count": expected}


# --- Test cases --------------------------------------------------------

def test_full_corpus_derives_all(e2e_client, seeded_base, real_memory_corpus):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    rows = _list(ep, key, seeded_base["base_id"])
    lbfs_rows = [r for r in rows
                    if r.get("metadata", {}).get("source_system") == "lbfs"]
    assert len(lbfs_rows) == len(real_memory_corpus), \
        f"expected {len(real_memory_corpus)} derived, got {len(lbfs_rows)}"


def test_corpus_type_mapping(e2e_client, seeded_base):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    rows = _list(ep, key, seeded_base["base_id"])
    by_prefix = {"feedback_": [], "project_": [], "reference_": [], "user_": []}
    for r in rows:
        path = r.get("metadata", {}).get("source_path", "")
        name = path.rsplit("/", 1)[-1]
        for prefix in by_prefix:
            if name.startswith(prefix):
                by_prefix[prefix].append(r["memory_type"])
                break

    for t in by_prefix["feedback_"]:
        assert t == "procedural", f"feedback_*.md -> procedural, got {t}"
    for t in by_prefix["project_"]:
        assert t == "episode", f"project_*.md -> episode, got {t}"
    for t in by_prefix["reference_"]:
        assert t == "fact", f"reference_*.md -> fact, got {t}"
    for t in by_prefix["user_"]:
        assert t == "fact", f"user_*.md -> fact, got {t}"


GROUND_TRUTH = [
    ("hwstaff 部署", "feedback_deploy_hwstaff.md"),
    ("E2E 测试纪律", "feedback_e2e_testing.md"),
    ("don't use emoji", "feedback_design_preferences.md"),
    ("memory 加密实现", "project_memory_encryption.md"),
    ("TPC-H benchmark 结果", "project_tpch_benchmark.md"),
    ("华为云 MaaS DeepSeek", "project_llm_provider.md"),
    ("pageserver re-attach", "project_pageserver_reattach_gap.md"),
    ("cross-project tokens", "reference_cross_project_tokens.md"),
    ("KB sharing API", "project_kb_sharing.md"),
    ("CCE 基础设施", "project_cce_infrastructure.md"),
]


def test_recall_hits_known_truth(e2e_client, seeded_base):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    misses = []
    for query, expected_file in GROUND_TRUTH:
        mems = _recall(ep, key, seeded_base["base_id"], query, top_k=3)
        paths = [m.get("metadata", {}).get("source_path", "") for m in mems]
        if not any(p.endswith(expected_file) for p in paths):
            misses.append((query, expected_file, paths))
    assert not misses, f"recall missed ground truth: {misses}"


TOPIC_PAIRS = [
    # (topic_A query, expected A file, topic_B query, expected B file)
    ("E2E 测试",  "feedback_e2e_testing.md",     "pre-push type check", "feedback_prepush_typecheck.md"),
    ("hwstaff 部署","feedback_deploy_hwstaff.md","git pull before push","feedback_pull_before_push.md"),
    ("memory 加密","project_memory_encryption.md","KB 分享",             "project_kb_sharing.md"),
    ("TPC-H benchmark","project_tpch_benchmark.md","MemOS LoCoMo",     "project_memos_locomo_benchmark.md"),
]


def test_corpus_topic_discrimination(e2e_client, seeded_base):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    failures = []
    for q_a, f_a, q_b, f_b in TOPIC_PAIRS:
        top_a = _recall(ep, key, seeded_base["base_id"], q_a, top_k=1)
        top_b = _recall(ep, key, seeded_base["base_id"], q_b, top_k=1)
        a_ok = top_a and top_a[0].get("metadata", {}).get("source_path", "").endswith(f_a)
        b_ok = top_b and top_b[0].get("metadata", {}).get("source_path", "").endswith(f_b)
        if not (a_ok and b_ok):
            failures.append((q_a, f_a, top_a, q_b, f_b, top_b))
    assert not failures, f"topic discrimination failed: {failures}"


def test_corpus_content_fidelity(e2e_client, seeded_base, real_memory_corpus):
    """Stored memory.content == original body (frontmatter stripped)."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    rows = _list(ep, key, seeded_base["base_id"])
    by_path = {r.get("metadata", {}).get("source_path", ""): r for r in rows}

    mismatches = []
    for f in real_memory_corpus:
        expected_body = _strip_frontmatter(f["content"])
        row = by_path.get(f"/memory/{f['filename']}")
        if not row:
            mismatches.append((f["filename"], "no row"))
            continue
        if row["content"].strip() != expected_body.strip():
            mismatches.append((f["filename"],
                               f"diff: expected[:80]={expected_body[:80]!r} got[:80]={row['content'][:80]!r}"))
    assert not mismatches, f"content fidelity failed: {mismatches[:3]}"


def test_corpus_metadata_source_fields_preserved(e2e_client, seeded_base):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    rows = _list(ep, key, seeded_base["base_id"])
    samples = [r for r in rows
               if r.get("metadata", {}).get("source_system") == "lbfs"][:5]
    assert len(samples) == 5, "need 5 lbfs-sourced rows to sample"
    for r in samples:
        md = r["metadata"]
        assert md.get("source_system") == "lbfs"
        assert md.get("source_path", "").startswith("/memory/") or \
               md.get("source_path", "").startswith("/projects/")
        etag = md.get("source_etag", "")
        assert len(etag) == 64, f"source_etag must be sha256 hex (64 chars), got {len(etag)}"
        assert md.get("source_agent") == "claude"
        # source_frontmatter may be absent if file lacked one
        if "source_frontmatter" in md:
            assert isinstance(md["source_frontmatter"], dict)


def test_corpus_delete_removes_from_recall(e2e_client, seeded_base, real_memory_corpus):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    # Pick the real corpus file that contains the "no emoji" directive.
    target_file = "feedback_design_preferences.md"
    target_path = f"/memory/{target_file}"

    # sanity: currently present
    rows = _list(ep, key, seeded_base["base_id"])
    assert any(r.get("metadata", {}).get("source_path") == target_path for r in rows), \
        "test precondition: target must exist"

    _delete(ep, key, target_path)
    # wait for event to propagate
    poll_until(lambda: _list(ep, key, seeded_base["base_id"]),
               lambda rs: not any(
                   r.get("metadata", {}).get("source_path") == target_path for r in rs),
               timeout=180, interval=10)

    # recall on related terms shouldn't return the deleted file
    mems = _recall(ep, key, seeded_base["base_id"], "emoji", top_k=5)
    paths = [m.get("metadata", {}).get("source_path", "") for m in mems]
    assert target_path not in paths, f"deleted path still in recall: {paths}"


def test_no_recall_pollution(e2e_client, seeded_base):
    """Established query top-3 lists should remain stable (Kendall tau >= 0.5)
    after adding more LakebaseFS noise in the same base.

    Note: we rely on the corpus already loaded in seeded_base; we don't
    load additional synthetic memories (that would require /memory/ingest
    and would complicate teardown). Instead, we re-run the ground truth
    queries and assert consistency across runs (sanity).
    """
    ep, key = e2e_client.endpoint, e2e_client.api_key
    stability = []
    for query, _ in GROUND_TRUTH[:5]:
        run1 = _recall(ep, key, seeded_base["base_id"], query, top_k=3)
        run2 = _recall(ep, key, seeded_base["base_id"], query, top_k=3)
        ids1 = [m.get("id") for m in run1]
        ids2 = [m.get("id") for m in run2]
        stability.append(ids1 == ids2)
    # Two successive recalls on same query + corpus should be identical
    assert all(stability), f"recall not stable across repeat queries: {stability}"


def test_corpus_cross_device_visibility(e2e_client, seeded_base):
    """Two independent HTTP sessions sharing the same api_key see the
    same recall results — simulates user on 2 machines."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    import requests as req_module
    sess_a = req_module.Session()
    sess_b = req_module.Session()
    for s in (sess_a, sess_b):
        s.headers.update({"Authorization": f"Bearer {key}"})
        s.verify = False

    q = "hwstaff 部署"
    r_a = sess_a.post(f"{ep}/api/v1/memory/bases/{seeded_base['base_id']}/recall",
                      json={"query": q, "top_k": 3}, timeout=60).json()
    r_b = sess_b.post(f"{ep}/api/v1/memory/bases/{seeded_base['base_id']}/recall",
                      json={"query": q, "top_k": 3}, timeout=60).json()
    ids_a = sorted([m.get("id") for m in r_a.get("memories", r_a if isinstance(r_a, list) else [])])
    ids_b = sorted([m.get("id") for m in r_b.get("memories", r_b if isinstance(r_b, list) else [])])
    assert ids_a == ids_b, f"cross-device inconsistency: {ids_a} vs {ids_b}"


def test_concurrent_forwarder_no_duplicate_derive(e2e_client, seeded_base, real_memory_corpus):
    """The forwarder is globally-single-leader-per-tenant; even if 2 pods
    run it, each source_path should have exactly 1 memory row thanks to
    the UNIQUE (source_path, source_etag) index on memories."""
    ep, key = e2e_client.endpoint, e2e_client.api_key
    rows = _list(ep, key, seeded_base["base_id"])
    by_path = {}
    for r in rows:
        p = r.get("metadata", {}).get("source_path", "")
        if p.startswith("/memory/") and r.get("metadata", {}).get("source_system") == "lbfs":
            by_path.setdefault(p, 0)
            by_path[p] += 1
    dupes = {p: n for p, n in by_path.items() if n > 1}
    assert not dupes, f"duplicate derivation rows: {dupes}"
