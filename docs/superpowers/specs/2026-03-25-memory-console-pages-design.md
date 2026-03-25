# Memory Console Pages — Design Spec

**Date:** 2026-03-25
**Status:** Approved
**Scope:** Add 3 new pages to DBay Console for memory browsing, trait insights, and usage stats; expand sidebar menu

---

## Problem

The memory module sidebar has only one menu item ("记忆库" — the base list). Users cannot browse individual memories, view digest-generated traits, or see usage statistics from the console. All memory operations require CLI or API calls.

---

## Goals

1. Expand memory sidebar to 4 menu items
2. Add "记忆浏览" page — browse, search, and manage memories by type
3. Add "反思洞察" page — view traits grouped by lifecycle stage
4. Add "用量统计" page — memory count distribution and summary stats

---

## 1. Sidebar Menu Changes

**File:** `lakeon-console/src/layouts/ConsoleLayout.vue`

Current memory menu (line 137-141):
```html
<template v-if="activeRail === 'memory'">
  <div class="nav-group">
    <router-link to="/memory" ...>记忆库</router-link>
  </div>
</template>
```

**After:**
```html
<template v-if="activeRail === 'memory'">
  <div class="nav-group">
    <router-link to="/memory" ...>记忆库</router-link>
    <router-link to="/memory/browse" ...>记忆浏览</router-link>
    <router-link to="/memory/traits" ...>反思洞察</router-link>
    <router-link to="/memory/stats" ...>用量统计</router-link>
  </div>
</template>
```

The last 3 items require a selected memory base. The pages read `memoryBaseId` from a shared store or URL query param. If no base is selected, show a prompt to select one.

**Memory base selector:** A dropdown at the top of each sub-page (browse/traits/stats) that lists the user's memory bases. Selection persists in route query `?base=mem_xxx` or a Pinia store.

---

## 2. 记忆浏览 Page (`MemoryBrowse.vue`)

**Route:** `/memory/browse` (or `/memory/browse?base=mem_xxx`)

### 2.1 Layout

```
┌─────────────────────────────────────────────────┐
│ [记忆库选择器 ▼]                                  │
├─────────────────────────────────────────────────┤
│ [全部] [fact] [episode] [procedural]            │
│ [decision] [rejection] [convention]             │
├─────────────────────────────────────────────────┤
│ 🔍 [搜索记忆...                    ] [搜索]      │
├─────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────┐ │
│ │ [decision]  选择 asyncpg 替代 SQLAlchemy     │ │
│ │ rationale: 项目全异步  project: lakeon       │ │
│ │ importance: 0.8    2026-03-25 12:00         │ │
│ │                                    [删除]   │ │
│ └─────────────────────────────────────────────┘ │
│ ┌─────────────────────────────────────────────┐ │
│ │ [rejection]  不使用 Redis 缓存              │ │
│ │ reason: 运维复杂度高   project: lakeon       │ │
│ │ importance: 0.7    2026-03-25 11:30         │ │
│ │                                    [删除]   │ │
│ └─────────────────────────────────────────────┘ │
│                                                 │
│ 显示 1-20 / 共 42 条     [< 上一页] [下一页 >]    │
└─────────────────────────────────────────────────┘
```

### 2.2 Type Filter Buttons

Reuse `typeColors` from `MemoryBaseDetail.vue`:

```javascript
const typeColors = {
  fact:       { bg: '#e8f4fd', text: '#1976d2', border: '#90caf9' },
  episode:    { bg: '#f3e5f5', text: '#7b1fa2', border: '#ce93d8' },
  procedural: { bg: '#fff3e0', text: '#e65100', border: '#ffcc80' },
  decision:   { bg: '#e8f5e9', text: '#2e7d32', border: '#a5d6a7' },
  rejection:  { bg: '#ffebee', text: '#c62828', border: '#ef9a9a' },
  convention: { bg: '#ede7f6', text: '#4527a0', border: '#b39ddb' },
}
```

### 2.3 Search Mode vs List Mode

- **Empty search box:** calls `GET /api/v1/memory/bases/{id}/memories?memory_type=X&offset=0&limit=20` (list mode)
- **With search text:** calls `POST /api/v1/memory/bases/{id}/recall` with `{query, top_k: 20, memory_types: [X]}` (semantic search mode)
- Search results show relevance score badge instead of importance

### 2.4 Memory Card

Each card shows:
- **Type badge** (colored, from typeColors)
- **Content** (main text, truncated to 3 lines, expand on click)
- **Metadata** (type-specific):
  - decision: `rationale` + `project`
  - rejection: `reason` + `project`
  - convention: `scope` + `project`
  - fact: `category`
  - episode: `timestamp`
  - procedural: `category`
- **Importance** (0.0-1.0 as percentage bar)
- **Created time**
- **Delete button** (with confirmation)

### 2.5 API Calls

| Action | API | Method |
|--------|-----|--------|
| List memories | `/memory/bases/{id}/memories` | GET |
| Search memories | `/memory/bases/{id}/recall` | POST |
| Delete memory | `/memory/bases/{id}/memories/{memId}` | DELETE |

All APIs already exist from the endpoints refactor.

---

## 3. 反思洞察 Page (`MemoryTraits.vue`)

**Route:** `/memory/traits` (or `/memory/traits?base=mem_xxx`)

### 3.1 Layout

```
┌─────────────────────────────────────────────────┐
│ [记忆库选择器 ▼]                                  │
├─────────────────────────────────────────────────┤
│                                                 │
│ ● Core (2)                                      │
│ ┌─────────────────────────────────────────────┐ │
│ │ [core] behavior                              │ │
│ │ 偏好轻量异步库，避免 ORM 开销                  │ │
│ │ ████████░░ 80%   +5 / -1                    │ │
│ │ [trend → candidate → emerging → established → ●core] │
│ │ 首次: 2026-03-20                             │ │
│ └─────────────────────────────────────────────┘ │
│                                                 │
│ ● Established (3)                               │
│ ...                                             │
│                                                 │
│ ● Emerging (1)                                  │
│ ...                                             │
│                                                 │
│ ▸ Earlier (5)   [点击展开]                       │
└─────────────────────────────────────────────────┘
```

### 3.2 Stage Groups

Traits grouped by `trait_stage`, displayed in order:

| Stage | 中文标签 | Badge 颜色 | 显示方式 |
|-------|---------|-----------|---------|
| `core` | 核心 | 金色 (#f59e0b) | 展开 |
| `established` | 稳定 | 绿色 (#22c55e) | 展开 |
| `emerging` | 萌芽 | 蓝色 (#3b82f6) | 展开 |
| `trend` | 趋势 | 灰色 | 折叠在 "Earlier" 下 |
| `candidate` | 候选 | 灰色 | 折叠在 "Earlier" 下 |

Each group header: stage label + count badge.

### 3.3 Trait Card

Each card shows:
- **Stage badge** (colored per stage)
- **Subtype label** (behavior / preference / core, small text)
- **Content** (main insight text)
- **Confidence bar** (0-100%, color: green ≥80%, blue ≥50%, yellow ≥30%, red <30%)
- **Reinforcement / Contradiction count** (`+5 / -1`)
- **Lifecycle progress bar** — horizontal 5-step indicator showing current stage:
  ```
  trend → candidate → emerging → established → core
  ○────────○────────●────────○────────○
  ```
  Steps before current stage are filled, current is highlighted, future is empty.
- **Created time**

### 3.4 API Calls

| Action | API | Method |
|--------|-----|--------|
| List traits | `/memory/bases/{id}/traits` | GET |

The existing `/traits` endpoint returns traits ordered by stage + confidence. No changes needed.

---

## 4. 用量统计 Page (`MemoryStats.vue`)

**Route:** `/memory/stats` (or `/memory/stats?base=mem_xxx`)

### 4.1 Layout

```
┌─────────────────────────────────────────────────┐
│ [记忆库选择器 ▼]                                  │
├─────────────────────────────────────────────────┤
│                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │    42    │  │     5    │  │  3 小时前  │      │
│  │ 总记忆数  │  │ Trait 数  │  │ 最后活跃   │      │
│  └──────────┘  └──────────┘  └──────────┘      │
│                                                 │
│  类型分布                                        │
│  ┌─────────────────────────────────────────┐    │
│  │  fact ████████████  18                  │    │
│  │  decision ████████  12                  │    │
│  │  episode ██████  8                      │    │
│  │  rejection ███  4                       │    │
│  │  procedural ██  3                       │    │
│  │  convention █  1                        │    │
│  └─────────────────────────────────────────┘    │
│                                                 │
└─────────────────────────────────────────────────┘
```

### 4.2 Components

- **Summary cards** (3 个): total memories, trait count, last activity time
- **Type distribution bar chart**: horizontal bars, colored by type, sorted by count descending

### 4.3 API Calls

| Action | API | Method |
|--------|-----|--------|
| Get stats | `/memory/bases/{id}/stats` | GET |

Response: `{total, by_type: {fact: 18, decision: 12, ...}, trait_count: 5}`

---

## 5. Router Changes

**File:** `lakeon-console/src/router/index.ts`

Add 3 new routes under memory:

```javascript
{ path: '/memory/browse', component: () => import('@/views/memory/MemoryBrowse.vue') },
{ path: '/memory/traits', component: () => import('@/views/memory/MemoryTraits.vue') },
{ path: '/memory/stats',  component: () => import('@/views/memory/MemoryStats.vue') },
```

---

## 6. Memory Base Selector Component

**File:** `lakeon-console/src/components/MemoryBaseSelector.vue`

Shared dropdown component used by all 3 new pages:

```html
<select v-model="selectedBase" @change="emit('change', selectedBase)">
  <option v-for="base in bases" :value="base.id">{{ base.name }}</option>
</select>
```

- Fetches bases from `/memory/bases` on mount
- Persists selection in route query `?base=mem_xxx`
- If only one base exists, auto-selects it
- If no base exists, shows "请先创建记忆库" with link to `/memory`

---

## 7. Files to Create/Modify

| File | Action |
|------|--------|
| `views/memory/MemoryBrowse.vue` | Create |
| `views/memory/MemoryTraits.vue` | Create |
| `views/memory/MemoryStats.vue` | Create |
| `components/MemoryBaseSelector.vue` | Create |
| `layouts/ConsoleLayout.vue` | Modify (sidebar menu) |
| `router/index.ts` | Modify (add routes) |
| `api/memory.ts` | Modify (add recall, delete, traits, stats API methods if missing) |

---

## 8. Not in Scope

- Trait evidence drill-down (展开查看支撑记忆) → Phase 2
- Trait feedback (👍👎) → Phase 2
- Trait delete → Phase 2
- Memory edit → not planned
- Graph/知识图谱 page → TBD
- Import page → not needed (ingest via API/CLI)
