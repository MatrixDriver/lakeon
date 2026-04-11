"""End-to-end test for the new wiki agent pipeline.

Flow: upload 6 real documents to a fresh KB and assert the agent produces
fewer than 20 wiki pages with no duplicate titles and no failed runs.

Run:
    python3 -m pytest tests/e2e/test_wiki_agent.py -v -s

Env vars:
    DBAY_ENDPOINT    (default: https://api.dbay.cloud:8443)
    DBAY_ADMIN_TOKEN (default: lakeon-sre-2026)
    KB_DOC_DIR       (default: ~/code/kb-doc)
"""
import os
import random
import sys
import time
from pathlib import Path

import httpx
import pytest

# Allow importing dbay_cli without pip install -e (same pattern as test_wiki.py)
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "dbay-cli"))
from dbay_cli.client import DbayClient  # noqa: E402

ENDPOINT = os.environ.get("DBAY_ENDPOINT", "https://api.dbay.cloud:8443")
BASE = f"{ENDPOINT}/api/v1"
ADMIN_TOKEN = os.environ.get("DBAY_ADMIN_TOKEN", "lakeon-sre-2026")
DOC_DIR = Path(os.environ.get("KB_DOC_DIR", str(Path.home() / "code" / "kb-doc")))

# --- Test parameters --------------------------------------------------
N_DOCS = 6
PAGE_COUNT_CEILING = 20
MIN_TOUCHES_PER_DOC = 3
DOC_READY_TIMEOUT = 600           # 10 min per doc for parse+chunk+embed+summarize
WIKI_AGENT_TIMEOUT = 900          # 15 min for agent ingest runs to appear in runlog
QUIESCE_TIMEOUT = 600              # 10 min for last running agent job to finish
HTTP_TIMEOUT = 60

RESERVED_FILENAMES = {"index.md", "log.md", "schema.md"}

# Deterministic selection — 6 smallest well-formed text-heavy docs
# (sized via `du -h` so the test is realistic but not multi-GB).
TEST_FILES = [
    "Novel and Film comparison - Jane Eyre - Author Study.pdf",  # 44K EN literature
    "国庆历史试卷答案.docx",                                       # 100K CN history
    "Time Travel on CarbonData_v1.pdf",                            # 148K EN tech
    "华杯赛几何.pdf",                                              # 460K CN math
    "小学奥数知识点38页.pdf",                                      # 760K CN math
    "QuickInsights-camera-ready-final.pdf",                        # 824K EN research
]


# ─── Fixtures ──────────────────────────────────────────────────


@pytest.fixture(scope="module")
def wiki_agent_kb():
    """Create a throwaway tenant + KB, upload six docs, wait for wiki agent
    ingest to complete for each, then yield the shared state to the tests.

    Teardown deletes the tenant (cascades to KB + docs + run logs).
    """
    docs = _resolve_test_docs()  # fail fast if any are missing

    ts = int(time.time())
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    invite = admin.admin_create_invite_code(max_uses=1)
    fake_ip = f"10.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}"
    reg = DbayClient(endpoint=ENDPOINT, extra_headers={"X-Forwarded-For": fake_ip})
    tenant = reg.create_tenant(
        username=f"e2e-wiki-agent-{ts}",
        password=f"WikiAgent@{ts}",
        name=f"Wiki Agent E2E {ts}",
        invite_code=invite.get("code"),
    )
    client = DbayClient(endpoint=ENDPOINT, api_key=tenant["api_key"])
    headers = {"Authorization": f"Bearer {tenant['api_key']}"}

    # Create KB and wait for READY
    kb = client.create_knowledge_base("Wiki Agent E2E")
    kb_id = kb["id"]
    for _ in range(30):
        info = client.get_knowledge_base(kb_id)
        if info.get("status") == "READY":
            break
        time.sleep(2)
    else:
        admin.admin_batch_delete_tenants([tenant["id"]])
        pytest.fail(f"KB {kb_id} never reached READY")

    # Upload docs via presigned URLs, then trigger batch processing
    uploaded = []  # [{doc_id, filename}]
    for doc_path in docs:
        upload_info = client.batch_get_upload_urls(
            kb_id, [{"filename": doc_path.name}]
        )
        entries = upload_info.get("documents", [])
        assert len(entries) == 1, f"upload URL not returned for {doc_path.name}: {upload_info}"
        doc_id = entries[0]["document_id"]
        upload_url = entries[0]["upload_url"]
        with open(doc_path, "rb") as f:
            r = httpx.put(upload_url, content=f.read(), verify=False, timeout=120)
        assert r.status_code in (200, 201), (
            f"upload failed for {doc_path.name}: {r.status_code} {r.text[:200]}"
        )
        uploaded.append({"doc_id": doc_id, "filename": doc_path.name})
        print(f"  uploaded {doc_path.name} ({doc_path.stat().st_size // 1024}KB) id={doc_id}")

    doc_ids = [u["doc_id"] for u in uploaded]
    client.batch_process_documents(doc_ids)

    # Wait for every doc to reach READY (parse + chunk + embed + summarize)
    pending = set(doc_ids)
    deadline = time.time() + DOC_READY_TIMEOUT
    last_states = {}
    while pending and time.time() < deadline:
        r = httpx.get(
            f"{BASE}/knowledge/documents",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        if r.status_code == 200:
            doc_list = r.json() if isinstance(r.json(), list) else r.json().get("documents", [])
            for d in doc_list:
                if d.get("id") in pending:
                    status = d.get("status", "UNKNOWN")
                    last_states[d["id"]] = status
                    if status == "READY":
                        pending.discard(d["id"])
                    elif status == "FAILED":
                        admin.admin_batch_delete_tenants([tenant["id"]])
                        pytest.fail(
                            f"doc {d.get('filename')} FAILED: {d.get('error')}"
                        )
        if pending:
            time.sleep(5)
    if pending:
        admin.admin_batch_delete_tenants([tenant["id"]])
        pytest.fail(
            f"{len(pending)} docs did not reach READY within {DOC_READY_TIMEOUT}s: "
            + ", ".join(f"{d}={last_states.get(d, '?')}" for d in pending)
        )

    # Wait for wiki agent ingest runs to appear — one per triggered doc
    # WIKI_UPDATE is enqueued asynchronously after summarize, so we poll the
    # run log for an entry with a matching trigger_doc filename.
    expected_filenames = {u["filename"] for u in uploaded}
    seen_success: set[str] = set()
    deadline = time.time() + WIKI_AGENT_TIMEOUT
    while seen_success != expected_filenames and time.time() < deadline:
        r = httpx.get(
            f"{BASE}/knowledge/bases/{kb_id}/wiki/runlog",
            params={"limit": 100},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        if r.status_code == 200:
            logs = r.json()
            for log in logs:
                if (log.get("run_type") or "") != "ingest":
                    continue
                if (log.get("status") or "").lower() != "success":
                    continue
                trig = log.get("trigger_doc") or ""
                for name in expected_filenames:
                    if name in trig or trig in name:
                        seen_success.add(name)
        if seen_success != expected_filenames:
            time.sleep(5)

    missing = expected_filenames - seen_success
    if missing:
        admin.admin_batch_delete_tenants([tenant["id"]])
        pytest.fail(
            f"wiki agent ingest did not complete for {len(missing)} docs within "
            f"{WIKI_AGENT_TIMEOUT}s: {sorted(missing)}"
        )

    # Final quiesce wait — no "running" runs remaining
    deadline = time.time() + QUIESCE_TIMEOUT
    while time.time() < deadline:
        r = httpx.get(
            f"{BASE}/knowledge/bases/{kb_id}/wiki/runlog",
            params={"limit": 50},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        if r.status_code == 200:
            running = [l for l in r.json()
                       if (l.get("status") or "").lower() == "running"]
            if not running:
                break
        time.sleep(5)
    else:
        admin.admin_batch_delete_tenants([tenant["id"]])
        pytest.fail(f"wiki agent did not quiesce within {QUIESCE_TIMEOUT}s")

    yield {
        "tenant_id": tenant["id"],
        "kb_id": kb_id,
        "headers": headers,
        "client": client,
        "uploaded": uploaded,
    }

    # Teardown
    try:
        admin.admin_batch_delete_tenants([tenant["id"]])
    except Exception as e:
        print(f"tenant teardown failed: {e}")


# ─── Helpers ────────────────────────────────────────────────────


def _resolve_test_docs() -> list[Path]:
    """Resolve the deterministic test doc list — fail if any are missing.

    We use a hard-coded filename list (not a glob) so the test is stable
    across kb-doc contents and CI environments.
    """
    if not DOC_DIR.exists():
        pytest.skip(f"Test doc dir {DOC_DIR} missing — populate it before running")
    resolved = []
    missing = []
    for name in TEST_FILES:
        p = DOC_DIR / name
        if p.exists():
            resolved.append(p)
        else:
            missing.append(name)
    if missing:
        pytest.skip(
            f"Missing {len(missing)} test docs in {DOC_DIR}: {missing}. "
            f"Populate kb-doc or adjust TEST_FILES."
        )
    assert len(resolved) == N_DOCS, f"expected {N_DOCS} docs, got {len(resolved)}"
    return resolved


# ─── Tests ──────────────────────────────────────────────────────


class TestWikiAgentPageCount:
    """After ingesting 6 real documents the wiki agent should produce a
    compact, deduplicated wiki — not one page per source chunk."""

    def test_under_twenty_pages(self, wiki_agent_kb):
        """Total wiki pages (excluding reserved) must be < 20."""
        headers = wiki_agent_kb["headers"]
        kb_id = wiki_agent_kb["kb_id"]

        r = httpx.get(
            f"{BASE}/knowledge/wiki/pages",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        r.raise_for_status()
        all_pages = r.json()
        wiki_pages = [
            p for p in all_pages
            if p.get("filename") not in RESERVED_FILENAMES
            and (p.get("doc_type") or p.get("docType") or "wiki") == "wiki"
        ]
        titles = [p.get("title") or p.get("filename", "?") for p in wiki_pages]
        print(f"\nWiki pages produced ({len(wiki_pages)}):")
        for t in titles:
            print(f"  - {t}")

        assert len(wiki_pages) < PAGE_COUNT_CEILING, (
            f"expected < {PAGE_COUNT_CEILING} wiki pages for {N_DOCS} docs, "
            f"got {len(wiki_pages)}: " + ", ".join(titles)
        )

    def test_no_duplicate_titles(self, wiki_agent_kb):
        """The agent must deduplicate wiki page titles, not create N copies."""
        headers = wiki_agent_kb["headers"]
        kb_id = wiki_agent_kb["kb_id"]

        r = httpx.get(
            f"{BASE}/knowledge/wiki/pages",
            params={"kb_id": kb_id},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        r.raise_for_status()
        pages = [
            p for p in r.json()
            if p.get("filename") not in RESERVED_FILENAMES
            and (p.get("doc_type") or p.get("docType") or "wiki") == "wiki"
        ]
        titles = [p.get("title") or p.get("filename", "?") for p in pages]
        seen: set[str] = set()
        dups = []
        for t in titles:
            if t in seen:
                dups.append(t)
            seen.add(t)
        assert not dups, f"duplicate wiki page titles: {dups}"

    def test_schema_page_preserved(self, wiki_agent_kb):
        """schema.md must still exist — the agent cannot delete reserved pages."""
        headers = wiki_agent_kb["headers"]
        kb_id = wiki_agent_kb["kb_id"]

        r = httpx.get(
            f"{BASE}/knowledge/bases/{kb_id}/wiki/page-by-title",
            params={"title": "KB Schema"},
            headers=headers,
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        assert r.status_code == 200, f"schema lookup failed: {r.status_code} {r.text[:200]}"
        # Fall back to listing in case the seed uses a different title
        if not r.json().get("found"):
            r2 = httpx.get(
                f"{BASE}/knowledge/wiki/pages",
                params={"kb_id": kb_id},
                headers=headers,
                verify=False,
                timeout=HTTP_TIMEOUT,
            )
            r2.raise_for_status()
            filenames = {p.get("filename") for p in r2.json()}
            assert "schema.md" in filenames, (
                f"schema.md not present after ingest. filenames={sorted(filenames)}"
            )


class TestWikiAgentRunLogs:
    """Every doc should drive an ingest run that touches multiple pages and
    completes successfully (not max_rounds_exceeded, not error)."""

    def _fetch_ingest_logs(self, wiki_agent_kb) -> list[dict]:
        r = httpx.get(
            f"{BASE}/knowledge/bases/{wiki_agent_kb['kb_id']}/wiki/runlog",
            params={"limit": 100},
            headers=wiki_agent_kb["headers"],
            verify=False,
            timeout=HTTP_TIMEOUT,
        )
        r.raise_for_status()
        logs = r.json()
        return [l for l in logs if (l.get("run_type") or "") == "ingest"]

    def test_all_ingest_runs_succeeded(self, wiki_agent_kb):
        """No ingest run should have error or non-success status."""
        logs = self._fetch_ingest_logs(wiki_agent_kb)
        non_success = [
            l for l in logs
            if (l.get("status") or "").lower() not in ("success",)
        ]
        assert not non_success, (
            f"{len(non_success)} ingest runs did not succeed: "
            + str([
                {"trigger": l.get("trigger_doc"), "status": l.get("status"),
                 "error": l.get("error_message")}
                for l in non_success
            ])
        )

    def test_each_doc_produced_successful_run(self, wiki_agent_kb):
        """Each uploaded doc should have at least one successful ingest run."""
        logs = self._fetch_ingest_logs(wiki_agent_kb)
        successful = [
            l for l in logs
            if (l.get("status") or "").lower() == "success"
        ]
        assert len(successful) >= N_DOCS, (
            f"expected >= {N_DOCS} successful ingest runs, got {len(successful)}"
        )

        triggered = {l.get("trigger_doc") or "" for l in successful}
        for uploaded in wiki_agent_kb["uploaded"]:
            fname = uploaded["filename"]
            assert any(fname in t or t in fname for t in triggered), (
                f"no successful ingest run found for {fname}. triggered={triggered}"
            )

    def test_each_run_touched_multiple_pages(self, wiki_agent_kb):
        """Each ingest run should have created or updated >= MIN_TOUCHES_PER_DOC
        pages — otherwise the agent is effectively a no-op for that doc."""
        logs = self._fetch_ingest_logs(wiki_agent_kb)
        successful = [
            l for l in logs
            if (l.get("status") or "").lower() == "success"
        ]
        # Group by trigger_doc and keep the latest run per doc so retries
        # don't double-count.
        latest_by_doc: dict[str, dict] = {}
        for log in successful:
            key = log.get("trigger_doc") or log.get("run_id") or log.get("id") or ""
            existing = latest_by_doc.get(key)
            if existing is None or (log.get("created_at") or "") >= (existing.get("created_at") or ""):
                latest_by_doc[key] = log

        assert len(latest_by_doc) >= N_DOCS, (
            f"expected at least {N_DOCS} distinct docs in run log, got "
            f"{len(latest_by_doc)}: {list(latest_by_doc.keys())}"
        )

        for key, log in latest_by_doc.items():
            touches = (
                (log.get("pages_created") or 0)
                + (log.get("pages_updated") or 0)
            )
            assert touches >= MIN_TOUCHES_PER_DOC, (
                f"run for {key!r} touched only {touches} pages "
                f"(expected >= {MIN_TOUCHES_PER_DOC}): {log}"
            )
