# Notebook Persistence + Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist notebooks to OBS with version snapshots, add a notebook list page, auto-save with Ctrl+S, and version history.

**Architecture:** DB table `notebooks` stores metadata, OBS stores cell content as JSON. Backend provides CRUD + version REST API. Frontend splits into list page (new) and editor page (refactored from current DatalakeNotebook.vue). Auto-save via debounced PUT, manual Ctrl+S creates version snapshot.

**Tech Stack:** Spring Boot + AWS S3 SDK (OBS), Vue 3 + TypeScript

---

## File Structure

### Backend
- **Create:** `lakeon-api/src/main/resources/db/migration/V24__create_notebooks.sql`
- **Create:** `lakeon-api/src/main/java/com/lakeon/notebook/NotebookEntity.java`
- **Create:** `lakeon-api/src/main/java/com/lakeon/notebook/NotebookRepository.java`
- **Create:** `lakeon-api/src/main/java/com/lakeon/notebook/NotebookStorageService.java` — OBS read/write/version/delete
- **Create:** `lakeon-api/src/main/java/com/lakeon/notebook/NotebookCrudController.java` — REST CRUD + versions

### Frontend
- **Create:** `lakeon-console/src/api/notebooks.ts` — REST client
- **Create:** `lakeon-console/src/views/datalake/DatalakeNotebookList.vue` — list page
- **Modify:** `lakeon-console/src/views/datalake/DatalakeNotebook.vue` — load from API, auto-save, version history
- **Modify:** `lakeon-console/src/router/index.ts` — update routes

---

### Task 1: DB Migration + Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V24__create_notebooks.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookRepository.java`

- [ ] **Step 1: Create migration**

Create `lakeon-api/src/main/resources/db/migration/V24__create_notebooks.sql`:

```sql
CREATE TABLE notebooks (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    image       VARCHAR(32) NOT NULL DEFAULT 'python-data',
    dataset_ids TEXT,
    obs_path    VARCHAR(512),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notebooks_tenant ON notebooks(tenant_id);
```

- [ ] **Step 2: Create NotebookEntity**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookEntity.java`:

```java
package com.lakeon.notebook;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notebooks")
public class NotebookEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(length = 32, nullable = false)
    private String image;

    @Column(name = "dataset_ids", columnDefinition = "text")
    private String datasetIds;

    @Column(name = "obs_path", length = 512)
    private String obsPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = "nb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() { updatedAt = Instant.now(); }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getDatasetIds() { return datasetIds; }
    public void setDatasetIds(String datasetIds) { this.datasetIds = datasetIds; }
    public String getObsPath() { return obsPath; }
    public void setObsPath(String obsPath) { this.obsPath = obsPath; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: Create NotebookRepository**

Create `lakeon-api/src/main/java/com/lakeon/notebook/NotebookRepository.java`:

```java
package com.lakeon.notebook;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface NotebookRepository extends JpaRepository<NotebookEntity, String> {
    List<NotebookEntity> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
    Optional<NotebookEntity> findByIdAndTenantId(String id, String tenantId);
}
```

- [ ] **Step 4: Compile + commit**

```bash
cd lakeon-api && mvn compile -q
git add lakeon-api/src/main/resources/db/migration/V24__create_notebooks.sql \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookEntity.java \
        lakeon-api/src/main/java/com/lakeon/notebook/NotebookRepository.java
git commit -m "feat(notebook): add NotebookEntity + migration for persistence"
```

---

### Task 2: NotebookStorageService (OBS Operations)

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookStorageService.java`

- [ ] **Step 1: Create the OBS storage service**

This service handles all OBS operations for notebooks: read, write, version snapshots, list versions, delete.

Follow the existing S3 pattern from `DatasetService.java`:
```java
S3Client s3 = S3Client.builder()
    .endpointOverride(URI.create(props.getObs().getEndpoint()))
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
    .region(Region.of("cn-north-4"))
    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
    .build()
```

Methods:

- `String read(String obsPath)` — GetObject, return as UTF-8 string. Returns `null` if not found.
- `void write(String obsPath, String content)` — PutObject with `text/json; charset=utf-8`
- `void createVersionSnapshot(String obsPath, String content)` — write to `{dir}/versions/{ISO-timestamp}.json`, then cleanup old versions (keep max 50)
- `List<String> listVersions(String obsPath)` — ListObjectsV2 on `{dir}/versions/` prefix, return timestamps sorted desc
- `String readVersion(String obsPath, String timestamp)` — GetObject on `{dir}/versions/{timestamp}.json`
- `void deleteAll(String obsPath)` — ListObjectsV2 + DeleteObjects for the entire notebook prefix

All methods create their own S3Client (try-with-resources) — same pattern as existing codebase.

- [ ] **Step 2: Compile + commit**

```bash
cd lakeon-api && mvn compile -q
git add lakeon-api/src/main/java/com/lakeon/notebook/NotebookStorageService.java
git commit -m "feat(notebook): add NotebookStorageService for OBS read/write/versions"
```

---

### Task 3: NotebookCrudController (REST API)

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/notebook/NotebookCrudController.java`

- [ ] **Step 1: Create the CRUD controller**

`@RestController` at `/api/v1/datalake/notebooks`. Auth via `TenantEntity tenant = (TenantEntity) req.getAttribute("tenant")`.

Endpoints:

**POST `/`** — Create notebook
- Body: `{name, image}`
- Creates NotebookEntity, sets `obsPath = "notebooks/" + tenantId + "/" + id + "/notebook.json"`
- Writes empty notebook JSON to OBS: `{"cells":[],"image":"...","datasetIds":[]}`
- Returns entity as map

**GET `/`** — List notebooks
- `notebookRepo.findByTenantIdOrderByUpdatedAtDesc(tenantId)`
- Returns list of maps (no OBS read, just DB metadata)

**GET `/:id`** — Get notebook with content
- Find by id + tenantId (404 if not found)
- Read content from OBS via `storageService.read(obsPath)`
- Return DB metadata + content merged

**PUT `/:id`** — Save notebook
- Query param `version` (boolean, default false)
- Body: raw JSON string (the notebook content)
- Write to OBS via `storageService.write(obsPath, body)`
- If `version=true`: also `storageService.createVersionSnapshot(obsPath, body)`
- Update `updatedAt` in DB

**PATCH `/:id`** — Rename
- Body: `{name}`
- Update name in DB

**DELETE `/:id`** — Delete
- Delete all OBS files via `storageService.deleteAll(obsPathPrefix)`
- Delete DB entity

**GET `/:id/versions`** — List versions
- `storageService.listVersions(obsPath)` → return list of timestamps

**GET `/:id/versions/:ts`** — Get version content
- `storageService.readVersion(obsPath, ts)`

**POST `/:id/versions/:ts/restore`** — Restore version
- Read version content → write as current notebook.json → return content

Helper `notebookToMap(NotebookEntity e)`: id, name, image, dataset_ids, obs_path, created_at, updated_at

- [ ] **Step 2: Compile + commit**

```bash
cd lakeon-api && mvn compile -q
git add lakeon-api/src/main/java/com/lakeon/notebook/NotebookCrudController.java
git commit -m "feat(notebook): add NotebookCrudController with CRUD + version endpoints"
```

---

### Task 4: Frontend — API Client + List Page

**Files:**
- Create: `lakeon-console/src/api/notebooks.ts`
- Create: `lakeon-console/src/views/datalake/DatalakeNotebookList.vue`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: Create API client**

Create `lakeon-console/src/api/notebooks.ts`:

```typescript
import client from './client'

export const notebooksApi = {
  list: () => client.get('/datalake/notebooks'),
  create: (name: string, image: string) =>
    client.post('/datalake/notebooks', { name, image }),
  get: (id: string) => client.get(`/datalake/notebooks/${id}`),
  save: (id: string, content: string, version = false) =>
    client.put(`/datalake/notebooks/${id}${version ? '?version=true' : ''}`, content, {
      headers: { 'Content-Type': 'application/json' },
    }),
  rename: (id: string, name: string) =>
    client.patch(`/datalake/notebooks/${id}`, { name }),
  remove: (id: string) => client.delete(`/datalake/notebooks/${id}`),
  listVersions: (id: string) => client.get(`/datalake/notebooks/${id}/versions`),
  getVersion: (id: string, ts: string) =>
    client.get(`/datalake/notebooks/${id}/versions/${ts}`),
  restore: (id: string, ts: string) =>
    client.post(`/datalake/notebooks/${id}/versions/${ts}/restore`),
}
```

- [ ] **Step 2: Create list page**

Create `lakeon-console/src/views/datalake/DatalakeNotebookList.vue`:

List page showing all notebooks. Table with columns: Name, Image, Last Modified, Actions (Open / Rename / Delete).

"New Notebook" button opens a dialog with name input + image select dropdown. On submit: POST → redirect to `/datalake/notebook/:id`.

Click a row → navigate to `/datalake/notebook/:id`.

Rename: inline edit or prompt dialog → PATCH.

Delete: confirm dialog → DELETE.

Follow existing page patterns (page-container, page-header, data-table).

- [ ] **Step 3: Update routes**

In `lakeon-console/src/router/index.ts`, replace the notebook route:

```typescript
// Old:
{ path: 'datalake/notebook', name: 'DatalakeNotebook', ... },

// New:
{ path: 'datalake/notebook', name: 'DatalakeNotebookList', component: () => import('../views/datalake/DatalakeNotebookList.vue') },
{ path: 'datalake/notebook/:id', name: 'DatalakeNotebookEditor', component: () => import('../views/datalake/DatalakeNotebook.vue') },
```

- [ ] **Step 4: Type check + commit**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
git add lakeon-console/src/api/notebooks.ts \
        lakeon-console/src/views/datalake/DatalakeNotebookList.vue \
        lakeon-console/src/router/index.ts
git commit -m "feat(notebook): add notebook list page + API client + routes"
```

---

### Task 5: Refactor Editor Page — Load from API + Auto-save

**Files:**
- Modify: `lakeon-console/src/views/datalake/DatalakeNotebook.vue`

- [ ] **Step 1: Replace localStorage with API loading**

In `DatalakeNotebook.vue`:

- Get notebook ID from route: `const route = useRoute(); const notebookId = route.params.id as string`
- `onMounted`: call `notebooksApi.get(notebookId)` → populate cells, image, datasetIds from response
- Remove all localStorage read/write (`saveCells`, `loadCells` that use localStorage)
- Add a "Back" link to `/datalake/notebook` in the toolbar

- [ ] **Step 2: Add auto-save (debounce 3s)**

```typescript
import { watch } from 'vue'

const saveStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
let saveTimer: ReturnType<typeof setTimeout> | null = null

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => autoSave(), 3000)
}

async function autoSave() {
  if (!notebookId) return
  saveStatus.value = 'saving'
  try {
    const content = JSON.stringify({
      cells: cells.value.map(c => ({
        id: c.id, code: c.code, cellType: c.cellType,
        outputs: c.outputs, execCount: c.execCount, durationMs: c.durationMs,
      })),
      image: imageKey.value,
      datasetIds: selectedDatasetId.value ? [selectedDatasetId.value] : [],
    })
    await notebooksApi.save(notebookId, content, false)
    saveStatus.value = 'saved'
    setTimeout(() => { if (saveStatus.value === 'saved') saveStatus.value = 'idle' }, 2000)
  } catch {
    saveStatus.value = 'error'
  }
}
```

Call `scheduleSave()` wherever cells change (in `@update:code`, `addCell`, `deleteCell`, `toggleCellType`).

- [ ] **Step 3: Add Ctrl+S manual save with version**

```typescript
function handleKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    manualSave()
  }
}

async function manualSave() {
  if (!notebookId) return
  saveStatus.value = 'saving'
  try {
    const content = buildNotebookJson()
    await notebooksApi.save(notebookId, content, true) // version=true
    saveStatus.value = 'saved'
    setTimeout(() => { if (saveStatus.value === 'saved') saveStatus.value = 'idle' }, 2000)
  } catch {
    saveStatus.value = 'error'
  }
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onUnmounted(() => window.removeEventListener('keydown', handleKeydown))
```

- [ ] **Step 4: Add save status indicator in toolbar**

```html
<span v-if="saveStatus === 'saving'" class="nb-save-status saving">Saving...</span>
<span v-else-if="saveStatus === 'saved'" class="nb-save-status saved">Saved ✓</span>
<span v-else-if="saveStatus === 'error'" class="nb-save-status error">Save failed</span>
```

CSS:
```css
.nb-save-status { font-size: 11px; padding: 2px 8px; border-radius: 4px; }
.nb-save-status.saving { color: #a16207; background: #fef9c3; }
.nb-save-status.saved { color: #16a34a; background: #dcfce7; }
.nb-save-status.error { color: #dc2626; background: #fee2e2; }
```

- [ ] **Step 5: Type check + commit**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
git add lakeon-console/src/views/datalake/DatalakeNotebook.vue
git commit -m "feat(notebook): load from API, auto-save debounce 3s, Ctrl+S version save"
```

---

### Task 6: Version History Panel

**Files:**
- Modify: `lakeon-console/src/views/datalake/DatalakeNotebook.vue`

- [ ] **Step 1: Add version history side panel**

Add a "History" button in toolbar. When clicked, fetches version list and shows a panel (similar to reference panel but on the left or replacing ref panel):

```html
<button class="nb-btn" @click="toggleHistory" :disabled="!notebookId">History</button>
```

Version panel:
```html
<div v-if="showHistory" class="nb-history-panel">
  <div class="nb-ref-header">
    <h3>Version History</h3>
    <button class="nb-ref-close" @click="showHistory = false">&times;</button>
  </div>
  <div v-if="versions.length === 0" style="color:#9ca3af;font-size:12px;padding:8px;">No versions yet. Press Ctrl+S to save a version.</div>
  <div v-for="v in versions" :key="v" class="nb-version-item" @click="previewVersion(v)">
    <span class="nb-version-time">{{ formatVersionTime(v) }}</span>
    <button class="nb-cell-btn" @click.stop="restoreVersion(v)">Restore</button>
  </div>
</div>
```

- [ ] **Step 2: Implement version functions**

```typescript
const showHistory = ref(false)
const versions = ref<string[]>([])

async function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value) {
    const { data } = await notebooksApi.listVersions(notebookId)
    versions.value = data
  }
}

function formatVersionTime(ts: string): string {
  return new Date(ts.replace(/-/g, ':')).toLocaleString('zh-CN')
}

async function restoreVersion(ts: string) {
  if (!confirm('Restore this version? Current content will be replaced.')) return
  const { data } = await notebooksApi.restore(notebookId, ts)
  // Reload cells from restored content
  loadNotebookContent(data)
}
```

- [ ] **Step 3: CSS for history panel**

```css
.nb-history-panel { width: 240px; position: sticky; top: 16px; flex-shrink: 0; padding: 14px; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; max-height: 80vh; overflow-y: auto; }
.nb-version-item { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px solid #f1f5f9; cursor: pointer; }
.nb-version-item:hover { background: #f1f5f9; }
.nb-version-time { color: #374151; }
```

- [ ] **Step 4: Type check + commit**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
git add lakeon-console/src/views/datalake/DatalakeNotebook.vue
git commit -m "feat(notebook): add version history panel with restore"
```

---

### Task 7: Build + Deploy + Verify

- [ ] **Step 1: Build**
```bash
cd lakeon-api && mvn compile -q
cd ../lakeon-console && npm run build
```

- [ ] **Step 2: Deploy API**
```bash
IMAGE_TAG=0.9.130 ./deploy/cce/build-and-push-api.sh
./deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 3: Push for Railway**
```bash
git push origin main
```

- [ ] **Step 4: Manual verification**
- [ ] Navigate to `/datalake/notebook` → see empty notebook list
- [ ] Click "New Notebook" → dialog → fill name + select image → creates and redirects to editor
- [ ] Type code in cell → after 3s toolbar shows "Saved ✓"
- [ ] Press Ctrl+S → creates version snapshot
- [ ] Navigate back to list → see notebook with updated timestamp
- [ ] Click "History" → see version entry → click "Restore" → content restored
- [ ] Rename notebook from list page
- [ ] Delete notebook from list page
