"""Wiki Agent E2E tests — validates the FULL pipeline end-to-end:
    上传文档 → 解析+切片+embedding → summarize → wiki 生成 → 图谱 → 对话 → 沉淀

Uses local markdown upload (no external URL dependency).
Every test that depends on pipeline completion will FAIL (not skip) if the pipeline doesn't work.
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
TIMEOUT = 30

# Small but rich markdown document — enough content for wiki pages + wikilinks
BLOCKCHAIN_MD = """# 区块链共识机制

## 工作量证明 (PoW)
工作量证明是比特币使用的共识机制。矿工通过计算哈希值来竞争区块打包权。
PoW 的优点是安全性高，经过十多年验证；缺点是能源消耗巨大，每年消耗与中等国家相当的电力。
比特币网络通过调整难度来维持约10分钟的出块间隔。

## 权益证明 (PoS)
权益证明是以太坊 2.0 采用的[[共识机制]]。验证者通过质押 ETH 来获得出块权。
PoS 比 [[工作量证明 (PoW)]] 节能超过 99%，但可能导致"富者愈富"的中心化问题。
以太坊的 PoS 要求至少质押 32 ETH 成为验证者。

## 拜占庭容错 (BFT)
BFT 系列算法（如 PBFT、Tendermint）适用于联盟链场景。
在 3f+1 个节点中，最多可以容忍 f 个恶意节点。
Cosmos 网络使用 Tendermint BFT 作为其共识引擎。

## Layer 2 扩容方案
为了解决主链吞吐量限制，[[Layer 2]] 扩容方案应运而生：
- **Lightning Network**：基于支付通道的链下扩容，适用于比特币小额支付
- **ZK-Rollups**：使用[[零知识证明]]进行批量验证，安全性等同 L1
- **Optimistic Rollups**：乐观执行，通过欺诈证明保障安全，代表项目有 Arbitrum 和 Optimism

## 零知识证明
零知识证明（ZKP）是一种密码学技术，允许证明者向验证者证明某个陈述的真实性，
而无需透露任何额外信息。在区块链中，ZKP 被广泛用于[[Layer 2 扩容方案]]和隐私保护。
主要的 ZKP 方案包括 zk-SNARKs（Zcash 使用）和 zk-STARKs（StarkNet 使用）。
"""


@pytest.fixture(scope="module")
def pipeline_kb():
    """Create tenant + KB, upload markdown doc, wait for FULL pipeline completion.
    This fixture MUST succeed for all pipeline tests to run.
    """
    ts = int(time.time())
    admin = DbayClient(endpoint=BASE.replace("/api/v1", ""), api_key=ADMIN_TOKEN)
    invite = admin.admin_create_invite_code(max_uses=1)

    fake_ip = f"10.{random.randint(0,255)}.{random.randint(0,255)}.{random.randint(1,254)}"
    reg = DbayClient(endpoint=BASE.replace("/api/v1", ""),
                     extra_headers={"X-Forwarded-For": fake_ip})
    tenant = reg.create_tenant(
        username=f"wiki-e2e-{ts}", password=f"WikiTest@{ts}",
        name=f"Wiki Pipeline E2E {ts}", invite_code=invite.get("code"),
    )
    client = DbayClient(endpoint=BASE.replace("/api/v1", ""), api_key=tenant["api_key"])
    headers = {"Authorization": f"Bearer {tenant['api_key']}"}

    # Create KB
    kb = client.create_knowledge_base("Pipeline E2E Test")
    kb_id = kb["id"]

    # Wait KB READY
    for _ in range(30):
        info = client.get_knowledge_base(kb_id)
        if info.get("status") == "READY":
            break
        time.sleep(2)
    assert info.get("status") == "READY", f"KB not ready: {info.get('status')}"

    # Upload markdown doc via presigned URL
    upload_info = client.batch_get_upload_urls(kb_id, [{"filename": "blockchain-consensus.md"}])
    docs = upload_info.get("documents", [])
    assert len(docs) == 1, f"No upload URL returned: {upload_info}"
    doc_id = docs[0]["document_id"]
    upload_url = docs[0]["upload_url"]

    r = httpx.put(upload_url, content=BLOCKCHAIN_MD.encode("utf-8"), verify=False, timeout=60)
    assert r.status_code in [200, 201], f"Upload failed: {r.status_code}"

    # Trigger processing
    client.batch_process_documents([doc_id])

    # Wait for document READY (up to 4 min — parse+chunk+embed+summarize)
    doc_status = "PROCESSING"
    for _ in range(48):  # 48 * 5s = 240s = 4 min
        r = httpx.get(f"{BASE}/knowledge/documents",
                      params={"kb_id": kb_id},
                      headers=headers, verify=False, timeout=TIMEOUT)
        if r.status_code == 200:
            doc_list = r.json() if isinstance(r.json(), list) else r.json().get("documents", [])
            for d in doc_list:
                if d.get("id") == doc_id:
                    doc_status = d.get("status", "UNKNOWN")
                    break
        if doc_status == "READY":
            break
        time.sleep(5)

    # Wait extra time for WIKI_UPDATE (async after summarize, up to 2 min)
    wiki_pages = []
    if doc_status == "READY":
        for _ in range(24):  # 24 * 5s = 120s
            r = httpx.get(f"{BASE}/knowledge/wiki/pages",
                          params={"kb_id": kb_id},
                          headers=headers, verify=False, timeout=TIMEOUT)
            if r.status_code == 200:
                wiki_pages = r.json()
                content_pages = [p for p in wiki_pages
                                 if p.get("filename", "") not in ("index.md", "log.md")]
                if len(content_pages) > 0:
                    break
            time.sleep(5)

    yield {
        "tenant_id": tenant["id"],
        "kb_id": kb_id,
        "doc_id": doc_id,
        "doc_status": doc_status,
        "wiki_pages": wiki_pages,
        "headers": headers,
        "client": client,
    }

    # Cleanup
    admin.admin_batch_delete_tenants([tenant["id"]])


# ---------------------------------------------------------------------------
# 1. 文档处理流水线
# ---------------------------------------------------------------------------

class TestPipeline:
    """Document must be fully processed before wiki can be generated."""

    def test_document_reaches_ready(self, pipeline_kb):
        """Document MUST reach READY status — parse + chunk + embed + summarize."""
        assert pipeline_kb["doc_status"] == "READY", \
            f"Document stuck at '{pipeline_kb['doc_status']}' after 4 min. Pipeline broken."

    def test_document_has_chunks(self, pipeline_kb):
        """Processed document must have chunks."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        r = httpx.get(f"{BASE}/knowledge/documents",
                      params={"kb_id": pipeline_kb["kb_id"]},
                      headers=pipeline_kb["headers"], verify=False, timeout=TIMEOUT)
        docs = r.json() if isinstance(r.json(), list) else r.json().get("documents", [])
        doc = next((d for d in docs if d["id"] == pipeline_kb["doc_id"]), None)
        assert doc is not None
        assert (doc.get("chunks_count") or 0) > 0, \
            f"Document has 0 chunks — chunking pipeline broken"


# ---------------------------------------------------------------------------
# 2. Wiki 页面自动生成
# ---------------------------------------------------------------------------

class TestWikiGeneration:
    """After document processing, Wiki Agent must auto-generate wiki pages."""

    def test_wiki_pages_generated(self, pipeline_kb):
        """Wiki pages MUST be generated after document reaches READY."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        pages = pipeline_kb["wiki_pages"]
        content_pages = [p for p in pages
                         if p.get("filename", "") not in ("index.md", "log.md")]
        assert len(content_pages) > 0, \
            f"No wiki pages generated after 2 min wait. WIKI_UPDATE pipeline broken. Total pages: {len(pages)}"

    def test_wiki_page_has_content(self, pipeline_kb):
        """Each wiki page must have non-trivial markdown content."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        pages = pipeline_kb["wiki_pages"]
        content_pages = [p for p in pages
                         if p.get("filename", "") not in ("index.md", "log.md")]
        assert len(content_pages) > 0, "No wiki pages"

        page = content_pages[0]
        page_id = page.get("id") or page.get("document_id")
        r = httpx.get(f"{BASE}/knowledge/wiki/pages/{page_id}/content",
                      params={"kb_id": pipeline_kb["kb_id"]},
                      headers=pipeline_kb["headers"], verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        content = r.json().get("content", "")
        assert len(content) > 50, f"Wiki page content too short ({len(content)} chars)"

    def test_wiki_page_has_wikilinks(self, pipeline_kb):
        """At least one wiki page must contain [[wikilink]] cross-references."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        pages = pipeline_kb["wiki_pages"]
        content_pages = [p for p in pages
                         if p.get("filename", "") not in ("index.md", "log.md")]
        assert len(content_pages) > 0, "No wiki pages"

        found = False
        for page in content_pages[:5]:
            page_id = page.get("id") or page.get("document_id")
            r = httpx.get(f"{BASE}/knowledge/wiki/pages/{page_id}/content",
                          params={"kb_id": pipeline_kb["kb_id"]},
                          headers=pipeline_kb["headers"], verify=False, timeout=TIMEOUT)
            if r.status_code == 200 and "[[" in r.json().get("content", ""):
                found = True
                break
        assert found, "No wiki page contains [[wikilinks]] — LLM not extracting relationships"


# ---------------------------------------------------------------------------
# 3. 知识图谱
# ---------------------------------------------------------------------------

class TestWikiGraph:
    """Knowledge graph must have nodes from wiki pages."""

    def test_graph_has_nodes(self, pipeline_kb):
        """Graph must have nodes if wiki pages exist."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        r = httpx.get(f"{BASE}/knowledge/wiki/graph",
                      params={"kb_id": pipeline_kb["kb_id"]},
                      headers=pipeline_kb["headers"], verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        data = r.json()
        assert "nodes" in data and "edges" in data
        # If wiki pages exist, graph should have nodes
        if len(pipeline_kb["wiki_pages"]) > 0:
            assert len(data["nodes"]) > 0, \
                f"Graph has 0 nodes but wiki has {len(pipeline_kb['wiki_pages'])} pages"


# ---------------------------------------------------------------------------
# 4. 对话 — 基于内容回答
# ---------------------------------------------------------------------------

class TestWikiChat:
    """Chat must return answers grounded in the uploaded document content."""

    def test_chat_returns_relevant_answer(self, pipeline_kb):
        """Chat about PoW/PoS should mention relevant terms from the document."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        r = httpx.post(f"{BASE}/knowledge/wiki/chat",
                       json={"kb_id": pipeline_kb["kb_id"],
                             "question": "PoW和PoS有什么区别？", "history": []},
                       headers=pipeline_kb["headers"], verify=False, timeout=120)
        assert r.status_code == 200, f"Chat failed: {r.text}"
        answer = r.json().get("answer", "")
        assert len(answer) > 20, f"Answer too short: {answer}"
        terms = ["PoW", "PoS", "工作量", "权益", "比特币", "以太坊", "质押", "矿工"]
        found = any(t in answer for t in terms)
        assert found, f"Answer doesn't reference document content: {answer[:200]}"

    def test_chat_multi_turn(self, pipeline_kb):
        """Multi-turn conversation should work with history."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        r1 = httpx.post(f"{BASE}/knowledge/wiki/chat",
                        json={"kb_id": pipeline_kb["kb_id"],
                              "question": "什么是Layer 2?", "history": []},
                        headers=pipeline_kb["headers"], verify=False, timeout=120)
        assert r1.status_code == 200, f"Chat turn 1 failed: {r1.text}"
        a1 = r1.json().get("answer", "")

        r2 = httpx.post(f"{BASE}/knowledge/wiki/chat",
                        json={"kb_id": pipeline_kb["kb_id"],
                              "question": "ZK-Rollups具体怎么工作？",
                              "history": [
                                  {"role": "user", "content": "什么是Layer 2?"},
                                  {"role": "assistant", "content": a1},
                              ]},
                        headers=pipeline_kb["headers"], verify=False, timeout=120)
        assert r2.status_code == 200, f"Chat turn 2 failed: {r2.text}"
        a2 = r2.json().get("answer", "")
        assert len(a2) > 20, f"Follow-up answer too short: {a2}"


# ---------------------------------------------------------------------------
# 5. 沉淀知识 — 保存回 Wiki 并验证
# ---------------------------------------------------------------------------

class TestKnowledgeSettlement:
    """Save chat answer to wiki, then verify it appears in page list with content."""

    def test_save_and_verify(self, pipeline_kb):
        """Full cycle: chat → save → verify page exists with content."""
        assert pipeline_kb["doc_status"] == "READY", "Document not ready"
        headers = pipeline_kb["headers"]
        kb_id = pipeline_kb["kb_id"]

        # 1. Chat
        r = httpx.post(f"{BASE}/knowledge/wiki/chat",
                       json={"kb_id": kb_id,
                             "question": "总结Layer 2扩容方案的优缺点", "history": []},
                       headers=headers, verify=False, timeout=120)
        assert r.status_code == 200, f"Chat failed: {r.text}"
        answer = r.json().get("answer", "")
        assert len(answer) > 20

        # 2. Save to wiki
        save_title = f"L2扩容总结-{int(time.time())}"
        r = httpx.post(f"{BASE}/knowledge/wiki/save-response",
                       json={"kb_id": kb_id, "title": save_title, "content": answer},
                       headers=headers, verify=False, timeout=60)
        assert r.status_code == 200, f"Save failed: {r.text}"

        # 3. Verify page appears in list (poll up to 10s)
        found_page = None
        for _ in range(5):
            r = httpx.get(f"{BASE}/knowledge/wiki/pages",
                          params={"kb_id": kb_id},
                          headers=headers, verify=False, timeout=TIMEOUT)
            assert r.status_code == 200
            for p in r.json():
                combined = (p.get("filename", "") + p.get("title", "")).lower()
                if save_title.lower() in combined:
                    found_page = p
                    break
            if found_page:
                break
            time.sleep(2)
        assert found_page is not None, \
            f"Saved page '{save_title}' not in wiki list. Pages: {[p.get('filename') for p in r.json()]}"

        # 4. Verify page content is readable
        page_id = found_page.get("id") or found_page.get("document_id")
        r = httpx.get(f"{BASE}/knowledge/wiki/pages/{page_id}/content",
                      params={"kb_id": kb_id},
                      headers=headers, verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        content = r.json().get("content", "")
        assert len(content) > 20, f"Saved page has no content"


# ---------------------------------------------------------------------------
# 6. URL 导入 — 完整流水线验证
# ---------------------------------------------------------------------------

class TestUrlIngestPipeline:
    """URL ingest must work end-to-end: fetch → create doc → process → READY."""

    def test_url_ingest_full_pipeline(self, pipeline_kb):
        """Import URL → doc created with MARKDOWN format → processing → READY."""
        headers = pipeline_kb["headers"]
        kb_id = pipeline_kb["kb_id"]

        # 1. Ingest URL
        r = httpx.post(f"{BASE}/knowledge/wiki/ingest-url",
                       json={"kb_id": kb_id,
                             "url": "https://ethereum.org/developers/docs/scaling/zk-rollups/"},
                       headers=headers, verify=False, timeout=60)
        assert r.status_code == 200, f"URL ingest failed ({r.status_code}): {r.text}"
        url_doc_id = r.json()["document_id"]

        # 2. Verify document exists with format MARKDOWN
        time.sleep(2)
        r = httpx.get(f"{BASE}/knowledge/documents",
                      params={"kb_id": kb_id}, headers=headers, verify=False, timeout=TIMEOUT)
        docs = r.json() if isinstance(r.json(), list) else r.json().get("documents", [])
        url_doc = next((d for d in docs if d["id"] == url_doc_id), None)
        assert url_doc is not None, "URL imported doc not in document list"
        assert url_doc.get("format") == "MARKDOWN", \
            f"Expected MARKDOWN, got '{url_doc.get('format')}'"

        # 3. Wait for READY (up to 4 min)
        status = "PROCESSING"
        for _ in range(48):
            r = httpx.get(f"{BASE}/knowledge/documents",
                          params={"kb_id": kb_id}, headers=headers, verify=False, timeout=TIMEOUT)
            doc_list = r.json() if isinstance(r.json(), list) else r.json().get("documents", [])
            d = next((x for x in doc_list if x["id"] == url_doc_id), None)
            if d:
                status = d.get("status", "UNKNOWN")
            if status == "READY":
                break
            if status == "FAILED":
                error = d.get("error", "unknown") if d else "doc not found"
                pytest.fail(f"URL doc processing FAILED: {error}")
            time.sleep(5)
        assert status == "READY", \
            f"URL doc stuck at '{status}' after 4 min — processing pipeline broken for URL imports"

    def test_url_ingest_invalid_url(self, pipeline_kb):
        """Invalid URL must return error status, not crash."""
        r = httpx.post(f"{BASE}/knowledge/wiki/ingest-url",
                       json={"kb_id": pipeline_kb["kb_id"],
                             "url": "https://nonexistent-domain-12345.com/page"},
                       headers=pipeline_kb["headers"], verify=False, timeout=60)
        assert r.status_code in [422, 502], \
            f"Expected error status, got {r.status_code}: {r.text}"


# ---------------------------------------------------------------------------
# 7. Admin Wiki Config
# ---------------------------------------------------------------------------

class TestAdminWikiConfig:
    """Admin API for wiki agent configuration."""

    def _headers(self):
        return {"Authorization": f"Bearer {ADMIN_TOKEN}", "X-Admin-Token": ADMIN_TOKEN}

    def test_get_config(self):
        r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                      headers=self._headers(), verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
        data = r.json()
        assert "ingest_prompt" in data and len(data["ingest_prompt"]) > 0
        assert "model" in data

    def test_requires_admin(self):
        r = httpx.get(f"{BASE}/knowledge/admin/wiki/config",
                      verify=False, timeout=TIMEOUT)
        assert r.status_code in [401, 403]

    def test_update_config(self):
        """Update prompt (safe — doesn't break LLM calls unlike model change)."""
        r = httpx.put(f"{BASE}/knowledge/admin/wiki/config",
                      json={"ingest_prompt": "test prompt"},
                      headers=self._headers(), verify=False, timeout=TIMEOUT)
        assert r.status_code == 200
