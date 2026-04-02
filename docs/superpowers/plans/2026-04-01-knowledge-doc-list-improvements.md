# 知识库文档列表页改进 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add pagination, status filtering, sortable columns, and separate upload/processing progress display to the knowledge base document list page.

**Architecture:** Backend adds paginated query + stats endpoint to KnowledgeController/DocumentRepository. Frontend splits progress display into upload speed and processing progress, adds status filter tabs, pagination controls, and sortable table headers.

**Tech Stack:** Java/Spring Data JPA (backend), Vue 3 Composition API + TypeScript (frontend)

**Spec:** `docs/superpowers/specs/2026-04-01-knowledge-doc-list-improvements-design.md`

---

### Task 1: Backend — Add paginated document listing with status filter and sorting

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java:609-617`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java:146-154`

- [ ] **Step 1: Add paginated query to DocumentRepository**

Add a native query method to `DocumentRepository.java` after line 17 (`countByStatus`):

```java
@Query(value = """
    SELECT * FROM documents
    WHERE tenant_id = :tenantId
      AND (:kbId IS NULL OR kb_id = :kbId)
      AND (:status IS NULL OR status = :status)
    ORDER BY
      CASE WHEN :sortBy = 'upload_time' AND :sortOrder = 'asc' THEN created_at END ASC,
      CASE WHEN :sortBy = 'upload_time' AND :sortOrder = 'desc' THEN created_at END DESC,
      CASE WHEN :sortBy = 'size_bytes' AND :sortOrder = 'asc' THEN size_bytes END ASC NULLS FIRST,
      CASE WHEN :sortBy = 'size_bytes' AND :sortOrder = 'desc' THEN size_bytes END DESC NULLS LAST,
      CASE WHEN :sortBy = 'chunks_count' AND :sortOrder = 'asc' THEN chunks_count END ASC NULLS FIRST,
      CASE WHEN :sortBy = 'chunks_count' AND :sortOrder = 'desc' THEN chunks_count END DESC NULLS LAST,
      CASE WHEN :sortBy = 'status' AND :sortOrder = 'asc' THEN status END ASC,
      CASE WHEN :sortBy = 'status' AND :sortOrder = 'desc' THEN status END DESC,
      created_at DESC
    LIMIT :limit OFFSET :offset
    """, nativeQuery = true)
List<DocumentEntity> findPagedDocuments(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId,
    @Param("status") String status,
    @Param("sortBy") String sortBy,
    @Param("sortOrder") String sortOrder,
    @Param("limit") int limit,
    @Param("offset") int offset);

@Query(value = """
    SELECT COUNT(*) FROM documents
    WHERE tenant_id = :tenantId
      AND (:kbId IS NULL OR kb_id = :kbId)
      AND (:status IS NULL OR status = :status)
    """, nativeQuery = true)
long countDocuments(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId,
    @Param("status") String status);
```

- [ ] **Step 2: Add paginated list method to KnowledgeService**

In `KnowledgeService.java`, add a new method after the existing `listDocuments` method (line 617):

```java
public record DocumentPage(List<DocumentEntity> documents, long total, int page, int pageSize) {}

public DocumentPage listDocumentsPaged(String tenantId, String kbId,
                                       String status, String sortBy, String sortOrder,
                                       int page, int pageSize) {
    // Validate and default sort params
    if (sortBy == null || !List.of("upload_time", "size_bytes", "chunks_count", "status").contains(sortBy)) {
        sortBy = "upload_time";
    }
    if (sortOrder == null || !List.of("asc", "desc").contains(sortOrder)) {
        sortOrder = "desc";
    }
    if (pageSize < 1) pageSize = 50;
    if (pageSize > 200) pageSize = 200;
    if (page < 1) page = 1;

    int offset = (page - 1) * pageSize;
    String statusParam = (status != null && !status.isBlank()) ? status : null;
    String kbParam = (kbId != null && !kbId.isBlank()) ? kbId : null;

    List<DocumentEntity> docs = documentRepository.findPagedDocuments(
        tenantId, kbParam, statusParam, sortBy, sortOrder, pageSize, offset);
    long total = documentRepository.countDocuments(tenantId, kbParam, statusParam);
    return new DocumentPage(docs, total, page, pageSize);
}
```

- [ ] **Step 3: Update listDocuments endpoint in KnowledgeController**

Replace the `listDocuments` method at line 146-154 of `KnowledgeController.java`:

```java
@GetMapping("/documents")
public Map<String, Object> listDocuments(HttpServletRequest req,
                                         @RequestParam(value = "kb_id", required = false) String kbId,
                                         @RequestParam(value = "database_id", required = false) String databaseId,
                                         @RequestParam(value = "page", defaultValue = "1") int page,
                                         @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "sort_by", defaultValue = "upload_time") String sortBy,
                                         @RequestParam(value = "sort_order", defaultValue = "desc") String sortOrder) {
    TenantEntity tenant = getTenant(req);
    KnowledgeService.DocumentPage result = knowledgeService.listDocumentsPaged(
        tenant.getId(), kbId, status, sortBy, sortOrder, page, pageSize);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("documents", result.documents().stream().map(this::toDocumentResponse).toList());
    response.put("total", result.total());
    response.put("page", result.page());
    response.put("page_size", result.pageSize());
    return response;
}
```

- [ ] **Step 4: Verify backend compiles**

Run:
```bash
cd lakeon-api && mvn compile -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat(knowledge): add paginated document listing with filter and sort"
```

---

### Task 2: Backend — Add document stats endpoint

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`

- [ ] **Step 1: Add stats query to DocumentRepository**

Add to `DocumentRepository.java`:

```java
@Query(value = """
    SELECT status, COUNT(*) as cnt FROM documents
    WHERE tenant_id = :tenantId AND kb_id = :kbId
    GROUP BY status
    """, nativeQuery = true)
List<Object[]> countByStatusGrouped(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId);
```

- [ ] **Step 2: Add stats endpoint to KnowledgeController**

Add after the `listDocuments` method in `KnowledgeController.java`:

```java
@GetMapping("/documents/stats")
public Map<String, Object> documentStats(HttpServletRequest req,
                                         @RequestParam("kb_id") String kbId) {
    TenantEntity tenant = getTenant(req);
    List<Object[]> rows = documentRepository.countByStatusGrouped(tenant.getId(), kbId);
    long total = 0, processing = 0, ready = 0, failed = 0, pending = 0;
    for (Object[] row : rows) {
        String status = (String) row[0];
        long count = ((Number) row[1]).longValue();
        total += count;
        switch (status) {
            case "PROCESSING" -> processing = count;
            case "READY" -> ready = count;
            case "FAILED" -> failed = count;
            case "PENDING" -> pending = count;
        }
    }
    return Map.of("total", total, "processing", processing, "ready", ready, "failed", failed, "pending", pending);
}
```

**Important:** This endpoint must be declared BEFORE the `@GetMapping("/documents/{id}")` route in the controller, otherwise Spring will try to match "stats" as a document ID. Move it right after the `listDocuments` method.

- [ ] **Step 3: Verify backend compiles**

Run:
```bash
cd lakeon-api && mvn compile -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat(knowledge): add document stats endpoint for progress tracking"
```

---

### Task 3: Frontend — Update API client for pagination and stats

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`

- [ ] **Step 1: Add DocumentListResponse and DocumentStats interfaces and update API functions**

In `lakeon-console/src/api/knowledge.ts`, add the new interface after the existing `Document` interface (after line 43), and update the `listDocuments` function:

```typescript
export interface DocumentListResponse {
  documents: Document[]
  total: number
  page: number
  page_size: number
}

export interface DocumentStats {
  total: number
  processing: number
  ready: number
  failed: number
  pending: number
}
```

Replace the `listDocuments` function (line 87-89):

```typescript
export function listDocuments(kbId: string, params?: {
  page?: number
  page_size?: number
  status?: string
  sort_by?: string
  sort_order?: string
}) {
  return api.get<DocumentListResponse>('/knowledge/documents', {
    params: { kb_id: kbId, ...params },
  })
}
```

Add new function after `listDocuments`:

```typescript
export function getDocumentStats(kbId: string) {
  return api.get<DocumentStats>('/knowledge/documents/stats', {
    params: { kb_id: kbId },
  })
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts
git commit -m "feat(knowledge): update API client for pagination and stats"
```

---

### Task 4: Frontend — Refactor document list to use pagination, filtering, and sorting

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

This is the largest task. It modifies the `<script setup>` section of the Vue component.

- [ ] **Step 1: Add new reactive state for pagination, filtering, sorting, and stats**

In the `<script setup>` section, find where `documents` and `docSearch` are declared (around line 500-510). Add new state nearby:

```typescript
// ── Pagination, filtering, sorting state ──
const docPage = ref(1)
const docPageSize = ref(50)
const docTotal = ref(0)
const docStatusFilter = ref<string | undefined>(undefined)  // undefined = all
const docSortBy = ref('upload_time')
const docSortOrder = ref<'asc' | 'desc'>('desc')
const docStats = ref<DocumentStats>({ total: 0, processing: 0, ready: 0, failed: 0, pending: 0 })
```

Add the import for `getDocumentStats` and `DocumentStats` at the top of the file where other knowledge imports are:

```typescript
import { ..., listDocuments, getDocumentStats, type DocumentStats } from '@/api/knowledge'
```

- [ ] **Step 2: Rewrite loadDocuments to use pagination params**

Replace the existing `loadDocuments` function (lines 594-603):

```typescript
async function loadDocuments() {
  const kbId = route.params.kbId as string
  docLoading.value = true
  try {
    const resp = await listDocuments(kbId, {
      page: docPage.value,
      page_size: docPageSize.value,
      status: docStatusFilter.value,
      sort_by: docSortBy.value,
      sort_order: docSortOrder.value,
    })
    documents.value = resp.data.documents
    docTotal.value = resp.data.total
  } finally {
    docLoading.value = false
  }
}
```

- [ ] **Step 3: Add stats loading and helper functions**

Add after `loadDocuments`:

```typescript
async function loadStats() {
  const kbId = route.params.kbId as string
  try {
    const resp = await getDocumentStats(kbId)
    docStats.value = resp.data
  } catch { /* ignore */ }
}

function setStatusFilter(status: string | undefined) {
  docStatusFilter.value = status
  docPage.value = 1
  loadDocuments()
  loadStats()
}

function setSort(field: string) {
  if (docSortBy.value === field) {
    // Toggle: desc -> asc -> no sort (back to default)
    if (docSortOrder.value === 'desc') {
      docSortOrder.value = 'asc'
    } else {
      docSortBy.value = 'upload_time'
      docSortOrder.value = 'desc'
    }
  } else {
    docSortBy.value = field
    docSortOrder.value = 'desc'
  }
  docPage.value = 1
  loadDocuments()
}

function sortIcon(field: string): string {
  if (docSortBy.value !== field) return '↕'
  return docSortOrder.value === 'asc' ? '↑' : '↓'
}

function setPage(p: number) {
  docPage.value = p
  loadDocuments()
}
```

- [ ] **Step 4: Update filteredDocs computed to work with server-side data**

Replace the `filteredDocs` computed (lines 588-592). Since filtering is now server-side, `filteredDocs` just applies the local search filter on the already-paginated results:

```typescript
const filteredDocs = computed(() => {
  if (!docSearch.value) return documents.value
  const q = docSearch.value.toLowerCase()
  return documents.value.filter(d => d.filename.toLowerCase().includes(q))
})
```

This stays the same — local search is additive to server-side status filter.

- [ ] **Step 5: Update polling to use pagination and stats**

Replace the polling section (lines 807-838):

```typescript
// ── Auto-poll PROCESSING documents for progress ────────────────
let pollTimer: ReturnType<typeof setInterval> | null = null

function startPollingIfNeeded() {
  const hasProcessing = docStats.value.processing > 0
  if (hasProcessing && !pollTimer) {
    pollTimer = setInterval(async () => {
      try {
        await Promise.all([loadDocuments(), loadStats()])
        if (docStats.value.processing === 0) {
          stopPolling()
        }
      } catch { /* ignore */ }
    }, 8000)
  } else if (!hasProcessing && pollTimer) {
    stopPolling()
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(() => docStats.value.processing, (val) => {
  if (val > 0) startPollingIfNeeded()
  else stopPolling()
})
onUnmounted(stopPolling)
```

- [ ] **Step 6: Update onMounted to load stats**

Replace the `onMounted` block (lines 840-849):

```typescript
onMounted(async () => {
  const kbId = route.params.kbId as string
  const [kbResp] = await Promise.all([
    getKnowledgeBase(kbId),
    loadDocuments(),
    loadStats(),
  ])
  kb.value = kbResp.data
  loadDataSources()
  startPollingIfNeeded()
})
```

- [ ] **Step 7: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(knowledge): refactor document list with pagination, filter, sort"
```

---

### Task 5: Frontend — Update template for status tabs, sortable headers, and pagination

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` (template section)

- [ ] **Step 1: Replace status summary with filter tabs**

Replace the status summary bar (lines 70-74):

```html
<!-- Status filter tabs -->
<div v-if="docStats.total > 0" style="display: flex; gap: 0; margin-bottom: 14px; border-bottom: 1px solid #e8e8e8;">
  <div v-for="tab in [
    { key: undefined, label: '全部', count: docStats.total },
    { key: 'PROCESSING', label: '处理中', count: docStats.processing },
    { key: 'READY', label: '已就绪', count: docStats.ready },
    { key: 'FAILED', label: '失败', count: docStats.failed },
  ]" :key="tab.label"
    style="padding: 8px 16px; font-size: 13px; cursor: pointer; transition: color 0.2s;"
    :style="{
      color: docStatusFilter === tab.key ? '#1890ff' : '#666',
      borderBottom: docStatusFilter === tab.key ? '2px solid #1890ff' : '2px solid transparent',
      fontWeight: docStatusFilter === tab.key ? 500 : 400,
    }"
    @click="setStatusFilter(tab.key)">
    {{ tab.label }} ({{ tab.count }})
  </div>
  <div style="flex: 1;"></div>
  <div style="padding: 8px 0; font-size: 12px; color: #999; align-self: center;">
    共 {{ docTotal }} 条
  </div>
</div>
```

- [ ] **Step 2: Make table headers sortable**

Replace the `<thead>` section (lines 111-123). Keep the checkbox and filename columns unchanged, make size/chunks/status/upload_time sortable:

```html
<thead>
  <tr>
    <th style="width: 36px; text-align: center;">
      <input type="checkbox" ref="selectAllCheckbox" :checked="isAllSelected" @change="toggleSelectAll" style="cursor: pointer;">
    </th>
    <th>文件名</th>
    <th>格式</th>
    <th style="cursor: pointer; user-select: none;" @click="setSort('size_bytes')">
      大小 <span style="color: #bbb;">{{ sortIcon('size_bytes') }}</span>
    </th>
    <th style="cursor: pointer; user-select: none;" @click="setSort('chunks_count')">
      Chunks <span style="color: #bbb;">{{ sortIcon('chunks_count') }}</span>
    </th>
    <th style="cursor: pointer; user-select: none;" @click="setSort('status')">
      状态 <span style="color: #bbb;">{{ sortIcon('status') }}</span>
    </th>
    <th style="cursor: pointer; user-select: none;" @click="setSort('upload_time')">
      上传时间 <span style="color: #bbb;">{{ sortIcon('upload_time') }}</span>
    </th>
    <th>操作</th>
  </tr>
</thead>
```

- [ ] **Step 3: Add pagination controls after the table**

After the closing `</table>` tag and before the end of the documents tab `</div>`, add pagination:

```html
<!-- Pagination -->
<div v-if="docTotal > docPageSize" style="display: flex; justify-content: space-between; align-items: center; margin-top: 14px; font-size: 12px; color: #999;">
  <div>每页 {{ docPageSize }} 条</div>
  <div style="display: flex; gap: 4px; align-items: center;">
    <button class="page-btn" :disabled="docPage <= 1" @click="setPage(docPage - 1)">&lsaquo;</button>
    <template v-for="p in paginationPages" :key="p">
      <span v-if="p === '...'" style="padding: 3px 6px; color: #999;">...</span>
      <button v-else class="page-btn" :class="{ active: p === docPage }" @click="setPage(p as number)">{{ p }}</button>
    </template>
    <button class="page-btn" :disabled="docPage >= totalPages" @click="setPage(docPage + 1)">&rsaquo;</button>
  </div>
</div>
```

Add the pagination computed in `<script setup>`:

```typescript
const totalPages = computed(() => Math.ceil(docTotal.value / docPageSize.value))

const paginationPages = computed(() => {
  const total = totalPages.value
  const current = docPage.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const pages: (number | string)[] = [1]
  if (current > 3) pages.push('...')
  for (let i = Math.max(2, current - 1); i <= Math.min(total - 1, current + 1); i++) {
    pages.push(i)
  }
  if (current < total - 2) pages.push('...')
  pages.push(total)
  return pages
})
```

- [ ] **Step 4: Add pagination button CSS**

Add to `<style scoped>` section:

```css
.page-btn {
  padding: 3px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 3px;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
  color: #333;
}
.page-btn:hover:not(:disabled) {
  border-color: #1890ff;
  color: #1890ff;
}
.page-btn.active {
  background: #1890ff;
  color: #fff;
  border-color: #1890ff;
}
.page-btn:disabled {
  color: #d9d9d9;
  cursor: not-allowed;
}
```

- [ ] **Step 5: Verify frontend compiles**

Run:
```bash
cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10
```
Expected: No errors

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(knowledge): add status filter tabs, sortable headers, pagination UI"
```

---

### Task 6: Frontend — Add upload speed and processing progress card

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Add upload stats reactive state**

Add near the other upload-related state (around line 637):

```typescript
const uploadStats = reactive({
  totalBytes: 0,
  uploadedBytes: 0,
  startTime: 0,
  speed: 0,    // bytes/s
  eta: 0,      // seconds
})
```

- [ ] **Step 2: Update runBatchUpload to track upload bytes and speed**

In the `runBatchUpload` function, add byte tracking. After `uploading.value = true` (line 639), add:

```typescript
uploadStats.totalBytes = files.reduce((sum, f) => sum + f.size, 0)
uploadStats.uploadedBytes = 0
uploadStats.startTime = Date.now()
uploadStats.speed = 0
uploadStats.eta = 0

// Speed update interval
const speedInterval = setInterval(() => {
  const elapsed = (Date.now() - uploadStats.startTime) / 1000
  if (elapsed > 0) {
    uploadStats.speed = uploadStats.uploadedBytes / elapsed
    const remaining = uploadStats.totalBytes - uploadStats.uploadedBytes
    uploadStats.eta = uploadStats.speed > 0 ? remaining / uploadStats.speed : 0
  }
}, 1000)
```

Inside the upload task (after `const uploadResp = await fetch(...)` succeeds, around line 665), add byte tracking:

```typescript
uploadStats.uploadedBytes += batchFiles[i]!.size
```

In the `finally` block of `runBatchUpload` (line 703), add cleanup:

```typescript
clearInterval(speedInterval)
```

- [ ] **Step 3: Replace upload progress bar with combined progress card**

Replace the upload progress bar template (lines 88-97) with the combined progress card:

```html
<!-- Combined progress card -->
<div v-if="uploading || docStats.processing > 0" style="background: #fff; border: 1px solid #e8e8e8; border-radius: 8px; padding: 14px 18px; margin-bottom: 16px;">
  <!-- Upload row -->
  <div v-if="uploading || uploadJustFinished" style="display: flex; align-items: center; gap: 10px; margin-bottom: 10px;">
    <span style="font-size: 12px; color: #666; width: 56px; flex-shrink: 0;">上传</span>
    <div style="flex: 1; height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden;">
      <div :style="{ width: (uploadProgress.length > 0 ? Math.round(uploadProgress.filter(f => f.status === 'done' || f.status === 'error').length / uploadProgress.length * 100) : 0) + '%', height: '100%', background: '#1890ff', borderRadius: '3px', transition: 'width 0.3s' }"></div>
    </div>
    <span style="font-size: 13px; color: #333; min-width: 75px; text-align: right;">
      {{ uploadProgress.filter(f => f.status === 'done').length }}/{{ uploadProgress.length }}
    </span>
    <span v-if="uploading && uploadStats.speed > 0" style="font-size: 11px; color: #999; min-width: 160px;">
      {{ formatSpeed(uploadStats.speed) }} · 预计还需 {{ formatEta(uploadStats.eta) }}
    </span>
    <span v-else-if="!uploading" style="font-size: 11px; color: #52c41a;">上传完成</span>
  </div>
  <!-- Processing row -->
  <div v-if="docStats.processing > 0 || docStats.pending > 0" style="display: flex; align-items: center; gap: 10px;">
    <span style="font-size: 12px; color: #666; width: 56px; flex-shrink: 0;">处理</span>
    <div style="flex: 1; height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden;">
      <div :style="{ width: (docStats.total > 0 ? Math.round((docStats.ready + docStats.failed) / docStats.total * 100) : 0) + '%', height: '100%', background: '#52c41a', borderRadius: '3px', transition: 'width 0.3s' }"></div>
    </div>
    <span style="font-size: 13px; color: #333; min-width: 75px; text-align: right;">
      {{ docStats.ready + docStats.failed }}/{{ docStats.total }} ({{ docStats.total > 0 ? Math.round((docStats.ready + docStats.failed) / docStats.total * 100) : 0 }}%)
    </span>
    <span style="font-size: 11px; color: #999; min-width: 160px;">
      <span v-if="docStats.failed > 0" style="color: #e6393d;">{{ docStats.failed }} 失败</span>
    </span>
  </div>
  <!-- Expandable detail -->
  <div v-if="docStats.processing > 0 || docStats.pending > 0" style="font-size: 11px; color: #999; margin-top: 6px; padding-left: 66px;">
    排队 {{ docStats.pending }} · 解析中 {{ docStats.processing }} · 已完成 {{ docStats.ready }} · 失败 {{ docStats.failed }}
  </div>
</div>
```

- [ ] **Step 4: Add formatting helper functions**

Add in `<script setup>`:

```typescript
function formatSpeed(bytesPerSec: number): string {
  if (bytesPerSec < 1024) return `${Math.round(bytesPerSec)} B/s`
  if (bytesPerSec < 1024 * 1024) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`
  return `${(bytesPerSec / 1024 / 1024).toFixed(1)} MB/s`
}

function formatEta(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)} 秒`
  if (seconds < 3600) return `${Math.round(seconds / 60)} 分钟`
  return `${(seconds / 3600).toFixed(1)} 小时`
}
```

- [ ] **Step 5: Verify frontend compiles**

Run:
```bash
cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10
```
Expected: No errors

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(knowledge): add upload speed and processing progress card"
```

---

### Task 7: Fix callers of listDocuments that expect array response

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue` (any remaining direct `.data` array usage)
- Check: `lakeon-admin/src/views/knowledge/KnowledgeList.vue`

The `listDocuments` API now returns `{ documents, total, page, page_size }` instead of an array. Any code that does `resp.data.some(...)` or `resp.data.forEach(...)` needs to use `resp.data.documents` instead.

- [ ] **Step 1: Search for all listDocuments callers**

Run:
```bash
grep -rn "listDocuments" lakeon-console/src/ lakeon-admin/src/ --include="*.ts" --include="*.vue" | grep -v "node_modules"
```

Fix any callers that still expect the old array format. The main one is in the polling section — ensure it uses `resp.data.documents` instead of `resp.data`.

- [ ] **Step 2: Check admin panel usage**

Read `lakeon-admin/src/views/knowledge/KnowledgeList.vue` and check if it calls `listDocuments`. If so, update it to handle the new response format. If the admin panel has its own API client, it may need the same change.

- [ ] **Step 3: Verify both frontends compile**

Run:
```bash
cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10
cd lakeon-admin && npx vue-tsc -b --noEmit 2>&1 | tail -10
```
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "fix(knowledge): update all listDocuments callers for new paginated response"
```

---

### Task 8: End-to-end verification

- [ ] **Step 1: Deploy and test backend API**

Start the API locally or deploy to dev, then verify:

```bash
# Test paginated list
curl -s "http://localhost:8090/api/v1/knowledge/documents?kb_id=<KB_ID>&page=1&page_size=5" -H "Authorization: Bearer <TOKEN>" | jq '.total, (.documents | length)'

# Test stats
curl -s "http://localhost:8090/api/v1/knowledge/documents/stats?kb_id=<KB_ID>" -H "Authorization: Bearer <TOKEN>" | jq .

# Test status filter
curl -s "http://localhost:8090/api/v1/knowledge/documents?kb_id=<KB_ID>&status=READY&page=1&page_size=5" -H "Authorization: Bearer <TOKEN>" | jq '.total'

# Test sorting
curl -s "http://localhost:8090/api/v1/knowledge/documents?kb_id=<KB_ID>&sort_by=size_bytes&sort_order=desc&page=1&page_size=5" -H "Authorization: Bearer <TOKEN>" | jq '[.documents[].size_bytes]'
```

- [ ] **Step 2: Test frontend in browser**

Open the knowledge base document page and verify:
1. Progress card shows upload speed during upload and processing progress
2. Status filter tabs work and show correct counts
3. Clicking table headers sorts correctly
4. Pagination works: page numbers, prev/next buttons
5. Filtering + sorting + pagination work together (e.g., filter "失败", sort by upload time, page 2)

- [ ] **Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix(knowledge): address e2e test findings"
```
