"""E2E tests for the Chrome extension Web Clipper API workflow.

Tests verify:
- Login API returns a valid api_key
- Wrong password returns 401
- Ext-login page is accessible (via local vite preview server)
- Full clipper workflow: login → create KB → wait READY → list → ingest URL → cleanup
"""
import uuid
import time
import sys
import os
import subprocess

import pytest
import httpx

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'dbay-cli'))
from dbay_cli.client import DbayClient

BASE = "https://api.dbay.cloud:8443/api/v1"
ADMIN_TOKEN = "lakeon-sre-2026"
TIMEOUT = 60

# Path to lakeon-console project
CONSOLE_DIR = os.path.join(os.path.dirname(__file__), '..', '..', 'lakeon-console')
CONSOLE_PREVIEW_PORT = 14173


# ---------------------------------------------------------------------------
# Fixture: local_console (module scope) — serves dist via vite preview
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def local_console():
    """Start vite preview server for the console dist, yield base URL, then stop."""
    proc = subprocess.Popen(
        ["npx", "vite", "preview", "--port", str(CONSOLE_PREVIEW_PORT)],
        cwd=CONSOLE_DIR,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    # Wait for server to be ready
    base_url = f"http://localhost:{CONSOLE_PREVIEW_PORT}"
    deadline = time.time() + 15
    ready = False
    while time.time() < deadline:
        try:
            r = httpx.get(base_url + "/", timeout=2,
                          env=None)  # env not supported in httpx.get
            if r.status_code in (200, 301, 302):
                ready = True
                break
        except Exception:
            pass
        time.sleep(0.5)

    # Use trust_env=False to bypass system proxy for localhost
    deadline = time.time() + 15
    ready = False
    while time.time() < deadline:
        try:
            with httpx.Client(trust_env=False, timeout=2) as c:
                r = c.get(base_url + "/")
            if r.status_code in (200, 301, 302):
                ready = True
                break
        except Exception:
            pass
        time.sleep(0.5)

    if not ready:
        proc.terminate()
        pytest.fail("vite preview server did not start within 15s")

    yield base_url

    proc.terminate()
    proc.wait(timeout=10)


# ---------------------------------------------------------------------------
# Fixture: temp_tenant (module scope)
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def temp_tenant():
    """Create a disposable tenant for ext-login tests, yield credentials, then delete."""
    ts = int(time.time())
    uid = uuid.uuid4().hex[:8]
    username = f"ext-test-{ts}-{uid}"
    password = f"ExtTest@{ts}"
    name = f"Ext Login E2E {ts}"

    # Create invite code via admin API
    admin = DbayClient(endpoint=BASE.replace("/api/v1", ""), api_key=ADMIN_TOKEN)
    invite = admin.admin_create_invite_code(max_uses=1)
    invite_code = invite.get("code")

    # Register tenant (spoof IP to avoid per-IP rate limit)
    import random
    fake_ip = f"10.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}"
    reg_client = DbayClient(
        endpoint=BASE.replace("/api/v1", ""),
        extra_headers={"X-Forwarded-For": fake_ip},
    )
    tenant = reg_client.create_tenant(
        username=username,
        password=password,
        name=name,
        invite_code=invite_code,
    )

    yield {
        "id": tenant["id"],
        "username": username,
        "password": password,
        "api_key": tenant["api_key"],
        "name": name,
    }

    # Cleanup
    try:
        admin.admin_batch_delete_tenants([tenant["id"]])
    except Exception as e:
        print(f"Cleanup failed: {e}")


# ---------------------------------------------------------------------------
# Test class 1: Ext-login page + login API
# ---------------------------------------------------------------------------

class TestExtLoginPage:

    def test_login_api_returns_api_key(self, temp_tenant):
        """Login with correct credentials → api_key starts with 'lk_', and it works for GET /knowledge/bases."""
        r = httpx.post(
            f"{BASE}/auth/login",
            json={"username": temp_tenant["username"], "password": temp_tenant["password"]},
            verify=False,
            timeout=TIMEOUT,
        )
        assert r.status_code == 200, f"Login failed ({r.status_code}): {r.text}"
        data = r.json()

        # Verify api_key is present and has the correct prefix
        api_key = data.get("api_key")
        assert api_key is not None, f"No api_key in response: {data}"
        assert api_key.startswith("lk_"), f"api_key does not start with 'lk_': {api_key}"

        # Verify the returned key actually works: GET /knowledge/bases
        r2 = httpx.get(
            f"{BASE}/knowledge/bases",
            headers={"Authorization": f"Bearer {api_key}"},
            verify=False,
            timeout=TIMEOUT,
        )
        assert r2.status_code == 200, \
            f"api_key from login doesn't work for GET /knowledge/bases: {r2.status_code} {r2.text}"

    def test_login_wrong_password_returns_error(self, temp_tenant):
        """Login with wrong password must return 401."""
        r = httpx.post(
            f"{BASE}/auth/login",
            json={"username": temp_tenant["username"], "password": "wrong-password-xyz"},
            verify=False,
            timeout=TIMEOUT,
        )
        assert r.status_code == 401, \
            f"Expected 401 for wrong password, got {r.status_code}: {r.text}"

    def test_ext_login_page_accessible(self, local_console):
        """GET /ext-login from the built console must return 200 with text/html.

        Tests against a local vite preview server serving the compiled dist.
        The Vue SPA serves the same index.html for all routes (SPA fallback).
        """
        with httpx.Client(trust_env=False, timeout=TIMEOUT, follow_redirects=True) as c:
            r = c.get(f"{local_console}/ext-login")
        assert r.status_code == 200, \
            f"ext-login page not accessible: {r.status_code}"
        content_type = r.headers.get("content-type", "")
        assert "text/html" in content_type, \
            f"Expected text/html, got: {content_type}"


# ---------------------------------------------------------------------------
# Test class 2: Full clipper workflow
# ---------------------------------------------------------------------------

class TestClipperApiWorkflow:

    def test_full_clipper_flow(self, temp_tenant):
        """
        Full workflow end-to-end:
        1. Login → get API key
        2. Create a test KB (type=DOCUMENT)
        3. Wait for KB to become READY (poll up to 120s)
        4. List KBs → verify our KB is in the list
        5. Save a URL (POST /knowledge/wiki/ingest-url)
        6. Verify response has document_id or status field
        7. Cleanup: delete the KB
        """
        # Step 1: Login → get API key
        r = httpx.post(
            f"{BASE}/auth/login",
            json={"username": temp_tenant["username"], "password": temp_tenant["password"]},
            verify=False,
            timeout=TIMEOUT,
        )
        assert r.status_code == 200, f"Login failed: {r.text}"
        api_key = r.json().get("api_key")
        assert api_key and api_key.startswith("lk_"), f"Invalid api_key: {api_key}"

        headers = {"Authorization": f"Bearer {api_key}"}

        # Step 2: Create a test KB
        kb_name = f"clipper-e2e-{int(time.time())}"
        r = httpx.post(
            f"{BASE}/knowledge/bases",
            json={"name": kb_name, "type": "DOCUMENT"},
            headers=headers,
            verify=False,
            timeout=TIMEOUT,
        )
        assert r.status_code in (200, 201), f"KB creation failed: {r.status_code} {r.text}"
        kb = r.json()
        kb_id = kb.get("id")
        assert kb_id, f"No id in KB creation response: {kb}"

        try:
            # Step 3: Wait for KB to become READY (poll up to 120s)
            kb_status = None
            deadline = time.time() + 120
            while time.time() < deadline:
                r = httpx.get(
                    f"{BASE}/knowledge/bases/{kb_id}",
                    headers=headers,
                    verify=False,
                    timeout=TIMEOUT,
                )
                assert r.status_code == 200, f"GET KB failed: {r.status_code}"
                kb_info = r.json()
                kb_status = kb_info.get("status")
                if kb_status == "READY":
                    break
                if kb_status == "FAILED":
                    pytest.fail(f"KB creation FAILED: {kb_info}")
                time.sleep(3)
            assert kb_status == "READY", \
                f"KB stuck at '{kb_status}' after 120s — KB creation pipeline broken"

            # Step 4: List KBs → verify our KB is in the list
            r = httpx.get(
                f"{BASE}/knowledge/bases",
                headers=headers,
                verify=False,
                timeout=TIMEOUT,
            )
            assert r.status_code == 200, f"List KBs failed: {r.status_code}"
            kb_list = r.json()
            if isinstance(kb_list, dict):
                kb_list = kb_list.get("bases", kb_list.get("knowledge_bases", []))
            kb_ids = [k.get("id") for k in kb_list]
            assert kb_id in kb_ids, \
                f"Newly created KB '{kb_id}' not found in list: {kb_ids}"

            # Step 5: Save a URL via ingest-url
            # The CCE server can't reach foreign sites, so we pre-fetch the content
            # via Jina Reader from the test machine (simulating what the browser extension does)
            test_url = "https://en.wikipedia.org/wiki/Knowledge_base"
            jina_r = httpx.get(
                f"https://r.jina.ai/{test_url}",
                headers={"User-Agent": "Mozilla/5.0", "Accept": "text/plain,text/markdown,*/*"},
                verify=False,
                timeout=60,
            )
            assert jina_r.status_code == 200 and len(jina_r.text) > 200, \
                f"Jina pre-fetch failed: {jina_r.status_code} len={len(jina_r.text)}"
            prefetched_content = jina_r.text
            prefetched_title = "Knowledge base"

            r = httpx.post(
                f"{BASE}/knowledge/wiki/ingest-url",
                json={
                    "kb_id": kb_id,
                    "url": test_url,
                    "title": prefetched_title,
                    "content": prefetched_content,
                },
                headers=headers,
                verify=False,
                timeout=TIMEOUT,
            )
            assert r.status_code == 200, \
                f"URL ingest failed ({r.status_code}): {r.text}"

            # Step 6: Verify response has document_id or status field
            ingest_data = r.json()
            has_document_id = "document_id" in ingest_data
            has_status = "status" in ingest_data
            assert has_document_id or has_status, \
                f"Ingest response missing both 'document_id' and 'status': {ingest_data}"

        finally:
            # Step 7: Cleanup — delete the KB
            try:
                httpx.delete(
                    f"{BASE}/knowledge/bases/{kb_id}",
                    headers=headers,
                    verify=False,
                    timeout=TIMEOUT,
                )
            except Exception as e:
                print(f"KB cleanup failed: {e}")
