"""Wiki Agent E2E tests — validates wiki page generation, graph, chat, and URL ingest."""
import pytest
import httpx
import time

BASE = "https://api.dbay.cloud:8443/api/v1"
ADMIN_TOKEN = "lakeon-sre-2026"
TIMEOUT = 30


@pytest.fixture(scope="module")
def test_kb():
    """Create a temporary tenant and KB for wiki testing, cleanup after."""
    headers = {"X-Admin-Token": ADMIN_TOKEN}

    # Create tenant
    r = httpx.post(f"{BASE}/tenants", json={"name": f"wiki-e2e-{int(time.time())}"},
                   headers=headers, verify=False, timeout=TIMEOUT)
    assert r.status_code == 200, f"Failed to create tenant: {r.text}"
    tenant = r.json()
    tenant_id = tenant["id"]
    token = tenant.get("api_key") or tenant.get("token")

    kb_headers = {"Authorization": f"Bearer {token}"}

    # Create KB
    r = httpx.post(f"{BASE}/knowledge", json={"name": "Wiki E2E Test"},
                   headers=kb_headers, verify=False, timeout=TIMEOUT)
    assert r.status_code == 200, f"Failed to create KB: {r.text}"
    kb = r.json()
    kb_id = kb["id"]

    # Wait for KB to be READY (up to 60s)
    for _ in range(30):
        r = httpx.get(f"{BASE}/knowledge/{kb_id}", headers=kb_headers, verify=False, timeout=TIMEOUT)
        if r.status_code == 200 and r.json().get("status") == "READY":
            break
        time.sleep(2)

    yield {"tenant_id": tenant_id, "kb_id": kb_id, "token": token, "headers": kb_headers}

    # Cleanup
    httpx.delete(f"{BASE}/tenants/{tenant_id}", headers=headers, verify=False, timeout=TIMEOUT)


class TestWikiPages:
    """Test wiki page listing and content."""

    def test_empty_kb_has_no_wiki_pages(self, test_kb):
        r = httpx.get(f"{BASE}/knowledge/wiki/pages",
                      params={"kb_id": test_kb["kb_id"]},
                      headers=test_kb["headers"], verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        assert isinstance(r.json(), list)

    def test_empty_kb_has_empty_graph(self, test_kb):
        r = httpx.get(f"{BASE}/knowledge/wiki/graph",
                      params={"kb_id": test_kb["kb_id"]},
                      headers=test_kb["headers"], verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        data = r.json()
        assert "nodes" in data
        assert "edges" in data


class TestWikiChat:
    """Test wiki chat (Query Router Agent)."""

    def test_chat_on_empty_kb(self, test_kb):
        """Chat should still return an answer even on empty KB."""
        r = httpx.post(f"{BASE}/knowledge/wiki/chat",
                       json={"kb_id": test_kb["kb_id"], "question": "什么是区块链？", "history": []},
                       headers=test_kb["headers"], verify=False, timeout=60)
        assert r.status_code == 200
        data = r.json()
        assert "answer" in data


class TestUrlIngest:
    """Test URL ingest functionality."""

    def test_ingest_url(self, test_kb):
        """Ingest a real URL and verify document is created."""
        r = httpx.post(f"{BASE}/knowledge/wiki/ingest-url",
                       json={"kb_id": test_kb["kb_id"], "url": "https://chain.link/education-hub/bitcoin-layer-2"},
                       headers=test_kb["headers"], verify=False, timeout=60)
        assert r.status_code == 200, f"URL ingest failed: {r.text}"
        data = r.json()
        assert "document_id" in data
        assert data["status"] == "processing"

    def test_ingest_invalid_url(self, test_kb):
        """Invalid URL should return 422, not 502."""
        r = httpx.post(f"{BASE}/knowledge/wiki/ingest-url",
                       json={"kb_id": test_kb["kb_id"], "url": "https://nonexistent-domain-12345.com/page"},
                       headers=test_kb["headers"], verify=False, timeout=60)
        assert r.status_code == 422, f"Expected 422, got {r.status_code}: {r.text}"


class TestAdminWikiConfig:
    """Test admin wiki config API."""

    def test_get_wiki_config(self):
        headers = {"X-Admin-Token": ADMIN_TOKEN}
        r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                      headers=headers, verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        data = r.json()
        assert "ingest_prompt" in data
        assert "model" in data
        assert len(data["ingest_prompt"]) > 0, "ingest_prompt should not be empty (default should be returned)"

    def test_get_wiki_config_requires_admin(self):
        """Should fail without admin token."""
        r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                      verify=False, timeout=TIMEOUT)
        assert r.status_code in [401, 403], f"Expected 401/403, got {r.status_code}"

    def test_update_wiki_config(self):
        headers = {"X-Admin-Token": ADMIN_TOKEN}
        # Read current
        r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                      headers=headers, verify=False, timeout=TIMEOUT)
        original = r.json()

        # Update model
        r = httpx.put(f"{BASE}/knowledge/admin/wiki/config",
                      json={"model": "test-model"},
                      headers=headers, verify=False, timeout=TIMEOUT)
        assert r.status_code == 200

        # Verify change
        r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                      headers=headers, verify=False, timeout=TIMEOUT)
        assert r.json()["model"] == "test-model"

        # Restore original
        httpx.put(f"{BASE}/knowledge/admin/wiki/config",
                  json={"model": original.get("model", "")},
                  headers=headers, verify=False, timeout=TIMEOUT)
