# Chunk Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add chunk viewing, editing, quality analysis, and rechunking with Neon time-travel rollback to the knowledge base feature.

**Architecture:** Single data source (user Neon DB for chunks) + OBS for fulltext Markdown. Pipeline precomputes quality metrics. Rechunk uses Neon branching for zero-risk rollback. Console adds document detail page and KB-level chunks tab.

**Tech Stack:** Java 17 / Spring Boot (API), Python (pipeline job), Vue 3 / TypeScript (console), PostgreSQL + pgvector (user DB), OBS (fulltext storage), Neon branching (rollback)

**Spec:** `docs/superpowers/specs/2026-03-18-chunk-management-design.md`

---

## File Structure

### Backend (Java) — New Files
- `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkController.java` — REST endpoints for chunk CRUD, stats, rechunk
- `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java` — Chunk business logic (CRUD, stats queries, rechunk orchestration)
- `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeDbHelper.java` — Shared helper: compute connstr resolution, JDBC credential extraction (extracted from KnowledgeService)
- `lakeon-api/src/main/java/com/lakeon/knowledge/RechunkStatus.java` — Enum: IDLE, IN_PROGRESS

### Backend (Java) — Modified Files
- `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java` — Add rechunk_status, rechunk_started_at fields
- `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` — Extract JDBC helpers to KnowledgeDbHelper, add compute preheat method, fulltext OBS read
- `lakeon-api/src/main/resources/db/migration/V13__add_rechunk_fields.sql` — Flyway migration for documents table changes

### Pipeline (Python) — Modified Files
- `knowledge/job/chunker.py` — Add char_offset_start/end, overlap_prev, char_count computation
- `knowledge/job/writer.py` — Update CREATE TABLE with new columns, ALTER TABLE for existing tables, write new fields
- `knowledge/job/parser.py` — Return page/bbox metadata from Marker
- `knowledge/job/main.py` — Add fulltext upload to OBS step, call duplicate detection
- `knowledge/job/callback.py` — Include quality stats summary in success callback

### Frontend (Vue) — New Files
- `lakeon-console/src/views/knowledge/DocumentDetail.vue` — Document detail page with chunk list + content/fulltext/context tabs
- `lakeon-console/src/components/knowledge/ChunkList.vue` — Chunk list panel (left side)
- `lakeon-console/src/components/knowledge/ChunkContent.vue` — Chunk content/edit view (right side tab)
- `lakeon-console/src/components/knowledge/FulltextHighlight.vue` — Fulltext with chunk highlight (right side tab)
- `lakeon-console/src/components/knowledge/ChunkStats.vue` — Quality stats (histogram, anomalies) for KB chunks tab
- `lakeon-console/src/components/knowledge/RechunkDialog.vue` — Rechunk parameter dialog + comparison view

### Frontend (Vue) — Modified Files
- `lakeon-console/src/api/knowledge.ts` — Add chunk API functions and TypeScript interfaces
- `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` — Add Chunks tab, make document rows clickable
- `lakeon-console/src/router/index.ts` — Add route for DocumentDetail page

### Tests
- `tests/e2e/test_chunk_management.py` — E2E tests for chunk CRUD, stats, rechunk, rollback

---

## Task 1: Pipeline — Compute offsets, quality metrics, upload fulltext

**Files:**
- Modify: `knowledge/job/chunker.py`
- Modify: `knowledge/job/writer.py`
- Modify: `knowledge/job/parser.py`
- Modify: `knowledge/job/main.py`
- Modify: `knowledge/job/callback.py`

This is the foundation — all other tasks depend on chunks having the new fields.

- [ ] **Step 1: Update chunker.py — add offset and quality metric computation**

In `knowledge/job/chunker.py`, modify `chunk_document()` to track character offsets in the original markdown and compute overlap:

```python
def chunk_document(markdown: str, filename: str, format: str) -> List[Dict[str, Any]]:
    # ... existing chunking logic ...
    # For each chunk, add:
    #   char_offset_start: int  — start position in `markdown` string
    #   char_offset_end: int    — end position in `markdown` string
    #   char_count: int         — len(chunk_content)
    #   overlap_prev: int       — number of overlapping chars with previous chunk
```

The key change: track `current_offset` as you walk through the markdown. When creating a chunk from `markdown[start:end]`, record `char_offset_start=start, char_offset_end=end`. For overlap, compare the tail of the previous chunk with the head of the current chunk.

- [ ] **Step 2: Update chunker.py — add duplicate detection**

After all chunks are created, compute pairwise cosine similarity for non-adjacent chunks. This runs in-memory on the embedding vectors (available after embedding step in main.py). Add a new function:

```python
def detect_duplicates(chunks: List[Dict], embeddings: List[List[float]], threshold: float = 0.92) -> None:
    """Mutates chunks in-place, adding duplicate_of/similarity to metadata."""
    import numpy as np
    emb_matrix = np.array(embeddings)
    norms = np.linalg.norm(emb_matrix, axis=1, keepdims=True)
    emb_normed = emb_matrix / norms
    sim_matrix = emb_normed @ emb_normed.T
    for i in range(len(chunks)):
        for j in range(i + 2, len(chunks)):  # skip adjacent (i+1)
            if sim_matrix[i][j] > threshold:
                chunks[j]["metadata"]["duplicate_of"] = chunks[i]["chunk_index"]
                chunks[j]["metadata"]["similarity"] = round(float(sim_matrix[i][j]), 4)
                break  # only mark the first duplicate match
```

- [ ] **Step 3: Move duplicate detection call to main.py**

In `knowledge/job/main.py`, call `detect_duplicates(chunks, embeddings)` after the embedding step and before `write_chunks()`. Import the function from chunker.

- [ ] **Step 4: Update parser.py — extract page metadata from Marker**

In `knowledge/job/parser.py`, for PDF format, use `pymupdf4llm` with page tracking. The current `pymupdf4llm.to_markdown()` returns markdown. Modify to also return page boundaries:

```python
def parse_document(file_path: str, format: str) -> Tuple[str, List[Dict]]:
    """Returns (markdown_text, page_metadata).
    page_metadata: [{page: int, char_start: int, char_end: int}, ...]
    """
```

For non-PDF formats, return empty page_metadata.

- [ ] **Step 5: Update chunker.py — assign page_start/page_end per chunk**

Add a function to map chunk offsets to page numbers using page_metadata:

```python
def assign_pages(chunks: List[Dict], page_metadata: List[Dict]) -> None:
    """Sets page_start and page_end on each chunk based on char_offset and page_metadata."""
    for chunk in chunks:
        start = chunk["char_offset_start"]
        end = chunk["char_offset_end"]
        chunk["page_start"] = None
        chunk["page_end"] = None
        for pm in page_metadata:
            if pm["char_start"] <= start < pm["char_end"]:
                chunk["page_start"] = pm["page"]
            if pm["char_start"] < end <= pm["char_end"]:
                chunk["page_end"] = pm["page"]
```

- [ ] **Step 6: Update writer.py — new CREATE TABLE and INSERT**

Update the CREATE TABLE in `write_chunks()`:

```sql
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id SERIAL PRIMARY KEY,
    document_id VARCHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    metadata JSONB,
    char_offset_start INT,
    char_offset_end INT,
    char_count INT,
    overlap_prev INT DEFAULT 0,
    page_start INT,
    page_end INT,
    bbox JSONB,
    level SMALLINT DEFAULT 0,
    source_chunks INT[],
    edited BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);
```

Update the INSERT to include all new fields.

Also add `ALTER TABLE` statements after `CREATE TABLE IF NOT EXISTS` for existing databases that already have the old schema:

```sql
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS char_offset_start INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS char_offset_end INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS char_count INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS overlap_prev INT DEFAULT 0;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS page_start INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS page_end INT;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS bbox JSONB;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS level SMALLINT DEFAULT 0;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS source_chunks INT[];
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS edited BOOLEAN DEFAULT FALSE;
ALTER TABLE knowledge_chunks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ;
```

This handles the case where a KB already has chunks from the old pipeline.

- [ ] **Step 7: Update main.py — upload fulltext.md to OBS**

After parsing, upload the full markdown text to OBS. The job params already include OBS credentials and bucket info. Add after the parse step:

```python
# Upload fulltext to OBS
fulltext_key = f"knowledge/{params['tenant_id']}/{params['kb_id']}/{params['document_id']}/fulltext.md"
s3.put_object(Bucket=bucket, Key=fulltext_key, Body=markdown.encode('utf-8'))
```

The `tenant_id` and `kb_id` need to be passed in job params — update `KnowledgeService.processDocument()` to include them.

- [ ] **Step 8: Update main.py — pass page_metadata through pipeline**

Update the orchestration to thread `page_metadata` from parser through chunker:

```python
markdown, page_metadata = parse_document(tmp_path, fmt)
chunks = chunk_document(markdown, filename, fmt)
assign_pages(chunks, page_metadata)
# ... embeddings ...
detect_duplicates(chunks, embeddings)
write_chunks(connstr, document_id, chunks, embeddings)
```

- [ ] **Step 9: Update callback.py — include quality stats in success callback**

Update `report_success()` to include quality statistics:

```python
def report_success(chunks_count, quality_stats=None):
    """quality_stats: {anomaly_count, duplicate_count, avg_char_count}"""
    result = {"chunks_count": chunks_count}
    if quality_stats:
        result["quality_stats"] = quality_stats
    # POST to callback URL with result
```

In `main.py`, compute quality stats before callback:

```python
quality_stats = {
    "anomaly_count": sum(1 for c in chunks if len(c["content"]) < 80 or len(c["content"]) > 800),
    "duplicate_count": sum(1 for c in chunks if "duplicate_of" in c["metadata"]),
    "avg_char_count": sum(len(c["content"]) for c in chunks) // len(chunks) if chunks else 0,
}
report_success(len(chunks), quality_stats)
```

- [ ] **Step 10: Update KnowledgeService.java — pass tenant_id and kb_id in job params**

In `processDocument()`, add `tenant_id` and `kb_id` to the job params map so the Python job can construct the OBS fulltext path.

- [ ] **Step 11: Test the pipeline locally**

Run the existing knowledge E2E tests to ensure the pipeline still works with the new fields:

```bash
cd /Users/jacky/code/lakeon && python -m pytest tests/e2e/test_knowledge.py -v
```

- [ ] **Step 12: Commit**

```bash
git add knowledge/job/ lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java
git commit -m "feat(pipeline): compute chunk offsets, quality metrics, upload fulltext to OBS"
```

---

## Task 2: Backend — DocumentEntity changes + Flyway migration

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/RechunkStatus.java`
- Create: `lakeon-api/src/main/resources/db/migration/V13__add_rechunk_fields.sql`

- [ ] **Step 1: Create RechunkStatus enum**

```java
package com.lakeon.knowledge;

public enum RechunkStatus {
    IDLE, IN_PROGRESS
}
```

- [ ] **Step 2: Add fields to DocumentEntity**

Add to `DocumentEntity.java`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "rechunk_status")
private RechunkStatus rechunkStatus = RechunkStatus.IDLE;

@Column(name = "rechunk_started_at")
private Instant rechunkStartedAt;
```

- [ ] **Step 3: Create Flyway migration**

Create `V13__add_rechunk_fields.sql` (latest existing is V12):

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS rechunk_status VARCHAR(16) DEFAULT 'IDLE';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS rechunk_started_at TIMESTAMP;
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/RechunkStatus.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java \
        lakeon-api/src/main/resources/db/migration/
git commit -m "feat(model): add rechunk_status and rechunk_started_at to DocumentEntity"
```

---

## Task 3: Backend — ChunkController + ChunkService (read endpoints)

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeDbHelper.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkController.java`
- Create: `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`

- [ ] **Step 0: Extract KnowledgeDbHelper from KnowledgeService**

`KnowledgeService` has duplicated JDBC credential logic (`dUser`/`dPass` extraction from connstr, `resolveComputeConnstr()`). Extract into a shared `@Component`:

```java
@Component
public class KnowledgeDbHelper {
    // Resolve KB → databaseId → compute pod → JDBC URL
    public String resolveJdbcUrl(String tenantId, String kbId)
    // Extract username from Neon connstr
    public String extractUser(String connstr)
    // Extract password from Neon connstr
    public String extractPassword(String connstr)
    // Get a JDBC Connection for a KB's compute
    public Connection getComputeConnection(String tenantId, String kbId)
}
```

Refactor `KnowledgeService.search()` and `deleteDocument()` to use `KnowledgeDbHelper` instead of their inline credential parsing. This avoids duplicating the pattern a third time in ChunkService.

- [ ] **Step 1: Create ChunkService with list and get methods**

`ChunkService.java` injects `KnowledgeDbHelper` for compute database access.

**URL path note:** Chunk endpoints use `/api/v1/knowledge/bases/{kbId}/documents/{docId}/chunks` (kbId in path) because the kbId is needed to resolve which Neon compute database to connect to. Existing KnowledgeController document endpoints use flat paths (`/documents/{id}`) without kbId because they query RDS directly. The nested path is intentional.

Key methods:

```java
public class ChunkService {
    // List chunks for a document (paginated)
    public Map<String, Object> listChunks(String tenantId, String kbId, String docId,
                                           int level, int offset, int limit)

    // Get single chunk by document + chunk_index
    public Map<String, Object> getChunk(String tenantId, String kbId, String docId, int chunkIndex)

    // Get adjacent chunks (chunk_index ± 1)
    public Map<String, Object> getChunkContext(String tenantId, String kbId, String docId, int chunkIndex)

    // Get chunk stats (length distribution, anomalies, similarity, duplicates)
    public Map<String, Object> getChunkStats(String tenantId, String kbId, String docId)

    // Get fulltext from OBS (no compute needed)
    public String getFulltext(String tenantId, String kbId, String docId)

    // List chunks across all documents in KB (for global chunks tab)
    public Map<String, Object> listKbChunks(String tenantId, String kbId,
                                             String docId, String status, int offset, int limit)
}
```

The JDBC connection pattern: resolve KB → get databaseId → get compute pod IP → connect via proxy or direct JDBC. Reuse `KnowledgeService`'s existing `resolveComputeConnstr()` or similar helper.

- [ ] **Step 2: Implement listChunks**

SQL:
```sql
SELECT id, chunk_index, content, metadata, char_count, overlap_prev, char_offset_start,
       char_offset_end, page_start, page_end, level, edited, created_at, updated_at
FROM knowledge_chunks
WHERE document_id = ? AND level = ?
ORDER BY chunk_index
LIMIT ? OFFSET ?
```

Also query `SELECT count(*) FROM knowledge_chunks WHERE document_id = ? AND level = ?` for total count.

Return `{chunks: [...], total: N, offset: O, limit: L}`.

- [ ] **Step 3: Implement getChunk**

```sql
SELECT * FROM knowledge_chunks WHERE document_id = ? AND chunk_index = ? AND level = 0
```

- [ ] **Step 4: Implement getChunkContext**

```sql
SELECT chunk_index, content, char_count, metadata
FROM knowledge_chunks
WHERE document_id = ? AND level = 0
  AND chunk_index IN (? - 1, ? + 1)
ORDER BY chunk_index
```

Return `{prev: {...} or null, next: {...} or null}`.

- [ ] **Step 5: Implement getChunkStats**

Run the 4 queries from the spec (Section 7) in a single JDBC connection:
1. Length distribution histogram
2. Anomalous chunks
3. Adjacent semantic similarity
4. Duplicates (from metadata)

Also compute summary: total_chunks, avg_char_count, anomaly_count, duplicate_count.

- [ ] **Step 6: Implement getFulltext**

Read from OBS using S3 client:
```java
String key = String.format("knowledge/%s/%s/%s/fulltext.md", tenantId, kbId, docId);
// Use existing S3 client to getObject, return as string
```

This does NOT need compute running.

- [ ] **Step 7: Create ChunkController**

```java
@RestController
@RequestMapping("/api/v1/knowledge/bases/{kbId}")
public class ChunkController {

    @GetMapping("/documents/{docId}/chunks")
    public ResponseEntity<?> listChunks(HttpServletRequest request,
        @PathVariable String kbId, @PathVariable String docId,
        @RequestParam(defaultValue = "0") int level,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "50") int limit)

    @GetMapping("/chunks")
    public ResponseEntity<?> listKbChunks(HttpServletRequest request,
        @PathVariable String kbId,
        @RequestParam(required = false) String doc_id,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "50") int limit)

    @GetMapping("/documents/{docId}/chunks/{chunkIndex}")
    public ResponseEntity<?> getChunk(...)

    @GetMapping("/documents/{docId}/chunks/{chunkIndex}/context")
    public ResponseEntity<?> getChunkContext(...)

    @GetMapping("/documents/{docId}/fulltext")
    public ResponseEntity<?> getFulltext(...)

    @GetMapping("/documents/{docId}/chunk-stats")
    public ResponseEntity<?> getChunkStats(...)
}
```

Follow the auth pattern from `KnowledgeController`: extract tenant from `HttpServletRequest` via the auth filter.

- [ ] **Step 8: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/ChunkController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java
git commit -m "feat(api): chunk read endpoints — list, detail, context, stats, fulltext"
```

---

## Task 4: Backend — Chunk write endpoints (edit, delete, create)

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java`

- [ ] **Step 1: Implement editChunk in ChunkService**

```java
public Map<String, Object> editChunk(String tenantId, String kbId, String docId,
                                      int chunkIndex, String newContent) {
    // 1. Call embedding service to get new embedding for newContent
    // 2. UPDATE knowledge_chunks SET content = ?, embedding = ?, char_count = ?,
    //    edited = true, updated_at = now()
    //    WHERE document_id = ? AND chunk_index = ? AND level = 0
    // 3. Return updated chunk
}
```

Embedding call: reuse the HTTP client pattern from `KnowledgeService.search()` which calls the embedding API.

- [ ] **Step 2: Implement deleteChunk in ChunkService**

```java
public void deleteChunk(String tenantId, String kbId, String docId, int chunkIndex) {
    // Single transaction:
    // 1. DELETE FROM knowledge_chunks WHERE document_id = ? AND chunk_index = ? AND level = 0
    // 2. UPDATE knowledge_chunks SET chunk_index = chunk_index - 1
    //    WHERE document_id = ? AND chunk_index > ? AND level = 0
    // 3. Update DocumentEntity.chunksCount -= 1 (in RDS)
}
```

- [ ] **Step 3: Implement createChunk in ChunkService**

```java
public Map<String, Object> createChunk(String tenantId, String kbId, String docId,
                                        String content, int insertAfterIndex) {
    // Single transaction:
    // 1. UPDATE knowledge_chunks SET chunk_index = chunk_index + 1
    //    WHERE document_id = ? AND chunk_index > ? AND level = 0
    // 2. Call embedding service for content
    // 3. INSERT INTO knowledge_chunks (document_id, chunk_index, content, embedding,
    //    char_count, edited, level, created_at, updated_at)
    //    VALUES (?, insertAfterIndex + 1, ?, ?, len(content), true, 0, now(), now())
    // 4. Update DocumentEntity.chunksCount += 1
}
```

- [ ] **Step 4: Add write endpoints to ChunkController**

```java
@PutMapping("/documents/{docId}/chunks/{chunkIndex}")
public ResponseEntity<?> editChunk(HttpServletRequest request,
    @PathVariable String kbId, @PathVariable String docId,
    @PathVariable int chunkIndex, @RequestBody Map<String, String> body)

@DeleteMapping("/documents/{docId}/chunks/{chunkIndex}")
public ResponseEntity<?> deleteChunk(...)

@PostMapping("/documents/{docId}/chunks")
public ResponseEntity<?> createChunk(HttpServletRequest request,
    @PathVariable String kbId, @PathVariable String docId,
    @RequestBody Map<String, Object> body)  // {content, insert_after_index}
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/ChunkController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java
git commit -m "feat(api): chunk write endpoints — edit, delete, create with embedding regen"
```

---

## Task 5: Backend — Rechunk + Neon branch rollback

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/ChunkService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java`

- [ ] **Step 1: Implement rechunk in ChunkService**

```java
public Map<String, Object> rechunk(String tenantId, String kbId, String docId,
                                    int maxTokens, double overlapRatio, String customSeparator) {
    // 1. CAS lock: UPDATE documents SET rechunk_status='IN_PROGRESS', rechunk_started_at=now()
    //    WHERE id=? AND rechunk_status='IDLE' — fail if 0 rows updated
    // 2. Resolve KB → database → get Neon tenant/timeline info
    // 3. Create Neon branch: rechunk/{docId}/{timestamp}
    //    via BranchService.create() or NeonApiClient.createTimeline()
    // 4. Clean up old branches: list branches matching rechunk/{docId}/*, keep newest 3, delete rest
    // 5. Submit DOCUMENT_PARSE job with rechunk params (max_tokens, overlap_ratio, custom_separator)
    //    The job will atomically DELETE old chunks + INSERT new ones
    // 6. Return {job_id, branch_id, branch_name}
}
```

- [ ] **Step 2: Update pipeline to accept rechunk params**

In `main.py`, check for optional params `max_tokens`, `overlap_ratio`, `custom_separator` in the job params. If present, pass to `chunk_document()`. Modify `chunker.py` to accept these as parameters instead of using hardcoded constants.

- [ ] **Step 3: Implement rechunk rollback in ChunkService**

```java
public void rechunkRollback(String tenantId, String kbId, String docId, String branchId) {
    // 1. Find branch by branchId, verify it belongs to this KB's database
    // 2. Connect to branch's compute (may need to start it)
    // 3. Read all chunks: SELECT * FROM knowledge_chunks WHERE document_id = ?
    // 4. Connect to main timeline
    // 5. In single transaction:
    //    DELETE FROM knowledge_chunks WHERE document_id = ? AND level = 0
    //    INSERT all chunks from branch
    // 6. Update DocumentEntity.chunksCount, rechunk_status = IDLE
}
```

- [ ] **Step 4: Add rechunk timeout auto-reset**

In `ChunkService` or `KnowledgeService`, when getting a document, check:

```java
if (doc.getRechunkStatus() == RechunkStatus.IN_PROGRESS
    && doc.getRechunkStartedAt() != null
    && doc.getRechunkStartedAt().plusSeconds(1800).isBefore(Instant.now())) {
    doc.setRechunkStatus(RechunkStatus.IDLE);
    documentRepository.save(doc);
}
```

- [ ] **Step 5: Add rechunk endpoints to ChunkController**

```java
@PostMapping("/documents/{docId}/rechunk")
public ResponseEntity<?> rechunk(HttpServletRequest request,
    @PathVariable String kbId, @PathVariable String docId,
    @RequestBody Map<String, Object> body)  // {max_tokens, overlap_ratio, custom_separator}

@PostMapping("/documents/{docId}/rechunk/rollback")
public ResponseEntity<?> rechunkRollback(HttpServletRequest request,
    @PathVariable String kbId, @PathVariable String docId,
    @RequestBody Map<String, Object> body)  // {branch_id}
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/ knowledge/job/
git commit -m "feat(api): rechunk with Neon branch rollback — branch creation, job submission, data copy rollback"
```

---

## Task 6: Frontend — API client + TypeScript interfaces

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`

- [ ] **Step 1: Add TypeScript interfaces**

```typescript
export interface Chunk {
  id: number
  document_id: string
  chunk_index: number
  content: string
  metadata: Record<string, any>
  char_count: number
  overlap_prev: number
  char_offset_start: number | null
  char_offset_end: number | null
  page_start: number | null
  page_end: number | null
  level: number
  edited: boolean
  created_at: string
  updated_at: string | null
}

export interface ChunkListResponse {
  chunks: Chunk[]
  total: number
  offset: number
  limit: number
}

export interface ChunkContext {
  prev: Chunk | null
  next: Chunk | null
}

export interface ChunkStats {
  total_chunks: number
  avg_char_count: number
  anomaly_count: number
  duplicate_count: number
  length_distribution: { bucket: number; count: number }[]
  anomalous_chunks: { id: number; chunk_index: number; char_count: number }[]
  adjacent_similarities: { chunk_index: number; similarity: number }[]
  duplicates: { chunk_index: number; duplicate_of: number; similarity: number }[]
}

export interface RechunkResponse {
  job_id: string
  branch_id: string
  branch_name: string
}
```

- [ ] **Step 2: Add API functions**

```typescript
export async function listChunks(kbId: string, docId: string, level = 0, offset = 0, limit = 50): Promise<ChunkListResponse>
export async function getChunk(kbId: string, docId: string, chunkIndex: number): Promise<Chunk>
export async function getChunkContext(kbId: string, docId: string, chunkIndex: number): Promise<ChunkContext>
export async function getChunkStats(kbId: string, docId: string): Promise<ChunkStats>
export async function getFulltext(kbId: string, docId: string): Promise<string>
export async function editChunk(kbId: string, docId: string, chunkIndex: number, content: string): Promise<Chunk>
export async function deleteChunk(kbId: string, docId: string, chunkIndex: number): Promise<void>
export async function createChunk(kbId: string, docId: string, content: string, insertAfterIndex: number): Promise<Chunk>
export async function rechunk(kbId: string, docId: string, params: { max_tokens?: number; overlap_ratio?: number; custom_separator?: string }): Promise<RechunkResponse>
export async function rechunkRollback(kbId: string, docId: string, branchId: string): Promise<void>
export async function listKbChunks(kbId: string, options?: { doc_id?: string; status?: string; offset?: number; limit?: number }): Promise<ChunkListResponse>
```

Follow existing pattern: use `apiClient.get()`/`.post()`/`.put()`/`.delete()`.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts
git commit -m "feat(console): chunk management API client and TypeScript interfaces"
```

---

## Task 7: Frontend — Document Detail page (chunk list + content view)

**Files:**
- Create: `lakeon-console/src/views/knowledge/DocumentDetail.vue`
- Create: `lakeon-console/src/components/knowledge/ChunkList.vue`
- Create: `lakeon-console/src/components/knowledge/ChunkContent.vue`
- Modify: `lakeon-console/src/router/index.ts`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Add route for DocumentDetail**

In `router/index.ts`, add:
```typescript
{
  path: 'knowledge/:kbId/documents/:docId',
  name: 'DocumentDetail',
  component: () => import('@/views/knowledge/DocumentDetail.vue')
}
```

- [ ] **Step 2: Make document rows clickable in KnowledgeBaseDetail**

In `KnowledgeBaseDetail.vue`, in the Documents tab table, wrap each document row with `@click="router.push({ name: 'DocumentDetail', params: { kbId, docId: doc.id } })"` and add `cursor: pointer` style.

- [ ] **Step 3: Create ChunkList.vue component**

Left panel component showing chunk cards:
- Props: `chunks: Chunk[]`, `selectedIndex: number`, `stats: ChunkStats | null`
- Each card: chunk_index (#N), char_count, section (from metadata), overlap_prev, 2-line content preview
- Anomaly indicators: orange left border + warning icon for short (<80) or long (>800), red for duplicates (from metadata.duplicate_of)
- Selected chunk: blue highlight
- Emits: `@select(chunkIndex)`

- [ ] **Step 4: Create ChunkContent.vue component**

Right panel component with 3 tabs:
- Props: `chunk: Chunk`, `context: ChunkContext | null`
- **Tab 1 "切片内容"**: Full content display, chunk metadata (index, char_count, section, page). Edit/Delete buttons.
  - Edit mode: textarea replaces content, Save/Cancel buttons
  - Edit calls `editChunk()` API, emits `@updated`
  - Delete confirms then calls `deleteChunk()` API, emits `@deleted`
- **Tab 2 "原文定位"**: Placeholder (implemented in Task 8)
- **Tab 3 "相邻切片"**: Show prev/next chunk content from `context`

- [ ] **Step 5: Create DocumentDetail.vue page**

Main page composing the components:
- Breadcrumb: 知识库 / {kb.name} / {doc.filename}
- Top bar: document info + [重新切片] + [新增切片] buttons
- Left panel: `<ChunkList>` (340px width)
- Right panel: `<ChunkContent>`
- On mount: fetch document info, listChunks(), getChunkStats()
- On chunk select: fetch getChunk() + getChunkContext()
- Threshold display: `⚠️ 过短 < 80 字 | 过长 > 800 字 | 疑似重复 > 92% 相似度`
- Compute preheat: on mount, call a lightweight endpoint to wake compute if suspended

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/knowledge/DocumentDetail.vue \
        lakeon-console/src/components/knowledge/ChunkList.vue \
        lakeon-console/src/components/knowledge/ChunkContent.vue \
        lakeon-console/src/router/index.ts \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): document detail page with chunk list and content view"
```

---

## Task 8: Frontend — Fulltext highlight (原文定位)

**Files:**
- Create: `lakeon-console/src/components/knowledge/FulltextHighlight.vue`
- Modify: `lakeon-console/src/components/knowledge/ChunkContent.vue`

- [ ] **Step 1: Install markdown-it**

```bash
cd lakeon-console && npm install markdown-it && npm install -D @types/markdown-it
```

- [ ] **Step 2: Create FulltextHighlight.vue**

Component that renders fulltext markdown with chunk highlighting:
- Props: `fulltext: string`, `chunkOffsetStart: number`, `chunkOffsetEnd: number`
- Logic:
  1. Split fulltext into 3 parts: before, highlight, after using char_offset_start/end
  2. Insert `<mark>` wrapper in the markdown source: `before + "==HIGHLIGHT_START==" + highlight + "==HIGHLIGHT_END==" + after`
  3. Render combined markdown with markdown-it
  4. Post-render: replace marker tags with `<mark class="chunk-highlight">` elements
  5. `nextTick(() => scrollHighlightIntoView())`
- Styling: `.chunk-highlight { background: rgba(79, 195, 247, 0.2); border-left: 3px solid #4fc3f7; padding: 2px 0; }`

- [ ] **Step 3: Wire into ChunkContent.vue Tab 2**

In ChunkContent, Tab 2 "原文定位":
- On first access, call `getFulltext(kbId, docId)` and cache the result
- Render `<FulltextHighlight :fulltext="cachedFulltext" :chunkOffsetStart="chunk.char_offset_start" :chunkOffsetEnd="chunk.char_offset_end" />`

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/components/knowledge/FulltextHighlight.vue \
        lakeon-console/src/components/knowledge/ChunkContent.vue
git commit -m "feat(console): fulltext highlight — markdown rendering with chunk position highlighting"
```

---

## Task 9: Frontend — KB Chunks tab (global stats + chunk table)

**Files:**
- Create: `lakeon-console/src/components/knowledge/ChunkStats.vue`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Create ChunkStats.vue**

Component for the KB-level chunks tab:
- On mount: fetch chunk-stats for all documents (iterate or use listKbChunks endpoint)
- **Stats cards**: total chunks, avg char count, anomaly count, duplicate count
- **Length distribution histogram**: Simple CSS bar chart using the bucket data
- **Threshold display**: `⚠️ 过短 < 80 字 | 过长 > 800 字 | 疑似重复 > 92% 相似度`
- **Filter bar**: dropdown for document filter, dropdown for status filter (全部/过短/过长/疑似重复)
- **Chunk table**: columns — #, 文档, 内容预览, 字数, 状态. Rows clickable → navigate to DocumentDetail page with chunk pre-selected

- [ ] **Step 2: Add Chunks tab to KnowledgeBaseDetail**

Add fourth tab "切片" after "搜索". When active, render `<ChunkStats :kbId="kbId" />`.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/components/knowledge/ChunkStats.vue \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): KB-level chunks tab with stats, histogram, and filterable chunk table"
```

---

## Task 10: Frontend — Rechunk dialog + comparison view

**Files:**
- Create: `lakeon-console/src/components/knowledge/RechunkDialog.vue`
- Modify: `lakeon-console/src/views/knowledge/DocumentDetail.vue`

- [ ] **Step 1: Create RechunkDialog.vue**

Dialog component:
- **Phase 1 — Parameter input**:
  - Max tokens (number input, default 400)
  - Overlap ratio (slider 0-30%, default 10%)
  - Custom separator (text input, optional)
  - [开始重新切片] button
- **Phase 2 — Progress**: Show job progress (poll getDocument for status/progress)
- **Phase 3 — Comparison**:
  - Side by side: old stats vs new stats (total chunks, avg length, anomaly count, duplicate count)
  - [保留新版本] and [回滚到旧版本] buttons
  - Rollback calls rechunkRollback(), reloads chunk list

- [ ] **Step 2: Wire dialog into DocumentDetail**

Connect [重新切片] button to open `<RechunkDialog>`. On completion (keep or rollback), refresh the chunk list and stats.

- [ ] **Step 3: Wire [新增切片] button**

Simple dialog: textarea for content, number input for "insert after chunk #". Calls createChunk() API, refreshes list.

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/components/knowledge/RechunkDialog.vue \
        lakeon-console/src/views/knowledge/DocumentDetail.vue
git commit -m "feat(console): rechunk dialog with parameter input, progress, and comparison view"
```

---

## Task 11: E2E Tests

**Files:**
- Create: `tests/e2e/test_chunk_management.py`

- [ ] **Step 1: Set up module-scoped fixtures**

Follow the pattern from `test_knowledge.py`: create a `module`-scoped fixture that creates a KB, uploads a document, and waits for processing to complete. All chunk tests reuse this fixture.

```python
@pytest.fixture(scope="module")
def processed_doc(api_client):
    """Create KB + upload and process a test document. Returns (kb, doc) tuple."""
    kb = api_client.create_kb("Chunk Test KB")
    doc = upload_and_process(api_client, kb["id"], "test_doc.md")
    yield kb, doc
    api_client.delete_kb(kb["id"])
```

- [ ] **Step 2: Write chunk list + detail tests**

```python
def test_list_chunks(processed_doc):
    """List chunks — verify fields present including new offset/quality fields."""

def test_get_chunk_detail():
    """Get a specific chunk by index — verify content, metadata, offsets."""

def test_get_chunk_context():
    """Get adjacent chunks — verify prev/next returned correctly."""

def test_get_chunk_stats():
    """Get stats — verify length_distribution, anomalies, similarities, duplicates."""
```

- [ ] **Step 2: Write chunk edit/delete/create tests**

```python
def test_edit_chunk():
    """Edit chunk content — verify embedding re-generated, edited=true."""

def test_delete_chunk():
    """Delete a chunk — verify chunk_index reindexed, chunks_count updated."""

def test_create_chunk():
    """Insert new chunk — verify chunk_index shifted, embedding generated."""
```

- [ ] **Step 3: Write fulltext test**

```python
def test_get_fulltext():
    """Get fulltext.md from OBS — verify markdown content returned."""
```

- [ ] **Step 4: Write rechunk + rollback tests**

```python
def test_rechunk():
    """Rechunk with different params — verify branch created, new chunks differ."""

def test_rechunk_rollback():
    """Rechunk then rollback — verify chunks restored to original state."""

def test_rechunk_concurrent_protection():
    """Start rechunk, try second rechunk — verify 409 conflict returned."""
```

- [ ] **Step 5: Run all tests**

```bash
cd /Users/jacky/code/lakeon && python -m pytest tests/e2e/test_chunk_management.py -v
```

- [ ] **Step 6: Commit**

```bash
git add tests/e2e/test_chunk_management.py
git commit -m "test(knowledge): E2E tests for chunk CRUD, stats, fulltext, rechunk, rollback"
```
