# Memory Console Pages — Design Spec

**Date:** 2026-03-25
**Status:** Approved
**Scope:** Add 3 new pages to DBay Console for memory browsing, trait insights, and usage stats; expand sidebar menu

---

## Problem

The memory module sidebar has only one menu item ("记忆库" — the base list). Users cannot browse individual memories, view digest-generated traits, or see usage statistics from the console. All memory operations require CLI or API calls.

**Existing overlap:** `MemoryBaseDetail.vue` (`/memory/:memId`) has tabs for "记忆", "特征", "图谱". The new pages replace these inline tabs with standalone sidebar navigation. After this change, `MemoryBaseDetail.vue` should **remove** the "记忆", "特征", "图谱" tabs and keep only "概览" and "接入" tabs. The standalone pages provide a richer experience (lifecycle visualization, search, type distribution) that doesn't fit in a tab.

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

The last 3 items require a selected memory base. The pages read `memoryBaseId` from route query param `?base=mem_xxx`. If no base is selected, show a prompt to select one.

**Memory base selector:** A dropdown at the top of each sub-page (browse/traits/stats) that lists the user's memory bases. Selection persists in route query `?base=mem_xxx` (bookmarkable, no new Pinia store needed).

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

Extract `typeColors` into a shared constants file `src/constants/memory.ts` (currently inline in `MemoryBaseDetail.vue`). Use the existing hex values from `MemoryBaseDetail.vue` to avoid visual changes:

```typescript
// src/constants/memory.ts
export const MEMORY_TYPE_COLORS: Record<string, { bg: string; text: string }> = {
  fact:       { bg: '#e6f7ff', text: '#1890ff' },
  episode:    { bg: '#f9f0ff', text: '#722ed1' },
  procedural: { bg: '#fff7e6', text: '#d48806' },
  decision:   { bg: '#f6ffed', text: '#389e0d' },
  rejection:  { bg: '#fff1f0', text: '#cf1322' },
  convention: { bg: '#f0f5ff', text: '#2f54eb' },
};

export const MEMORY_TYPES = ['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] as const;
```

Both `MemoryBaseDetail.vue` and the new pages import from this file.

### 2.3 Search Mode vs List Mode

- **Empty search box:** calls `GET /api/v1/memory/bases/{id}/memories?memory_type=X&offset=0&limit=20` (list mode)
- **With search text:** calls `POST /api/v1/memory/bases/{id}/recall` with `{query, top_k: 20, memory_types: [X]}` (semantic search mode). Note: `api/memory.ts` `recallMemories()` needs updating to pass `memory_types` param (currently only sends `query` and `top_k`).
- Search results show relevance score badge instead of importance
- **Pagination in search mode:** recall returns `top_k` results with no offset — pagination controls are hidden in search mode (same as existing `MemoryBaseDetail.vue` behavior)

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
│  ┌──────────────┐  ┌──────────────┐             │
│  │      42      │  │       5      │             │
│  │   总记忆数    │  │   Trait 数    │             │
│  └──────────────┘  └──────────────┘             │
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

- **Summary cards** (2 个): total memories, trait count. (Remove "最后活跃" — the stats API doesn't return a last_activity timestamp, and adding one is out of scope.)
- **Type distribution bar chart**: horizontal bars, colored by type (from shared `MEMORY_TYPE_COLORS`), sorted by count descending

### 4.3 API Calls

| Action | API | Method |
|--------|-----|--------|
| Get stats | `/memory/bases/{id}/stats` | GET |

Response: `{total, by_type: {fact: 18, decision: 12, ...}, trait_count: 5}`

---

## 5. Router Changes

**File:** `lakeon-console/src/router/index.ts`

Add 3 new routes **before** the existing `/memory/:memId` dynamic route (Vue Router matches first match):

```javascript
// These MUST come before { path: 'memory/:memId' } to avoid "browse" matching as memId
{ path: '/memory/browse', component: () => import('@/views/memory/MemoryBrowse.vue') },
{ path: '/memory/traits', component: () => import('@/views/memory/MemoryTraits.vue') },
{ path: '/memory/stats',  component: () => import('@/views/memory/MemoryStats.vue') },
// Existing:
{ path: '/memory/:memId', component: () => import('@/views/memory/MemoryBaseDetail.vue') },
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
| `constants/memory.ts` | Create (shared type colors + type list) |
| `components/MemoryBaseSelector.vue` | Create (shared base dropdown) |
| `views/memory/MemoryBrowse.vue` | Create (browse/search page) |
| `views/memory/MemoryTraits.vue` | Create (traits page with lifecycle) |
| `views/memory/MemoryStats.vue` | Create (stats page with bar chart) |
| `layouts/ConsoleLayout.vue` | Modify (add 3 sidebar menu items) |
| `router/index.ts` | Modify (add 3 routes BEFORE `:memId`) |
| `api/memory.ts` | Modify (add `memory_types` param to `recallMemories`) |
| `views/memory/MemoryBaseDetail.vue` | Modify (remove 记忆/特征/图谱 tabs, keep 概览+接入; import shared `MEMORY_TYPE_COLORS`) |

**UI patterns:** follow existing codebase — `window.confirm()` for delete confirmation, `console.error` for load failures, loading ref + "加载中..." text for loading states.

---

## 8. Not in Scope

- Trait evidence drill-down (展开查看支撑记忆) → Phase 2
- Trait feedback (👍👎) → Phase 2
- Trait delete → Phase 2
- Memory edit → not planned
- Graph/知识图谱 page → TBD
- Import page → not needed (ingest via API/CLI)
