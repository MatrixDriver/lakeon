# KB Folder/Metadata + Parallel Ingestion Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add folder/metadata fields to documents, preserve directory structure during upload, auto-generate tags from folder paths, add new parallel ingest API with tenant quota control.

**Architecture:** Backend model changes + API additions, frontend upload flow enhancement, drain() concurrency control. No new services.

**Tech Stack:** Java 21/Spring Boot, Vue 3/TypeScript, Flyway, PostgreSQL JSONB

---

### Task 1: DocumentEntity — add folder and metadata fields

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java`
- Create: `lakeon-api/src/main/resources/db/migration/V30__add_document_folder_metadata.sql`

- [ ] **Step 1: Add Flyway migration**

Create `lakeon-api/src/main/resources/db/migration/V30__add_document_folder_metadata.sql`:

```sql
ALTER TABLE documents ADD COLUMN IF NOT EXISTS folder VARCHAR(512) DEFAULT '';
ALTER TABLE documents ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}'::jsonb;
CREATE INDEX IF NOT EXISTS idx_documents_folder ON documents (kb_id, folder);
CREATE INDEX IF NOT EXISTS idx_documents_metadata ON documents USING GIN (metadata);
```

- [ ] **Step 2: Add fields to DocumentEntity**

In `DocumentEntity.java`, add after the `tags` field (line 77):

```java
@Column(name = "folder", length = 512)
private String folder = "";

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "metadata", columnDefinition = "jsonb")
private Map<String, String> metadata = new LinkedHashMap<>();
```

Add import at top: `import java.util.LinkedHashMap;` and `import java.util.Map;`

- [ ] **Step 3: Add getters/setters**

After line 165 (end of existing getters/setters), add:

```java
public String getFolder() { return folder; }
public void setFolder(String folder) { this.folder = folder; }

public Map<String, String> getMetadata() { return metadata; }
public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
```

- [ ] **Step 4: Add folder and metadata to toDocumentResponse**

In `KnowledgeController.java`, in `toDocumentResponse()` method (around line 397, after tags), add:

```java
map.put("folder", doc.getFolder());
map.put("metadata", doc.getMetadata());
```

- [ ] **Step 5: Verify compilation**

Run: `cd lakeon-api && mvn compile -pl . -q 2>&1 | tail -10`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DocumentEntity.java \
        lakeon-api/src/main/resources/db/migration/V30__add_document_folder_metadata.sql \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat: add folder and metadata fields to DocumentEntity"
```

---

### Task 2: batch-upload-urls API — accept folder/metadata + auto-tags

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java` (batchGenerateUploadUrls method, lines 444-466)

- [ ] **Step 1: Extract folder and metadata from fileSpec**

In `batchGenerateUploadUrls()`, after line 450 (`List<String> tags = ...`), add:

```java
String folder = (String) fileSpec.get("folder");
@SuppressWarnings("unchecked")
Map<String, String> metadata = (Map<String, String>) fileSpec.get("metadata");
```

- [ ] **Step 2: Set folder, metadata, and auto-generate tags from folder path**

After `doc.setStatus(DocumentStatus.PENDING);` (line 463) and before `if (tags != null ...)`, add:

```java
// Set folder
if (folder != null && !folder.isBlank()) {
    // Strip leading/trailing slashes and the root directory name (first segment from webkitdirectory)
    String cleanFolder = folder.replaceAll("^/+|/+$", "");
    // webkitRelativePath includes root dir: "mydir/sub/file.txt" → folder="mydir/sub"
    // Strip the root directory (it's just the selected directory name, not meaningful structure)
    int firstSlash = cleanFolder.indexOf('/');
    if (firstSlash > 0) {
        cleanFolder = cleanFolder.substring(firstSlash + 1);
    } else {
        cleanFolder = ""; // file is directly in root directory
    }
    doc.setFolder(cleanFolder);

    // Auto-generate tags from folder path segments
    if (!cleanFolder.isEmpty()) {
        List<String> autoTags = new ArrayList<>(List.of(cleanFolder.split("/")));
        if (tags != null) {
            autoTags.addAll(tags);
        }
        // Deduplicate
        tags = autoTags.stream().distinct().toList();
    }
}
```

Then keep the existing tag-setting block:
```java
if (tags != null && !tags.isEmpty()) {
    doc.setTags(tags);
}
```

- [ ] **Step 3: Set metadata**

After the tags block, add:

```java
// Set metadata (from per-file or top-level batch metadata)
if (metadata != null && !metadata.isEmpty()) {
    doc.setMetadata(metadata);
}
```

Also handle top-level batch metadata. At the beginning of `batchGenerateUploadUrls()`, after parsing `files`, add extraction of top-level metadata:

In the controller at `KnowledgeController.java` `batchUploadUrls` method (line 116-126), the top-level metadata needs to be extracted and passed. Change the controller to also extract `metadata`:

```java
@SuppressWarnings("unchecked")
Map<String, String> batchMetadata = (Map<String, String>) body.get("metadata");
```

And pass it to the service. However, the current service method signature doesn't accept batch-level metadata. The simplest approach: let the service iterate files and merge batch-level metadata. Add a parameter to `batchGenerateUploadUrls`:

Change signature to:
```java
public List<Map<String, Object>> batchGenerateUploadUrls(TenantEntity tenant, String kbId,
        List<Map<String, Object>> files, Map<String, String> batchMetadata) {
```

In the controller, change:
```java
List<Map<String, Object>> documents = knowledgeService.batchGenerateUploadUrls(tenant, kbId, files, batchMetadata);
```

In the service, when setting metadata per document, merge batch metadata with per-file metadata (per-file takes precedence):

```java
Map<String, String> mergedMetadata = new LinkedHashMap<>();
if (batchMetadata != null) mergedMetadata.putAll(batchMetadata);
if (metadata != null) mergedMetadata.putAll(metadata);
if (!mergedMetadata.isEmpty()) {
    doc.setMetadata(mergedMetadata);
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -pl . -q 2>&1 | tail -10`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat: accept folder/metadata in batch-upload-urls, auto-generate tags from folder"
```

---

### Task 3: Frontend — pass webkitRelativePath as folder in upload

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts` (line 130)
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` (line 801)

- [ ] **Step 1: Update API type**

In `lakeon-console/src/api/knowledge.ts`, change `batchGetUploadUrls`:

```typescript
export function batchGetUploadUrls(kbId: string, files: { filename: string; tags?: string[]; folder?: string }[]) {
  return api.post<{ documents: { document_id: string; filename: string; upload_url: string; expires_in: number }[] }>(
    '/knowledge/batch-upload-urls', { kb_id: kbId, files }
  )
}
```

- [ ] **Step 2: Extract folder from webkitRelativePath in runBatchUpload**

In `KnowledgeBaseDetail.vue`, find line 801:
```typescript
const fileSpecs = batchFiles.map(f => ({ filename: f.name }))
```

Replace with:
```typescript
const fileSpecs = batchFiles.map(f => {
  const spec: { filename: string; folder?: string } = { filename: f.name }
  if ((f as any).webkitRelativePath) {
    const parts = (f as any).webkitRelativePath.split('/')
    if (parts.length > 1) {
      // webkitRelativePath: "rootDir/sub1/sub2/file.txt" → folder: "rootDir/sub1/sub2"
      spec.folder = parts.slice(0, -1).join('/')
    }
  }
  return spec
})
```

- [ ] **Step 3: TypeScript check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10`

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): pass directory path as folder in upload"
```

---

### Task 4: New ingest API with tenant quota

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java` (KnowledgeConfig)

- [ ] **Step 1: Add maxConcurrentJobs to KnowledgeConfig**

In `LakeonProperties.java`, in the `KnowledgeConfig` inner class (around line 336), add field:

```java
private int maxConcurrentJobs = 2;

public int getMaxConcurrentJobs() { return maxConcurrentJobs; }
public void setMaxConcurrentJobs(int maxConcurrentJobs) { this.maxConcurrentJobs = maxConcurrentJobs; }
```

- [ ] **Step 2: Add ingest method to KnowledgeService**

In `KnowledgeService.java`, add a new method after `batchProcessDocuments`:

```java
/**
 * Ingest documents with parallel pods controlled by tenant quota.
 * Splits document_ids into N groups (N = maxConcurrentJobs), each group becomes one task.
 */
@Transactional
public Map<String, Object> ingestDocuments(TenantEntity tenant, List<String> documentIds,
                                            Map<String, String> metadata) {
    if (documentIds == null || documentIds.isEmpty()) {
        throw new BadRequestException("document_ids is required");
    }

    // Validate all documents exist, are PENDING, belong to same KB
    List<DocumentEntity> docs = new ArrayList<>();
    String kbId = null;
    String databaseId = null;

    for (String docId : documentIds) {
        DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Document not found: " + docId));
        if (doc.getStatus() != DocumentStatus.PENDING) {
            throw new BadRequestException("Document " + docId + " is not PENDING (status: " + doc.getStatus() + ")");
        }
        if (kbId == null) {
            kbId = doc.getKbId();
        } else if (!kbId.equals(doc.getKbId())) {
            throw new BadRequestException("All documents must belong to the same knowledge base");
        }
        docs.add(doc);
    }

    final String finalKbId = kbId;
    KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(finalKbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Knowledge base not found: " + finalKbId));
    databaseId = kb.getDatabaseId();
    if (databaseId == null) {
        throw new BadRequestException("Knowledge base " + kbId + " has no database assigned");
    }

    // Apply metadata to all documents if provided
    if (metadata != null && !metadata.isEmpty()) {
        for (DocumentEntity doc : docs) {
            Map<String, String> merged = new LinkedHashMap<>(doc.getMetadata() != null ? doc.getMetadata() : Map.of());
            merged.putAll(metadata);
            doc.setMetadata(merged);
            documentRepository.save(doc);
        }
    }

    // Split into N groups based on tenant quota
    int podCount = props.getKnowledge().getMaxConcurrentJobs();
    podCount = Math.min(podCount, documentIds.size()); // don't create more pods than docs

    List<List<String>> groups = new ArrayList<>();
    for (int i = 0; i < podCount; i++) {
        groups.add(new ArrayList<>());
    }
    for (int i = 0; i < documentIds.size(); i++) {
        groups.get(i % podCount).add(documentIds.get(i));
    }

    // Create one BATCH_DOCUMENT_PARSE task per group
    List<String> taskIds = new ArrayList<>();
    List<Integer> docsPerPod = new ArrayList<>();
    String embModel = kb.getEmbeddingModel() != null
            ? kb.getEmbeddingModel() : props.getKnowledge().getEmbeddingModel();

    for (List<String> group : groups) {
        List<Map<String, Object>> docParams = new ArrayList<>();
        for (String docId : group) {
            DocumentEntity doc = docs.stream().filter(d -> d.getId().equals(docId)).findFirst().orElseThrow();
            Map<String, Object> dp = new LinkedHashMap<>();
            dp.put("document_id", doc.getId());
            dp.put("obs_key", doc.getObsKey());
            dp.put("format", doc.getFormat());
            dp.put("filename", doc.getFilename());
            dp.put("kb_id", doc.getKbId());
            dp.put("tenant_id", tenant.getId());
            docParams.add(dp);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("document_ids", group);
        params.put("documents", docParams);
        params.put("tenant_id", tenant.getId());
        params.put("kb_id", kbId);
        params.put("database_connstr", "placeholder");
        params.put("embedding_api_url", props.getKnowledge().getEmbeddingApiUrl());
        params.put("embedding_api_key", props.getKnowledge().getEmbeddingApiKey());
        params.put("embedding_model", embModel);

        KbWriteTaskEntity task = kbWriteQueue.submit(tenant.getId(), kbId,
                databaseId, KbWriteTaskType.BATCH_DOCUMENT_PARSE, params);
        taskIds.add(task.getId());
        docsPerPod.add(group.size());
    }

    // Mark all documents as PROCESSING
    for (DocumentEntity doc : docs) {
        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);
    }

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("task_ids", taskIds);
    result.put("pod_count", podCount);
    result.put("documents_per_pod", docsPerPod);
    result.put("total_documents", documentIds.size());
    return result;
}
```

- [ ] **Step 3: Add ingest endpoint to KnowledgeController**

In `KnowledgeController.java`, add after the `batchProcess` endpoint:

```java
@SuppressWarnings("unchecked")
@PostMapping("/ingest")
public Map<String, Object> ingest(HttpServletRequest req, @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    List<String> documentIds = (List<String>) body.get("document_ids");
    Map<String, String> metadata = (Map<String, String>) body.get("metadata");
    return knowledgeService.ingestDocuments(tenant, documentIds, metadata);
}
```

- [ ] **Step 4: Verify compilation**

Run: `cd lakeon-api && mvn compile -pl . -q 2>&1 | tail -10`

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java
git commit -m "feat: add /knowledge/ingest API with tenant quota-based parallel pods"
```

---

### Task 5: KbWriteQueue drain() — respect tenant quota for heavyweight tasks

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java` (drain method, lines 459-468)
- Modify: `lakeon-api/src/main/java/com/lakeon/job/JobService.java` (line 31)

- [ ] **Step 1: Add heavyweight concurrency check in drain()**

In `KbWriteQueue.java`, in the `drain()` method, after the lightweight check (after line 468 `return;`), add:

```java
// Check heavyweight task concurrency against tenant quota
long runningHeavy = active.stream()
    .filter(t -> t.getStatus() == KbWriteTaskStatus.RUNNING
              && !LIGHTWEIGHT_TYPES.contains(t.getType()))
    .count();
int quota = props.getKnowledge().getMaxConcurrentJobs();
if (runningHeavy >= quota) {
    log.debug("db {} at heavyweight quota ({}/{}), pausing drain",
              databaseId, runningHeavy, quota);
    return;
}
```

- [ ] **Step 2: Increase JobService executor pool**

In `JobService.java`, change line 31:

```java
// Before:
private final ExecutorService executor = Executors.newFixedThreadPool(2);
// After:
private final ExecutorService executor = Executors.newFixedThreadPool(8);
```

- [ ] **Step 3: Verify compilation and existing tests**

Run: `cd lakeon-api && mvn compile -pl . -q 2>&1 | tail -10`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java \
        lakeon-api/src/main/java/com/lakeon/job/JobService.java
git commit -m "feat: add tenant quota concurrency control in drain(), increase job launcher pool"
```

---

### Task 6: Frontend — use ingest API for directory upload

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Add ingest API function**

In `lakeon-console/src/api/knowledge.ts`, add after `batchProcessDocuments`:

```typescript
export function ingestDocuments(documentIds: string[], metadata?: Record<string, string>) {
  const body: any = { document_ids: documentIds }
  if (metadata) body.metadata = metadata
  return api.post<{ task_ids: string[]; pod_count: number; documents_per_pod: number[]; total_documents: number }>(
    '/knowledge/ingest', body
  )
}
```

- [ ] **Step 2: Modify runBatchUpload to collect all doc IDs then call ingest**

In `KnowledgeBaseDetail.vue`, the current `runBatchUpload` calls `batchProcessDocuments(successIds)` after each batch of 20. Change it to:
1. Collect all successful document IDs across all batches
2. After the loop, call `ingestDocuments` once with all IDs

Replace the batch loop body. Find the current code around lines 840-841:
```typescript
// Submit batch process
await batchProcessDocuments(successIds)
```

Replace the entire try block inside `runBatchUpload` with:

```typescript
  try {
    const BATCH_SIZE = 20
    const allDocumentIds: string[] = []

    for (let batchStart = 0; batchStart < files.length; batchStart += BATCH_SIZE) {
      const batchFiles = files.slice(batchStart, batchStart + BATCH_SIZE)
      const batchIndices = batchFiles.map((_, i) => batchStart + i)

      // Get presigned URLs for this batch
      const fileSpecs = batchFiles.map(f => {
        const spec: { filename: string; folder?: string } = { filename: f.name }
        if ((f as any).webkitRelativePath) {
          const parts = (f as any).webkitRelativePath.split('/')
          if (parts.length > 1) {
            spec.folder = parts.slice(0, -1).join('/')
          }
        }
        return spec
      })
      const urlResp = await batchGetUploadUrls(kbId, fileSpecs)
      const docItems = urlResp.data.documents

      // Concurrent PUT uploads (3 at a time)
      const CONCURRENCY = 3
      const documentIds: string[] = new Array(docItems.length)
      const uploadTasks = docItems.map((item, i) => async () => {
        const idx = batchIndices[i]!
        uploadProgress.value[idx] = { filename: item.filename, status: 'uploading' }
        const uploadResp = await fetch(item.upload_url, { method: 'PUT', body: batchFiles[i] })
        if (!uploadResp.ok) {
          uploadProgress.value[idx] = { filename: item.filename, status: 'error', error: `HTTP ${uploadResp.status}` }
          return null
        }
        uploadStats.uploadedBytes += batchFiles[i]!.size
        documentIds[i] = item.document_id
        return item.document_id
      })

      const results: (string | null)[] = []
      for (let i = 0; i < uploadTasks.length; i += CONCURRENCY) {
        const chunk = uploadTasks.slice(i, i + CONCURRENCY)
        const chunkResults = await Promise.all(chunk.map(t => t()))
        results.push(...chunkResults)
      }

      const successIds = results.filter((id): id is string => id !== null)
      allDocumentIds.push(...successIds)

      // Mark as uploading complete
      batchIndices.forEach((idx, i) => {
        if (results[i] !== null) {
          uploadProgress.value[idx] = { filename: files[idx]!.name, status: 'processing' }
        }
      })
    }

    // All uploads done — trigger parallel ingestion with a single API call
    if (allDocumentIds.length > 0) {
      await ingestDocuments(allDocumentIds)
    }
  }
```

Also add import at the top of the script section:
```typescript
import { batchGetUploadUrls, batchProcessDocuments, ingestDocuments, ... } from '@/api/knowledge'
```

- [ ] **Step 3: TypeScript check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10`

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts \
        lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): use ingest API for parallel directory upload"
```

---

### Task 7: MCP — update knowledge_upload_directory to use ingest API

**Files:**
- Modify: `dbay-mcp/src/dbay_mcp/server.py`

- [ ] **Step 1: Update upload_directory to pass folder and use ingest API**

In `server.py`, modify the `knowledge_upload_directory` function. In the batch loop (around line 296-334):

1. Build fileSpecs with folder:
```python
file_specs = []
for f in batch_files:
    spec = {"filename": f.name}
    rel = f.relative_to(dir_path)
    if len(rel.parts) > 1:
        spec["folder"] = "/".join(rel.parts[:-1])
    if tags:
        spec["tags"] = tags
    file_specs.append(spec)
```

2. After the upload loop, instead of calling `batch-process` per batch, collect all doc_ids.

3. After the main loop, call `POST /knowledge/ingest` once with all collected doc_ids:
```python
if all_doc_ids:
    _api("POST", "/knowledge/ingest", json={"document_ids": all_doc_ids})
```

- [ ] **Step 2: Run MCP tests**

Run: `cd dbay-mcp && python -m pytest tests/ -q 2>&1 | tail -10`

- [ ] **Step 3: Commit**

```bash
git add dbay-mcp/src/dbay_mcp/server.py
git commit -m "feat(mcp): use ingest API with folder paths for directory upload"
```

---

### Task 8: Final verification

- [ ] **Step 1: Run API tests**

Run: `cd lakeon-api && mvn test -pl . -Dspring.profiles.active=test -q 2>&1 | tail -20`

- [ ] **Step 2: TypeScript check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10`

- [ ] **Step 3: Verify commit history**

```bash
git log --oneline -10
```
