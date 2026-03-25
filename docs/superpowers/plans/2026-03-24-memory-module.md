# DBay 记忆库 (Memory Module) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "记忆库" module to DBay — a memory management system for AI agents, supporting both built-in (自研) and third-party (mem0, hindsight) memory backends, with full console UI and Python SDK (`pip install dbay`).

**Architecture:** Java API layer (Spring Boot) handles CRUD for memory base instances and proxies content requests to a Python memory microservice (FastAPI). Console (Vue 3) adds a new rail icon "记忆库" between 知识库 and 数据湖. Python SDK provides `MemoryClient` for agent developers to integrate via `pip install dbay`.

**Tech Stack:** Java 17 / Spring Boot 3.3 (API proxy), Python 3.12 / FastAPI (memory microservice), Vue 3 / OpenTiny (console), Python / httpx (SDK), PostgreSQL 17 + pgvector + pg_search (storage in user's database)

---

## File Structure

### Java API Layer (`lakeon-api/src/main/java/com/lakeon/memory/`)

| File | Responsibility |
|------|---------------|
| `MemoryBaseEntity.java` | JPA entity for memory base instances |
| `MemoryBaseType.java` | Enum: BUILTIN, MEM0, HINDSIGHT, CUSTOM |
| `MemoryBaseRepository.java` | Spring Data JPA repository |
| `MemoryService.java` | Business logic: create/delete instances, proxy to Python |
| `MemoryController.java` | REST endpoints `/api/v1/memory/**` |
| `MemoryDbHelper.java` | Resolve memory base → compute pod → connstr (reuses KnowledgeDbHelper pattern) |

### Flyway Migration (`lakeon-api/src/main/resources/db/migration/`)

| File | Responsibility |
|------|---------------|
| `V19__create_memory_bases.sql` | DDL for `memory_bases` table in metadata DB |

### Python Memory Microservice (`memory/service/`)

| File | Responsibility |
|------|---------------|
| `main.py` | FastAPI app with all endpoints |
| `engine.py` | Core memory engine: extraction, hybrid search, trait reflection |
| `models.py` | Pydantic request/response models |
| `schema.py` | PG schema init for user databases (memories, traits, graph tables) |
| `providers.py` | Pluggable embedding provider (SiliconFlow default) |
| `Dockerfile` | Python 3.12 slim image, port 8001 |
| `requirements.txt` | Dependencies |

### Python SDK (`sdk/dbay/`)

| File | Responsibility |
|------|---------------|
| `sdk/dbay/__init__.py` | Package init, exports MemoryClient |
| `sdk/dbay/memory.py` | MemoryClient class — ingest/recall/digest/list |
| `sdk/dbay/client.py` | Base HTTP client (httpx), shared with future knowledge client |
| `sdk/pyproject.toml` | Package config for `pip install dbay` |

### Console (`lakeon-console/src/`)

| File | Responsibility |
|------|---------------|
| `api/memory.ts` | API client for memory endpoints |
| `views/memory/MemoryBases.vue` | List page: all memory base instances |
| `views/memory/MemoryBaseDetail.vue` | Instance detail: overview + memories/traits/graph tabs |
| `components/memory/TraitCard.vue` | Trait visualization component |
| `components/memory/KnowledgeGraph.vue` | D3 interactive graph browser |

### Deployment (`deploy/`)

| File | Responsibility |
|------|---------------|
| `deploy/helm/lakeon/templates/memory-service.yaml` | K8s Deployment + Service |

---

## Phase 1: Console + API Instance Management (P0)

### Task 1: Flyway Migration + Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V19__create_memory_bases.sql`
- Create: `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseType.java`
- Create: `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseRepository.java`

- [ ] **Step 1: Create Flyway migration**

```sql
-- V19__create_memory_bases.sql
CREATE TABLE memory_bases (
    id              VARCHAR(32)  PRIMARY KEY,
    tenant_id       VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    type            VARCHAR(16)  NOT NULL DEFAULT 'BUILTIN',
    database_id     VARCHAR(32),
    db_password     VARCHAR(256),
    status          VARCHAR(32)  NOT NULL DEFAULT 'CREATING',
    memory_count    INT          DEFAULT 0,
    trait_count     INT          DEFAULT 0,
    embedding_model VARCHAR(128),
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_memory_bases_tenant_id ON memory_bases(tenant_id);
CREATE INDEX idx_memory_bases_status ON memory_bases(status);
```

- [ ] **Step 2: Create MemoryBaseType enum**

```java
package com.lakeon.memory;

public enum MemoryBaseType {
    BUILTIN, MEM0, HINDSIGHT, CUSTOM
}
```

- [ ] **Step 3: Create MemoryBaseEntity**

Follow `KnowledgeBaseEntity` pattern exactly (no BaseEntity superclass). All fields explicitly declared:

```java
package com.lakeon.memory;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "memory_bases", indexes = {
    @Index(name = "idx_memory_bases_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_memory_bases_status", columnList = "status")
})
public class MemoryBaseEntity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private MemoryBaseType type = MemoryBaseType.BUILTIN;

    @Column(name = "database_id", length = 32)
    private String databaseId;

    @Column(name = "db_password", length = 256)
    private String dbPassword;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "CREATING";

    @Column(name = "memory_count")
    private int memoryCount = 0;

    @Column(name = "trait_count")
    private int traitCount = 0;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "mem_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Generate all getters and setters (same pattern as KnowledgeBaseEntity)
}
```

- [ ] **Step 4: Create Repository**

```java
package com.lakeon.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MemoryBaseRepository extends JpaRepository<MemoryBaseEntity, String> {
    List<MemoryBaseEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<MemoryBaseEntity> findByIdAndTenantId(String id, String tenantId);
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/jacky/code/lakeon && ./gradlew :lakeon-api:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/ lakeon-api/src/main/resources/db/migration/V19__create_memory_bases.sql
git commit -m "feat(memory): add MemoryBaseEntity, repository, and Flyway migration"
```

---

### Task 2: Memory Controller + Service — Instance CRUD

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java`
- Create: `lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java`
- Reference: `lakeon-api/src/main/java/com/lakeon/knowledge/KnowledgeController.java` (pattern)
- Reference: `lakeon-api/src/main/java/com/lakeon/service/exception/NotFoundException.java`

- [ ] **Step 1: Create MemoryService**

Note: Service receives `tenantId` as explicit parameter (no TenantContext — the codebase uses `getTenant(req)` in controllers).

```java
package com.lakeon.memory;

import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class MemoryService {

    private final MemoryBaseRepository repository;

    public MemoryService(MemoryBaseRepository repository) {
        this.repository = repository;
    }

    public List<MemoryBaseEntity> listBases(String tenantId) {
        return repository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    public MemoryBaseEntity getBase(String tenantId, String id) {
        return repository.findByIdAndTenantId(id, tenantId)
            .orElseThrow(() -> new NotFoundException("Memory base not found: " + id));
    }

    public MemoryBaseEntity createBase(String tenantId, String name, String description,
                                        MemoryBaseType type, String embeddingModel) {
        var entity = new MemoryBaseEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setType(type);
        entity.setEmbeddingModel(embeddingModel != null ? embeddingModel : "BAAI/bge-m3");
        // For BUILTIN: will init PG schema in Task 6 after microservice is ready
        // For MEM0/HINDSIGHT/CUSTOM: just record the entry, user manages schema themselves
        entity.setStatus("READY");
        entity = repository.save(entity);
        return entity;
    }

    public void deleteBase(String tenantId, String id) {
        var entity = getBase(tenantId, id);
        repository.delete(entity);
    }
}
```

- [ ] **Step 2: Create MemoryController**

Note: Uses `getTenant(req)` pattern and `toMemResponse()` for snake_case JSON output — matching `KnowledgeController` exactly.

```java
package com.lakeon.memory;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/bases")
    public List<Map<String, Object>> listBases(HttpServletRequest req) {
        TenantEntity tenant = getTenant(req);
        return memoryService.listBases(tenant.getId()).stream()
                .map(this::toMemResponse)
                .toList();
    }

    @GetMapping("/bases/{id}")
    public Map<String, Object> getBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        return toMemResponse(memoryService.getBase(tenant.getId(), id));
    }

    @PostMapping("/bases")
    public Map<String, Object> createBase(HttpServletRequest req, @RequestBody Map<String, String> body) {
        TenantEntity tenant = getTenant(req);
        return toMemResponse(memoryService.createBase(
            tenant.getId(),
            body.get("name"),
            body.get("description"),
            MemoryBaseType.valueOf(body.getOrDefault("type", "BUILTIN")),
            body.get("embedding_model")
        ));
    }

    @DeleteMapping("/bases/{id}")
    public Map<String, Object> deleteBase(HttpServletRequest req, @PathVariable String id) {
        TenantEntity tenant = getTenant(req);
        memoryService.deleteBase(tenant.getId(), id);
        return Map.of("status", "deleted");
    }

    private TenantEntity getTenant(HttpServletRequest req) {
        return (TenantEntity) req.getAttribute("tenant");
    }

    private Map<String, Object> toMemResponse(MemoryBaseEntity mem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", mem.getId());
        map.put("tenant_id", mem.getTenantId());
        map.put("name", mem.getName());
        map.put("description", mem.getDescription());
        map.put("type", mem.getType().name());
        map.put("database_id", mem.getDatabaseId());
        map.put("status", mem.getStatus());
        map.put("memory_count", mem.getMemoryCount());
        map.put("trait_count", mem.getTraitCount());
        map.put("embedding_model", mem.getEmbeddingModel());
        map.put("error", mem.getError());
        map.put("created_at", mem.getCreatedAt() != null ? mem.getCreatedAt().toString() : null);
        map.put("updated_at", mem.getUpdatedAt() != null ? mem.getUpdatedAt().toString() : null);
        return map;
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/jacky/code/lakeon && ./gradlew :lakeon-api:compileJava`

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/
git commit -m "feat(memory): add MemoryController and MemoryService for instance CRUD"
```

---

### Task 3: Console — Add 记忆库 Rail Icon + Sidebar

**Files:**
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: Add memory rail icon**

In `ConsoleLayout.vue`, insert a new rail-icon div **after** the rail-icon with `title="知识库"` and **before** the rail-icon with `title="数据湖"`:

```vue
<div class="rail-icon" :class="{ active: activeRail === 'memory' }" @click="switchRail('memory')" title="记忆库">
  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
    <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
    <line x1="10" y1="21" x2="14" y2="21"/>
    <line x1="9" y1="17" x2="15" y2="17"/>
  </svg>
  <span class="rail-label">记忆库</span>
</div>
```

- [ ] **Step 2: Update TypeScript types and maps**

Change `RailKey` type (in `<script setup>` section):
```typescript
type RailKey = 'db' | 'kb' | 'memory' | 'datalake' | 'settings'
```

Add to `railTitles`:
```typescript
memory: '记忆库',
```

Add to `railDefaultRoutes`:
```typescript
memory: '/memory',
```

- [ ] **Step 3: Add sidebar nav for memory**

After the `<!-- 知识库菜单 -->` template block (the `<template v-if="activeRail === 'kb'">` block), insert:

```vue
<!-- 记忆库菜单 -->
<template v-if="activeRail === 'memory'">
  <div class="nav-group">
    <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">记忆库</router-link>
  </div>
</template>
```

- [ ] **Step 4: Update route watcher**

In the `watch(() => route.path, ...)` callback, add a new condition **before** the `path.startsWith('/datalake')` check:

```typescript
} else if (path.startsWith('/memory')) {
  activeRail.value = 'memory'
```

- [ ] **Step 5: Add routes in router/index.ts**

After the Knowledge routes (after the line `{ path: 'knowledge/search', ...}`), add:

```typescript
// Memory
{ path: 'memory', name: 'MemoryBases', component: () => import('../views/memory/MemoryBases.vue') },
{ path: 'memory/:memId', name: 'MemoryBaseDetail', component: () => import('../views/memory/MemoryBaseDetail.vue') },
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/src/layouts/ConsoleLayout.vue lakeon-console/src/router/index.ts
git commit -m "feat(memory): add 记忆库 rail icon, sidebar nav, and routes"
```

---

### Task 4: Console — Memory API Client + List Page + Detail Stub

**Files:**
- Create: `lakeon-console/src/api/memory.ts`
- Create: `lakeon-console/src/views/memory/MemoryBases.vue`
- Create: `lakeon-console/src/views/memory/MemoryBaseDetail.vue`

- [ ] **Step 1: Create API client**

```typescript
// src/api/memory.ts
import api from './client'

export interface MemoryBase {
  id: string
  tenant_id: string
  name: string
  description: string | null
  type: 'BUILTIN' | 'MEM0' | 'HINDSIGHT' | 'CUSTOM'
  status: string
  database_id: string | null
  memory_count: number
  trait_count: number
  embedding_model: string | null
  error: string | null
  created_at: string
  updated_at: string
}

export function listMemoryBases() {
  return api.get<MemoryBase[]>('/memory/bases')
}

export function getMemoryBase(id: string) {
  return api.get<MemoryBase>(`/memory/bases/${id}`)
}

export function createMemoryBase(name: string, description?: string, options?: {
  type?: MemoryBase['type']
  embedding_model?: string
}) {
  return api.post<MemoryBase>('/memory/bases', { name, description, ...options })
}

export function deleteMemoryBase(id: string) {
  return api.delete(`/memory/bases/${id}`)
}
```

- [ ] **Step 2: Create MemoryBases.vue list page**

Follow `KnowledgeBases.vue` pattern exactly. Key elements:
- Page header: `<h1 class="page-title">记忆库</h1>` + "创建记忆库" button
- Create dialog with type selector (radio buttons like knowledge base DOCUMENT/TABLE):
  - **自研记忆库** (BUILTIN): name + description + embedding model selector
  - **mem0** (MEM0): name + description + info text "请参考文档在您的 DBay 数据库上部署 mem0"
  - **hindsight** (HINDSIGHT): name + description + info text
  - **自定义** (CUSTOM): name + description + info text
- Data table columns: 名称, 类型 (badge), 记忆数, 特征数, 状态, 创建时间
- Type badge colors: BUILTIN→`tag-red` "自研", MEM0→`tag-blue` "mem0", HINDSIGHT→`tag-green` "hindsight", CUSTOM→`tag-gray` "自定义"
- Row click → `router.push('/memory/' + item.id)`
- Delete with confirmation dialog
- Empty state: brain SVG icon + "暂无记忆库，点击右上角创建"

- [ ] **Step 3: Create MemoryBaseDetail.vue stub**

```vue
<template>
  <div class="page-container">
    <div class="breadcrumb">
      <router-link to="/memory">记忆库</router-link>
      <span class="breadcrumb-sep">/</span>
      <span>{{ base?.name || '...' }}</span>
    </div>
    <div class="page-header">
      <h1 class="page-title">{{ base?.name || '...' }}</h1>
      <div class="page-header-actions">
        <span class="status-tag" :class="base?.status === 'READY' ? 'tag-green' : 'tag-gray'">{{ base?.status }}</span>
      </div>
    </div>

    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">概览</div>
      <div v-if="base?.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'memories' }" @click="activeTab = 'memories'">记忆</div>
      <div v-if="base?.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'traits' }" @click="activeTab = 'traits'">特征</div>
      <div class="tab-item" :class="{ active: activeTab === 'settings' }" @click="activeTab = 'settings'">接入</div>
    </div>

    <!-- Overview -->
    <div v-if="activeTab === 'overview'" class="section-card">
      <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; padding: 24px;">
        <div><div style="font-size: 28px; font-weight: 700;">{{ base?.memory_count || 0 }}</div><div style="color: #999; margin-top: 4px;">记忆</div></div>
        <div><div style="font-size: 28px; font-weight: 700;">{{ base?.trait_count || 0 }}</div><div style="color: #999; margin-top: 4px;">特征</div></div>
        <div><div style="font-size: 28px; font-weight: 700;">{{ typeLabel }}</div><div style="color: #999; margin-top: 4px;">类型</div></div>
      </div>
    </div>

    <!-- Memories tab placeholder — Task 7 will replace -->
    <div v-if="activeTab === 'memories'" class="section-card">
      <p style="color: #999; text-align: center; padding: 40px 0;">记忆列表（Phase 2 实现）</p>
    </div>

    <!-- Traits tab placeholder — Task 8 will replace -->
    <div v-if="activeTab === 'traits'" class="section-card">
      <p style="color: #999; text-align: center; padding: 40px 0;">特征可视化（Phase 2 实现）</p>
    </div>

    <!-- Settings tab -->
    <div v-if="activeTab === 'settings'" class="section-card" style="padding: 24px;">
      <h3 style="margin-bottom: 16px;">MCP 接入配置</h3>
      <p style="color: #666; font-size: 14px;">记忆库 ID: <code>{{ base?.id }}</code></p>
      <p style="color: #666; font-size: 14px; margin-top: 8px;">API Endpoint: <code>https://api.dbay.cloud:8443/api/v1/memory/bases/{{ base?.id }}</code></p>
      <h3 style="margin: 24px 0 12px;">Python SDK</h3>
      <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto;">pip install dbay

from dbay import MemoryClient

client = MemoryClient(
    api_key="your_api_key",
    base_id="{{ base?.id }}"
)
client.ingest("用户喜欢使用 TypeScript")
results = client.recall("用户的技术偏好")</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getMemoryBase, type MemoryBase } from '../../api/memory'

const route = useRoute()
const base = ref<MemoryBase | null>(null)
const activeTab = ref('overview')

const typeLabels: Record<string, string> = { BUILTIN: '自研', MEM0: 'mem0', HINDSIGHT: 'hindsight', CUSTOM: '自定义' }
const typeLabel = computed(() => typeLabels[base.value?.type || ''] || base.value?.type || '')

onMounted(async () => {
  const memId = route.params.memId as string
  const resp = await getMemoryBase(memId)
  base.value = resp.data
})
</script>
```

- [ ] **Step 4: Verify dev server**

Run: `cd /Users/jacky/code/lakeon/lakeon-console && npm run dev`
Verify: Click 记忆库 rail icon → see list page. Click "创建记忆库" → see type selector. Navigate to a detail page → see tabs.

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/api/memory.ts lakeon-console/src/views/memory/
git commit -m "feat(memory): add memory bases list page and detail stub with tabs"
```

---

## Phase 2: Memory Microservice + Content Visualization (P1)

### Task 5: Python Memory Microservice

**Files:**
- Create: `memory/service/main.py`
- Create: `memory/service/schema.py`
- Create: `memory/service/engine.py`
- Create: `memory/service/models.py`
- Create: `memory/service/providers.py`
- Create: `memory/service/requirements.txt`
- Create: `memory/service/Dockerfile`

- [ ] **Step 1: Create requirements.txt**

```
fastapi==0.115.0
uvicorn==0.34.0
psycopg2-binary==2.9.10
httpx==0.28.1
numpy==2.2.3
pydantic==2.11.1
```

- [ ] **Step 2: Create schema.py**

```python
import psycopg2
import time

SCHEMA_SQL = """
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS memories (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    memory_type VARCHAR(20) NOT NULL,  -- fact, episode, procedural
    importance FLOAT DEFAULT 0.5,
    access_count INT DEFAULT 0,
    last_accessed_at TIMESTAMPTZ,
    embedding vector(1024),
    metadata JSONB DEFAULT '{}',
    event_time TIMESTAMPTZ,
    valid_from TIMESTAMPTZ DEFAULT now(),
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS traits (
    id SERIAL PRIMARY KEY,
    content TEXT NOT NULL,
    trait_stage VARCHAR(20) DEFAULT 'trend',  -- trend, candidate, emerging, established, core
    trait_subtype VARCHAR(20),  -- behavior, preference, core
    confidence FLOAT DEFAULT 0.0,
    reinforcement_count INT DEFAULT 0,
    contradiction_count INT DEFAULT 0,
    context TEXT,
    evidence_ids INT[],
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS graph_nodes (
    id SERIAL PRIMARY KEY,
    node_type VARCHAR(50) NOT NULL,
    node_id VARCHAR(200) NOT NULL,
    properties JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(node_type, node_id)
);

CREATE TABLE IF NOT EXISTS graph_edges (
    id SERIAL PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL,
    source_id VARCHAR(200) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    edge_type VARCHAR(100) NOT NULL,
    properties JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_memories_type ON memories(memory_type);
CREATE INDEX IF NOT EXISTS idx_memories_embedding ON memories USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_traits_stage ON traits(trait_stage);
CREATE INDEX IF NOT EXISTS idx_graph_edges_source ON graph_edges(source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_graph_edges_target ON graph_edges(target_type, target_id);
"""

def init_schema(connstr: str, retries: int = 10, delay: float = 3.0):
    """Create memory tables in user's database. Retries for Neon cold-start."""
    for attempt in range(retries):
        try:
            conn = psycopg2.connect(connstr, connect_timeout=30)
            conn.autocommit = True
            with conn.cursor() as cur:
                cur.execute(SCHEMA_SQL)
            conn.close()
            return
        except psycopg2.OperationalError:
            if attempt < retries - 1:
                time.sleep(delay)
            else:
                raise
```

- [ ] **Step 3: Create providers.py**

```python
import httpx
import os
from typing import Optional

EMBEDDING_API_URL = os.getenv('EMBEDDING_API_URL', 'https://api.siliconflow.cn/v1/embeddings')
EMBEDDING_API_KEY = os.getenv('EMBEDDING_API_KEY', '')
EMBEDDING_MODEL = os.getenv('EMBEDDING_MODEL', 'BAAI/bge-m3')

_client: Optional[httpx.AsyncClient] = None

def _get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient(timeout=30)
    return _client

async def get_embedding(text: str) -> list[float]:
    """Get 1024-dim embedding from SiliconFlow API."""
    client = _get_client()
    resp = await client.post(
        EMBEDDING_API_URL,
        json={'model': EMBEDDING_MODEL, 'input': text},
        headers={'Authorization': f'Bearer {EMBEDDING_API_KEY}'},
    )
    resp.raise_for_status()
    return resp.json()['data'][0]['embedding']

async def get_embeddings_batch(texts: list[str]) -> list[list[float]]:
    """Batch embedding for multiple texts."""
    client = _get_client()
    resp = await client.post(
        EMBEDDING_API_URL,
        json={'model': EMBEDDING_MODEL, 'input': texts},
        headers={'Authorization': f'Bearer {EMBEDDING_API_KEY}'},
    )
    resp.raise_for_status()
    data = resp.json()['data']
    return [d['embedding'] for d in sorted(data, key=lambda x: x['index'])]
```

- [ ] **Step 4: Create models.py**

```python
from pydantic import BaseModel
from typing import Optional, Literal
from datetime import datetime

class Memory(BaseModel):
    id: int
    content: str
    memory_type: str
    importance: float = 0.5
    access_count: int = 0
    metadata: dict = {}
    event_time: Optional[datetime] = None
    created_at: datetime

class Trait(BaseModel):
    id: int
    content: str
    trait_stage: str
    trait_subtype: Optional[str] = None
    confidence: float
    reinforcement_count: int = 0
    contradiction_count: int = 0
    context: Optional[str] = None
    created_at: datetime

class GraphNode(BaseModel):
    node_type: str
    node_id: str
    properties: dict = {}

class GraphEdge(BaseModel):
    source_type: str
    source_id: str
    target_type: str
    target_id: str
    edge_type: str

class IngestRequest(BaseModel):
    content: str
    role: str = 'user'
    memory_type: str = 'fact'
    importance: float = 0.5
    metadata: dict = {}

class RecallRequest(BaseModel):
    query: str
    top_k: int = 10
    memory_types: Optional[list[str]] = None

class MemoryStats(BaseModel):
    total: int
    by_type: dict
    trait_count: int
```

- [ ] **Step 5: Create engine.py — core memory operations**

```python
import psycopg2
import psycopg2.extras
import json
from typing import Optional
from models import Memory, Trait, MemoryStats, GraphNode, GraphEdge
from providers import get_embedding

def _connect(connstr: str):
    return psycopg2.connect(connstr, connect_timeout=30)

async def ingest(connstr: str, content: str, role: str, memory_type: str,
                 importance: float, metadata: dict) -> Memory:
    """Store a memory with embedding."""
    embedding = await get_embedding(content)
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                INSERT INTO memories (content, memory_type, importance, embedding, metadata, created_at)
                VALUES (%s, %s, %s, %s::vector, %s, now())
                RETURNING id, content, memory_type, importance, access_count, metadata, event_time, created_at
            """, (content, memory_type, importance, json.dumps(embedding), json.dumps(metadata)))
            row = cur.fetchone()
            conn.commit()
            return Memory(**row)
    finally:
        conn.close()

async def recall(connstr: str, query: str, top_k: int,
                 memory_types: Optional[list[str]]) -> list[Memory]:
    """Hybrid search: vector cosine similarity + RRF ranking.

    Algorithm (simplified from zhixing SDK SearchService):
    1. Generate query embedding
    2. Vector search: ORDER BY embedding <=> query_embedding LIMIT top_k*3
    3. Text search: WHERE content @@ plainto_tsquery(query) (if pg_search available, use BM25)
    4. RRF merge: score = sum(1/(k+rank)) for each retrieval path, k=60
    5. Return top_k results sorted by RRF score
    """
    embedding = await get_embedding(query)
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            # Vector search
            type_filter = ""
            params = [json.dumps(embedding), top_k * 3]
            if memory_types:
                type_filter = "WHERE memory_type = ANY(%s)"
                params = [json.dumps(embedding)] + [memory_types] + [top_k * 3]

            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at,
                       1 - (embedding <=> %s::vector) AS vector_score
                FROM memories
                {type_filter}
                ORDER BY embedding <=> %s::vector
                LIMIT %s
            """, [json.dumps(embedding)] + ([memory_types] if memory_types else []) +
                 [json.dumps(embedding), top_k * 3])
            vector_results = cur.fetchall()

            # Text search (simple LIKE fallback; upgrade to pg_search BM25 when available)
            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at,
                       ts_rank(to_tsvector('simple', content), plainto_tsquery('simple', %s)) AS text_score
                FROM memories
                {type_filter}
                WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', %s)
                ORDER BY text_score DESC
                LIMIT %s
            """, [query] + ([memory_types] if memory_types else []) + [query, top_k * 3])
            text_results = cur.fetchall()

            # RRF merge (k=60)
            rrf_scores: dict[int, float] = {}
            all_rows: dict[int, dict] = {}

            for rank, row in enumerate(vector_results):
                mid = row['id']
                rrf_scores[mid] = rrf_scores.get(mid, 0) + 1.0 / (60 + rank)
                all_rows[mid] = row

            for rank, row in enumerate(text_results):
                mid = row['id']
                rrf_scores[mid] = rrf_scores.get(mid, 0) + 1.0 / (60 + rank)
                all_rows[mid] = row

            # Sort by RRF score, return top_k
            sorted_ids = sorted(rrf_scores, key=lambda x: rrf_scores[x], reverse=True)[:top_k]

            # Update access counts
            if sorted_ids:
                cur.execute("""
                    UPDATE memories SET access_count = access_count + 1, last_accessed_at = now()
                    WHERE id = ANY(%s)
                """, (sorted_ids,))
                conn.commit()

            return [Memory(**{k: v for k, v in all_rows[mid].items()
                             if k not in ('vector_score', 'text_score')})
                    for mid in sorted_ids]
    finally:
        conn.close()

async def list_memories(connstr: str, memory_type: Optional[str],
                        offset: int, limit: int) -> dict:
    """Paginated memory list."""
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            where = "WHERE memory_type = %s" if memory_type else ""
            params_count = [memory_type] if memory_type else []
            params_list = ([memory_type] if memory_type else []) + [limit, offset]

            cur.execute(f"SELECT count(*) as total FROM memories {where}", params_count)
            total = cur.fetchone()['total']

            cur.execute(f"""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at
                FROM memories {where}
                ORDER BY created_at DESC
                LIMIT %s OFFSET %s
            """, params_list)
            rows = cur.fetchall()
            return {"memories": [Memory(**r) for r in rows], "total": total}
    finally:
        conn.close()

async def get_memory(connstr: str, memory_id: int) -> Memory:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                SELECT id, content, memory_type, importance, access_count, metadata,
                       event_time, created_at FROM memories WHERE id = %s
            """, (memory_id,))
            row = cur.fetchone()
            if not row:
                raise ValueError(f"Memory not found: {memory_id}")
            return Memory(**row)
    finally:
        conn.close()

async def delete_memory(connstr: str, memory_id: int):
    conn = _connect(connstr)
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM memories WHERE id = %s", (memory_id,))
            conn.commit()
    finally:
        conn.close()

async def get_stats(connstr: str) -> MemoryStats:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("SELECT memory_type, count(*) as cnt FROM memories GROUP BY memory_type")
            by_type = {r['memory_type']: r['cnt'] for r in cur.fetchall()}
            total = sum(by_type.values())
            cur.execute("SELECT count(*) as cnt FROM traits")
            trait_count = cur.fetchone()['cnt']
            return MemoryStats(total=total, by_type=by_type, trait_count=trait_count)
    finally:
        conn.close()

async def list_traits(connstr: str) -> list[Trait]:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                SELECT id, content, trait_stage, trait_subtype, confidence,
                       reinforcement_count, contradiction_count, context, created_at
                FROM traits ORDER BY
                    CASE trait_stage
                        WHEN 'core' THEN 1
                        WHEN 'established' THEN 2
                        WHEN 'emerging' THEN 3
                        WHEN 'candidate' THEN 4
                        WHEN 'trend' THEN 5
                    END, confidence DESC
            """)
            return [Trait(**r) for r in cur.fetchall()]
    finally:
        conn.close()

async def get_graph(connstr: str) -> dict:
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("SELECT node_type, node_id, properties FROM graph_nodes")
            nodes = [GraphNode(**r) for r in cur.fetchall()]
            cur.execute("SELECT source_type, source_id, target_type, target_id, edge_type FROM graph_edges")
            edges = [GraphEdge(**r) for r in cur.fetchall()]
            return {"nodes": [n.model_dump() for n in nodes],
                    "edges": [e.model_dump() for e in edges]}
    finally:
        conn.close()
```

- [ ] **Step 6: Create main.py — FastAPI app**

Note: `connstr` is passed via `X-Database-Connstr` header (not query param) for security.

```python
from fastapi import FastAPI, Header, HTTPException, Query
from typing import Optional
import schema
import engine
from models import IngestRequest, RecallRequest

app = FastAPI(title="DBay Memory Service")

def _require_connstr(x_database_connstr: Optional[str] = Header(None)) -> str:
    if not x_database_connstr:
        raise HTTPException(400, "X-Database-Connstr header required")
    return x_database_connstr

@app.post("/init")
async def init_memory(x_database_connstr: str = Header(...)):
    schema.init_schema(x_database_connstr)
    return {"status": "ok"}

@app.post("/ingest")
async def ingest(req: IngestRequest, x_database_connstr: str = Header(...)):
    mem = await engine.ingest(x_database_connstr, req.content, req.role,
                               req.memory_type, req.importance, req.metadata)
    return mem.model_dump()

@app.post("/recall")
async def recall(req: RecallRequest, x_database_connstr: str = Header(...)):
    results = await engine.recall(x_database_connstr, req.query, req.top_k, req.memory_types)
    return {"memories": [m.model_dump() for m in results]}

@app.get("/memories")
async def list_memories(
    x_database_connstr: str = Header(...),
    memory_type: Optional[str] = None,
    offset: int = 0,
    limit: int = 20,
):
    result = await engine.list_memories(x_database_connstr, memory_type, offset, limit)
    return {"memories": [m.model_dump() for m in result["memories"]], "total": result["total"]}

@app.get("/memories/{memory_id}")
async def get_memory(memory_id: int, x_database_connstr: str = Header(...)):
    try:
        mem = await engine.get_memory(x_database_connstr, memory_id)
        return mem.model_dump()
    except ValueError as e:
        raise HTTPException(404, str(e))

@app.delete("/memories/{memory_id}")
async def delete_memory(memory_id: int, x_database_connstr: str = Header(...)):
    await engine.delete_memory(x_database_connstr, memory_id)
    return {"status": "ok"}

@app.get("/stats")
async def get_stats(x_database_connstr: str = Header(...)):
    stats = await engine.get_stats(x_database_connstr)
    return stats.model_dump()

@app.get("/traits")
async def list_traits(x_database_connstr: str = Header(...)):
    traits = await engine.list_traits(x_database_connstr)
    return [t.model_dump() for t in traits]

@app.get("/graph")
async def get_graph(x_database_connstr: str = Header(...)):
    return await engine.get_graph(x_database_connstr)

@app.post("/digest")
async def digest(x_database_connstr: str = Header(...)):
    """Stub: trigger reflection to discover behavioral traits.
    Full implementation (LLM-based trait extraction from accumulated memories)
    will be added later. For now returns a placeholder."""
    # TODO: implement reflection logic — analyze recent memories with LLM,
    # extract behavioral patterns, create/update traits with evidence chains
    return {"status": "not_implemented", "message": "Digest/reflection coming soon"}

@app.get("/health")
async def health():
    return {"status": "ok"}
```

- [ ] **Step 7: Create Dockerfile**

```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py .
EXPOSE 8001
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
```

- [ ] **Step 8: Verify locally**

```bash
cd /Users/jacky/code/lakeon/memory/service
pip install -r requirements.txt
uvicorn main:app --port 8001
# Test health: curl http://localhost:8001/health
```

- [ ] **Step 9: Commit**

```bash
git add memory/
git commit -m "feat(memory): add Python memory microservice with FastAPI"
```

---

### Task 6: Java API — MemoryDbHelper + Proxy to Microservice

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/memory/MemoryDbHelper.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryService.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/memory/MemoryController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add MemoryConfig to LakeonProperties**

In `LakeonProperties.java`, add inner class (follow existing `KnowledgeConfig` pattern):

```java
@ConfigurationProperties(prefix = "lakeon")
public class LakeonProperties {
    // ... existing fields ...
    private MemoryConfig memory = new MemoryConfig();
    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }

    public static class MemoryConfig {
        private String serviceUrl = "http://memory-svc:8001";
        public String getServiceUrl() { return serviceUrl; }
        public void setServiceUrl(String serviceUrl) { this.serviceUrl = serviceUrl; }
    }
}
```

In `application.yml`, add:
```yaml
lakeon:
  memory:
    service-url: ${LAKEON_MEMORY_SERVICE_URL:http://memory-svc:8001}
```

- [ ] **Step 2: Create MemoryDbHelper**

Follow `KnowledgeDbHelper` pattern — reuses same compute pod resolution logic:

```java
package com.lakeon.memory;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.ComputeLifecycleService;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class MemoryDbHelper {

    private final MemoryBaseRepository memoryBaseRepository;
    private final DatabaseRepository databaseRepository;
    private final ComputeLifecycleService computeLifecycleService;
    private final ComputePodManager computePodManager;

    public MemoryDbHelper(MemoryBaseRepository memoryBaseRepository,
                          DatabaseRepository databaseRepository,
                          @Lazy ComputeLifecycleService computeLifecycleService,
                          ComputePodManager computePodManager) {
        this.memoryBaseRepository = memoryBaseRepository;
        this.databaseRepository = databaseRepository;
        this.computeLifecycleService = computeLifecycleService;
        this.computePodManager = computePodManager;
    }

    /**
     * Resolve memory base -> databaseId -> compute pod -> connstr.
     */
    public String resolveConnstr(String tenantId, String memId) {
        MemoryBaseEntity mem = memoryBaseRepository.findByIdAndTenantId(memId, tenantId)
                .orElseThrow(() -> new NotFoundException("Memory base not found: " + memId));
        if (!"READY".equals(mem.getStatus())) {
            throw new BadRequestException("Memory base is not ready: " + mem.getStatus());
        }
        String databaseId = mem.getDatabaseId();
        if (databaseId == null) {
            throw new BadRequestException("Memory base has no backing database");
        }

        DatabaseEntity db = databaseRepository.findByIdAndTenantId(databaseId, tenantId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));

        computeLifecycleService.wakeCompute(databaseId);

        String podName = "compute-" + databaseId.replace("_", "-");
        if (!computePodManager.waitForPodReady(podName, 120_000)) {
            throw new RuntimeException("Compute pod not ready: " + podName);
        }
        String podIp = computePodManager.getPodIp(podName);
        if (podIp == null) {
            throw new RuntimeException("Compute pod IP not available for: " + podName);
        }

        return "postgresql://cloud_admin:cloud-admin-internal@" + podIp + ":55433/" + db.getName()
                + "?sslmode=disable";
    }
}
```

- [ ] **Step 3: Update MemoryService with proxy logic**

Add `RestTemplate` proxy to Python memory service. Note: uses `getTenant(req)` pattern from controller — tenantId passed explicitly.

```java
// Add these fields and constructor params to MemoryService:
private final MemoryDbHelper dbHelper;
private final LakeonProperties props;
private final RestTemplate restTemplate = new RestTemplate();

// Constructor: add dbHelper and props params alongside repository

/**
 * Proxy POST/DELETE to Python memory service with X-Database-Connstr header.
 */
public Object proxyPost(String tenantId, String memId, String path, Object body) {
    String connstr = dbHelper.resolveConnstr(tenantId, memId);
    String url = props.getMemory().getServiceUrl() + path;

    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Database-Connstr", connstr);
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<?> entity = new HttpEntity<>(body, headers);
    ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
    return resp.getBody();
}

/**
 * Proxy GET to Python memory service with X-Database-Connstr header.
 */
public Object proxyGet(String tenantId, String memId, String path, Map<String, String> params) {
    String connstr = dbHelper.resolveConnstr(tenantId, memId);
    UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(props.getMemory().getServiceUrl() + path);
    if (params != null) {
        params.forEach((k, v) -> { if (v != null) builder.queryParam(k, v); });
    }

    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Database-Connstr", connstr);

    HttpEntity<?> entity = new HttpEntity<>(headers);
    ResponseEntity<Object> resp = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, entity, Object.class);
    return resp.getBody();
}

/**
 * Proxy DELETE to Python memory service.
 */
public Object proxyDelete(String tenantId, String memId, String path) {
    String connstr = dbHelper.resolveConnstr(tenantId, memId);
    String url = props.getMemory().getServiceUrl() + path;

    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Database-Connstr", connstr);

    HttpEntity<?> entity = new HttpEntity<>(headers);
    ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.DELETE, entity, Object.class);
    return resp.getBody();
}
```

- [ ] **Step 4: Add proxy endpoints to MemoryController**

All proxy endpoints use `getTenant(req)` and pass tenantId to service:

```java
// Add to MemoryController — proxy endpoints for memory content operations:

@PostMapping("/bases/{id}/ingest")
public Object ingest(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyPost(tenant.getId(), id, "/ingest", body);
}

@PostMapping("/bases/{id}/recall")
public Object recall(HttpServletRequest req, @PathVariable String id, @RequestBody Map<String, Object> body) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyPost(tenant.getId(), id, "/recall", body);
}

@PostMapping("/bases/{id}/digest")
public Object digest(HttpServletRequest req, @PathVariable String id) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyPost(tenant.getId(), id, "/digest", null);
}

@GetMapping("/bases/{id}/memories")
public Object listMemories(HttpServletRequest req, @PathVariable String id,
        @RequestParam(required = false) String memory_type,
        @RequestParam(defaultValue = "0") String offset,
        @RequestParam(defaultValue = "20") String limit) {
    TenantEntity tenant = getTenant(req);
    Map<String, String> params = new HashMap<>();
    if (memory_type != null) params.put("memory_type", memory_type);
    params.put("offset", offset);
    params.put("limit", limit);
    return memoryService.proxyGet(tenant.getId(), id, "/memories", params);
}

@GetMapping("/bases/{id}/memories/{memoryId}")
public Object getMemory(HttpServletRequest req, @PathVariable String id, @PathVariable int memoryId) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyGet(tenant.getId(), id, "/memories/" + memoryId, null);
}

@DeleteMapping("/bases/{id}/memories/{memoryId}")
public Object deleteMemory(HttpServletRequest req, @PathVariable String id, @PathVariable int memoryId) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyDelete(tenant.getId(), id, "/memories/" + memoryId);
}

@GetMapping("/bases/{id}/stats")
public Object stats(HttpServletRequest req, @PathVariable String id) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyGet(tenant.getId(), id, "/stats", null);
}

@GetMapping("/bases/{id}/traits")
public Object traits(HttpServletRequest req, @PathVariable String id) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyGet(tenant.getId(), id, "/traits", null);
}

@GetMapping("/bases/{id}/graph")
public Object graph(HttpServletRequest req, @PathVariable String id) {
    TenantEntity tenant = getTenant(req);
    return memoryService.proxyGet(tenant.getId(), id, "/graph", null);
}
```

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/jacky/code/lakeon && ./gradlew :lakeon-api:compileJava`

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/ lakeon-api/src/main/java/com/lakeon/config/ lakeon-api/src/main/resources/application.yml
git commit -m "feat(memory): add MemoryDbHelper and API proxy to Python microservice"
```

---

### Task 7: Console — Memory Content List (记忆 tab)

**Files:**
- Modify: `lakeon-console/src/api/memory.ts`
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue`

- [ ] **Step 1: Add memory content API functions to memory.ts**

```typescript
export interface MemoryItem {
  id: number
  content: string
  memory_type: 'fact' | 'episode' | 'procedural'
  importance: number
  access_count: number
  metadata: Record<string, any>
  event_time: string | null
  created_at: string
}

export interface MemoryStats {
  total: number
  by_type: Record<string, number>
  trait_count: number
}

export function getMemoryStats(memId: string) {
  return api.get<MemoryStats>(`/memory/bases/${memId}/stats`)
}

export function listMemories(memId: string, options?: {
  memory_type?: string
  offset?: number
  limit?: number
}) {
  return api.get<{ memories: MemoryItem[]; total: number }>(`/memory/bases/${memId}/memories`, { params: options })
}

export function deleteMemory(memId: string, memoryId: number) {
  return api.delete(`/memory/bases/${memId}/memories/${memoryId}`)
}

export function recallMemories(memId: string, query: string, topK = 10) {
  return api.post<{ memories: MemoryItem[] }>(`/memory/bases/${memId}/recall`, { query, top_k: topK })
}
```

- [ ] **Step 2: Replace memories tab placeholder in MemoryBaseDetail.vue**

Replace the `<div v-if="activeTab === 'memories'"` placeholder block with:
- Type filter buttons row: 全部 / fact / episode / procedural (click sets `memoryTypeFilter`)
- Search input (on enter, calls `recallMemories` instead of `listMemories`)
- Data table with `.data-table` class:
  - Columns: 内容 (truncated to 100 chars), 类型 (badge), 重要度 (0-1 bar), 访问次数, 创建时间
  - Type badge colors: fact→blue (`background:#e6f7ff;color:#1890ff`), episode→purple (`background:#f9f0ff;color:#722ed1`), procedural→amber (`background:#fff7e6;color:#d48806`)
- Click row → show full content in `.dialog-overlay` + `.dialog-box` modal
- Delete button per row with confirmation
- Pagination: prev/next buttons + "第 X / Y 页" indicator, 20 per page
- Empty state when no memories

- [ ] **Step 3: Update overview tab with real stats**

Replace static overview with `getMemoryStats()` call on mount. Show stat cards for each `by_type` entry.

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/api/memory.ts lakeon-console/src/views/memory/MemoryBaseDetail.vue
git commit -m "feat(memory): add memory content list with search, filter, and pagination"
```

---

### Task 8: Console — Trait Visualization (特征 tab)

**Files:**
- Create: `lakeon-console/src/components/memory/TraitCard.vue`
- Modify: `lakeon-console/src/api/memory.ts`
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue`

- [ ] **Step 1: Add trait API function to memory.ts**

```typescript
export interface Trait {
  id: number
  content: string
  trait_stage: 'trend' | 'candidate' | 'emerging' | 'established' | 'core'
  trait_subtype: string | null
  confidence: number
  reinforcement_count: number
  contradiction_count: number
  context: string | null
  created_at: string
}

export function listTraits(memId: string) {
  return api.get<Trait[]>(`/memory/bases/${memId}/traits`)
}
```

- [ ] **Step 2: Create TraitCard.vue**

```vue
<template>
  <div class="trait-card">
    <div class="trait-header">
      <span class="trait-stage-badge" :style="stageBadgeStyle">{{ stageLabel }}</span>
      <span v-if="trait.trait_subtype" class="trait-subtype">{{ trait.trait_subtype }}</span>
    </div>
    <p class="trait-content">{{ trait.content }}</p>
    <div class="trait-meta">
      <div class="confidence-bar-wrapper">
        <span style="font-size:12px;color:#999;">置信度</span>
        <div class="confidence-bar">
          <div class="confidence-fill" :style="{ width: (trait.confidence * 100) + '%' }"></div>
        </div>
        <span style="font-size:12px;color:#666;">{{ (trait.confidence * 100).toFixed(0) }}%</span>
      </div>
      <div style="display:flex;gap:16px;font-size:12px;color:#999;">
        <span>支持 {{ trait.reinforcement_count }}</span>
        <span>反驳 {{ trait.contradiction_count }}</span>
      </div>
    </div>
    <p v-if="trait.context" class="trait-context">{{ trait.context }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Trait } from '../../api/memory'

const props = defineProps<{ trait: Trait }>()

const stageColors: Record<string, string> = {
  core: '#e6393d', established: '#d48806', emerging: '#389e0d',
  candidate: '#1890ff', trend: '#999',
}
const stageLabels: Record<string, string> = {
  core: '核心', established: '稳定', emerging: '新兴',
  candidate: '候选', trend: '趋势',
}
const stageLabel = computed(() => stageLabels[props.trait.trait_stage] || props.trait.trait_stage)
const stageBadgeStyle = computed(() => {
  const color = stageColors[props.trait.trait_stage] || '#999'
  return { background: color + '15', color, border: `1px solid ${color}30` }
})
</script>

<style scoped>
.trait-card { border: 1px solid #ebebeb; border-radius: 6px; padding: 16px; margin-bottom: 12px; }
.trait-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.trait-stage-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 500; }
.trait-subtype { font-size: 12px; color: #999; }
.trait-content { font-size: 14px; color: #333; margin: 0 0 12px; line-height: 1.5; }
.trait-meta { display: flex; justify-content: space-between; align-items: center; }
.confidence-bar-wrapper { display: flex; align-items: center; gap: 8px; }
.confidence-bar { width: 100px; height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.confidence-fill { height: 100%; background: #e6393d; border-radius: 3px; transition: width 0.3s; }
.trait-context { font-size: 12px; color: #999; margin-top: 8px; font-style: italic; }
</style>
```

- [ ] **Step 3: Implement traits tab in MemoryBaseDetail.vue**

Replace the `<div v-if="activeTab === 'traits'"` placeholder block with:
- Call `listTraits(memId)` when tab activates
- Group traits by stage: core → established → emerging → candidate → trend
- Each group: section header with stage name (colored) + count badge
- List of `<TraitCard>` components
- "Earlier stages" (candidate + trend) collapsible by default
- Empty state: "暂无特征，记忆库积累足够记忆后系统会自动发现行为特征"

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/components/memory/TraitCard.vue lakeon-console/src/api/memory.ts lakeon-console/src/views/memory/MemoryBaseDetail.vue
git commit -m "feat(memory): add trait visualization with stage grouping and confidence bars"
```

---

### Task 9: Python SDK — `pip install dbay`

**Files:**
- Create: `sdk/pyproject.toml`
- Create: `sdk/dbay/__init__.py`
- Create: `sdk/dbay/client.py`
- Create: `sdk/dbay/memory.py`

- [ ] **Step 1: Create pyproject.toml**

```toml
[project]
name = "dbay"
version = "0.1.0"
description = "DBay Python SDK — Serverless PostgreSQL, Knowledge Base, and Memory for AI Agents"
requires-python = ">=3.10"
dependencies = [
    "httpx>=0.27.0",
]

[project.optional-dependencies]
dev = ["pytest>=8.0.0"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["dbay"]
```

- [ ] **Step 2: Create dbay/client.py — base HTTP client**

```python
"""Base HTTP client for DBay API."""
import httpx
from typing import Any, Optional


class DbayApiError(Exception):
    def __init__(self, status_code: int, body: Any = None):
        self.status_code = status_code
        self.body = body
        if isinstance(body, dict):
            err = body.get("error", body)
            msg = err.get("message", str(body)) if isinstance(err, dict) else str(err)
        else:
            msg = str(body)
        super().__init__(f"DBay API Error [{status_code}]: {msg}")


class BaseClient:
    DEFAULT_BASE_URL = "https://api.dbay.cloud:8443/api/v1"

    def __init__(self, api_key: str, base_url: Optional[str] = None, timeout: float = 60):
        self.base_url = (base_url or self.DEFAULT_BASE_URL).rstrip("/")
        self.api_key = api_key
        self._http = httpx.Client(
            timeout=timeout,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )

    def _request(self, method: str, path: str, **kwargs) -> Any:
        resp = self._http.request(method, f"{self.base_url}{path}", **kwargs)
        if resp.status_code >= 400:
            try:
                body = resp.json()
            except Exception:
                body = {"error": {"message": resp.text}}
            raise DbayApiError(resp.status_code, body)
        if resp.status_code == 204:
            return {}
        return resp.json() if resp.content else None

    def get(self, path: str, params: Optional[dict] = None) -> Any:
        return self._request("GET", path, params=params)

    def post(self, path: str, json: Optional[dict] = None) -> Any:
        return self._request("POST", path, json=json)

    def delete(self, path: str) -> Any:
        return self._request("DELETE", path)

    def close(self):
        self._http.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
```

- [ ] **Step 3: Create dbay/memory.py — MemoryClient**

```python
"""DBay Memory client for AI agent memory management."""
from typing import Optional
from .client import BaseClient


class MemoryClient:
    """Client for DBay Memory API.

    Usage:
        from dbay import MemoryClient

        client = MemoryClient(api_key="lk_xxx", base_id="mem_abc123")
        client.ingest("User prefers TypeScript over JavaScript")
        results = client.recall("What programming languages does the user prefer?")
        for mem in results:
            print(mem["content"], mem["memory_type"])
    """

    def __init__(self, api_key: str, base_id: str, base_url: Optional[str] = None, timeout: float = 60):
        self._client = BaseClient(api_key=api_key, base_url=base_url, timeout=timeout)
        self._base_id = base_id
        self._prefix = f"/memory/bases/{base_id}"

    def ingest(self, content: str, role: str = "user", memory_type: str = "fact",
               importance: float = 0.5, metadata: Optional[dict] = None) -> dict:
        """Store a memory."""
        return self._client.post(f"{self._prefix}/ingest", json={
            "content": content,
            "role": role,
            "memory_type": memory_type,
            "importance": importance,
            "metadata": metadata or {},
        })

    def recall(self, query: str, top_k: int = 10,
               memory_types: Optional[list[str]] = None) -> list[dict]:
        """Search memories by semantic similarity."""
        resp = self._client.post(f"{self._prefix}/recall", json={
            "query": query,
            "top_k": top_k,
            "memory_types": memory_types,
        })
        return resp.get("memories", [])

    def digest(self) -> dict:
        """Run reflection to discover behavioral traits."""
        return self._client.post(f"{self._prefix}/digest")

    def list_memories(self, memory_type: Optional[str] = None,
                      offset: int = 0, limit: int = 20) -> dict:
        """List memories with pagination."""
        params = {"offset": offset, "limit": limit}
        if memory_type:
            params["memory_type"] = memory_type
        return self._client.get(f"{self._prefix}/memories", params=params)

    def get_memory(self, memory_id: int) -> dict:
        """Get a single memory by ID."""
        return self._client.get(f"{self._prefix}/memories/{memory_id}")

    def delete_memory(self, memory_id: int) -> dict:
        """Delete a memory."""
        return self._client.delete(f"{self._prefix}/memories/{memory_id}")

    def list_traits(self) -> list[dict]:
        """List all discovered traits."""
        return self._client.get(f"{self._prefix}/traits")

    def stats(self) -> dict:
        """Get memory statistics."""
        return self._client.get(f"{self._prefix}/stats")

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
```

- [ ] **Step 4: Create dbay/__init__.py**

```python
"""DBay Python SDK — Serverless PostgreSQL, Knowledge Base, and Memory for AI Agents."""
from .memory import MemoryClient
from .client import DbayApiError

__all__ = ["MemoryClient", "DbayApiError"]
__version__ = "0.1.0"
```

- [ ] **Step 5: Verify package**

```bash
cd /Users/jacky/code/lakeon/sdk
pip install -e .
python -c "from dbay import MemoryClient; print('OK')"
```

- [ ] **Step 6: Commit**

```bash
git add sdk/
git commit -m "feat(sdk): add dbay Python SDK with MemoryClient"
```

---

## Phase 3: Deployment + Advanced UI (P2)

### Task 10: Knowledge Graph Visualization (D3)

**Files:**
- Create: `lakeon-console/src/components/memory/KnowledgeGraph.vue`
- Modify: `lakeon-console/src/api/memory.ts`
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue`

- [ ] **Step 1: Install d3**

```bash
cd /Users/jacky/code/lakeon/lakeon-console && npm install d3 @types/d3
```

- [ ] **Step 2: Add graph API + types to memory.ts**

```typescript
export interface GraphData {
  nodes: { node_type: string; node_id: string; properties: Record<string, any> }[]
  edges: { source_type: string; source_id: string; target_type: string; target_id: string; edge_type: string }[]
}

export function getGraph(memId: string) {
  return api.get<GraphData>(`/memory/bases/${memId}/graph`)
}
```

- [ ] **Step 3: Create KnowledgeGraph.vue**

D3 force-directed graph component:
- Node circles colored by type: person→#1890ff, organization→#389e0d, skill→#d48806, location→#e6393d, event→#722ed1, entity→#999
- Node labels (node_id text)
- Edge lines with edge_type labels (small, rotated)
- d3-zoom for pan/zoom on SVG container
- d3-force simulation: forceLink + forceManyBody + forceCenter
- Click node → emit event to show side panel with properties
- Node type filter checkboxes above the SVG
- Empty state: "知识图谱暂无数据"

Reference zhixing cloud `KnowledgeGraph` component design, adapted from React/D3 to Vue 3/D3.

- [ ] **Step 4: Add "图谱" tab to MemoryBaseDetail.vue**

Add new tab after "特征" (only shown for BUILTIN type). When tab activates, call `getGraph(memId)` and render `<KnowledgeGraph>` component.

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/components/memory/KnowledgeGraph.vue lakeon-console/src/api/memory.ts lakeon-console/src/views/memory/MemoryBaseDetail.vue package.json package-lock.json
git commit -m "feat(memory): add D3 knowledge graph visualization"
```

---

### Task 11: Helm Deployment for Memory Service

**Files:**
- Create: `deploy/helm/lakeon/templates/memory-service.yaml`
- Modify: `deploy/helm/lakeon/values.yaml`
- Modify: `deploy/cce/sites/hwstaff/values.yaml`

- [ ] **Step 1: Create K8s Deployment + Service**

```yaml
{{- if .Values.memory.enabled }}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: memory-svc
  namespace: {{ .Release.Namespace }}
  labels:
    app: lakeon-memory
spec:
  replicas: 1
  selector:
    matchLabels:
      app: lakeon-memory
  template:
    metadata:
      labels:
        app: lakeon-memory
    spec:
      {{- if .Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- range .Values.global.imagePullSecrets }}
        - name: {{ . }}
        {{- end }}
      {{- end }}
      containers:
        - name: memory
          image: {{ .Values.memory.image }}
          ports:
            - containerPort: 8001
          env:
            - name: EMBEDDING_API_URL
              value: {{ .Values.embedding.apiUrl | default "https://api.siliconflow.cn/v1/embeddings" }}
            - name: EMBEDDING_API_KEY
              valueFrom:
                secretKeyRef:
                  name: lakeon-secrets
                  key: embedding-api-key
                  optional: true
            - name: EMBEDDING_MODEL
              value: {{ .Values.embedding.model | default "BAAI/bge-m3" }}
          resources:
            requests:
              cpu: {{ .Values.memory.cpu | default "500m" }}
              memory: {{ .Values.memory.memory | default "512Mi" }}
            limits:
              cpu: {{ .Values.memory.cpu | default "500m" }}
              memory: {{ .Values.memory.memory | default "512Mi" }}
          readinessProbe:
            httpGet:
              path: /health
              port: 8001
            initialDelaySeconds: 10
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /health
              port: 8001
            initialDelaySeconds: 30
            periodSeconds: 30
---
apiVersion: v1
kind: Service
metadata:
  name: memory-svc
  namespace: {{ .Release.Namespace }}
spec:
  selector:
    app: lakeon-memory
  ports:
    - port: 8001
      targetPort: 8001
{{- end }}
```

- [ ] **Step 2: Add default values**

In `deploy/helm/lakeon/values.yaml`:
```yaml
memory:
  enabled: false
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.1.0
  cpu: 500m
  memory: 512Mi
```

In `deploy/cce/sites/hwstaff/values.yaml`:
```yaml
memory:
  enabled: true
  image: swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory:0.1.0
```

- [ ] **Step 3: Commit**

```bash
git add deploy/
git commit -m "feat(memory): add Helm deployment for memory microservice"
```

---

## Summary

| Phase | Tasks | Delivers |
|-------|-------|----------|
| **Phase 1** (Tasks 1-4) | Entity + API CRUD + Console rail + List page | 用户可以创建/管理记忆库实例，看到 4 种类型（自研/mem0/hindsight/自定义） |
| **Phase 2** (Tasks 5-9) | Python 微服务 + API 代理 + 记忆列表 + Trait 可视化 + SDK | BUILTIN 类型完整可用，`pip install dbay` 可用 |
| **Phase 3** (Tasks 10-11) | D3 图谱 + K8s 部署 | 知识图谱浏览 + 生产部署就绪 |

**Future work (not in this plan):**
- Timeline view (时间线)
- Import tool (对话导入: ChatGPT, Claude, WeChat)
- MCP Server endpoints (让 Claude Code / Cursor 通过 MCP 直接使用记忆库)
- Digest scheduling (定时反思)
- Q-value feedback loop (记忆质量飞轮)
- SDK: knowledge client, database client (扩展 `pip install dbay` 的能力)
