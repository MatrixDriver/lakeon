# KB Folder Tree View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat document list with a folder browser — users see directories first, click to enter, breadcrumb to navigate back.

**Architecture:** Backend adds folder filter + folders aggregation API. Frontend renders folder cards + document table, with breadcrumb navigation.

**Tech Stack:** Java 21/Spring Boot, Vue 3/TypeScript, PostgreSQL

---

### Task 1: Backend — add folder filter to document list + folders API

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java`

- [ ] **Step 1: Add folder parameter to document query**

In `DocumentRepository.java`, modify the `findPagedDocuments` native query to accept a `folder` parameter. Add to the WHERE clause:

```sql
AND (:folder IS NULL OR folder = :folder)
```

Add the parameter to both `findPagedDocuments` and `countDocuments` methods:

```java
List<DocumentEntity> findPagedDocuments(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId,
    @Param("status") String status,
    @Param("folder") String folder,
    @Param("sortBy") String sortBy,
    @Param("sortOrder") String sortOrder,
    @Param("limit") int limit,
    @Param("offset") int offset);

long countDocuments(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId,
    @Param("status") String status,
    @Param("folder") String folder);
```

- [ ] **Step 2: Add folders aggregation query**

In `DocumentRepository.java`, add a new native query that lists immediate subfolders for a given parent path:

```java
@Query(value = """
    SELECT
      CASE WHEN :parent = '' THEN split_part(folder, '/', 1)
           ELSE split_part(substring(folder from length(:parent) + 2), '/', 1)
      END AS name,
      COUNT(*) AS doc_count,
      COALESCE(SUM(size_bytes), 0) AS total_size
    FROM documents
    WHERE tenant_id = :tenantId
      AND kb_id = :kbId
      AND folder != ''
      AND (
        (:parent = '' AND folder NOT LIKE '' )
        OR (:parent != '' AND folder LIKE :parent || '/%')
      )
    GROUP BY name
    HAVING name != ''
    ORDER BY name
    """, nativeQuery = true)
List<Object[]> findSubfolders(
    @Param("tenantId") String tenantId,
    @Param("kbId") String kbId,
    @Param("parent") String parent);
```

- [ ] **Step 3: Update KnowledgeService.listDocumentsPaged**

Add `folder` parameter to `listDocumentsPaged`:

```java
public DocumentPage listDocumentsPaged(String tenantId, String kbId,
                                       String status, String folder, String sortBy, String sortOrder,
                                       int page, int pageSize) {
```

Pass `folder` to both repository calls. If folder is empty string, pass it as-is (matches root-level docs). If null, don't filter by folder.

- [ ] **Step 4: Add listFolders method to KnowledgeService**

```java
public record FolderInfo(String name, String path, long documentCount, long totalSize) {}

public List<FolderInfo> listFolders(String tenantId, String kbId, String parent) {
    if (parent == null) parent = "";
    List<Object[]> rows = documentRepository.findSubfolders(tenantId, kbId, parent);
    return rows.stream().map(row -> {
        String name = (String) row[0];
        long count = ((Number) row[1]).longValue();
        long size = ((Number) row[2]).longValue();
        String path = parent.isEmpty() ? name : parent + "/" + name;
        return new FolderInfo(name, path, count, size);
    }).toList();
}
```

- [ ] **Step 5: Add folder parameter to listDocuments controller + add folders endpoint**

In `KnowledgeController.java`:

Add `folder` parameter to the `listDocuments` endpoint:
```java
@RequestParam(value = "folder", required = false) String folder
```

Pass it to `listDocumentsPaged`.

Add a new endpoint:
```java
@GetMapping("/folders")
public List<Map<String, Object>> listFolders(HttpServletRequest req,
                                              @RequestParam("kb_id") String kbId,
                                              @RequestParam(value = "parent", defaultValue = "") String parent) {
    TenantEntity tenant = getTenant(req);
    return knowledgeService.listFolders(tenant.getId(), kbId, parent).stream()
        .map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("path", f.path());
            m.put("document_count", f.documentCount());
            m.put("total_size", f.totalSize());
            return m;
        }).toList();
}
```

- [ ] **Step 6: Verify compilation**

Run: `cd lakeon-api && mvn compile -pl . -q 2>&1 | tail -10`

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/DocumentRepository.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeService.java \
        lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java
git commit -m "feat: add folder filter to document list and folders aggregation API"
```

---

### Task 2: Frontend — update API types and add folders API

**Files:**
- Modify: `lakeon-console/src/api/knowledge.ts`

- [ ] **Step 1: Update Document interface**

Add `folder` and `metadata` fields:

```typescript
export interface Document {
  id: string
  kb_id: string
  filename: string
  format: string
  size_bytes: number
  chunks_count: number | null
  status: string
  progress?: number
  progress_message?: string
  error: string | null
  tags: string[]
  folder: string
  metadata: Record<string, string>
  created_at: string
}
```

- [ ] **Step 2: Add folder parameter to listDocuments**

Find the `listDocuments` function. Add `folder` to its params:

```typescript
export function listDocuments(kbId: string, params?: {
  page?: number; page_size?: number; status?: string;
  sort_by?: string; sort_order?: string; folder?: string
}) {
  return api.get<DocumentListResponse>('/knowledge/documents', {
    params: { kb_id: kbId, ...params }
  })
}
```

- [ ] **Step 3: Add Folder interface and listFolders API**

```typescript
export interface Folder {
  name: string
  path: string
  document_count: number
  total_size: number
}

export function listFolders(kbId: string, parent: string = '') {
  return api.get<Folder[]>('/knowledge/folders', {
    params: { kb_id: kbId, parent }
  })
}
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/knowledge.ts
git commit -m "feat(console): add folder/metadata to Document type, add listFolders API"
```

---

### Task 3: Frontend — folder tree view in KnowledgeBaseDetail

**Files:**
- Modify: `lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue`

- [ ] **Step 1: Add folder state and load functions**

In the `<script setup>` section, add:

```typescript
import { listFolders, type Folder } from '@/api/knowledge'

// Folder navigation state
const currentFolder = ref('')
const folderPath = computed(() => currentFolder.value ? currentFolder.value.split('/') : [])
const folders = ref<Folder[]>([])
const foldersLoading = ref(false)

async function loadFolders() {
  const kbId = route.params.kbId as string
  foldersLoading.value = true
  try {
    const resp = await listFolders(kbId, currentFolder.value)
    folders.value = resp.data
  } finally {
    foldersLoading.value = false
  }
}

function navigateToFolder(path: string) {
  currentFolder.value = path
  docPage.value = 1
  loadFolders()
  loadDocuments()
}

function navigateUp() {
  const parts = currentFolder.value.split('/')
  parts.pop()
  navigateToFolder(parts.join('/'))
}
```

- [ ] **Step 2: Update loadDocuments to pass folder**

In the existing `loadDocuments` function, add `folder` to the API params:

```typescript
const resp = await listDocuments(kbId, {
  page: docPage.value,
  page_size: docPageSize.value,
  status: docStatusFilter.value,
  sort_by: docSortBy.value,
  sort_order: docSortOrder.value,
  folder: currentFolder.value || undefined,
})
```

- [ ] **Step 3: Call loadFolders in onMounted**

Find the `onMounted` or initial data loading section. Add `loadFolders()` alongside `loadDocuments()`.

- [ ] **Step 4: Add breadcrumb navigation to template**

Above the document table (before the `<div v-if="filteredDocs.length > 0" class="table-wrapper">`), add breadcrumb navigation:

```html
<!-- Breadcrumb navigation -->
<div class="breadcrumb" style="margin-bottom: 12px; display: flex; align-items: center; gap: 4px; font-size: 14px;">
  <span class="breadcrumb-item" :class="{ active: !currentFolder }"
        @click="navigateToFolder('')" style="cursor: pointer; color: var(--color-primary);">
    全部文档
  </span>
  <template v-for="(segment, i) in folderPath" :key="i">
    <span style="color: #999; margin: 0 2px;">/</span>
    <span class="breadcrumb-item"
          :class="{ active: i === folderPath.length - 1 }"
          @click="navigateToFolder(folderPath.slice(0, i + 1).join('/'))"
          style="cursor: pointer; color: var(--color-primary);">
      {{ segment }}
    </span>
  </template>
</div>
```

- [ ] **Step 5: Add folder cards before the document table**

After the breadcrumb, before the document table, add folder cards:

```html
<!-- Folder grid -->
<div v-if="folders.length > 0" class="folder-grid" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; margin-bottom: 16px;">
  <div v-for="folder in folders" :key="folder.path"
       class="folder-card"
       @click="navigateToFolder(folder.path)"
       style="padding: 12px 16px; border: 1px solid #e8e8e8; border-radius: 8px; cursor: pointer; transition: all 0.15s; background: #fafafa;">
    <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 4px;">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#e6a23c" stroke-width="2">
        <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
      </svg>
      <span style="font-weight: 500; font-size: 14px;">{{ folder.name }}</span>
    </div>
    <div style="font-size: 12px; color: #999;">
      {{ folder.document_count }} 个文档 · {{ formatSize(folder.total_size) }}
    </div>
  </div>
</div>
```

- [ ] **Step 6: Add hover style for folder cards**

In the `<style>` section, add:

```css
.folder-card:hover {
  border-color: var(--color-primary);
  background: #fff7ed !important;
}
.breadcrumb-item:hover {
  text-decoration: underline;
}
.breadcrumb-item.active {
  color: #333 !important;
  font-weight: 500;
  cursor: default !important;
}
.breadcrumb-item.active:hover {
  text-decoration: none;
}
```

- [ ] **Step 7: Add formatSize helper if not exists**

Check if there's already a `formatSize` function. If not, add:

```typescript
function formatSize(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return size.toFixed(i > 0 ? 1 : 0) + ' ' + units[i]
}
```

- [ ] **Step 8: TypeScript check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10`

- [ ] **Step 9: Commit**

```bash
git add lakeon-console/src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): add folder tree view with breadcrumb navigation"
```

---

### Task 4: Final verification

- [ ] **Step 1: Run API tests**

Run: `cd lakeon-api && mvn test -pl . -Dtest="LogQueryServiceTest,KbWriteQueueRetryTest,PipelineControllerTest,PipelineServiceTest,ComputeLifecycleServiceTest" -Dspring.profiles.active=test 2>&1 | grep -E "Tests run:|BUILD"`

- [ ] **Step 2: TypeScript check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -10`

- [ ] **Step 3: Verify commit history**

```bash
git log --oneline -5
```
