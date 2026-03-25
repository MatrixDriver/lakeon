# SRE Memory Admin — Design Spec

**Date:** 2026-03-25
**Status:** Approved
**Scope:** Add memory base management to SRE admin console (backend API + frontend page)

---

## Problem

The SRE admin console manages databases, knowledge bases, tenants, and infrastructure — but has no visibility into memory bases. Admins cannot list, inspect, or delete memory bases across tenants, nor trigger digest operations.

---

## 1. Backend Admin API

**File:** `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`
**Service:** `lakeon-api/src/main/java/com/lakeon/service/AdminService.java`

All endpoints require admin token auth (existing `AdminAuthFilter`).

### 1.1 Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/admin/memory/stats` | GET | Global stats: base_count, total_memories, total_traits, by_status |
| `/admin/memory/bases` | GET | List all memory bases, optional `?tenant_id=&status=` filters |
| `/admin/memory/bases/{id}` | GET | Single base detail with recent 10 memories preview |
| `/admin/memory/bases/{id}` | DELETE | Admin delete (deletes entity, does NOT delete backing database) |
| `/admin/memory/bases/batch` | DELETE | Batch delete by `{"ids": [...]}` |
| `/admin/memory/bases/{id}/digest` | POST | Trigger digest via proxy to Python service |

### 1.2 Stats Response

```json
{
  "base_count": 12,
  "total_memories": 342,
  "total_traits": 28,
  "by_status": {"READY": 10, "PROVISIONING": 1, "ERROR": 1}
}
```

Implementation: query `memory_bases` table with `COUNT` and `GROUP BY status`. For total_memories and total_traits, sum `memory_count` and `trait_count` cached fields from entities.

### 1.3 List Response

```json
[
  {
    "id": "mem_xxx",
    "tenant_id": "tn_abc",
    "name": "my-memory",
    "status": "READY",
    "one_llm_mode": true,
    "database_id": "db_yyy",
    "memory_count": 42,
    "trait_count": 5,
    "embedding_model": "BAAI/bge-m3",
    "error": null,
    "created_at": "2026-03-25T12:00:00Z"
  }
]
```

Reuse existing `MemoryBaseRepository.findAll()` with optional filtering via `@Query` or Specification.

### 1.4 Detail Response (with memory preview)

Same as list item plus:
```json
{
  ...base fields,
  "recent_memories": [
    {"id": 1, "content": "Chose asyncpg...", "memory_type": "decision", "metadata": {"source": "claude-code"}, "created_at": "..."}
  ]
}
```

The recent_memories are fetched by proxying `GET /memories?limit=10` to the Python service (reusing `MemoryService.proxyGet`).

### 1.5 Digest Trigger

Proxies `POST /digest` to the Python service for the specified memory base. Reuses `MemoryService.proxyPost`.

---

## 2. Frontend Page

**File:** `lakeon-admin/src/views/memory/MemoryList.vue`

### 2.1 Layout

```
┌─────────────────────────────────────────────────────┐
│  统计卡片                                            │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐       │
│  │  12    │ │  342   │ │  28    │ │   1    │       │
│  │ 记忆库  │ │ 总记忆  │ │ Traits │ │ 异常   │       │
│  └────────┘ └────────┘ └────────┘ └────────┘       │
├─────────────────────────────────────────────────────┤
│  筛选: [状态 ▼] [租户ID ____]     [□ 全选] [批量删除]  │
├─────────────────────────────────────────────────────┤
│  □ | ID          | 名称      | 租户       | 状态    │
│  □ | mem_xxx     | my-memory | tn_abc     | READY  │
│    ▸ 展开: memories=42 traits=5 mode=agent-extract  │
│      最近记忆: [decision] Chose asyncpg... (CC)     │
│      [触发Digest] [删除]                            │
│  □ | mem_yyy     | test      | tn_def     | PROV.  │
└─────────────────────────────────────────────────────┘
```

### 2.2 Features

- **Stats cards**: 4 cards from `/admin/memory/stats`
- **Filters**: status dropdown (ALL/READY/PROVISIONING/ERROR), tenant_id text input
- **Table**: checkbox + ID + name + tenant_id + status + one_llm_mode + memory_count + trait_count + created_at
- **Expandable rows**: click to expand, lazy-load detail via `/admin/memory/bases/{id}`, show recent memories with type badges and source
- **Batch delete**: multi-select checkboxes + batch delete button with confirmation
- **Row actions**: [触发 Digest] button, [删除] button with confirmation
- **Status dots**: green=READY, yellow=PROVISIONING, red=ERROR (same pattern as KnowledgeList)

### 2.3 Patterns to Follow

Follow `KnowledgeList.vue` patterns:
- Stats cards at top with colored numbers
- Filter bar with select + input
- `data-table` class for the table
- Expandable rows with lazy detail loading
- `window.confirm()` for delete confirmation
- Status dot component (`.status-dot.green/.yellow/.red`)

---

## 3. Router + Sidebar

### 3.1 Route

**File:** `lakeon-admin/src/router/index.ts`

Add after the knowledge route:
```typescript
{ path: 'memory', name: 'MemoryAdmin', component: () => import('../views/memory/MemoryList.vue') },
```

### 3.2 Sidebar

**File:** `lakeon-admin/src/layouts/AdminLayout.vue`

In the "运维管理" nav group, after "知识库":
```html
<router-link to="/memory" class="nav-item" active-class="active">记忆库</router-link>
```

---

## 4. API Client

**File:** `lakeon-admin/src/api/admin.ts`

Add methods:
```typescript
memoryStats: () => client.get('/memory/stats'),
listMemoryBases: (params?: { tenant_id?: string; status?: string }) =>
  client.get('/memory/bases', { params }),
getMemoryBase: (id: string) => client.get(`/memory/bases/${id}`),
deleteMemoryBase: (id: string) => client.delete(`/memory/bases/${id}`),
batchDeleteMemoryBases: (ids: string[]) =>
  client.delete('/memory/bases/batch', { data: { ids } }),
triggerDigest: (id: string) => client.post(`/memory/bases/${id}/digest`),
```

---

## 5. Files to Create/Modify

| File | Action |
|------|--------|
| `lakeon-api/.../AdminController.java` | Modify — add 6 memory endpoints |
| `lakeon-api/.../AdminService.java` | Modify — add memory query methods |
| `lakeon-admin/src/api/admin.ts` | Modify — add 6 API methods |
| `lakeon-admin/src/views/memory/MemoryList.vue` | Create — main admin page |
| `lakeon-admin/src/router/index.ts` | Modify — add route |
| `lakeon-admin/src/layouts/AdminLayout.vue` | Modify — add sidebar item |

---

## 6. Not in Scope

- Memory base creation from admin (admins manage, don't create)
- Memory editing (read-only for admin)
- Individual memory deletion from admin (only base-level delete)
- Memory base transfer between tenants
