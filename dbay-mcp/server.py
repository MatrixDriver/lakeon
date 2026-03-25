"""DBay Knowledge Base MCP server for Claude Code.

Provides knowledge_search, knowledge_list, and knowledge_upload tools
that call the DBay API over HTTPS.

Config: env vars DBAY_API_KEY / DBAY_ENDPOINT, or ~/.dbay/config.json
"""

import json
import os
from pathlib import Path

import httpx
from fastmcp import FastMCP

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

CONFIG_FILE = Path.home() / ".dbay" / "config.json"


def _load_config() -> dict:
    if CONFIG_FILE.exists():
        return json.loads(CONFIG_FILE.read_text())
    return {}


def _get_endpoint() -> str:
    return os.environ.get("DBAY_ENDPOINT") or _load_config().get("endpoint") or "https://api.dbay.cloud:8443"


def _get_api_key() -> str | None:
    return os.environ.get("DBAY_API_KEY") or _load_config().get("api_key")


# ---------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------

_client: httpx.Client | None = None


def _http() -> httpx.Client:
    global _client
    if _client is None:
        api_key = _get_api_key()
        if not api_key:
            raise RuntimeError(
                "No API key found. Set DBAY_API_KEY env var or run `dbay login`."
            )
        _client = httpx.Client(
            base_url=f"{_get_endpoint()}/api/v1",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            verify=False,
            timeout=120,
        )
    return _client


def _api(method: str, path: str, **kwargs) -> dict:
    resp = _http().request(method, path, **kwargs)
    if resp.status_code >= 400:
        try:
            body = resp.json()
        except Exception:
            body = resp.text
        raise RuntimeError(f"API {resp.status_code}: {body}")
    if resp.status_code == 204:
        return {}
    return resp.json() if resp.content else {}


# ---------------------------------------------------------------------------
# MCP server
# ---------------------------------------------------------------------------

mcp = FastMCP("dbay-knowledge")


def _resolve_kb_id(name_or_id: str) -> str:
    """Resolve a KB name to its ID. If it looks like an ID (kb_ prefix), return as-is."""
    if name_or_id.startswith("kb_"):
        return name_or_id
    bases = _api("GET", "/knowledge/bases")
    for kb in bases:
        if kb.get("name") == name_or_id:
            return kb["id"]
    raise RuntimeError(f"Knowledge base '{name_or_id}' not found")


@mcp.tool(description="List all knowledge bases")
def knowledge_list() -> str:
    """List all knowledge bases with their id, name, type, status, and document count."""
    bases = _api("GET", "/knowledge/bases")
    lines = []
    for kb in bases:
        lines.append(
            f"- {kb['name']} (id={kb['id']}, type={kb.get('type','DOCUMENT')}, "
            f"model={kb.get('embedding_model','?')}, "
            f"status={kb.get('status','?')}, docs={kb.get('document_count',0)})"
        )
    return "\n".join(lines) if lines else "No knowledge bases found."


@mcp.tool(description="Search a knowledge base using hybrid vector + BM25 search")
def knowledge_search(kb_name_or_id: str, query: str, top_k: int = 5) -> str:
    """Search a knowledge base by name or ID.

    Args:
        kb_name_or_id: Knowledge base name or ID (e.g. "my-kb" or "kb_abc123")
        query: Search query in natural language
        top_k: Number of results to return (default 5, max 50)
    """
    kb_id = _resolve_kb_id(kb_name_or_id)
    body = {"kb_id": kb_id, "query": query, "top_k": min(top_k, 50)}
    data = _api("POST", "/knowledge/search", json=body)

    results = data.get("results", [])
    if not results:
        return "No results found."

    parts = []
    if data.get("rewritten_query"):
        parts.append(f"[Query rewritten to: {data['rewritten_query']}]\n")

    for i, r in enumerate(results, 1):
        meta = r.get("metadata", {})
        if isinstance(meta, str):
            try:
                meta = json.loads(meta)
            except Exception:
                meta = {}
        doc_id = meta.get("document_id", "?")
        score = r.get("score", 0)
        content = r.get("content", "").strip()
        parts.append(f"### Result {i} (score={score:.3f}, doc={doc_id})\n{content}")

    return "\n\n".join(parts)


@mcp.tool(description="Upload a local file to a knowledge base for processing")
def knowledge_upload(kb_name_or_id: str, file_path: str, tags: list[str] | None = None) -> str:
    """Upload a document to a knowledge base. Supports PDF, DOCX, Markdown, and plain text.

    Args:
        kb_name_or_id: Knowledge base name or ID
        file_path: Absolute path to the file to upload
        tags: Optional list of tags for the document
    """
    kb_id = _resolve_kb_id(kb_name_or_id)
    fp = Path(file_path).expanduser().resolve()
    if not fp.exists():
        raise RuntimeError(f"File not found: {fp}")

    filename = fp.name

    # 1. Get presigned upload URL
    params = f"?kb_id={kb_id}&filename={filename}"
    if tags:
        for t in tags:
            params += f"&tags={t}"
    url_data = _api("GET", f"/knowledge/upload-url{params}")
    doc_id = url_data["document_id"]
    upload_url = url_data["upload_url"]

    # 2. Upload file to OBS via presigned URL
    content_type = _guess_content_type(filename)
    with open(fp, "rb") as f:
        put_resp = httpx.put(
            upload_url,
            content=f.read(),
            headers={"Content-Type": content_type},
            verify=False,
            timeout=300,
        )
    if put_resp.status_code >= 400:
        raise RuntimeError(f"Upload failed ({put_resp.status_code}): {put_resp.text}")

    # 3. Trigger processing
    doc = _api("POST", f"/knowledge/documents/{doc_id}/process")
    status = doc.get("status", "PROCESSING")
    return f"Uploaded {filename} → document {doc_id} (status={status}). Processing will run in the background."


SUPPORTED_EXTENSIONS = {".pdf", ".docx", ".md", ".markdown", ".txt"}

BATCH_SIZE = 20
UPLOAD_CONCURRENCY = 3


@mcp.tool(description="Upload all supported files from a local directory to a knowledge base")
def knowledge_upload_directory(
    kb_name_or_id: str,
    directory_path: str,
    recursive: bool = True,
    tags: list[str] | None = None,
) -> str:
    """Upload all supported documents from a directory to a knowledge base.

    Scans for .pdf, .docx, .md, .markdown, .txt files. Uses batch API for efficiency.

    Args:
        kb_name_or_id: Knowledge base name or ID
        directory_path: Absolute path to the directory
        recursive: Whether to scan subdirectories (default True)
        tags: Optional tags to apply to all uploaded documents
    """
    import asyncio

    kb_id = _resolve_kb_id(kb_name_or_id)
    dir_path = Path(directory_path).expanduser().resolve()
    if not dir_path.is_dir():
        raise RuntimeError(f"Not a directory: {dir_path}")

    # Collect supported files
    if recursive:
        files = [f for f in dir_path.rglob("*") if f.is_file() and f.suffix.lower() in SUPPORTED_EXTENSIONS]
    else:
        files = [f for f in dir_path.iterdir() if f.is_file() and f.suffix.lower() in SUPPORTED_EXTENSIONS]

    if not files:
        return f"No supported files found in {dir_path}. Supported: {', '.join(sorted(SUPPORTED_EXTENSIONS))}"

    files.sort(key=lambda f: f.name)

    total = len(files)
    uploaded = 0
    failed = 0
    processed_batches = 0
    errors: list[str] = []

    # Process in batches of BATCH_SIZE
    for batch_start in range(0, total, BATCH_SIZE):
        batch_files = files[batch_start:batch_start + BATCH_SIZE]
        file_specs = [{"filename": f.name, **({"tags": tags} if tags else {})} for f in batch_files]

        try:
            # 1. Get presigned URLs
            batch_resp = _api("POST", "/knowledge/batch-upload-urls", json={"kb_id": kb_id, "files": file_specs})
            doc_items = batch_resp["documents"]

            # 2. Upload files concurrently (UPLOAD_CONCURRENCY at a time)
            doc_ids: list[str] = []
            for i in range(0, len(doc_items), UPLOAD_CONCURRENCY):
                chunk_items = doc_items[i:i + UPLOAD_CONCURRENCY]
                chunk_files = batch_files[i:i + UPLOAD_CONCURRENCY]
                for item, fp in zip(chunk_items, chunk_files):
                    content_type = _guess_content_type(fp.name)
                    with open(fp, "rb") as fh:
                        put_resp = httpx.put(
                            item["upload_url"],
                            content=fh.read(),
                            headers={"Content-Type": content_type},
                            verify=False,
                            timeout=300,
                        )
                    if put_resp.status_code < 400:
                        doc_ids.append(item["document_id"])
                        uploaded += 1
                    else:
                        failed += 1
                        errors.append(f"{fp.name}: upload HTTP {put_resp.status_code}")

            # 3. Trigger batch processing
            if doc_ids:
                _api("POST", "/knowledge/batch-process", json={"document_ids": doc_ids})
                processed_batches += 1

        except Exception as e:
            failed += len(batch_files)
            errors.append(f"Batch starting at {batch_files[0].name}: {e}")

    # Summary
    parts = [f"Directory: {dir_path}", f"Total files found: {total}", f"Uploaded: {uploaded}", f"Failed: {failed}"]
    if processed_batches:
        parts.append(f"Batch jobs submitted: {processed_batches} (processing runs in background)")
    if errors:
        parts.append(f"Errors:\n" + "\n".join(f"  - {e}" for e in errors[:10]))
    return "\n".join(parts)


def _guess_content_type(filename: str) -> str:
    ext = Path(filename).suffix.lower()
    return {
        ".pdf": "application/pdf",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".md": "text/markdown",
        ".txt": "text/plain",
    }.get(ext, "application/octet-stream")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    mcp.run(transport="stdio")
