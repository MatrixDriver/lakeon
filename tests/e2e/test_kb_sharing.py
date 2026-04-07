"""E2E tests for KB sharing.

Verifies REAL business outcomes of KB sharing:
  - Access control before/after sharing
  - Member read access, upload, wiki, chat
  - Member cannot delete or manage shares
  - Share removal revokes access
  - Unrelated tenants are fully isolated
"""
import pytest
import httpx
import time
import random
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'dbay-cli'))
from dbay_cli.client import DbayClient

BASE = "https://api.dbay.cloud:8443/api/v1"
ADMIN_TOKEN = "lakeon-sre-2026"
ENDPOINT = "https://api.dbay.cloud:8443"

# Small markdown doc to upload as a member
TEST_DOC_CONTENT = """# 量子计算基础

## 量子比特 (Qubit)
量子比特是量子计算的基本单位，与经典比特不同，量子比特可以同时处于0和1的叠加态。
这种特性叫做[[量子叠加]]，是量子计算速度优势的核心来源。

## 量子纠缠
两个量子比特可以形成[[量子纠缠]]，测量其中一个会瞬间影响另一个的状态，
无论两者相距多远。爱因斯坦将此称为"鬼魅般的超距作用"。

## 量子门
量子门是对量子比特进行操作的基本单元，类似于经典计算机中的逻辑门。
常见的量子门包括：Hadamard门、CNOT门和Toffoli门。

## 量子优势
量子计算机在特定问题上（如质因数分解、搜索无序数据库）能够超越经典计算机，
这称为[[量子优势]]或量子霸权。
"""


def _create_tenant(ts_suffix: str):
    """Create a tenant with invite code. Returns (client, tenant_dict, headers).
    tenant_dict has 'username' injected (not returned by API, tracked manually).
    """
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    invite = admin.admin_create_invite_code(max_uses=1)

    username = f"kb-share-{ts_suffix}"
    fake_ip = f"10.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"
    reg = DbayClient(endpoint=ENDPOINT, extra_headers={"X-Forwarded-For": fake_ip})
    tenant = reg.create_tenant(
        username=username,
        password=f"KbShare@{ts_suffix}",
        name=f"KB Share E2E {ts_suffix}",
        invite_code=invite.get("code"),
    )
    # Inject username since create_tenant API response doesn't include it
    tenant["username"] = username
    client = DbayClient(endpoint=ENDPOINT, api_key=tenant["api_key"])
    headers = {"Authorization": f"Bearer {tenant['api_key']}"}
    return client, tenant, headers


def _delete_tenant(tenant_id: str):
    """Delete a tenant via admin API."""
    try:
        admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
        admin.admin_batch_delete_tenants([tenant_id])
    except Exception:
        pass


@pytest.fixture(scope="module")
def setup():
    """Create two tenants (A=owner, B=member), a KB, and wait for READY."""
    ts = int(time.time())

    client_a, tenant_a, headers_a = _create_tenant(f"a-{ts}")
    client_b, tenant_b, headers_b = _create_tenant(f"b-{ts}")

    # Create KB owned by tenant_a
    r = httpx.post(f"{BASE}/knowledge/bases",
                   json={"name": f"Share E2E KB {ts}", "type": "DOCUMENT"},
                   headers=headers_a, verify=False, timeout=60, trust_env=False)
    assert r.status_code in [200, 201], f"KB creation failed: {r.status_code} {r.text}"
    kb_id = r.json()["id"]

    # Wait for KB READY (up to 120s)
    kb_status = "CREATING"
    for _ in range(40):
        r = httpx.get(f"{BASE}/knowledge/bases/{kb_id}",
                      headers=headers_a, verify=False, timeout=60, trust_env=False)
        if r.status_code == 200:
            kb_status = r.json().get("status", "CREATING")
            if kb_status == "READY":
                break
        time.sleep(3)
    assert kb_status == "READY", f"KB not ready after 120s: {kb_status}"

    state = {
        "client_a": client_a,
        "client_b": client_b,
        "tenant_a": tenant_a,
        "tenant_b": tenant_b,
        "headers_a": headers_a,
        "headers_b": headers_b,
        "kb_id": kb_id,
        # Will be populated during tests
        "share_id": None,
        "member_doc_id": None,
    }

    yield state

    # Cleanup: delete KB then both tenants
    try:
        httpx.delete(f"{BASE}/knowledge/bases/{kb_id}",
                     headers=headers_a, verify=False, timeout=60, trust_env=False)
    except Exception:
        pass
    _delete_tenant(tenant_a["id"])
    _delete_tenant(tenant_b["id"])


# ---------------------------------------------------------------------------
# TestShareManagement
# ---------------------------------------------------------------------------

class TestShareManagement:

    def test_01_tenant_b_cannot_access_before_share(self, setup):
        """Tenant B must NOT be able to access the KB before being shared."""
        r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code in [403, 404], \
            f"Expected 403/404 for unshared KB, got {r.status_code}: {r.text}"

    def test_02_create_share(self, setup):
        """Owner can share KB with tenant B by username."""
        username_b = setup["tenant_b"]["username"]
        r = httpx.post(f"{BASE}/knowledge/bases/{setup['kb_id']}/shares",
                       json={"username": username_b},
                       headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, f"Share creation failed: {r.status_code} {r.text}"
        data = r.json()
        assert "id" in data, f"Share response missing 'id': {data}"
        assert data.get("kb_id") == setup["kb_id"], f"kb_id mismatch in share response"
        assert data.get("role") == "member", f"Expected role=member, got: {data.get('role')}"
        # Store share_id for later tests
        setup["share_id"] = data["id"]

    def test_03_list_shares(self, setup):
        """Owner can list shares; list must contain tenant B with correct username."""
        r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}/shares",
                      headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, f"List shares failed: {r.status_code}"
        shares = r.json()
        assert isinstance(shares, list), f"Expected list, got: {type(shares)}"
        assert len(shares) == 1, f"Expected 1 share, got {len(shares)}"
        share = shares[0]
        assert share.get("username") == setup["tenant_b"]["username"], \
            f"Expected username {setup['tenant_b']['username']}, got {share.get('username')}"
        assert share.get("role") == "member", f"Expected role=member, got: {share.get('role')}"

    def test_04_tenant_b_sees_shared_kb_in_list(self, setup):
        """After sharing, tenant B sees the KB in their KB list with is_shared=True."""
        r = httpx.get(f"{BASE}/knowledge/bases",
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, f"KB list failed: {r.status_code}"
        kb_list = r.json()
        found = next((kb for kb in kb_list if kb["id"] == setup["kb_id"]), None)
        assert found is not None, \
            f"KB {setup['kb_id']} not in tenant B's KB list. IDs: {[k['id'] for k in kb_list]}"
        assert found.get("is_shared") is True, \
            f"Expected is_shared=true for shared KB, got: {found.get('is_shared')}"

    def test_05_tenant_b_can_read_kb(self, setup):
        """After sharing, tenant B can access KB detail."""
        r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, \
            f"Tenant B should be able to read shared KB, got {r.status_code}: {r.text}"
        data = r.json()
        assert data["id"] == setup["kb_id"]

    def test_06_member_cannot_list_shares(self, setup):
        """Member (tenant B) cannot list shares — admin only."""
        r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}/shares",
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 403, \
            f"Expected 403 for member listing shares, got {r.status_code}"

    def test_07_duplicate_share_rejected(self, setup):
        """Creating the same share twice must be rejected with 400."""
        username_b = setup["tenant_b"]["username"]
        r = httpx.post(f"{BASE}/knowledge/bases/{setup['kb_id']}/shares",
                       json={"username": username_b},
                       headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 400, \
            f"Expected 400 for duplicate share, got {r.status_code}: {r.text}"


# ---------------------------------------------------------------------------
# TestMemberOperations
# ---------------------------------------------------------------------------

class TestMemberOperations:

    def test_10_member_upload_document(self, setup):
        """Member can upload a document to the shared KB; document count must increase."""
        headers_b = setup["headers_b"]
        kb_id = setup["kb_id"]

        # Get initial document count
        r = httpx.get(f"{BASE}/knowledge/documents",
                      params={"kb_id": kb_id},
                      headers=headers_b, verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200
        resp_json = r.json()
        doc_list_before = resp_json if isinstance(resp_json, list) else resp_json.get("documents", [])
        count_before = len(doc_list_before)

        # Step 1: Get presigned upload URL
        r = httpx.get(f"{BASE}/knowledge/upload-url",
                      params={"kb_id": kb_id, "filename": "quantum-computing.md"},
                      headers=headers_b, verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, f"Get upload URL failed: {r.status_code} {r.text}"
        resp_data = r.json()
        doc_id = resp_data.get("document_id")
        upload_url = resp_data.get("upload_url")
        assert doc_id and upload_url, f"Missing doc_id or upload_url: {resp_data}"

        # Step 2: PUT file content to presigned URL
        r_upload = httpx.put(upload_url,
                             content=TEST_DOC_CONTENT.encode("utf-8"),
                             verify=False, timeout=60, trust_env=False)
        assert r_upload.status_code in [200, 201], f"File upload failed: {r_upload.status_code}"

        # Step 3: Trigger processing
        r = httpx.post(f"{BASE}/knowledge/documents/{doc_id}/process",
                       headers=headers_b, verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, f"Process document failed: {r.status_code} {r.text}"

        # Store doc_id for cleanup tests
        setup["member_doc_id"] = doc_id

        # Wait for document to appear in list and count to increase (up to 30s)
        found = False
        for _ in range(10):
            r = httpx.get(f"{BASE}/knowledge/documents",
                          params={"kb_id": kb_id},
                          headers=headers_b, verify=False, timeout=60, trust_env=False)
            if r.status_code == 200:
                resp_json = r.json()
                doc_list = resp_json if isinstance(resp_json, list) else resp_json.get("documents", [])
                if any(d["id"] == doc_id for d in doc_list):
                    found = True
                    break
            time.sleep(3)
        assert found, f"Uploaded document {doc_id} not in document list after 30s"

        # Verify document count increased
        r = httpx.get(f"{BASE}/knowledge/documents",
                      params={"kb_id": kb_id},
                      headers=headers_b, verify=False, timeout=60, trust_env=False)
        resp_json = r.json()
        doc_list_after = resp_json if isinstance(resp_json, list) else resp_json.get("documents", [])
        assert len(doc_list_after) > count_before, \
            f"Document count did not increase: before={count_before}, after={len(doc_list_after)}"

    def test_11_member_can_view_wiki(self, setup):
        """Member can read wiki pages of the shared KB."""
        r = httpx.get(f"{BASE}/knowledge/wiki/pages",
                      params={"kb_id": setup["kb_id"]},
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, \
            f"Member should be able to view wiki pages, got {r.status_code}: {r.text}"
        assert isinstance(r.json(), list), f"Expected list from wiki pages, got: {type(r.json())}"

    def test_12_member_can_view_graph(self, setup):
        """Member can read the wiki graph of the shared KB."""
        r = httpx.get(f"{BASE}/knowledge/wiki/graph",
                      params={"kb_id": setup["kb_id"]},
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, \
            f"Member should be able to view wiki graph, got {r.status_code}: {r.text}"
        data = r.json()
        assert "nodes" in data and "edges" in data, \
            f"Graph response missing nodes/edges: {list(data.keys())}"

    def test_13_member_can_chat(self, setup):
        """Member can chat with the shared KB's wiki."""
        r = httpx.post(f"{BASE}/knowledge/wiki/chat",
                       json={"kb_id": setup["kb_id"],
                             "question": "这个知识库有什么内容？", "history": []},
                       headers=setup["headers_b"], verify=False, timeout=120, trust_env=False)
        assert r.status_code == 200, \
            f"Member should be able to chat with shared KB, got {r.status_code}: {r.text}"
        answer = r.json().get("answer", "")
        assert len(answer) > 0, "Chat returned empty answer"

    def test_20_member_cannot_delete_document(self, setup):
        """Member cannot delete a document from the shared KB."""
        doc_id = setup.get("member_doc_id")
        if not doc_id:
            pytest.skip("No member doc_id available from test_10")

        r = httpx.delete(f"{BASE}/knowledge/documents/{doc_id}",
                         headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 403, \
            f"Expected 403 for member deleting document, got {r.status_code}: {r.text}"

        # Verify doc still exists (via owner headers)
        r_check = httpx.get(f"{BASE}/knowledge/documents",
                             params={"kb_id": setup["kb_id"]},
                             headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r_check.status_code == 200
        docs = r_check.json() if isinstance(r_check.json(), list) else r_check.json().get("documents", [])
        assert any(d["id"] == doc_id for d in docs), \
            f"Document {doc_id} was deleted despite member receiving 403"

    def test_21_member_cannot_delete_kb(self, setup):
        """Member cannot delete the shared KB."""
        r = httpx.delete(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                         headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 403, \
            f"Expected 403 for member deleting KB, got {r.status_code}: {r.text}"

        # Verify KB still exists (via owner headers)
        r_check = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                             headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r_check.status_code == 200, \
            f"KB was deleted despite member receiving 403. Owner check: {r_check.status_code}"

    def test_22_member_cannot_manage_shares(self, setup):
        """Member cannot create shares (invite others to the KB)."""
        r = httpx.post(f"{BASE}/knowledge/bases/{setup['kb_id']}/shares",
                       json={"username": "some-random-user"},
                       headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 403, \
            f"Expected 403 for member creating shares, got {r.status_code}: {r.text}"


# ---------------------------------------------------------------------------
# TestShareRemoval
# ---------------------------------------------------------------------------

class TestShareRemoval:

    def test_30_remove_share(self, setup):
        """Owner can delete the share, removing tenant B's access."""
        share_id = setup.get("share_id")
        assert share_id is not None, "share_id not set — test_02 must run first"

        r = httpx.delete(f"{BASE}/knowledge/bases/{setup['kb_id']}/shares/{share_id}",
                         headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, \
            f"Share deletion failed: {r.status_code} {r.text}"

    def test_31_member_loses_access(self, setup):
        """After share removal, tenant B can no longer access the KB."""
        r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code in [403, 404], \
            f"Expected 403/404 after share removed, got {r.status_code}: {r.text}"

    def test_32_kb_not_in_member_list(self, setup):
        """After share removal, the KB does not appear in tenant B's KB list."""
        r = httpx.get(f"{BASE}/knowledge/bases",
                      headers=setup["headers_b"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200
        kb_list = r.json()
        found = any(kb["id"] == setup["kb_id"] for kb in kb_list)
        assert not found, \
            f"KB {setup['kb_id']} still in tenant B's list after share removed"

    def test_33_admin_still_has_access(self, setup):
        """After share removal, owner (tenant A) still has full access."""
        r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                      headers=setup["headers_a"], verify=False, timeout=60, trust_env=False)
        assert r.status_code == 200, \
            f"Owner should still have access after removing share, got {r.status_code}"
        assert r.json()["id"] == setup["kb_id"]


# ---------------------------------------------------------------------------
# TestUnrelatedTenantIsolation
# ---------------------------------------------------------------------------

class TestUnrelatedTenantIsolation:

    def test_40_unrelated_tenant_no_access(self, setup):
        """A completely unrelated tenant (not a member) cannot access the KB."""
        ts = int(time.time())
        client_c, tenant_c, headers_c = _create_tenant(f"c-{ts}")
        try:
            r = httpx.get(f"{BASE}/knowledge/bases/{setup['kb_id']}",
                          headers=headers_c, verify=False, timeout=60, trust_env=False)
            assert r.status_code in [403, 404], \
                f"Expected 403/404 for unrelated tenant, got {r.status_code}: {r.text}"

            # Also verify they can't see it in their list
            r_list = httpx.get(f"{BASE}/knowledge/bases",
                               headers=headers_c, verify=False, timeout=60, trust_env=False)
            assert r_list.status_code == 200
            kb_list = r_list.json()
            assert not any(kb["id"] == setup["kb_id"] for kb in kb_list), \
                f"Unrelated tenant can see KB {setup['kb_id']} in their list"
        finally:
            _delete_tenant(tenant_c["id"])
