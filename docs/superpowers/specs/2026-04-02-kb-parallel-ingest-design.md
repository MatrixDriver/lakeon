# Knowledge Base Parallel Ingestion & Directory Structure

## Problem

Uploading 2600+ documents to a single knowledge base causes:
1. **130 Job Pods created** (20 docs/batch = 130 batches), overwhelming the system
2. **2-thread pod launcher bottleneck** in JobService — takes 38+ minutes to start all pods
3. **API rate limit storm** — all pods hit SiliconFlow embedding/LLM API simultaneously
4. **Directory structure lost** — `webkitRelativePath` is discarded, only filename preserved
5. **No metadata support** — can't attach structured KV pairs (author, category) to documents

## Design

### 1. Document Model Changes

#### 1.1 New `folder` field on DocumentEntity

```java
@Column(name = "folder", length = 512)
private String folder;  // e.g. "中国哲学/老子", empty string for root
```

- Stores the relative directory path from the upload root
- Does not include the filename
- Root-level files have `folder = ""`

#### 1.2 New `metadata` field on DocumentEntity

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "metadata", columnDefinition = "jsonb")
private Map<String, String> metadata = new LinkedHashMap<>();
```

- Structured KV pairs: `{"作者": "余秋雨", "朝代": "清"}`
- Separate from tags — tags are flat labels for categorization, metadata is structured attributes
- Searchable via JSONB operators

#### 1.3 Tags auto-population from folder

When a document has `folder = "中国哲学/老子"`, the system auto-generates tags `["中国哲学", "老子"]` merged with any user-specified tags.

### 2. Upload Flow Redesign

#### 2.1 Frontend changes (KnowledgeBaseDetail.vue)

**Phase 1 — Upload to OBS** (unchanged batching for presigned URLs):
- Still batch 20 files at a time for `batch-upload-urls` (OBS presigned URL limit)
- Still 3 concurrent uploads
- NEW: pass `folder` (from `file.webkitRelativePath`) and optional `metadata` in `fileSpecs`

**Phase 2 — Trigger ingestion** (new):
- After ALL files uploaded to OBS, call **one** new API: `POST /knowledge/ingest`
- Pass all `document_ids` at once (no 20-doc limit)
- Backend splits into N tasks based on tenant quota

The `batch-process` API remains available for small batches (single file upload, retry).

#### 2.2 New API: `POST /knowledge/ingest`

```
POST /api/v1/knowledge/ingest
{
  "document_ids": ["doc_xxx", "doc_yyy", ...],  // all document IDs
  "metadata": {"作者": "余秋雨"}                 // optional, applied to all docs
}
```

Response:
```json
{
  "task_ids": ["kwt_aaa", "kwt_bbb"],
  "pod_count": 2,
  "documents_per_pod": [1300, 1300]
}
```

Backend logic:
1. Validate all documents exist, are PENDING, belong to same KB
2. Validate target database exists and is not in ERROR state
3. Apply metadata to all documents (merge with existing)
4. Query tenant quota `kbMaxConcurrentJobs` (default 2)
5. Split document_ids into N equal groups
6. Create N `BATCH_DOCUMENT_PARSE` tasks, each with its group
7. Mark all documents as PROCESSING

#### 2.3 `batch-upload-urls` API changes

Add `folder` to file spec:
```json
{
  "kb_id": "kb_xxx",
  "files": [
    {"filename": "道德经.txt", "folder": "中国哲学/老子", "tags": ["经典"]},
    {"filename": "第一回.txt", "folder": "红楼梦"}
  ],
  "metadata": {"分类": "古典文学"}
}
```

- `folder` saved to DocumentEntity.folder
- `metadata` (top-level) applied to all documents in batch
- Each folder path segment auto-added to document tags

### 3. Parallel Ingestion with Tenant Quota

#### 3.1 Tenant quota

Add to `LakeonProperties`:
```yaml
lakeon:
  knowledge:
    max-concurrent-jobs: 2  # default tenant quota
```

Future: per-tenant override via TenantEntity field.

#### 3.2 KbWriteQueue drain() concurrency control

Current behavior: drain() loop submits ALL heavyweight tasks without limit.

New behavior:
```java
// In drain() while loop, before picking next task:
long runningHeavy = active.stream()
    .filter(t -> t.getStatus() == KbWriteTaskStatus.RUNNING
              && !LIGHTWEIGHT_TYPES.contains(t.getType()))
    .count();
int quota = props.getKnowledge().getMaxConcurrentJobs();
if (runningHeavy >= quota) {
    log.debug("db {} at quota ({}/{}), pausing drain", databaseId, runningHeavy, quota);
    return;
}
```

When a heavyweight task completes (callback), trigger `drain(databaseId)` to pick up the next queued task.

#### 3.3 JobService executor pool

Increase from 2 to 8 threads. The pod launch thread pool is no longer the concurrency bottleneck — the tenant quota in drain() controls actual parallelism.

### 4. Frontend: Folder Tree View

Replace flat document list with a folder browser:

**Layout:**
- Breadcrumb navigation: `知识库 / 中国哲学 / 老子`
- Folder cards/rows with document count and aggregate size
- Click folder to enter, click document to view detail
- Root level shows top-level folders + root-level documents

**API support:**
- `GET /knowledge/documents?kb_id=xxx&folder=中国哲学/老子` — filter by folder (exact prefix match)
- `GET /knowledge/folders?kb_id=xxx&parent=中国哲学` — list immediate subfolders with counts
- New `FolderDTO`: `{name, path, documentCount, totalSize}`

**Folder aggregation** is computed from documents table via `GROUP BY folder` query, not stored separately (no separate folder entity).

### 5. Document Metadata Editing

**Single document:**
- `PATCH /knowledge/documents/{id}/metadata` with `{"metadata": {"作者": "余秋雨"}}`
- Merges with existing metadata (null value removes key)

**Bulk edit:**
- `POST /knowledge/documents/bulk-metadata` with `{"document_ids": [...], "metadata": {...}}`
- Applies same metadata to all specified documents (merge)

**Frontend:**
- Document detail panel shows metadata as editable KV table
- Bulk select documents → "Edit Metadata" button → modal with KV editor

### 6. Search Integration

**Tags** (existing): `search()` already filters by tags via `findIdsByKbIdAndTenantIdAndTagsContaining`.

**Metadata** (new): Add optional `metadata` filter to search API:
```json
{
  "kb_id": "kb_xxx",
  "query": "道德经的核心思想",
  "metadata": {"作者": "老子"}
}
```

Implementation: add WHERE clause `metadata @> '{"作者": "老子"}'::jsonb` to the search SQL in the user's compute pod database.

**Folder** (new): Add optional `folder` filter to search API to scope search to a subtree.

### 7. Bug Fixes (included in this work)

| # | Bug | Fix |
|---|-----|-----|
| 1 | Database not found — no pre-check before queueing | Validate DB exists and status != ERROR in `ingest` and `executeHeavyweight` |
| 2 | LogQueryService JDBC prefix | Change `postgres://` to `jdbc:postgresql://` in LogQueryService DSN handling |
| 3 | version "latest" param type | Change API `@RequestParam int version` to `String`, resolve "latest" to max version |
| 4 | CHECKPOINT race condition | Skip CHECKPOINT if pod is already marked for deletion |
| 5 | No exponential backoff on RATE_LIMIT | Add exponential backoff with jitter: 30s, 60s, 120s, 240s (capped) |
| 6 | Optimistic lock on KnowledgeBaseEntity | Use `@Version` or `UPDATE ... SET document_count = document_count + 1` atomic increment |

### 8. MCP & CLI Updates

- `dbay-mcp` `knowledge_upload_directory`: pass folder paths, call new `ingest` API after all uploads
- `dbay-cli` `batch_process_documents`: update to use `ingest` API for large batches
- `dbay-sre-mcp`: already configured (sre-config.json + MCP server registered)

## DB Migration

```sql
ALTER TABLE documents ADD COLUMN folder VARCHAR(512) DEFAULT '';
ALTER TABLE documents ADD COLUMN metadata JSONB DEFAULT '{}';
CREATE INDEX idx_documents_folder ON documents (kb_id, folder);
CREATE INDEX idx_documents_metadata ON documents USING GIN (metadata);
```

## Out of Scope

- Per-tenant quota management UI (use config for now)
- Nested folder CRUD operations (folders are derived from documents, not managed independently)
- Folder-level metadata inheritance (apply at upload time, not dynamically)
- Resumable upload (OBS presigned URL + frontend retry covers most cases)
