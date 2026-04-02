# SRE 数据湖运维 + 全局租户名称解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add datalake (jobs + datasets) admin pages to the SRE console, and show tenant names instead of raw IDs across all admin pages.

**Architecture:** Three-layer change: (1) Backend adds admin endpoints for datalake jobs/datasets with cross-tenant access; (2) Frontend adds a global tenant name store loaded once in AdminLayout; (3) All existing pages (databases, operations, audit, knowledge, memory) plus new datalake pages consume the store to display "租户名称" instead of raw `tenant_id`.

**Tech Stack:** Spring Boot (Java 17), Vue 3 + TypeScript + Pinia, existing admin API patterns

---

## File Structure

### Backend (lakeon-api)
- **Modify:** `AdminController.java` — add datalake/dataset admin endpoints + admin log streaming
- **Modify:** `DatalakeJobRepository.java` — add cross-tenant query methods
- **Modify:** `DatasetRepository.java` — add cross-tenant query methods

### Frontend (lakeon-admin)
- **Create:** `src/stores/tenants.ts` — global tenant name store (Pinia)
- **Modify:** `src/layouts/AdminLayout.vue` — load tenant store on mount
- **Create:** `src/views/datalake/DatalakeAdmin.vue` — datalake jobs + datasets page with tabs
- **Modify:** `src/api/admin.ts` — add datalake/dataset API methods
- **Modify:** `src/router/index.ts` — add datalake route
- **Modify:** `src/layouts/AdminLayout.vue` — add sidebar nav entry for "数据湖"
- **Modify:** `src/views/databases/DatabaseList.vue` — use tenant name store
- **Modify:** `src/views/operations/OperationList.vue` — use tenant name store
- **Modify:** `src/views/AuditLogs.vue` — use tenant name store
- **Modify:** `src/views/knowledge/KnowledgeList.vue` — use tenant name store
- **Modify:** `src/views/memory/MemoryList.vue` — use tenant name store

---

### Task 1: Backend — Datalake Admin Endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/dataset/DatasetRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`

- [ ] **Step 1: Add repository query methods**

Add cross-tenant queries to `DatalakeJobRepository.java`:

```java
// Add after existing methods:
List<DatalakeJobEntity> findAllByOrderByCreatedAtDesc();
List<DatalakeJobEntity> findByStatusOrderByCreatedAtDesc(DatalakeJobStatus status);
```

Add cross-tenant queries to `DatasetRepository.java`:

```java
// Add after existing methods:
List<DatasetEntity> findAllByOrderByCreatedAtDesc();
List<DatasetEntity> findByStatusOrderByCreatedAtDesc(DatasetStatus status);
```

- [ ] **Step 2: Add admin endpoints to AdminController**

Add these imports to `AdminController.java`:

```java
import com.lakeon.datalake.*;
import com.lakeon.dataset.*;
```

Add these fields and constructor params:

```java
private final DatalakeJobRepository datalakeJobRepository;
private final DatalakeLogService datalakeLogService;
private final DatasetRepository datasetRepository;
```

(Add to constructor signature and body.)

Add endpoints before the `// ── Helpers` section:

```java
// ── Datalake Admin ──────────────────────────────────────

@GetMapping("/datalake/stats")
public Map<String, Object> getDatalakeStats() {
    List<DatalakeJobEntity> all = datalakeJobRepository.findAll();
    Map<String, Long> byStatus = all.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                    j -> j.getStatus().name(), java.util.stream.Collectors.counting()));
    Map<String, Long> byType = all.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                    j -> j.getType().name(), java.util.stream.Collectors.counting()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("job_count", all.size());
    result.put("by_status", byStatus);
    result.put("by_type", byType);
    result.put("running_count", byStatus.getOrDefault("RUNNING", 0L) + byStatus.getOrDefault("STARTING", 0L));
    result.put("failed_count", byStatus.getOrDefault("FAILED", 0L));
    return result;
}

@GetMapping("/datalake/jobs")
public List<Map<String, Object>> listAllDatalakeJobs(
        @RequestParam(required = false, name = "tenant_id") String tenantId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String type) {
    List<DatalakeJobEntity> jobs;
    if (tenantId != null) {
        jobs = datalakeJobRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    } else if (status != null) {
        jobs = datalakeJobRepository.findByStatusOrderByCreatedAtDesc(
                DatalakeJobStatus.valueOf(status.toUpperCase()));
    } else {
        jobs = datalakeJobRepository.findAllByOrderByCreatedAtDesc();
    }
    if (type != null) {
        DatalakeJobType t = DatalakeJobType.valueOf(type.toUpperCase());
        jobs = jobs.stream().filter(j -> j.getType() == t).toList();
    }
    return jobs.stream().map(this::datalakeJobToMap).toList();
}

@GetMapping("/datalake/jobs/{id}")
public Map<String, Object> getDatalakeJobAdmin(@PathVariable String id) {
    DatalakeJobEntity job = datalakeJobRepository.findById(id)
            .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Job not found: " + id));
    Map<String, Object> result = datalakeJobToMap(job);
    // Include full spec JSON for SRE debugging
    result.put("spec", job.getSpec());
    return result;
}

@DeleteMapping("/datalake/jobs/{id}")
public Map<String, Object> cancelDatalakeJobAdmin(@PathVariable String id) {
    DatalakeJobEntity job = datalakeJobRepository.findById(id)
            .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Job not found: " + id));
    if (job.getStatus() == DatalakeJobStatus.SUCCEEDED
            || job.getStatus() == DatalakeJobStatus.FAILED
            || job.getStatus() == DatalakeJobStatus.CANCELLED) {
        return Map.of("id", id, "status", job.getStatus().name(), "message", "Job already in terminal state");
    }
    job.setStatus(DatalakeJobStatus.CANCELLED);
    job.setFinishedAt(java.time.Instant.now());
    datalakeJobRepository.save(job);
    return Map.of("id", id, "status", "CANCELLED");
}

@GetMapping(value = "/datalake/jobs/{id}/logs", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
public org.springframework.web.servlet.mvc.method.annotation.SseEmitter streamDatalakeJobLogsAdmin(@PathVariable String id) {
    DatalakeJobEntity job = datalakeJobRepository.findById(id)
            .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Job not found: " + id));
    // Admin bypass: pass job's own tenantId to skip tenant check
    return datalakeLogService.streamLogs(job.getTenantId(), id);
}

// ── Dataset Admin ──────────────────────────────────────

@GetMapping("/datalake/datasets")
public List<Map<String, Object>> listAllDatasets(
        @RequestParam(required = false, name = "tenant_id") String tenantId,
        @RequestParam(required = false) String status) {
    List<DatasetEntity> datasets;
    if (tenantId != null) {
        datasets = datasetRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    } else if (status != null) {
        datasets = datasetRepository.findByStatusOrderByCreatedAtDesc(
                DatasetStatus.valueOf(status.toUpperCase()));
    } else {
        datasets = datasetRepository.findAllByOrderByCreatedAtDesc();
    }
    return datasets.stream().map(this::datasetToMap).toList();
}

@GetMapping("/datalake/datasets/{id}")
public Map<String, Object> getDatasetAdmin(@PathVariable String id) {
    DatasetEntity ds = datasetRepository.findById(id)
            .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Dataset not found: " + id));
    Map<String, Object> result = datasetToMap(ds);
    result.put("schema_json", ds.getSchemaJson());
    return result;
}

@DeleteMapping("/datalake/datasets/{id}")
public Map<String, Object> deleteDatasetAdmin(@PathVariable String id) {
    DatasetEntity ds = datasetRepository.findById(id)
            .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Dataset not found: " + id));
    datasetRepository.delete(ds);
    return Map.of("deleted", id);
}
```

Add helper methods in the Helpers section:

```java
private Map<String, Object> datalakeJobToMap(DatalakeJobEntity j) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", j.getId());
    m.put("tenant_id", j.getTenantId());
    m.put("name", j.getName());
    m.put("type", j.getType().name());
    m.put("status", j.getStatus().name());
    m.put("base_image", j.getBaseImage());
    m.put("cci_namespace", j.getCciNamespace());
    m.put("k8s_job_name", j.getK8sJobName());
    m.put("ray_job_name", j.getRayJobName());
    m.put("log_obs_path", j.getLogObsPath());
    m.put("core_hours", j.getCoreHours());
    m.put("gpu_hours", j.getGpuHours());
    m.put("error_message", j.getErrorMessage());
    m.put("started_at", j.getStartedAt() != null ? j.getStartedAt().toString() : null);
    m.put("finished_at", j.getFinishedAt() != null ? j.getFinishedAt().toString() : null);
    m.put("created_at", j.getCreatedAt() != null ? j.getCreatedAt().toString() : null);
    return m;
}

private Map<String, Object> datasetToMap(DatasetEntity ds) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", ds.getId());
    m.put("tenant_id", ds.getTenantId());
    m.put("name", ds.getName());
    m.put("description", ds.getDescription());
    m.put("source_type", ds.getSourceType() != null ? ds.getSourceType().name() : null);
    m.put("database_id", ds.getDatabaseId());
    m.put("obs_path", ds.getObsPath());
    m.put("row_count", ds.getRowCount());
    m.put("file_size", ds.getFileSize());
    m.put("status", ds.getStatus() != null ? ds.getStatus().name() : null);
    m.put("job_id", ds.getJobId());
    m.put("error", ds.getError());
    m.put("created_at", ds.getCreatedAt() != null ? ds.getCreatedAt().toString() : null);
    return m;
}
```

- [ ] **Step 3: Verify backend compiles**

Run: `cd lakeon-api && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit backend changes**

```bash
git add lakeon-api/src/main/java/com/lakeon/datalake/DatalakeJobRepository.java \
        lakeon-api/src/main/java/com/lakeon/dataset/DatasetRepository.java \
        lakeon-api/src/main/java/com/lakeon/controller/AdminController.java
git commit -m "feat(admin): add datalake jobs + datasets admin endpoints"
```

---

### Task 2: Frontend — Global Tenant Name Store

**Files:**
- Create: `lakeon-admin/src/stores/tenants.ts`
- Modify: `lakeon-admin/src/layouts/AdminLayout.vue`

- [ ] **Step 1: Create the tenant name store**

Create `lakeon-admin/src/stores/tenants.ts`:

```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminApi } from '../api/admin'

export const useTenantStore = defineStore('tenants', () => {
  const tenantMap = ref<Record<string, string>>({})
  const loaded = ref(false)

  async function load() {
    if (loaded.value) return
    try {
      const res = await adminApi.listTenants()
      const list = res.data as Array<{ id: string; name: string }>
      const map: Record<string, string> = {}
      for (const t of list) {
        map[t.id] = t.name
      }
      tenantMap.value = map
      loaded.value = true
    } catch (e) {
      console.error('Failed to load tenants for name resolution', e)
    }
  }

  function name(tenantId: string): string {
    return tenantMap.value[tenantId] || tenantId
  }

  function refresh() {
    loaded.value = false
    return load()
  }

  return { tenantMap, loaded, load, name, refresh }
})
```

- [ ] **Step 2: Load tenant store in AdminLayout on mount**

In `lakeon-admin/src/layouts/AdminLayout.vue`, add to `<script setup>`:

```typescript
import { useTenantStore } from '../stores/tenants'
import { onMounted } from 'vue'

const tenantStore = useTenantStore()
onMounted(() => tenantStore.load())
```

(Keep the existing `ref`, `useRouter`, `useAdminAuthStore` imports — merge the `onMounted` call or add it alongside.)

- [ ] **Step 3: Commit**

```bash
git add lakeon-admin/src/stores/tenants.ts lakeon-admin/src/layouts/AdminLayout.vue
git commit -m "feat(admin): global tenant name store, loaded once in AdminLayout"
```

---

### Task 3: Frontend — Add Tenant Names to Existing Pages

**Files:**
- Modify: `lakeon-admin/src/views/databases/DatabaseList.vue`
- Modify: `lakeon-admin/src/views/operations/OperationList.vue`
- Modify: `lakeon-admin/src/views/AuditLogs.vue`
- Modify: `lakeon-admin/src/views/knowledge/KnowledgeList.vue`
- Modify: `lakeon-admin/src/views/memory/MemoryList.vue`

For each page, the change is the same pattern:

1. Import the store: `import { useTenantStore } from '../../stores/tenants'` (or `'../stores/tenants'` for AuditLogs)
2. Init: `const tenantStore = useTenantStore()`
3. Replace the `<td>` that shows raw `tenant_id` with a cell that shows tenant name + small ID underneath

- [ ] **Step 1: Update DatabaseList.vue**

In `<script setup>`, add:
```typescript
import { useTenantStore } from '../../stores/tenants'
const tenantStore = useTenantStore()
```

Change table header from `<th>租户ID</th>` to `<th>租户</th>`.

Replace the tenant_id cell (line 58):
```html
<!-- Old -->
<td style="font-family: monospace; font-size: 13px;">{{ db.tenant_id }}</td>
<!-- New -->
<td>{{ tenantStore.name(db.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ db.tenant_id }}</span></td>
```

- [ ] **Step 2: Update OperationList.vue**

In `<script setup>`, add:
```typescript
import { useTenantStore } from '../../stores/tenants'
const tenantStore = useTenantStore()
```

Change header from `<th>租户ID</th>` to `<th>租户</th>`.

Replace tenant_id cell (line 51):
```html
<!-- Old -->
<td style="font-family: monospace; font-size: 13px;">{{ op.tenant_id }}</td>
<!-- New -->
<td>{{ tenantStore.name(op.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ op.tenant_id }}</span></td>
```

Also update the filter placeholder from `"按租户 ID 筛选..."` to `"按租户 ID 或名称筛选..."`.

Update the CSV export header (line 187) to include tenant name:
```typescript
const header = '数据库,租户,租户ID,操作类型,唤醒类型,状态,耗时(ms),开始时间,错误信息'
```
And add `tenantStore.name(op.tenant_id)` as the second column in the CSV row.

- [ ] **Step 3: Update AuditLogs.vue**

In `<script setup>`, add:
```typescript
import { useTenantStore } from '../stores/tenants'
const tenantStore = useTenantStore()
```

Change header from `<th>租户ID</th>` to `<th>租户</th>`.

Replace tenant_id cell (line 49):
```html
<!-- Old -->
<td style="font-family: monospace; font-size: 13px;">{{ log.tenant_id }}</td>
<!-- New -->
<td>{{ tenantStore.name(log.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ log.tenant_id }}</span></td>
```

- [ ] **Step 4: Update KnowledgeList.vue**

In `<script setup>`, add:
```typescript
import { useTenantStore } from '../../stores/tenants'
const tenantStore = useTenantStore()
```

Change header from `<th>租户ID</th>` to `<th>租户</th>` (both in KB list table and tasks table).

Replace KB tenant_id cell (line 79):
```html
<!-- Old -->
<td style="font-family: monospace; font-size: 13px;">{{ kb.tenant_id }}</td>
<!-- New -->
<td>{{ tenantStore.name(kb.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ kb.tenant_id }}</span></td>
```

Replace tasks tenant_id cell (line 174):
```html
<!-- Old -->
<td style="font-family: monospace; font-size: 12px;">{{ task.tenant_id }}</td>
<!-- New -->
<td>{{ tenantStore.name(task.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ task.tenant_id }}</span></td>
```

- [ ] **Step 5: Update MemoryList.vue**

In `<script setup>`, add:
```typescript
import { useTenantStore } from '../../stores/tenants'
const tenantStore = useTenantStore()
```

Change header from `<th>租户</th>` to keep it (already says 租户).

Replace tenant cell (line 63):
```html
<!-- Old -->
<td style="font-family: monospace; font-size: 12px;">{{ base.tenant_id }}</td>
<!-- New -->
<td>{{ tenantStore.name(base.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ base.tenant_id }}</span></td>
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-admin/src/views/databases/DatabaseList.vue \
        lakeon-admin/src/views/operations/OperationList.vue \
        lakeon-admin/src/views/AuditLogs.vue \
        lakeon-admin/src/views/knowledge/KnowledgeList.vue \
        lakeon-admin/src/views/memory/MemoryList.vue
git commit -m "feat(admin): show tenant names across all admin pages"
```

---

### Task 4: Frontend — Datalake Admin API Methods

**Files:**
- Modify: `lakeon-admin/src/api/admin.ts`

- [ ] **Step 1: Add datalake/dataset API methods**

Add to `adminApi` object in `admin.ts`, after the Memory Admin section:

```typescript
  // Datalake Admin
  datalakeStats: () => client.get('/datalake/stats'),
  listDatalakeJobs: (params?: { tenant_id?: string; status?: string; type?: string }) =>
    client.get('/datalake/jobs', { params }),
  getDatalakeJob: (id: string) => client.get(`/datalake/jobs/${id}`),
  cancelDatalakeJob: (id: string) => client.delete(`/datalake/jobs/${id}`),

  // Dataset Admin
  listDatasets: (params?: { tenant_id?: string; status?: string }) =>
    client.get('/datalake/datasets', { params }),
  getDataset: (id: string) => client.get(`/datalake/datasets/${id}`),
  deleteDataset: (id: string) => client.delete(`/datalake/datasets/${id}`),
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-admin/src/api/admin.ts
git commit -m "feat(admin): add datalake/dataset admin API methods"
```

---

### Task 5: Frontend — Datalake Admin Page

**Files:**
- Create: `lakeon-admin/src/views/datalake/DatalakeAdmin.vue`
- Modify: `lakeon-admin/src/router/index.ts`
- Modify: `lakeon-admin/src/layouts/AdminLayout.vue`

- [ ] **Step 1: Create the DatalakeAdmin page**

Create `lakeon-admin/src/views/datalake/DatalakeAdmin.vue`:

```vue
<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">数据湖</h1>
    </div>

    <!-- Stats -->
    <div class="stats-row" v-if="stats">
      <div class="stat-card">
        <div class="stat-value">{{ stats.job_count }}</div>
        <div class="stat-label">作业总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #1890ff;">{{ stats.running_count }}</div>
        <div class="stat-label">运行中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #e53e3e;">{{ stats.failed_count }}</div>
        <div class="stat-label">失败</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #52c41a;">{{ datasetCount }}</div>
        <div class="stat-label">数据集</div>
      </div>
    </div>

    <!-- Tabs -->
    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'jobs' }" @click="activeTab = 'jobs'">作业列表</div>
      <div class="tab-item" :class="{ active: activeTab === 'datasets' }" @click="activeTab = 'datasets'; loadDatasets()">数据集管理</div>
    </div>

    <!-- Jobs Tab -->
    <template v-if="activeTab === 'jobs'">
      <div class="action-toolbar">
        <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="tenantFilter" style="width: 220px;" @keyup.enter="loadJobs" />
        <select class="form-select" v-model="typeFilter" style="width: 140px;">
          <option value="">全部类型</option>
          <option value="PYTHON">Python</option>
          <option value="RAY">Ray</option>
          <option value="FINETUNE">微调</option>
        </select>
        <select class="form-select" v-model="statusFilter" style="width: 140px;">
          <option value="">全部状态</option>
          <option value="PENDING">等待中</option>
          <option value="STARTING">启动中</option>
          <option value="RUNNING">运行中</option>
          <option value="SUCCEEDED">成功</option>
          <option value="FAILED">失败</option>
          <option value="CANCELLED">已取消</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadJobs">筛选</button>
      </div>

      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 30px;"></th>
              <th>作业名</th>
              <th>租户</th>
              <th>类型</th>
              <th>状态</th>
              <th>资源消耗</th>
              <th>开始时间</th>
              <th>耗时</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="job in jobs" :key="job.id">
              <tr>
                <td>
                  <button class="btn-icon-small" @click="toggleExpand(job.id)">
                    {{ expandedId === job.id ? '▼' : '▶' }}
                  </button>
                </td>
                <td>
                  <strong>{{ job.name }}</strong>
                  <br><span style="font-size: 11px; color: #999;">{{ job.id }}</span>
                </td>
                <td>
                  {{ tenantStore.name(job.tenant_id) }}
                  <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ job.tenant_id }}</span>
                </td>
                <td>
                  <span class="type-tag" :class="'type-' + job.type.toLowerCase()">{{ TYPE_LABELS[job.type] || job.type }}</span>
                </td>
                <td>
                  <span class="status-dot" :class="jobStatusClass(job.status)"></span>
                  {{ STATUS_LABELS[job.status] || job.status }}
                </td>
                <td style="font-size: 12px;">
                  <span v-if="job.core_hours">CPU: {{ Number(job.core_hours).toFixed(2) }}h</span>
                  <span v-if="job.gpu_hours"> GPU: {{ Number(job.gpu_hours).toFixed(2) }}h</span>
                  <span v-if="!job.core_hours && !job.gpu_hours">-</span>
                </td>
                <td>{{ formatDate(job.started_at) }}</td>
                <td>{{ formatDuration(job) }}</td>
                <td>
                  <button v-if="!isTerminal(job.status)" class="btn btn-text btn-small" style="color: #e53e3e;" @click="cancelJob(job)">取消</button>
                  <button class="btn btn-text btn-small" style="color: #1890ff;" @click="viewLogs(job)">日志</button>
                </td>
              </tr>
              <!-- Expanded detail row -->
              <tr v-if="expandedId === job.id" class="expanded-row">
                <td colspan="9" style="padding: 0;">
                  <div class="detail-panel">
                    <div v-if="detailLoading" style="color: #999;">加载中...</div>
                    <div v-else-if="detail">
                      <div class="detail-grid">
                        <div><strong>镜像:</strong> {{ detail.base_image || '-' }}</div>
                        <div><strong>命名空间:</strong> {{ detail.cci_namespace || '-' }}</div>
                        <div><strong>K8s Job:</strong> {{ detail.k8s_job_name || '-' }}</div>
                        <div><strong>Ray Job:</strong> {{ detail.ray_job_name || '-' }}</div>
                        <div><strong>日志路径:</strong> {{ detail.log_obs_path || '-' }}</div>
                        <div v-if="detail.error_message" style="color: #e53e3e; grid-column: 1 / -1;">
                          <strong>错误信息:</strong> {{ detail.error_message }}
                        </div>
                      </div>
                      <details v-if="detail.spec" style="margin-top: 8px;">
                        <summary style="cursor: pointer; font-size: 13px; color: #666;">查看完整 Spec</summary>
                        <pre style="background: #f5f5f5; padding: 8px; border-radius: 4px; font-size: 12px; max-height: 300px; overflow: auto;">{{ formatSpec(detail.spec) }}</pre>
                      </details>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="jobs.length === 0">
              <td colspan="9" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Datasets Tab -->
    <template v-if="activeTab === 'datasets'">
      <div class="action-toolbar">
        <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="dstenantFilter" style="width: 220px;" @keyup.enter="loadDatasets" />
        <select class="form-select" v-model="dsStatusFilter" style="width: 140px;">
          <option value="">全部状态</option>
          <option value="DRAFT">DRAFT</option>
          <option value="EXPORTING">EXPORTING</option>
          <option value="READY">READY</option>
          <option value="FAILED">FAILED</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadDatasets">筛选</button>
      </div>

      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>租户</th>
              <th>来源</th>
              <th>状态</th>
              <th>行数</th>
              <th>大小</th>
              <th>OBS 路径</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ds in datasets" :key="ds.id">
              <td>
                <strong>{{ ds.name }}</strong>
                <br><span style="font-size: 11px; color: #999;">{{ ds.id }}</span>
              </td>
              <td>
                {{ tenantStore.name(ds.tenant_id) }}
                <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ ds.tenant_id }}</span>
              </td>
              <td>
                <span class="source-tag">{{ SOURCE_LABELS[ds.source_type] || ds.source_type }}</span>
                <span v-if="ds.job_id" style="font-size: 11px; color: #999;"><br>{{ ds.job_id }}</span>
              </td>
              <td>
                <span class="status-dot" :class="dsStatusClass(ds.status)"></span>
                {{ ds.status }}
              </td>
              <td>{{ ds.row_count != null ? ds.row_count.toLocaleString() : '-' }}</td>
              <td>{{ formatSize(ds.file_size) }}</td>
              <td class="obs-path-cell">{{ ds.obs_path || '-' }}</td>
              <td>{{ formatDate(ds.created_at) }}</td>
              <td>
                <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="deleteDataset(ds)">删除</button>
              </td>
            </tr>
            <tr v-if="datasets.length === 0">
              <td colspan="9" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Log Viewer Dialog -->
    <div v-if="logDialogVisible" class="dialog-overlay" @click.self="logDialogVisible = false">
      <div class="dialog-box" style="width: 80vw; max-width: 900px; max-height: 80vh;">
        <div class="dialog-header">
          <h3>作业日志: {{ logJobName }}</h3>
          <button class="dialog-close" @click="logDialogVisible = false">&times;</button>
        </div>
        <div class="dialog-body">
          <pre class="log-content" ref="logContainer">{{ logContent }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'
import { useTenantStore } from '../../stores/tenants'

const tenantStore = useTenantStore()

interface DatalakeJob {
  id: string; tenant_id: string; name: string; type: string; status: string
  base_image?: string; cci_namespace?: string; k8s_job_name?: string; ray_job_name?: string
  log_obs_path?: string; core_hours?: number; gpu_hours?: number
  error_message?: string; started_at?: string; finished_at?: string; created_at: string
  spec?: string
}

interface Dataset {
  id: string; tenant_id: string; name: string; description?: string
  source_type: string; database_id?: string; obs_path?: string
  row_count?: number; file_size?: number; status: string
  job_id?: string; error?: string; created_at: string
}

const TYPE_LABELS: Record<string, string> = { PYTHON: 'Python', RAY: 'Ray', FINETUNE: '微调' }
const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中', STARTING: '启动中', RUNNING: '运行中',
  SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消',
}
const SOURCE_LABELS: Record<string, string> = { DB_EXPORT: '数据库导出', JOB_OUTPUT: '作业输出' }
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

const activeTab = ref('jobs')
const stats = ref<any>(null)
const datasetCount = ref(0)

// Jobs
const jobs = ref<DatalakeJob[]>([])
const tenantFilter = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const expandedId = ref<string | null>(null)
const detail = ref<DatalakeJob | null>(null)
const detailLoading = ref(false)

// Datasets
const datasets = ref<Dataset[]>([])
const dstenantFilter = ref('')
const dsStatusFilter = ref('')

// Log dialog
const logDialogVisible = ref(false)
const logJobName = ref('')
const logContent = ref('')
const logContainer = ref<HTMLElement | null>(null)

function isTerminal(status: string) { return TERMINAL.has(status) }

function jobStatusClass(status: string) {
  switch (status) {
    case 'SUCCEEDED': return 'dot-green'
    case 'FAILED': return 'dot-red'
    case 'RUNNING': return 'dot-blue'
    case 'STARTING': return 'dot-yellow'
    case 'CANCELLED': return 'dot-gray'
    default: return 'dot-gray'
  }
}

function dsStatusClass(status: string) {
  switch (status) {
    case 'READY': return 'dot-green'
    case 'EXPORTING': return 'dot-blue'
    case 'FAILED': return 'dot-red'
    default: return 'dot-gray'
  }
}

function formatDuration(job: DatalakeJob): string {
  if (!job.started_at) return '-'
  const start = new Date(job.started_at).getTime()
  const end = job.finished_at ? new Date(job.finished_at).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

function formatSize(bytes?: number): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function formatSpec(spec: string): string {
  try { return JSON.stringify(JSON.parse(spec), null, 2) } catch { return spec }
}

async function loadStats() {
  try {
    const { data } = await adminApi.datalakeStats()
    stats.value = data
  } catch { /* ignore */ }
}

async function loadJobs() {
  try {
    const params: Record<string, string> = {}
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    if (typeFilter.value) params.type = typeFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    const { data } = await adminApi.listDatalakeJobs(params)
    jobs.value = data
  } catch { /* ignore */ }
}

async function loadDatasets() {
  try {
    const params: Record<string, string> = {}
    if (dstenantFilter.value.trim()) params.tenant_id = dstenantFilter.value.trim()
    if (dsStatusFilter.value) params.status = dsStatusFilter.value
    const { data } = await adminApi.listDatasets(params)
    datasets.value = data
    datasetCount.value = data.length
  } catch { /* ignore */ }
}

async function toggleExpand(jobId: string) {
  if (expandedId.value === jobId) {
    expandedId.value = null
    return
  }
  expandedId.value = jobId
  detail.value = null
  detailLoading.value = true
  try {
    const { data } = await adminApi.getDatalakeJob(jobId)
    detail.value = data
  } catch { /* ignore */ }
  detailLoading.value = false
}

async function cancelJob(job: DatalakeJob) {
  if (!confirm(`确认取消作业 "${job.name}" (${job.id})？`)) return
  try {
    await adminApi.cancelDatalakeJob(job.id)
    await loadJobs()
    await loadStats()
  } catch (e: any) {
    alert(`取消失败: ${e.response?.data?.message || e.message}`)
  }
}

async function viewLogs(job: DatalakeJob) {
  logJobName.value = job.name
  logContent.value = '加载中...'
  logDialogVisible.value = true

  try {
    const baseUrl = (await import('../../api/client')).default.defaults.baseURL || ''
    const token = localStorage.getItem('lakeon_admin_token') || ''
    const url = `${baseUrl}/datalake/jobs/${job.id}/logs`

    const eventSource = new EventSource(url)
    logContent.value = ''

    eventSource.onmessage = (event) => {
      logContent.value += event.data + '\n'
      nextTick(() => {
        if (logContainer.value) {
          logContainer.value.scrollTop = logContainer.value.scrollHeight
        }
      })
    }

    eventSource.onerror = () => {
      eventSource.close()
      if (!logContent.value) {
        logContent.value = '[无法获取日志 — 作业可能尚未分配 Pod 或日志已过期]'
      }
    }

    // Close EventSource when dialog closes
    const unwatch = setInterval(() => {
      if (!logDialogVisible.value) {
        eventSource.close()
        clearInterval(unwatch)
      }
    }, 500)
  } catch {
    logContent.value = '[日志加载失败]'
  }
}

async function deleteDataset(ds: Dataset) {
  if (!confirm(`确认删除数据集 "${ds.name}" (${ds.id})？`)) return
  try {
    await adminApi.deleteDataset(ds.id)
    await loadDatasets()
  } catch (e: any) {
    alert(`删除失败: ${e.response?.data?.message || e.message}`)
  }
}

onMounted(() => {
  loadStats()
  loadJobs()
})
</script>

<style scoped>
.stats-row {
  display: flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap;
}
.stat-card {
  background: #fff; border: 1px solid #e5e5e5; border-radius: 6px;
  padding: 16px 24px; min-width: 120px; text-align: center;
}
.stat-value { font-size: 28px; font-weight: 600; color: #333; }
.stat-label { font-size: 13px; color: #999; margin-top: 4px; }

.tab-bar { display: flex; border-bottom: 1px solid #e5e5e5; margin-bottom: 16px; }
.tab-item {
  padding: 8px 16px; cursor: pointer; font-size: 14px; color: #666;
  border-bottom: 2px solid transparent;
}
.tab-item.active { color: #1890ff; border-bottom-color: #1890ff; }

.type-tag {
  display: inline-block; padding: 1px 8px; border-radius: 3px; font-size: 12px;
}
.type-python { background: #e6f7ff; color: #0073e6; }
.type-ray { background: #f6ffed; color: #389e0d; }
.type-finetune { background: #fff7e6; color: #d48806; }

.source-tag {
  display: inline-block; padding: 1px 6px; border-radius: 3px;
  font-size: 11px; background: #f0f0f0; color: #666;
}

.detail-panel { background: #f9fafb; padding: 12px 16px 12px 40px; }
.detail-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 6px 24px;
  font-size: 13px; color: #333;
}

.btn-icon-small {
  background: none; border: none; cursor: pointer; font-size: 11px;
  color: #999; padding: 2px 4px;
}
.expanded-row td { border-top: none !important; }

.obs-path-cell {
  max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-family: monospace; font-size: 12px;
}

.log-content {
  background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px;
  font-family: monospace; font-size: 12px; line-height: 1.5;
  max-height: 60vh; overflow-y: auto; white-space: pre-wrap; word-break: break-all;
}

.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 1000;
  display: flex; align-items: center; justify-content: center;
}
.dialog-box {
  background: #fff; border-radius: 8px; box-shadow: 0 4px 24px rgba(0,0,0,0.15);
  display: flex; flex-direction: column;
}
.dialog-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid #e5e5e5;
}
.dialog-header h3 { margin: 0; font-size: 16px; }
.dialog-close {
  background: none; border: none; font-size: 24px; cursor: pointer; color: #999;
}
.dialog-body { padding: 16px 20px; overflow-y: auto; }
</style>
```

- [ ] **Step 2: Add route**

In `lakeon-admin/src/router/index.ts`, add after the memory route:

```typescript
{ path: 'datalake', name: 'DatalakeAdmin', component: () => import('../views/datalake/DatalakeAdmin.vue') },
```

- [ ] **Step 3: Add sidebar nav entry**

In `lakeon-admin/src/layouts/AdminLayout.vue`, add after the 记忆库 nav item (line 81):

```html
<router-link to="/datalake" class="nav-item" active-class="active" @click="sidebarOpen = false">数据湖</router-link>
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-admin/src/views/datalake/DatalakeAdmin.vue \
        lakeon-admin/src/router/index.ts \
        lakeon-admin/src/layouts/AdminLayout.vue
git commit -m "feat(admin): datalake admin page — jobs, datasets, log viewer"
```

---

### Task 6: Verify End-to-End

- [ ] **Step 1: Build frontend**

```bash
cd lakeon-admin && npm run build
```

Expected: Build succeeds without TypeScript errors.

- [ ] **Step 2: Build backend**

```bash
cd lakeon-api && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Manual verification checklist**

Start the dev server (`cd lakeon-admin && npm run dev`) and check:
- [ ] Sidebar shows "数据湖" entry between "记忆库" and "审计日志"
- [ ] Dashboard / databases / operations / audit / knowledge / memory all show tenant names with small IDs underneath
- [ ] Datalake page shows stats cards, jobs tab with filtering
- [ ] Datasets tab shows datasets with tenant names
- [ ] Job expand shows detail panel with spec, error, k8s info
- [ ] Log viewer dialog opens for a job
- [ ] Cancel button works for non-terminal jobs
