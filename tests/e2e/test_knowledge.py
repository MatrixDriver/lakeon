"""
E2E tests for Knowledge Base functionality.

Tests: KB CRUD, document upload, processing, search.
"""
import os
import time
import tempfile
import pytest

from conftest import poll_until


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="module")
def kb(e2e_client):
    """Create a knowledge base, wait for READY, yield it, then delete."""
    kb = e2e_client.create_knowledge_base(
        name=f"e2e-kb-{int(time.time())}",
        description="E2E test knowledge base",
    )
    assert kb["id"].startswith("kb_")
    assert kb["status"] in ("CREATING", "READY")

    # Poll until READY (database provisioning may take time)
    kb = poll_until(
        lambda: e2e_client.get_knowledge_base(kb["id"]),
        condition=lambda k: k["status"] in ("READY", "FAILED"),
        timeout=180,
        interval=3,
    )
    assert kb["status"] == "READY", f"KB creation failed: {kb.get('error')}"

    yield kb

    # Cleanup
    try:
        e2e_client.delete_knowledge_base(kb["id"])
    except Exception:
        pass


@pytest.fixture
def sample_pdf():
    """Create a minimal PDF file for testing."""
    # Minimal valid PDF
    pdf_content = b"""%PDF-1.0
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]
   /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 44 >>
stream
BT /F1 12 Tf 100 700 Td (Hello Knowledge Base) Tj ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000266 00000 n
0000000360 00000 n
trailer
<< /Size 6 /Root 1 0 R >>
startxref
441
%%EOF"""
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
        f.write(pdf_content)
        path = f.name
    yield path
    os.unlink(path)


@pytest.fixture
def sample_md():
    """Create a Markdown file for testing."""
    content = """# OAuth Configuration Guide

## 1. Getting Client ID

To configure OAuth, you need to register your application:

1. Go to the developer portal
2. Create a new application
3. Copy the Client ID

## 2. Setting Up Callback URL

Configure the callback URL in your `application.yml`:

```yaml
oauth:
  client-id: your-client-id
  callback-url: https://example.com/callback
```

## 3. Token Management

| Parameter | Description | Default |
|-----------|-------------|---------|
| access_token_ttl | Access token TTL | 3600s |
| refresh_token_ttl | Refresh token TTL | 86400s |
"""
    with tempfile.NamedTemporaryFile(suffix=".md", mode="w", delete=False, encoding="utf-8") as f:
        f.write(content)
        path = f.name
    yield path
    os.unlink(path)


# ---------------------------------------------------------------------------
# Tests: Knowledge Base CRUD
# ---------------------------------------------------------------------------

class TestKnowledgeBaseCRUD:

    def test_create_kb(self, kb):
        """KB should be created and reach READY status."""
        assert kb["status"] == "READY"
        assert kb["name"].startswith("e2e-kb-")
        assert kb["description"] == "E2E test knowledge base"
        assert kb["database_id"] is not None  # Hidden DB was created

    def test_list_kbs(self, e2e_client, kb):
        """List should include the created KB."""
        kbs = e2e_client.list_knowledge_bases()
        ids = [k["id"] for k in kbs]
        assert kb["id"] in ids

    def test_get_kb(self, e2e_client, kb):
        """Get should return KB details."""
        result = e2e_client.get_knowledge_base(kb["id"])
        assert result["id"] == kb["id"]
        assert result["name"] == kb["name"]

    def test_get_kb_not_found(self, e2e_client):
        """Get nonexistent KB should return 404."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_knowledge_base("kb_nonexistent")
        assert exc_info.value.status_code == 404

    def test_delete_and_recreate(self, e2e_client):
        """Should be able to delete and recreate a KB."""
        kb = e2e_client.create_knowledge_base(name=f"e2e-delete-test-{int(time.time())}")
        kb_id = kb["id"]

        # Wait for creation
        poll_until(
            lambda: e2e_client.get_knowledge_base(kb_id),
            condition=lambda k: k["status"] in ("READY", "FAILED"),
            timeout=180,
        )

        # Delete
        e2e_client.delete_knowledge_base(kb_id)

        # Verify deleted
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_knowledge_base(kb_id)
        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Tests: Document Upload & Processing
# ---------------------------------------------------------------------------

class TestDocumentUpload:

    def test_upload_markdown(self, e2e_client, kb, sample_md):
        """Upload a Markdown file and verify it gets processed."""
        import httpx

        # 1. Get upload URL
        result = e2e_client.get_upload_url(kb["id"], "test-doc.md")
        assert "document_id" in result
        assert "upload_url" in result
        doc_id = result["document_id"]

        # 2. Upload file to presigned URL
        with open(sample_md, "rb") as f:
            resp = httpx.put(result["upload_url"], content=f.read(), verify=False, timeout=30)
        assert resp.status_code in (200, 201), f"Upload failed: {resp.status_code}"

        # 3. Trigger processing
        e2e_client.process_document(doc_id)

        # 4. Poll until processed
        doc = poll_until(
            lambda: e2e_client.get_document(doc_id),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=300,
            interval=5,
        )
        assert doc["status"] == "READY", f"Document processing failed: {doc.get('error')}"
        assert doc["chunks_count"] > 0

    def test_list_documents(self, e2e_client, kb):
        """After upload, document should appear in list."""
        docs = e2e_client.list_documents(kb["id"])
        assert len(docs) > 0
        doc = docs[0]
        assert "filename" in doc
        assert "status" in doc

    def test_delete_document(self, e2e_client, kb, sample_md):
        """Should be able to delete a document."""
        import httpx

        # Upload
        result = e2e_client.get_upload_url(kb["id"], "to-delete.md")
        doc_id = result["document_id"]
        with open(sample_md, "rb") as f:
            httpx.put(result["upload_url"], content=f.read(), verify=False, timeout=30)
        e2e_client.process_document(doc_id)

        # Wait for processing
        poll_until(
            lambda: e2e_client.get_document(doc_id),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=300,
            interval=5,
        )

        # Delete
        e2e_client.delete_document(doc_id)

        # Verify deleted
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_document(doc_id)
        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Tests: Search
# ---------------------------------------------------------------------------

class TestKnowledgeSearch:

    def test_search_returns_results(self, e2e_client, kb):
        """Search should return relevant results after document processing."""
        # Assumes test_upload_markdown has run and a doc is READY
        docs = e2e_client.list_documents(kb["id"])
        ready_docs = [d for d in docs if d["status"] == "READY"]
        if not ready_docs:
            pytest.skip("No READY documents to search")

        result = e2e_client.search_knowledge(kb["id"], "OAuth configuration", top_k=3)
        results = result.get("results", [])
        assert len(results) > 0
        assert "content" in results[0]
        assert "score" in results[0]

    def test_search_empty_query(self, e2e_client, kb):
        """Search with empty query should return error or empty results."""
        from dbay_cli.client import DbayApiError
        try:
            result = e2e_client.search_knowledge(kb["id"], "", top_k=3)
            # Some APIs return empty results for empty query
            assert len(result.get("results", [])) == 0
        except DbayApiError:
            pass  # 400 Bad Request is also acceptable

    def test_search_no_match(self, e2e_client, kb):
        """Search for irrelevant content should return few/no results."""
        result = e2e_client.search_knowledge(kb["id"], "quantum physics dark matter", top_k=3)
        # May still return results (semantic similarity), but scores should be low
        results = result.get("results", [])
        if results:
            assert results[0].get("score", 0) < 0.9  # Low relevance
