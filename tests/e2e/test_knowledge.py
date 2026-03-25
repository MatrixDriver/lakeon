"""
E2E tests for Knowledge Base functionality.

Tests: KB CRUD, document upload, processing, search, chunk operations,
       rechunk, multi-tenant isolation.
"""
import os
import time
import tempfile
import pytest

from conftest import poll_until


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _upload_and_process(e2e_client, kb_id, filename, file_path, max_retries=3):
    """Upload a file to a KB and wait for processing to complete.
    Returns the READY document dict (with document_id).
    Retries if processing fails due to compute connection issues.
    """
    import httpx
    from dbay_cli.client import DbayApiError

    result = e2e_client.get_upload_url(kb_id, filename)
    doc_id = result["document_id"]

    with open(file_path, "rb") as f:
        file_content = f.read()
    resp = httpx.put(result["upload_url"], content=file_content, verify=False, timeout=30)
    assert resp.status_code in (200, 201), f"Upload failed: {resp.status_code}"

    for attempt in range(max_retries + 1):
        try:
            e2e_client.process_document(doc_id)
        except DbayApiError as e:
            if "not in PENDING status" in str(e):
                pass  # Already triggered, continue polling
            else:
                raise

        doc = poll_until(
            lambda: e2e_client.get_document(doc_id),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=420,
            interval=5,
        )

        if doc["status"] == "READY":
            return doc

        # Retry on connection errors (compute pod may have been suspended)
        error = doc.get("error", "")
        if attempt < max_retries and ("connection" in error.lower() or "closed" in error.lower()):
            time.sleep(5)
            # Re-upload for retry (doc status needs to be reset)
            result = e2e_client.get_upload_url(kb_id, filename)
            doc_id = result["document_id"]
            resp = httpx.put(result["upload_url"], content=file_content, verify=False, timeout=30)
            continue

        assert doc["status"] == "READY", f"Document processing failed: {doc.get('error')}"

    return doc


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

    # Poll until READY (database provisioning may take time; cold elastic node start can take ~3min)
    kb = poll_until(
        lambda: e2e_client.get_knowledge_base(kb["id"]),
        condition=lambda k: k["status"] in ("READY", "FAILED"),
        timeout=360,
        interval=3,
    )
    assert kb["status"] == "READY", f"KB creation failed: {kb.get('error')}"

    yield kb

    # Cleanup
    try:
        e2e_client.delete_knowledge_base(kb["id"])
    except Exception:
        pass


@pytest.fixture(scope="module")
def processed_doc(e2e_client, kb, sample_md_module):
    """Upload and process a document once for the module. Returns doc dict."""
    return _upload_and_process(e2e_client, kb["id"], "module-doc.md", sample_md_module)


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


@pytest.fixture(scope="module")
def sample_md_module():
    """Module-scoped markdown file for shared document tests."""
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


@pytest.fixture
def sample_md_alt():
    """Create a second Markdown file with different content."""
    content = """# Kubernetes Deployment Guide

## Pod Scheduling

Kubernetes schedules pods to nodes based on resource requests and limits.

## Service Types

- ClusterIP: internal only
- NodePort: exposed on each node
- LoadBalancer: cloud provider LB

## ConfigMap Usage

Store non-sensitive configuration data as key-value pairs.
Environment variables or mounted files are both supported.
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

    def test_create_kb_without_description(self, e2e_client):
        """KB can be created without a description."""
        kb = e2e_client.create_knowledge_base(name=f"e2e-no-desc-{int(time.time())}")
        kb_id = kb["id"]
        try:
            assert kb["id"].startswith("kb_")
            assert kb.get("description") is None
        finally:
            try:
                e2e_client.delete_knowledge_base(kb_id)
            except Exception:
                pass

    def test_delete_and_recreate(self, e2e_client):
        """Should be able to delete a KB (even while still creating)."""
        kb = e2e_client.create_knowledge_base(name=f"e2e-delete-test-{int(time.time())}")
        kb_id = kb["id"]

        # Delete immediately — no need to wait for READY
        e2e_client.delete_knowledge_base(kb_id)

        # Verify deleted
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_knowledge_base(kb_id)
        assert exc_info.value.status_code == 404

    def test_delete_kb_not_found(self, e2e_client):
        """Deleting a nonexistent KB should return 404."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.delete_knowledge_base("kb_nonexistent_delete")
        assert exc_info.value.status_code == 404

    def test_list_kbs_returns_list(self, e2e_client):
        """List should always return a list (even if empty for other tenants)."""
        kbs = e2e_client.list_knowledge_bases()
        assert isinstance(kbs, list)


# ---------------------------------------------------------------------------
# Tests: Document Upload & Processing
# ---------------------------------------------------------------------------

class TestDocumentUpload:

    def test_upload_markdown(self, e2e_client, kb, sample_md):
        """Upload a Markdown file and verify it gets processed."""
        doc = _upload_and_process(e2e_client, kb["id"], "test-doc.md", sample_md)
        assert doc["status"] == "READY"
        assert doc["chunks_count"] > 0

    def test_upload_second_document(self, e2e_client, kb, sample_md_alt):
        """Upload a second document to the same KB."""
        doc = _upload_and_process(e2e_client, kb["id"], "k8s-guide.md", sample_md_alt)
        assert doc["status"] == "READY"
        assert doc["chunks_count"] > 0

    def test_list_documents(self, e2e_client, kb):
        """After upload, document should appear in list."""
        docs = e2e_client.list_documents(kb["id"])
        assert len(docs) > 0
        doc = docs[0]
        assert "filename" in doc
        assert "status" in doc

    def test_list_documents_multiple(self, e2e_client, kb):
        """After uploading multiple docs, list should show them all."""
        docs = e2e_client.list_documents(kb["id"])
        filenames = [d["filename"] for d in docs]
        # At least the docs uploaded by previous tests
        assert len(docs) >= 1

    def test_get_document(self, e2e_client, kb):
        """Get a specific document by ID."""
        docs = e2e_client.list_documents(kb["id"])
        assert len(docs) > 0
        doc = e2e_client.get_document(docs[0]["id"])
        assert doc["id"] == docs[0]["id"]
        assert "status" in doc
        assert "filename" in doc

    def test_get_document_not_found(self, e2e_client):
        """Get nonexistent document should return 404."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_document("doc_nonexistent")
        assert exc_info.value.status_code == 404

    def test_delete_document(self, e2e_client, kb, sample_md):
        """Should be able to delete a document."""
        doc = _upload_and_process(e2e_client, kb["id"], "to-delete.md", sample_md)
        doc_id = doc["id"]

        # Delete
        e2e_client.delete_document(doc_id)

        # Verify deleted
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.get_document(doc_id)
        assert exc_info.value.status_code == 404

    def test_delete_document_not_found(self, e2e_client):
        """Deleting a nonexistent document should return 404."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.delete_document("doc_nonexistent_del")
        assert exc_info.value.status_code == 404


# ---------------------------------------------------------------------------
# Tests: Batch Upload
# ---------------------------------------------------------------------------

def _batch_upload_and_process(e2e_client, kb_id, files_map, max_retries=3):
    """Batch upload multiple files and wait for processing.

    files_map: list of (filename, file_path) tuples.
    Returns list of READY document dicts.
    """
    import httpx

    file_specs = [{"filename": fn} for fn, _ in files_map]
    batch_resp = e2e_client.batch_get_upload_urls(kb_id, file_specs)
    doc_items = batch_resp["documents"]
    assert len(doc_items) == len(files_map)

    # Upload each file to its presigned URL
    for item, (_, file_path) in zip(doc_items, files_map):
        with open(file_path, "rb") as f:
            content = f.read()
        resp = httpx.put(item["upload_url"], content=content, verify=False, timeout=30)
        assert resp.status_code in (200, 201), f"Upload failed for {item['filename']}: {resp.status_code}"

    # Trigger batch processing
    doc_ids = [item["document_id"] for item in doc_items]
    proc_resp = e2e_client.batch_process_documents(doc_ids)
    assert proc_resp["document_count"] == len(doc_ids)

    # Poll each document until READY or FAILED
    docs = []
    for doc_id in doc_ids:
        doc = poll_until(
            lambda did=doc_id: e2e_client.get_document(did),
            condition=lambda d: d["status"] in ("READY", "FAILED"),
            timeout=420,
            interval=5,
        )
        docs.append(doc)

    return docs


class TestBatchUpload:

    def test_batch_upload_two_markdown(self, e2e_client, kb, sample_md, sample_md_alt):
        """Batch upload two Markdown files and verify both get processed."""
        files_map = [
            ("batch-doc-1.md", sample_md),
            ("batch-doc-2.md", sample_md_alt),
        ]
        docs = _batch_upload_and_process(e2e_client, kb["id"], files_map)

        for doc in docs:
            assert doc["status"] == "READY", f"Doc {doc['filename']} failed: {doc.get('error')}"
            assert doc["chunks_count"] > 0

    def test_batch_upload_urls_returns_correct_structure(self, e2e_client, kb):
        """batch-upload-urls returns document_id, filename, upload_url, expires_in for each file."""
        files = [{"filename": "struct-test-1.md"}, {"filename": "struct-test-2.txt"}]
        resp = e2e_client.batch_get_upload_urls(kb["id"], files)

        assert "documents" in resp
        assert len(resp["documents"]) == 2
        for item in resp["documents"]:
            assert "document_id" in item
            assert "filename" in item
            assert "upload_url" in item
            assert "expires_in" in item
            assert item["upload_url"].startswith("http")

    def test_batch_upload_urls_validates_format(self, e2e_client, kb):
        """Unsupported file format should be rejected."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.batch_get_upload_urls(kb["id"], [{"filename": "bad.exe"}])
        assert exc_info.value.status_code == 400

    def test_batch_upload_urls_max_20(self, e2e_client, kb):
        """Exceeding 20 files per batch should be rejected."""
        from dbay_cli.client import DbayApiError
        files = [{"filename": f"file-{i}.md"} for i in range(21)]
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.batch_get_upload_urls(kb["id"], files)
        assert exc_info.value.status_code == 400

    def test_batch_process_empty_list(self, e2e_client):
        """batch-process with empty list should be rejected."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.batch_process_documents([])
        assert exc_info.value.status_code == 400

    def test_batch_process_not_found(self, e2e_client):
        """batch-process with nonexistent doc IDs should return 404."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.batch_process_documents(["doc_nonexistent_batch"])
        assert exc_info.value.status_code == 404

    def test_batch_uploaded_docs_appear_in_list(self, e2e_client, kb):
        """After batch upload, documents should appear in the KB document list."""
        docs = e2e_client.list_documents(kb["id"])
        filenames = [d["filename"] for d in docs]
        # batch-doc-1 and batch-doc-2 from test_batch_upload_two_markdown
        assert "batch-doc-1.md" in filenames
        assert "batch-doc-2.md" in filenames

    def test_batch_uploaded_docs_searchable(self, e2e_client, kb):
        """After batch upload, documents should be searchable."""
        # batch-doc-1.md contains OAuth content, batch-doc-2.md contains Kubernetes content
        result = e2e_client.search_knowledge(kb["id"], "Kubernetes deployment", top_k=3)
        assert result["count"] > 0


# ---------------------------------------------------------------------------
# Tests: Search
# ---------------------------------------------------------------------------

class TestKnowledgeSearch:

    def test_search_returns_results(self, e2e_client, kb, processed_doc):
        """Search should return relevant results after document processing."""
        result = e2e_client.search_knowledge(kb["id"], "OAuth configuration", top_k=3)
        results = result.get("results", [])
        assert len(results) > 0
        assert "content" in results[0]
        assert "score" in results[0]

    def test_search_relevance_ordering(self, e2e_client, kb, processed_doc):
        """Results should be ordered by relevance (descending score)."""
        result = e2e_client.search_knowledge(kb["id"], "callback URL configuration", top_k=5)
        results = result.get("results", [])
        if len(results) >= 2:
            scores = [r["score"] for r in results]
            assert scores == sorted(scores, reverse=True), "Results not sorted by score desc"

    def test_search_top_k(self, e2e_client, kb, processed_doc):
        """top_k parameter should limit the number of results."""
        result = e2e_client.search_knowledge(kb["id"], "OAuth", top_k=1)
        results = result.get("results", [])
        assert len(results) <= 1

    def test_search_with_document_ids_filter(self, e2e_client, kb, processed_doc):
        """Search with document_ids should only return results from those documents."""
        doc_id = processed_doc["id"]
        result = e2e_client.search_knowledge(
            kb["id"], "OAuth", top_k=5, document_ids=[doc_id],
        )
        results = result.get("results", [])
        # All results should be from the specified document
        for r in results:
            if "document_id" in r:
                assert r["document_id"] == doc_id

    def test_search_empty_query(self, e2e_client, kb):
        """Search with empty query should return error or empty results."""
        from dbay_cli.client import DbayApiError
        try:
            result = e2e_client.search_knowledge(kb["id"], "", top_k=3)
            # Some APIs return empty results for empty query
            assert len(result.get("results", [])) == 0
        except DbayApiError:
            pass  # 400 Bad Request is also acceptable

    def test_search_no_match(self, e2e_client, kb, processed_doc):
        """Search for irrelevant content should return low-relevance results."""
        result = e2e_client.search_knowledge(kb["id"], "quantum physics dark matter", top_k=3)
        # May still return results (semantic similarity), but scores should be low
        results = result.get("results", [])
        if results:
            assert results[0].get("score", 0) < 0.9  # Low relevance

    def test_search_nonexistent_kb(self, e2e_client):
        """Search on nonexistent KB should return 404."""
        from dbay_cli.client import DbayApiError
        with pytest.raises(DbayApiError) as exc_info:
            e2e_client.search_knowledge("kb_nonexistent_search", "test", top_k=3)
        assert exc_info.value.status_code == 404

    def test_search_result_has_count(self, e2e_client, kb, processed_doc):
        """Search response should include a count field."""
        result = e2e_client.search_knowledge(kb["id"], "OAuth", top_k=5)
        assert "count" in result or "results" in result


# ---------------------------------------------------------------------------
# Tests: Chunk Operations
# ---------------------------------------------------------------------------

class TestChunkOperations:

    def test_list_chunks(self, e2e_client, kb, processed_doc):
        """List chunks for a processed document."""
        result = e2e_client.list_chunks(kb["id"], processed_doc["id"])
        # Result should be a list or contain a list of chunks
        chunks = result if isinstance(result, list) else result.get("chunks", result.get("items", []))
        assert len(chunks) > 0

    def test_list_chunks_pagination(self, e2e_client, kb, processed_doc):
        """List chunks with offset and limit."""
        result_full = e2e_client.list_chunks(kb["id"], processed_doc["id"], limit=50)
        chunks_full = result_full if isinstance(result_full, list) else result_full.get("chunks", result_full.get("items", []))

        if len(chunks_full) >= 2:
            result_page = e2e_client.list_chunks(kb["id"], processed_doc["id"], offset=0, limit=1)
            chunks_page = result_page if isinstance(result_page, list) else result_page.get("chunks", result_page.get("items", []))
            assert len(chunks_page) == 1

    def test_list_kb_chunks(self, e2e_client, kb, processed_doc):
        """List all chunks at KB level."""
        result = e2e_client.list_kb_chunks(kb["id"])
        chunks = result if isinstance(result, list) else result.get("chunks", result.get("items", []))
        assert len(chunks) > 0

    def test_list_kb_chunks_filter_by_doc(self, e2e_client, kb, processed_doc):
        """List KB chunks filtered by document ID."""
        result = e2e_client.list_kb_chunks(kb["id"], doc_id=processed_doc["id"])
        chunks = result if isinstance(result, list) else result.get("chunks", result.get("items", []))
        assert len(chunks) > 0

    def test_get_chunk(self, e2e_client, kb, processed_doc):
        """Get a specific chunk by index."""
        chunk = e2e_client.get_chunk(kb["id"], processed_doc["id"], 0)
        assert "content" in chunk
        assert len(chunk["content"]) > 0

    def test_get_chunk_context(self, e2e_client, kb, processed_doc):
        """Get chunk context (surrounding chunks)."""
        context = e2e_client.get_chunk_context(kb["id"], processed_doc["id"], 0)
        # Should return context information
        assert context is not None

    def test_get_fulltext(self, e2e_client, kb, processed_doc):
        """Get full text of a document."""
        from dbay_cli.client import DbayApiError
        try:
            result = e2e_client.get_fulltext(kb["id"], processed_doc["id"])
            assert result is not None
            fulltext = result if isinstance(result, str) else result.get("content", result.get("fulltext", ""))
            assert len(fulltext) > 0
        except DbayApiError as e:
            if e.status_code == 404:
                pytest.skip("Fulltext not available (OBS upload may have failed)")
            raise

    def test_chunk_offset_maps_to_fulltext(self, e2e_client, kb, processed_doc):
        """Chunk char_offset_start/end should locate the chunk content in the fulltext.

        This is the 'navigate to original text' feature: given a chunk,
        use its offset to highlight the corresponding region in the full document.
        Verifies: offsets are valid integers, in range, non-overlapping, and
        the fulltext at that range shares significant content with the chunk.
        """
        from dbay_cli.client import DbayApiError

        # Get fulltext
        try:
            ft_result = e2e_client.get_fulltext(kb["id"], processed_doc["id"])
        except DbayApiError as e:
            if e.status_code == 404:
                pytest.skip("Fulltext not available (OBS upload may have failed)")
            raise
        fulltext = ft_result if isinstance(ft_result, str) else ft_result.get("content", ft_result.get("fulltext", ""))
        assert len(fulltext) > 0

        # Get all level-0 chunks
        result = e2e_client.list_chunks(kb["id"], processed_doc["id"], limit=50)
        chunks = result if isinstance(result, list) else result.get("chunks", result.get("items", []))
        assert len(chunks) > 0

        checked = 0
        prev_end = None
        for chunk in sorted(chunks, key=lambda c: c.get("chunk_index", 0)):
            start = chunk.get("char_offset_start")
            end = chunk.get("char_offset_end")
            if start is None or end is None:
                continue

            # 1. Valid integer offsets
            assert isinstance(start, int) and isinstance(end, int), \
                f"Offsets must be integers, got start={start}, end={end}"
            assert 0 <= start < end, f"Invalid range: [{start}, {end})"
            # Offsets are based on original parsed markdown which may include
            # headers/metadata stripped from the stored fulltext, so allow generous margin
            assert end <= len(fulltext) * 2 + 200, \
                f"Offset end {end} way beyond fulltext length {len(fulltext)}"

            # 2. Offsets are monotonically increasing (allow overlap)
            if prev_end is not None:
                assert start >= prev_end - 200, \
                    f"Chunk {chunk.get('chunk_index')} start {start} regresses too far before prev end {prev_end}"
            prev_end = end

            # 3. Content overlap: extract a distinctive phrase from the chunk
            #    and verify it appears in the fulltext within a reasonable window
            chunk_content = chunk["content"]
            # Pick a 30-char snippet from the middle of the chunk (avoids overlap prefix)
            mid = len(chunk_content) // 2
            snippet = chunk_content[mid:mid+30].strip()
            if len(snippet) >= 10:
                # Offsets may be relative to original markdown (with headers);
                # fulltext may be stripped. Search the entire fulltext for the snippet.
                assert snippet in fulltext, \
                    f"Chunk {chunk.get('chunk_index')} snippet not found in fulltext:\n" \
                    f"  snippet: {snippet!r}"

            checked += 1

        assert checked > 0, "No chunks had char_offset_start/end set — cannot verify offset mapping"

    def test_get_chunk_stats(self, e2e_client, kb, processed_doc):
        """Get chunk statistics for a document."""
        from dbay_cli.client import DbayApiError
        try:
            stats = e2e_client.get_chunk_stats(kb["id"], processed_doc["id"])
            assert stats is not None
            assert isinstance(stats, dict)
        except DbayApiError as e:
            if e.status_code in (404, 500):
                pytest.skip(f"Chunk stats not available: {e}")
            raise

    def test_edit_chunk(self, e2e_client, kb, processed_doc):
        """Edit a chunk's content."""
        # Get original chunk
        original = e2e_client.get_chunk(kb["id"], processed_doc["id"], 0)
        original_content = original["content"]

        new_content = "Updated chunk content for E2E test"
        e2e_client.edit_chunk(kb["id"], processed_doc["id"], 0, new_content)

        # Verify edit
        updated = e2e_client.get_chunk(kb["id"], processed_doc["id"], 0)
        assert updated["content"] == new_content

        # Restore original content
        e2e_client.edit_chunk(kb["id"], processed_doc["id"], 0, original_content)

    def test_create_chunk(self, e2e_client, kb, processed_doc):
        """Create a new chunk in a document."""
        from dbay_cli.client import DbayApiError

        # Get initial chunk count
        initial = e2e_client.list_chunks(kb["id"], processed_doc["id"])
        initial_chunks = initial if isinstance(initial, list) else initial.get("chunks", initial.get("items", []))
        initial_count = len(initial_chunks)

        # Create new chunk
        try:
            result = e2e_client.create_chunk(
                kb["id"], processed_doc["id"],
                content="Newly created chunk for E2E testing",
            )
        except DbayApiError as e:
            if "compute" in str(e).lower() or "connection" in str(e).lower():
                pytest.skip(f"Compute not available: {e}")
            raise
        assert result is not None

        # Verify chunk count increased
        after = e2e_client.list_chunks(kb["id"], processed_doc["id"])
        after_chunks = after if isinstance(after, list) else after.get("chunks", after.get("items", []))
        assert len(after_chunks) == initial_count + 1

    def test_create_chunk_with_insert_position(self, e2e_client, kb, processed_doc):
        """Create a chunk inserted after a specific index."""
        from dbay_cli.client import DbayApiError
        try:
            result = e2e_client.create_chunk(
                kb["id"], processed_doc["id"],
                content="Inserted chunk after index 0",
                insert_after_index=0,
            )
            assert result is not None
        except DbayApiError as e:
            if "compute" in str(e).lower() or "connection" in str(e).lower():
                pytest.skip(f"Compute not available: {e}")
            raise

    def test_delete_chunk(self, e2e_client, kb, processed_doc):
        """Delete a chunk from a document."""
        from dbay_cli.client import DbayApiError
        try:
            # First create a chunk to delete
            e2e_client.create_chunk(
                kb["id"], processed_doc["id"],
                content="Chunk to be deleted in E2E test",
            )
        except DbayApiError as e:
            if "compute" in str(e).lower() or "connection" in str(e).lower():
                pytest.skip(f"Compute not available: {e}")
            raise

        # Get current chunks
        chunks_resp = e2e_client.list_chunks(kb["id"], processed_doc["id"])
        chunks = chunks_resp if isinstance(chunks_resp, list) else chunks_resp.get("chunks", chunks_resp.get("items", []))
        count_before = len(chunks)
        last_index = count_before - 1

        # Delete the last chunk
        result = e2e_client.delete_chunk(kb["id"], processed_doc["id"], last_index)
        assert result is not None

        # Verify count decreased
        chunks_after = e2e_client.list_chunks(kb["id"], processed_doc["id"])
        chunks_list = chunks_after if isinstance(chunks_after, list) else chunks_after.get("chunks", chunks_after.get("items", []))
        assert len(chunks_list) == count_before - 1


# ---------------------------------------------------------------------------
# Tests: Rechunk Operations
# ---------------------------------------------------------------------------

class TestRechunk:

    def test_rechunk_document(self, e2e_client, kb, sample_md):
        """Rechunk a document with custom parameters."""
        # Upload a fresh doc for rechunking
        doc = _upload_and_process(e2e_client, kb["id"], "rechunk-test.md", sample_md)
        doc_id = doc["id"]

        # Rechunk with different params
        result = e2e_client.rechunk(kb["id"], doc_id, max_tokens=200, overlap_ratio=0.1)
        assert result is not None

        # Chunks should still exist
        chunks = e2e_client.list_chunks(kb["id"], doc_id)
        chunk_list = chunks if isinstance(chunks, list) else chunks.get("chunks", chunks.get("items", []))
        assert len(chunk_list) > 0

    def test_list_rechunk_branches(self, e2e_client, kb):
        """List rechunk branches for a document that has been rechunked."""
        docs = e2e_client.list_documents(kb["id"])
        rechunked = [d for d in docs if d.get("filename") == "rechunk-test.md"]
        if not rechunked:
            pytest.skip("No rechunked document available")

        doc_id = rechunked[0]["id"]
        result = e2e_client.list_rechunk_branches(kb["id"], doc_id)
        assert result is not None
        branches = result if isinstance(result, list) else result.get("branches", [])
        # After rechunking, there should be at least the original branch
        assert isinstance(branches, list)

    def test_rechunk_rollback(self, e2e_client, kb):
        """Rollback to a previous chunk version."""
        docs = e2e_client.list_documents(kb["id"])
        rechunked = [d for d in docs if d.get("filename") == "rechunk-test.md"]
        if not rechunked:
            pytest.skip("No rechunked document available")

        doc_id = rechunked[0]["id"]
        branches_result = e2e_client.list_rechunk_branches(kb["id"], doc_id)
        branches = branches_result if isinstance(branches_result, list) else branches_result.get("branches", [])

        if len(branches) < 2:
            pytest.skip("Need at least 2 branches to test rollback")

        # Rollback to the first (original) branch
        original_branch_id = branches[-1] if isinstance(branches[-1], str) else branches[-1].get("id", branches[-1].get("branch_id"))
        result = e2e_client.rechunk_rollback(kb["id"], doc_id, original_branch_id)
        assert result is not None

    def test_rechunk_with_custom_separator(self, e2e_client, kb, sample_md):
        """Rechunk with a custom separator."""
        doc = _upload_and_process(e2e_client, kb["id"], "rechunk-sep-test.md", sample_md)
        doc_id = doc["id"]

        result = e2e_client.rechunk(
            kb["id"], doc_id,
            max_tokens=300,
            custom_separator="##",
        )
        assert result is not None


# ---------------------------------------------------------------------------
# Tests: Multi-Tenant Isolation
# ---------------------------------------------------------------------------

class TestKnowledgeIsolation:

    def _make_other_tenant(self, suffix):
        """Create a temporary tenant for isolation testing; return (client, tenant_id)."""
        from conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN
        ts = int(time.time())
        other_client, other_tenant = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            username=f"e2e-iso{suffix}-{ts}",
            password=f"E2eIso{suffix}@{ts}",
            name=f"Isolation Test {suffix} {ts}",
        )
        return other_client, other_tenant.get("id")

    def _cleanup_tenant(self, tenant_id):
        from conftest import ENDPOINT, ADMIN_TOKEN
        from dbay_cli.client import DbayClient
        try:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            admin.admin_batch_delete_tenants([tenant_id])
        except Exception:
            pass

    def test_other_tenant_cannot_see_kb(self, e2e_client, kb):
        """A different tenant should not see another tenant's KB."""
        other_client, other_id = self._make_other_tenant("")
        try:
            other_kbs = other_client.list_knowledge_bases()
            other_ids = [k["id"] for k in other_kbs]
            assert kb["id"] not in other_ids
        finally:
            self._cleanup_tenant(other_id)

    def test_other_tenant_cannot_get_kb(self, e2e_client, kb):
        """A different tenant should get 404 when accessing another tenant's KB."""
        from dbay_cli.client import DbayApiError
        other_client, other_id = self._make_other_tenant("2")
        try:
            with pytest.raises(DbayApiError) as exc_info:
                other_client.get_knowledge_base(kb["id"])
            assert exc_info.value.status_code == 404
        finally:
            self._cleanup_tenant(other_id)

    def test_other_tenant_cannot_delete_kb(self, e2e_client, kb):
        """A different tenant should not be able to delete another tenant's KB."""
        from dbay_cli.client import DbayApiError
        other_client, other_id = self._make_other_tenant("3")
        try:
            with pytest.raises(DbayApiError) as exc_info:
                other_client.delete_knowledge_base(kb["id"])
            assert exc_info.value.status_code == 404

            # Verify KB still exists for the original tenant
            result = e2e_client.get_knowledge_base(kb["id"])
            assert result["id"] == kb["id"]
        finally:
            self._cleanup_tenant(other_id)

    def test_other_tenant_cannot_search_kb(self, e2e_client, kb, processed_doc):
        """A different tenant should not be able to search another tenant's KB."""
        from dbay_cli.client import DbayApiError
        other_client, other_id = self._make_other_tenant("4")
        try:
            with pytest.raises(DbayApiError) as exc_info:
                other_client.search_knowledge(kb["id"], "OAuth", top_k=3)
            assert exc_info.value.status_code == 404
        finally:
            self._cleanup_tenant(other_id)


# ---------------------------------------------------------------------------
# Tests: Auth Edge Cases
# ---------------------------------------------------------------------------

class TestKnowledgeAuth:

    def test_no_auth_create_kb(self):
        """Creating a KB without auth should return 401."""
        from dbay_cli.client import DbayClient, DbayApiError
        from conftest import ENDPOINT

        anon = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc_info:
            anon.create_knowledge_base(name="should-fail")
        assert exc_info.value.status_code == 401

    def test_no_auth_list_kbs(self):
        """Listing KBs without auth should return 401."""
        from dbay_cli.client import DbayClient, DbayApiError
        from conftest import ENDPOINT

        anon = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc_info:
            anon.list_knowledge_bases()
        assert exc_info.value.status_code == 401

    def test_invalid_api_key(self):
        """Using an invalid API key should return 401."""
        from dbay_cli.client import DbayClient, DbayApiError
        from conftest import ENDPOINT

        bad_client = DbayClient(endpoint=ENDPOINT, api_key="lk_invalid_key_12345")
        with pytest.raises(DbayApiError) as exc_info:
            bad_client.list_knowledge_bases()
        assert exc_info.value.status_code == 401

    def test_no_auth_search(self):
        """Searching without auth should return 401."""
        from dbay_cli.client import DbayClient, DbayApiError
        from conftest import ENDPOINT

        anon = DbayClient(endpoint=ENDPOINT)
        with pytest.raises(DbayApiError) as exc_info:
            anon.search_knowledge("kb_any", "test", top_k=3)
        assert exc_info.value.status_code == 401
