# SRE Memory Admin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add memory base management to the SRE admin console — backend API endpoints + frontend page.

**Architecture:** Java AdminController gets 6 new endpoints following the knowledge base admin pattern. Vue admin frontend gets a new MemoryList page with stats cards, filtered table, expandable rows, and batch operations.

**Tech Stack:** Java 17 / Spring Boot, Vue 3 / TypeScript, Axios

**Spec:** `docs/superpowers/specs/2026-03-25-sre-memory-admin-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `lakeon-api/.../AdminController.java` | Modify | Add 6 memory admin endpoints |
| `lakeon-admin/src/api/admin.ts` | Modify | Add 6 API client methods |
| `lakeon-admin/src/views/memory/MemoryList.vue` | Create | Main admin page |
| `lakeon-admin/src/router/index.ts` | Modify | Add route |
| `lakeon-admin/src/layouts/AdminLayout.vue` | Modify | Add sidebar item |

---

## Task 1: Java Backend — Memory Admin Endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`

- [ ] **Step 1: Add MemoryBaseRepository injection**

Read AdminController.java. Add to imports:
```java
import com.lakeon.memory.MemoryBaseEntity;
import com.lakeon.memory.MemoryBaseRepository;
import com.lakeon.memory.MemoryService;
```

Add fields and constructor params (follow the KnowledgeBaseRepository pattern):
```java
    private final MemoryBaseRepository memoryBaseRepository;
    private final MemoryService memoryService;
```

Add to constructor parameters and assignments.

- [ ] **Step 2: Add memory stats endpoint**

After the knowledge endpoints (around line 555), add:

```java
    // ── Memory Admin ──────────────────────────────────────

    @GetMapping("/memory/stats")
    public Map<String, Object> getMemoryStats() {
        List<MemoryBaseEntity> all = memoryBaseRepository.findAll();
        Map<String, Long> byStatus = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(MemoryBaseEntity::getStatus, java.util.stream.Collectors.counting()));
        long totalMemories = all.stream().mapToInt(m -> m.getMemoryCount() != null ? m.getMemoryCount() : 0).sum();
        long totalTraits = all.stream().mapToInt(m -> m.getTraitCount() != null ? m.getTraitCount() : 0).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_count", all.size());
        result.put("total_memories", totalMemories);
        result.put("total_traits", totalTraits);
        result.put("by_status", byStatus);
        return result;
    }
```

- [ ] **Step 3: Add list, get, delete, batch delete endpoints**

```java
    @GetMapping("/memory/bases")
    public List<Map<String, Object>> listAllMemoryBases(
            @RequestParam(required = false, name = "tenant_id") String tenantId,
            @RequestParam(required = false) String status) {
        List<MemoryBaseEntity> bases;
        if (tenantId != null) {
            bases = memoryBaseRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        } else {
            bases = memoryBaseRepository.findAll();
            bases.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        }
        if (status != null) {
            bases = bases.stream().filter(b -> status.equalsIgnoreCase(b.getStatus())).toList();
        }
        return bases.stream().map(this::memBaseToMap).toList();
    }

    @GetMapping("/memory/bases/{id}")
    public Map<String, Object> getMemoryBaseAdmin(@PathVariable String id) {
        MemoryBaseEntity mem = memoryBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Memory base not found: " + id));
        Map<String, Object> result = memBaseToMap(mem);
        // Try to fetch recent memories via proxy
        try {
            Object memories = memoryService.proxyGet(mem.getTenantId(), id, "/memories",
                    Map.of("limit", "10"));
            result.put("recent_memories", memories);
        } catch (Exception e) {
            result.put("recent_memories", List.of());
            result.put("recent_memories_error", e.getMessage());
        }
        return result;
    }

    @DeleteMapping("/memory/bases/{id}")
    public Map<String, Object> deleteMemoryBaseAdmin(@PathVariable String id) {
        MemoryBaseEntity mem = memoryBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Memory base not found: " + id));
        memoryBaseRepository.delete(mem);
        return Map.of("deleted", id);
    }

    @DeleteMapping("/memory/bases/batch")
    public Map<String, Object> batchDeleteMemoryBases(@RequestBody Map<String, List<String>> body) {
        List<String> ids = body.getOrDefault("ids", List.of());
        int count = 0;
        for (String id : ids) {
            memoryBaseRepository.findById(id).ifPresent(mem -> memoryBaseRepository.delete(mem));
            count++;
        }
        return Map.of("deleted", count);
    }
```

- [ ] **Step 4: Add digest trigger endpoint**

```java
    @PostMapping("/memory/bases/{id}/digest")
    public Object triggerMemoryDigest(@PathVariable String id) {
        MemoryBaseEntity mem = memoryBaseRepository.findById(id)
                .orElseThrow(() -> new com.lakeon.service.exception.NotFoundException("Memory base not found: " + id));
        return memoryService.proxyPost(mem.getTenantId(), id, "/digest", null);
    }
```

- [ ] **Step 5: Add helper method**

```java
    private Map<String, Object> memBaseToMap(MemoryBaseEntity m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("tenant_id", m.getTenantId());
        map.put("name", m.getName());
        map.put("description", m.getDescription());
        map.put("type", m.getType() != null ? m.getType().name() : null);
        map.put("status", m.getStatus());
        map.put("one_llm_mode", Boolean.TRUE.equals(m.getOneLlmMode()));
        map.put("database_id", m.getDatabaseId());
        map.put("memory_count", m.getMemoryCount());
        map.put("trait_count", m.getTraitCount());
        map.put("embedding_model", m.getEmbeddingModel());
        map.put("error", m.getError());
        map.put("created_at", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
        return map;
    }
```

- [ ] **Step 6: Verify compilation**

Run: `cd lakeon-api && mvn compile -q 2>&1 | tail -3`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java
git commit -m "feat(admin): add 6 memory admin endpoints"
```

---

## Task 2: Frontend — API Client + Route + Sidebar

**Files:**
- Modify: `lakeon-admin/src/api/admin.ts`
- Modify: `lakeon-admin/src/router/index.ts`
- Modify: `lakeon-admin/src/layouts/AdminLayout.vue`

- [ ] **Step 1: Add API methods to admin.ts**

At the end of `adminApi` object, before the closing `}`, add:

```typescript
  // Memory Admin
  memoryStats: () => client.get('/memory/stats'),
  listMemoryBases: (params?: { tenant_id?: string; status?: string }) =>
    client.get('/memory/bases', { params }),
  getMemoryBase: (id: string) => client.get(`/memory/bases/${id}`),
  deleteMemoryBase: (id: string) => client.delete(`/memory/bases/${id}`),
  batchDeleteMemoryBases: (ids: string[]) =>
    client.delete('/memory/bases/batch', { data: { ids } }),
  triggerDigest: (id: string) => client.post(`/memory/bases/${id}/digest`),
```

- [ ] **Step 2: Add route**

In `lakeon-admin/src/router/index.ts`, after the knowledge route (line 28), add:

```typescript
      { path: 'memory', name: 'MemoryAdmin', component: () => import('../views/memory/MemoryList.vue') },
```

- [ ] **Step 3: Add sidebar item**

In `lakeon-admin/src/layouts/AdminLayout.vue`, find the "知识库" nav item in the "运维管理" group and add after it:

```html
<router-link to="/memory" class="nav-item" active-class="active">记忆库</router-link>
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-admin/src/api/admin.ts lakeon-admin/src/router/index.ts lakeon-admin/src/layouts/AdminLayout.vue
git commit -m "feat(admin): add memory admin API client, route, and sidebar"
```

---

## Task 3: Frontend — MemoryList Page

**Files:**
- Create: `lakeon-admin/src/views/memory/MemoryList.vue`

- [ ] **Step 1: Create the page**

Follow KnowledgeList.vue patterns. Full component:

```vue
<template>
  <div>
    <h2>记忆库管理</h2>

    <!-- Stats cards -->
    <div class="stats-row" style="display: flex; gap: 16px; margin-bottom: 24px;">
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #1890ff;">{{ stats.base_count ?? '-' }}</div>
        <div style="font-size: 12px; color: #999;">记忆库</div>
      </div>
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #52c41a;">{{ stats.total_memories ?? '-' }}</div>
        <div style="font-size: 12px; color: #999;">总记忆</div>
      </div>
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #722ed1;">{{ stats.total_traits ?? '-' }}</div>
        <div style="font-size: 12px; color: #999;">Traits</div>
      </div>
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #e53e3e;">{{ errorCount }}</div>
        <div style="font-size: 12px; color: #999;">异常</div>
      </div>
    </div>

    <!-- Filters -->
    <div style="display: flex; gap: 12px; margin-bottom: 16px; align-items: center;">
      <select v-model="statusFilter" @change="loadBases" style="padding: 6px 12px; border: 1px solid #d9d9d9; border-radius: 4px;">
        <option value="">全部状态</option>
        <option value="READY">READY</option>
        <option value="PROVISIONING">PROVISIONING</option>
        <option value="ERROR">ERROR</option>
      </select>
      <input v-model="tenantFilter" @keyup.enter="loadBases" placeholder="租户 ID..."
             style="padding: 6px 12px; border: 1px solid #d9d9d9; border-radius: 4px; width: 200px;" />
      <button class="btn" @click="loadBases">筛选</button>
      <div style="flex: 1;"></div>
      <button v-if="selectedIds.length > 0" class="btn btn-danger" @click="batchDelete">
        批量删除 ({{ selectedIds.length }})
      </button>
    </div>

    <!-- Table -->
    <table class="data-table" style="width: 100%;">
      <thead>
        <tr>
          <th style="width: 32px;"><input type="checkbox" @change="toggleAll" :checked="allSelected" /></th>
          <th>ID</th>
          <th>名称</th>
          <th>租户</th>
          <th>状态</th>
          <th>模式</th>
          <th>记忆数</th>
          <th>Traits</th>
          <th>创建时间</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="base in bases" :key="base.id">
          <tr @click="toggleExpand(base.id)" style="cursor: pointer;">
            <td @click.stop><input type="checkbox" :value="base.id" v-model="selectedIds" /></td>
            <td style="font-family: monospace; font-size: 12px;">{{ base.id }}</td>
            <td>{{ base.name }}</td>
            <td style="font-family: monospace; font-size: 12px;">{{ base.tenant_id }}</td>
            <td>
              <span class="status-dot" :class="statusColor(base.status)"></span>
              {{ base.status }}
            </td>
            <td>{{ base.one_llm_mode ? 'Agent-Extract' : '普通' }}</td>
            <td>{{ base.memory_count ?? 0 }}</td>
            <td>{{ base.trait_count ?? 0 }}</td>
            <td>{{ formatDate(base.created_at) }}</td>
          </tr>
          <!-- Expanded row -->
          <tr v-if="expandedId === base.id">
            <td colspan="9" style="padding: 16px; background: #fafafa;">
              <div v-if="detailLoading" style="color: #999;">加载中...</div>
              <div v-else-if="detail">
                <div style="display: flex; gap: 16px; margin-bottom: 12px; font-size: 13px; color: #666;">
                  <span>数据库: {{ detail.database_id || '无' }}</span>
                  <span>嵌入模型: {{ detail.embedding_model }}</span>
                  <span v-if="detail.error" style="color: #e53e3e;">错误: {{ detail.error }}</span>
                </div>

                <!-- Recent memories -->
                <div v-if="detail.recent_memories && detail.recent_memories.memories" style="margin-bottom: 12px;">
                  <h4 style="font-size: 13px; font-weight: 600; margin: 0 0 8px;">最近记忆 ({{ detail.recent_memories.memories.length }})</h4>
                  <div v-for="m in detail.recent_memories.memories" :key="m.id"
                       style="padding: 6px 8px; border-left: 3px solid #d9d9d9; margin-bottom: 4px; font-size: 12px;">
                    <span style="display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; margin-right: 6px;"
                          :style="`background: ${typeColor(m.memory_type)}20; color: ${typeColor(m.memory_type)};`">
                      {{ m.memory_type }}
                    </span>
                    {{ m.content?.substring(0, 100) }}{{ (m.content?.length ?? 0) > 100 ? '...' : '' }}
                    <span v-if="m.metadata?.source" style="color: #999; margin-left: 8px;">({{ m.metadata.source }})</span>
                  </div>
                  <div v-if="detail.recent_memories.memories.length === 0" style="color: #999; font-size: 12px;">暂无记忆</div>
                </div>

                <!-- Actions -->
                <div style="display: flex; gap: 8px;">
                  <button class="btn btn-sm" @click.stop="triggerDigest(base.id)">触发 Digest</button>
                  <button class="btn btn-sm btn-danger" @click.stop="deleteBase(base.id)">删除</button>
                </div>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>

    <p v-if="bases.length === 0" style="text-align: center; color: #999; padding: 32px;">暂无记忆库</p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { adminApi } from '@/api/admin'

const stats = ref<Record<string, any>>({})
const bases = ref<any[]>([])
const statusFilter = ref('')
const tenantFilter = ref('')
const selectedIds = ref<string[]>([])
const expandedId = ref<string | null>(null)
const detail = ref<any>(null)
const detailLoading = ref(false)

const errorCount = computed(() => stats.value.by_status?.ERROR ?? 0)
const allSelected = computed(() => bases.value.length > 0 && selectedIds.value.length === bases.value.length)

onMounted(() => {
  loadStats()
  loadBases()
})

async function loadStats() {
  try {
    const { data } = await adminApi.memoryStats()
    stats.value = data
  } catch (e) {
    console.error('Failed to load memory stats', e)
  }
}

async function loadBases() {
  try {
    const params: Record<string, string> = {}
    if (statusFilter.value) params.status = statusFilter.value
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    const { data } = await adminApi.listMemoryBases(params)
    bases.value = data
    selectedIds.value = []
  } catch (e) {
    console.error('Failed to load memory bases', e)
  }
}

async function toggleExpand(id: string) {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id
  detail.value = null
  detailLoading.value = true
  try {
    const { data } = await adminApi.getMemoryBase(id)
    detail.value = data
  } catch (e) {
    console.error('Failed to load detail', e)
  } finally {
    detailLoading.value = false
  }
}

function toggleAll(e: Event) {
  const checked = (e.target as HTMLInputElement).checked
  selectedIds.value = checked ? bases.value.map(b => b.id) : []
}

async function deleteBase(id: string) {
  if (!window.confirm(`确定删除记忆库 ${id}？`)) return
  try {
    await adminApi.deleteMemoryBase(id)
    bases.value = bases.value.filter(b => b.id !== id)
    if (expandedId.value === id) expandedId.value = null
    loadStats()
  } catch (e) {
    console.error('Delete failed', e)
  }
}

async function batchDelete() {
  if (!window.confirm(`确定删除 ${selectedIds.value.length} 个记忆库？`)) return
  try {
    await adminApi.batchDeleteMemoryBases(selectedIds.value)
    bases.value = bases.value.filter(b => !selectedIds.value.includes(b.id))
    selectedIds.value = []
    loadStats()
  } catch (e) {
    console.error('Batch delete failed', e)
  }
}

async function triggerDigest(id: string) {
  try {
    const { data } = await adminApi.triggerDigest(id)
    alert(`Digest 完成: ${JSON.stringify(data)}`)
  } catch (e: any) {
    alert(`Digest 失败: ${e.message}`)
  }
}

function statusColor(status: string): string {
  if (status === 'READY') return 'green'
  if (status === 'PROVISIONING') return 'yellow'
  return 'red'
}

const TYPE_COLORS: Record<string, string> = {
  fact: '#1890ff', episode: '#722ed1', procedural: '#d48806',
  decision: '#13c2c2', rejection: '#f5222d', convention: '#52c41a',
}
function typeColor(type: string): string { return TYPE_COLORS[type] || '#999' }

function formatDate(d: string | null): string {
  if (!d) return '-'
  return new Date(d).toLocaleString()
}
</script>
```

- [ ] **Step 2: Verify build**

Run: `cd lakeon-admin && npm run build 2>&1 | tail -5`
Expected: Build succeeds

- [ ] **Step 3: Commit**

```bash
git add lakeon-admin/src/views/memory/MemoryList.vue
git commit -m "feat(admin): add memory admin list page with stats, filters, and batch ops"
```

---

## Task 4: Build, Deploy, Verify

- [ ] **Step 1: Build API image**

```bash
IMAGE_TAG=0.9.58 ./deploy/cce/build-and-push-api.sh
# Update values.yaml tag
./deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 2: Build and push admin**

```bash
git push origin main  # Railway auto-deploys admin
```

- [ ] **Step 3: Verify**

- Login to SRE admin console
- Click "记忆库" in sidebar
- Verify stats cards show counts
- Verify table lists memory bases across tenants
- Expand a row → see recent memories with source tags
- Test "触发 Digest" button
- Test single and batch delete
