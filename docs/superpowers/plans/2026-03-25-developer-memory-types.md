# Developer Memory Types Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `decision`, `rejection`, `convention` memory types to the existing memory module.

**Architecture:** Extend the existing `memories` table CHECK constraint and Pydantic validation. No new tables or columns. Developer-specific fields stored in existing `metadata` JSONB. Frontend adds filter buttons and type-specific tag colors.

**Tech Stack:** Python (FastAPI, Pydantic, psycopg2), TypeScript (Vue 3), PostgreSQL

**Spec:** `docs/superpowers/specs/2026-03-25-developer-memory-types-design.md`

---

### Task 1: Python schema — Add CHECK constraint

**Files:**
- Modify: `memory/service/schema.py:65-78` (init_schema function)

- [ ] **Step 1: Add CHECK constraint SQL to init_schema()**

After the `cur.execute(SCHEMA_SQL)` line (line 71), add:

```python
                cur.execute("""
                    ALTER TABLE memories DROP CONSTRAINT IF EXISTS memories_memory_type_check;
                    ALTER TABLE memories ADD CONSTRAINT memories_memory_type_check
                      CHECK (memory_type IN ('fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'));
                """)
```

- [ ] **Step 2: Verify syntax**

Run: `cd memory/service && python -c "import schema; print('OK')"`
Expected: `OK` (no import errors)

- [ ] **Step 3: Commit**

```bash
git add memory/service/schema.py
git commit -m "feat(memory): add CHECK constraint for developer memory types"
```

---

### Task 2: Python models — Add Literal validation

**Files:**
- Modify: `memory/service/models.py:1-2` (imports)
- Modify: `memory/service/models.py:46` (IngestRequest.memory_type)

- [ ] **Step 1: Update import**

Change line 2 from:
```python
from typing import Optional
```
to:
```python
from typing import Optional, Literal
```

- [ ] **Step 2: Update IngestRequest.memory_type field**

Change line 46 from:
```python
    memory_type: str = "fact"
```
to:
```python
    memory_type: Literal['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] = "fact"
```

- [ ] **Step 3: Verify**

Run: `cd memory/service && python -c "from models import IngestRequest; r = IngestRequest(content='test', memory_type='decision'); print(r.memory_type)"`
Expected: `decision`

Run: `cd memory/service && python -c "from models import IngestRequest; IngestRequest(content='test', memory_type='invalid')" 2>&1 | head -3`
Expected: Pydantic validation error

- [ ] **Step 4: Commit**

```bash
git add memory/service/models.py
git commit -m "feat(memory): add Literal validation for memory types in IngestRequest"
```

---

### Task 3: Frontend API types

**Files:**
- Modify: `lakeon-console/src/api/memory.ts:41` (MemoryItem interface)

- [ ] **Step 1: Update memory_type union**

Change line 41 from:
```typescript
  memory_type: 'fact' | 'episode' | 'procedural'
```
to:
```typescript
  memory_type: 'fact' | 'episode' | 'procedural' | 'decision' | 'rejection' | 'convention'
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/memory.ts
git commit -m "feat(memory): add developer memory types to MemoryItem interface"
```

---

### Task 4: Frontend — Filter buttons and tag colors

**Files:**
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue:63-67` (filter buttons)
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue:91-93` (table tag colors)
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue:132-134` (detail dialog tag colors)
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue:476` (typeLabels — change 自研 to DBay记忆库)

- [ ] **Step 1: Add a typeColors helper in the script section**

After line 367 (`const PAGE_SIZE = 20`), add:

```typescript
const typeColors: Record<string, string> = {
  fact: 'background:#e6f7ff;color:#1890ff',
  episode: 'background:#f9f0ff;color:#722ed1',
  procedural: 'background:#fff7e6;color:#d48806',
  decision: 'background:#e6fffb;color:#13c2c2',
  rejection: 'background:#fff1f0;color:#f5222d',
  convention: 'background:#f6ffed;color:#52c41a',
}
function typeStyle(t: string) { return typeColors[t] || 'background:#f0f0f0;color:#666' }
```

- [ ] **Step 2: Add filter buttons for new types**

After the `procedural` filter button (line 67), add three more buttons:

```html
            <button class="btn" :class="memoryTypeFilter === 'decision' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('decision')" style="height:28px;font-size:12px;padding:0 12px;">decision</button>
            <button class="btn" :class="memoryTypeFilter === 'rejection' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('rejection')" style="height:28px;font-size:12px;padding:0 12px;">rejection</button>
            <button class="btn" :class="memoryTypeFilter === 'convention' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('convention')" style="height:28px;font-size:12px;padding:0 12px;">convention</button>
```

- [ ] **Step 3: Replace ternary chain in table with typeStyle()**

Replace lines 91-93 (the `<span>` with inline ternary) with:

```html
                  <span style="padding:2px 8px;border-radius:4px;font-size:12px;"
                    :style="typeStyle(mem.memory_type)">
                    {{ mem.memory_type }}
                  </span>
```

- [ ] **Step 4: Replace ternary chain in detail dialog with typeStyle()**

Replace lines 132-134 (the `<span>` in the dialog) with:

```html
                <span style="padding:2px 8px;border-radius:4px;font-size:12px;"
                  :style="typeStyle(selectedMemory?.memory_type || '')">
                  {{ selectedMemory?.memory_type }}
                </span>
```

- [ ] **Step 5: Add metadata display in detail dialog**

After the event_time div (line 142), add:

```html
              <div v-if="selectedMemory?.metadata && Object.keys(selectedMemory.metadata).length > 0" style="margin-top:12px;padding:10px;background:#f5f5f5;border-radius:4px;">
                <div v-if="selectedMemory.metadata.rationale" style="font-size:13px;color:#333;margin-bottom:4px;">
                  <strong>决策理由：</strong>{{ selectedMemory.metadata.rationale }}
                </div>
                <div v-if="selectedMemory.metadata.reason" style="font-size:13px;color:#333;margin-bottom:4px;">
                  <strong>排除原因：</strong>{{ selectedMemory.metadata.reason }}
                </div>
                <div v-if="selectedMemory.metadata.scope" style="font-size:13px;color:#333;margin-bottom:4px;">
                  <strong>范围：</strong>{{ selectedMemory.metadata.scope }}
                </div>
                <div v-if="selectedMemory.metadata.project" style="font-size:13px;color:#999;">
                  项目：{{ selectedMemory.metadata.project }}
                </div>
              </div>
```

- [ ] **Step 6: Verify frontend builds**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 7: Commit**

```bash
git add lakeon-console/src/views/memory/MemoryBaseDetail.vue
git commit -m "feat(memory): add developer memory type filters, colors, and metadata display"
```

---

### Task 5: Build, push, deploy, verify

**Files:**
- Modify: `deploy/cce/sites/hwstaff/values.yaml` (if memory service image tag needs bump)

- [ ] **Step 1: Build and push memory service image**

Check current memory service image tag:
```bash
grep 'memory' deploy/cce/sites/hwstaff/values.yaml
```

Build and push (adjust tag as needed):
```bash
cd memory/service && docker build -t swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory-svc:<new-tag> .
docker push swr.cn-north-4.myhuaweicloud.com/flex/lakeon-memory-svc:<new-tag>
```

- [ ] **Step 2: Build and push console image**

```bash
./deploy/cce/build-and-push-console.sh
```

- [ ] **Step 3: Update image tags in values.yaml if needed**

- [ ] **Step 4: Deploy**

```bash
./deploy/cce/deploy.sh
```

- [ ] **Step 5: End-to-end verification**

Test ingest with new types via curl:
```bash
# Ingest a decision
curl -X POST https://api.dbay.cloud:8443/api/v1/memory/bases/<mem_id>/ingest \
  -H "Authorization: Bearer <api_key>" \
  -H "Content-Type: application/json" \
  -d '{"content":"Choose asyncpg over SQLAlchemy","memory_type":"decision","metadata":{"rationale":"Project is fully async","project":"lakeon"}}'

# Ingest a rejection
curl -X POST https://api.dbay.cloud:8443/api/v1/memory/bases/<mem_id>/ingest \
  -H "Authorization: Bearer <api_key>" \
  -H "Content-Type: application/json" \
  -d '{"content":"No Redis","memory_type":"rejection","metadata":{"reason":"Adds operational complexity","project":"lakeon"}}'

# Recall filtered by type
curl -X POST https://api.dbay.cloud:8443/api/v1/memory/bases/<mem_id>/recall \
  -H "Authorization: Bearer <api_key>" \
  -H "Content-Type: application/json" \
  -d '{"query":"database choice","memory_types":["decision"]}'
```

- [ ] **Step 6: Verify in console UI**

Open the memory base detail page, confirm:
- Filter buttons show all 6 types
- New memories have correct tag colors
- Detail dialog shows metadata (rationale/reason/scope/project)

- [ ] **Step 7: Final commit (if any deploy config changes)**

```bash
git add deploy/
git commit -m "deploy: bump memory service and console image tags"
```
