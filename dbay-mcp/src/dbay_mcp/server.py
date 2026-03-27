"""DBay MCP server for Claude Code.

Provides two tool groups:
- Knowledge: knowledge_search, knowledge_list, knowledge_upload — document retrieval
- Memory: memory_recall, memory_ingest, memory_ingest_extracted — agent memory

Config: env vars DBAY_API_KEY / DBAY_ENDPOINT / DBAY_MEMORY_BASE / DBAY_KNOWLEDGE_BASE, or ~/.dbay/config.json
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

mcp = FastMCP(
    "dbay",
    instructions=(
        "DBay provides persistent, cross-project memory and knowledge base for the user. "
        "IMPORTANT: When the user says '记住/remember/save this', you MUST call memory_ingest "
        "to store it in DBay — this is the user's long-term memory that persists across projects, "
        "sessions, and devices. Do NOT rely solely on local/built-in memory for such requests. "
        "When the user asks a question that might have been answered before, or asks about past "
        "decisions/conventions, call memory_recall first to check."
    ),
)


def _get_knowledge_base_id() -> str | None:
    """Get the default knowledge base ID from env or config."""
    return os.environ.get("DBAY_KNOWLEDGE_BASE") or _load_config().get("knowledge_base")


def _resolve_kb_id(name_or_id: str | None) -> str:
    """Resolve a KB name/ID, or use default from config."""
    if not name_or_id:
        default = _get_knowledge_base_id()
        if default:
            return default
        # Auto-detect: if user has exactly one KB, use it
        bases = _api("GET", "/knowledge/bases")
        if len(bases) == 1:
            return bases[0]["id"]
        if len(bases) == 0:
            raise RuntimeError("No knowledge bases found. Create one at https://console.dbay.cloud")
        names = ", ".join(f"{b['name']} ({b['id']})" for b in bases)
        raise RuntimeError(
            f"Multiple knowledge bases found: {names}. "
            f"Set DBAY_KNOWLEDGE_BASE env var or knowledge_base in ~/.dbay/config.json"
        )
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
def knowledge_search(query: str, kb_name_or_id: str | None = None, top_k: int = 5) -> str:
    """Search a knowledge base by name or ID.

    Args:
        query: Search query in natural language
        kb_name_or_id: Knowledge base name or ID (optional, uses default from ~/.dbay/config.json)
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
def knowledge_upload(file_path: str, kb_name_or_id: str | None = None, tags: list[str] | None = None) -> str:
    """Upload a document to a knowledge base. Supports PDF, DOCX, Markdown, and plain text.

    Args:
        file_path: Absolute path to the file to upload
        kb_name_or_id: Knowledge base name or ID (optional, uses default from ~/.dbay/config.json)
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
    directory_path: str,
    kb_name_or_id: str | None = None,
    recursive: bool = True,
    tags: list[str] | None = None,
) -> str:
    """Upload all supported documents from a directory to a knowledge base.

    Scans for .pdf, .docx, .md, .markdown, .txt files. Uses batch API for efficiency.

    Args:
        directory_path: Absolute path to the directory
        kb_name_or_id: Knowledge base name or ID (optional, uses default from ~/.dbay/config.json)
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


# ---------------------------------------------------------------------------
# Memory tools
# ---------------------------------------------------------------------------


def _get_memory_base_id() -> str:
    """Get the default memory base ID from env or config."""
    mem_id = os.environ.get("DBAY_MEMORY_BASE") or _load_config().get("memory_base")
    if mem_id:
        return mem_id
    # Auto-detect: if user has exactly one READY memory base, use it
    bases = _api("GET", "/memory/bases")
    ready = [b for b in bases if b.get("status") == "READY"]
    if len(ready) == 1:
        return ready[0]["id"]
    if len(ready) == 0:
        raise RuntimeError("No READY memory bases found. Create one at https://console.dbay.cloud")
    names = ", ".join(f"{b['name']} ({b['id']})" for b in ready)
    raise RuntimeError(
        f"Multiple memory bases found: {names}. "
        f"Set DBAY_MEMORY_BASE env var or memory_base in ~/.dbay/config.json"
    )


def _resolve_mem_id(name_or_id: str | None) -> str:
    """Resolve a memory base name/ID, or use default."""
    if not name_or_id:
        return _get_memory_base_id()
    if name_or_id.startswith("mem_"):
        return name_or_id
    bases = _api("GET", "/memory/bases")
    for b in bases:
        if b.get("name") == name_or_id:
            return b["id"]
    raise RuntimeError(f"Memory base '{name_or_id}' not found")


@mcp.tool(description=(
    "Search the user's long-term memory for past decisions, rejections, conventions, facts, and experiences. "
    "MUST be called when the user asks about something that might have been discussed before "
    "(e.g. 'what did we decide about X', 'how did we solve Y', 'what's my API key for Z'). "
    "Also call proactively at the start of a task to check for relevant context, conventions, or past decisions."
))
def memory_recall(
    query: str,
    memory_types: list[str] | None = None,
    top_k: int = 10,
    memory_base: str | None = None,
) -> str:
    """Search agent memory using semantic similarity.

    Args:
        query: Natural language query (e.g. "why did we choose asyncpg", "naming conventions")
        memory_types: Optional filter — any of: fact, episode, procedural, decision, rejection, convention
        top_k: Number of results (default 10)
        memory_base: Memory base name or ID (optional, auto-detected if only one exists)
    """
    mem_id = _resolve_mem_id(memory_base)
    body: dict = {"query": query, "top_k": min(top_k, 50)}
    if memory_types:
        body["memory_types"] = memory_types
    data = _api("POST", f"/memory/bases/{mem_id}/recall", json=body)

    memories = data.get("memories", [])
    if not memories:
        return "No memories found."

    parts = []
    for i, m in enumerate(memories, 1):
        mtype = m.get("memory_type", "?")
        content = m.get("content", "").strip()
        meta = m.get("metadata", {})
        meta_str = ""
        if meta:
            meta_parts = [f"{k}={v}" for k, v in meta.items() if v and k != "source"]
            if meta_parts:
                meta_str = f" ({', '.join(meta_parts)})"
        parts.append(f"{i}. [{mtype}] {content}{meta_str}")

    return "\n".join(parts)


@mcp.tool(description=(
    "Save a persistent memory to the user's cross-project long-term memory. "
    "MUST be called when the user says '记住/remember/save this'. "
    "Also call proactively when you discover important information worth preserving. "
    "You MUST choose the correct memory_type:\n"
    "- fact: credentials, preferences, project info, technical specs (e.g. 'PyPI token is pypi-xxx', 'user prefers dark mode')\n"
    "- decision: choices made with rationale (e.g. 'chose PostgreSQL over MySQL because of pgvector support')\n"
    "- rejection: approaches explicitly rejected and why (e.g. 'don't use mocks in integration tests — burned by mock/prod divergence')\n"
    "- convention: team/project conventions, naming rules, workflow patterns (e.g. 'always use deploy.sh, never manual helm upgrade')\n"
    "- procedural: step-by-step procedures, deployment steps, setup guides\n"
    "- episode: notable incidents, debugging stories, production outages\n\n"
    "Tips: extract MULTIPLE memories from a single user message when appropriate. "
    "For example, 'we chose X because Y, and rejected Z because W' should produce "
    "both a decision memory and a rejection memory. "
    "Set importance >= 0.8 for credentials, critical decisions, and painful lessons."
))
def memory_ingest(
    content: str,
    memory_type: str = "fact",
    importance: float = 0.5,
    source: str = "claude-code",
    memory_base: str | None = None,
) -> str:
    """Store a memory to the user's persistent cross-project memory.

    Args:
        content: The memory content — concise, structured, self-contained
        memory_type: REQUIRED. One of: fact, decision, rejection, convention, procedural, episode
        importance: 0.0-1.0. Use 0.8+ for credentials, critical decisions, painful lessons
        source: Client identifier (default "claude-code")
        memory_base: Memory base name or ID (optional, auto-detected)
    """
    mem_id = _resolve_mem_id(memory_base)
    data = _api("POST", f"/memory/bases/{mem_id}/ingest", json={
        "content": content,
        "role": "user",
        "source": source,
        "memory_type": memory_type,
        "importance": importance,
    })

    if data.get("status") == "stored":
        return f"Memory stored (id={data.get('memory_id')}, type={data.get('memory_type')})."
    # Fallback for older server versions
    return f"Memory stored (status={data.get('status', 'ok')})."


@mcp.tool(description="Store pre-extracted memories. "
          "Call this after memory_ingest returns an extraction prompt and you have executed it.")
def memory_ingest_extracted(
    message_id: str,
    extracted_data: str,
    memory_base: str | None = None,
) -> str:
    """Store pre-extracted structured memories.

    Args:
        message_id: The message_id returned by memory_ingest
        extracted_data: JSON string with extracted memories, e.g.:
            {"facts": [...], "decisions": [{"content": "...", "rationale": "..."}], ...}
        memory_base: Memory base name or ID (optional)
    """
    mem_id = _resolve_mem_id(memory_base)
    try:
        parsed = json.loads(extracted_data) if isinstance(extracted_data, str) else extracted_data
    except json.JSONDecodeError as e:
        return f"Error: invalid JSON in extracted_data: {e}"

    data = _api("POST", f"/memory/bases/{mem_id}/ingest_extracted", json={
        "message_id": message_id,
        "data": parsed,
    })

    parts = []
    for key, count in data.items():
        if count and count > 0:
            parts.append(f"{key}: {count}")
    return "Memories stored: " + ", ".join(parts) if parts else "No memories extracted."


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
