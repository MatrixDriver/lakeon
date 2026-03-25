# Memory Console Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 3 new pages (记忆浏览, 反思洞察, 用量统计) to DBay Console sidebar for memory management.

**Architecture:** Extract shared constants from existing MemoryBaseDetail.vue, create MemoryBaseSelector component, add 3 new Vue pages, update router and sidebar. Simplify MemoryBaseDetail.vue by removing tabs that now have standalone pages.

**Tech Stack:** Vue 3, TypeScript, Vue Router, Huawei Cloud console CSS (existing)

**Spec:** `docs/superpowers/specs/2026-03-25-memory-console-pages-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `src/constants/memory.ts` | Create | Shared type colors, type list, stage colors |
| `src/components/MemoryBaseSelector.vue` | Create | Dropdown to select memory base, persists in `?base=` query |
| `src/views/memory/MemoryBrowse.vue` | Create | Browse/search/delete memories by type |
| `src/views/memory/MemoryTraits.vue` | Create | View traits grouped by lifecycle stage |
| `src/views/memory/MemoryStats.vue` | Create | Stats summary cards + type distribution bar chart |
| `src/layouts/ConsoleLayout.vue` | Modify | Add 3 sidebar menu items under memory |
| `src/router/index.ts` | Modify | Add 3 routes before `:memId` |
| `src/api/memory.ts` | Modify | Add `memory_types` param to `recallMemories` |
| `src/views/memory/MemoryBaseDetail.vue` | Modify | Remove 记忆/特征/图谱 tabs, import shared colors |

All paths relative to `lakeon-console/`.

---

## Task 1: Shared Constants + API Update

**Files:**
- Create: `lakeon-console/src/constants/memory.ts`
- Modify: `lakeon-console/src/api/memory.ts`

- [ ] **Step 1: Create shared constants file**

Create `lakeon-console/src/constants/memory.ts`:

```typescript
export const MEMORY_TYPES = ['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] as const
export type MemoryType = typeof MEMORY_TYPES[number]

// Colors from existing MemoryBaseDetail.vue — DO NOT change to preserve visual consistency
export const MEMORY_TYPE_COLORS: Record<string, { bg: string; text: string }> = {
  fact:       { bg: '#e6f7ff', text: '#1890ff' },
  episode:    { bg: '#f9f0ff', text: '#722ed1' },
  procedural: { bg: '#fff7e6', text: '#d48806' },
  decision:   { bg: '#e6fffb', text: '#13c2c2' },
  rejection:  { bg: '#fff1f0', text: '#f5222d' },
  convention: { bg: '#f6ffed', text: '#52c41a' },
}

export const MEMORY_TYPE_LABELS: Record<string, string> = {
  fact: '事实',
  episode: '情景',
  procedural: '流程',
  decision: '决策',
  rejection: '排除',
  convention: '约定',
}

export const TRAIT_STAGE_ORDER = ['core', 'established', 'emerging'] as const
export const TRAIT_EARLIER_STAGES = ['trend', 'candidate'] as const

export const TRAIT_STAGE_COLORS: Record<string, { bg: string; text: string }> = {
  core:        { bg: '#fffbe6', text: '#d48806' },
  established: { bg: '#f6ffed', text: '#389e0d' },
  emerging:    { bg: '#e6f7ff', text: '#1890ff' },
  trend:       { bg: '#f5f5f5', text: '#8c8c8c' },
  candidate:   { bg: '#f5f5f5', text: '#8c8c8c' },
}

export const TRAIT_STAGE_LABELS: Record<string, string> = {
  core: '核心',
  established: '稳定',
  emerging: '萌芽',
  trend: '趋势',
  candidate: '候选',
}
```

- [ ] **Step 2: Update recallMemories to accept memory_types**

In `lakeon-console/src/api/memory.ts`, change the `recallMemories` function (line 71-73):

```typescript
export function recallMemories(memId: string, query: string, topK = 10, memoryTypes?: string[]) {
  const body: Record<string, any> = { query, top_k: topK }
  if (memoryTypes && memoryTypes.length > 0) body.memory_types = memoryTypes
  return api.post<{ memories: MemoryItem[] }>(`/memory/bases/${memId}/recall`, body)
}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/constants/memory.ts lakeon-console/src/api/memory.ts
git commit -m "feat(console): add shared memory constants and update recall API"
```

---

## Task 2: MemoryBaseSelector Component

**Files:**
- Create: `lakeon-console/src/components/MemoryBaseSelector.vue`

- [ ] **Step 1: Create the component**

```vue
<template>
  <div class="mem-base-selector">
    <label class="form-label" style="margin-bottom: 4px;">记忆库</label>
    <select class="form-input" v-model="selected" @change="onChange" style="max-width: 300px;">
      <option v-if="bases.length === 0" value="" disabled>暂无记忆库</option>
      <option v-for="b in bases" :key="b.id" :value="b.id">
        {{ b.name }} ({{ b.status }})
      </option>
    </select>
    <p v-if="bases.length === 0" style="font-size: 12px; color: #999; margin-top: 4px;">
      请先 <router-link to="/memory" style="color: #0073e6;">创建记忆库</router-link>
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listMemoryBases, type MemoryBase } from '@/api/memory'

const emit = defineEmits<{ change: [id: string] }>()

const route = useRoute()
const router = useRouter()
const bases = ref<MemoryBase[]>([])
const selected = ref('')

onMounted(async () => {
  try {
    const { data } = await listMemoryBases()
    bases.value = data.filter(b => b.status === 'READY')
  } catch (e) {
    console.error('Failed to load memory bases', e)
  }
  // Restore from query param or auto-select first
  const fromQuery = route.query.base as string
  if (fromQuery && bases.value.some(b => b.id === fromQuery)) {
    selected.value = fromQuery
  } else if (bases.value.length > 0) {
    selected.value = bases.value[0].id
    router.replace({ query: { ...route.query, base: selected.value } })
  }
  if (selected.value) emit('change', selected.value)
})

function onChange() {
  router.replace({ query: { ...route.query, base: selected.value } })
  emit('change', selected.value)
}
</script>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/components/MemoryBaseSelector.vue
git commit -m "feat(console): add MemoryBaseSelector shared component"
```

---

## Task 3: Router + Sidebar

**Files:**
- Modify: `lakeon-console/src/router/index.ts`
- Modify: `lakeon-console/src/layouts/ConsoleLayout.vue`

- [ ] **Step 1: Add routes**

In `lakeon-console/src/router/index.ts`, replace the Memory section (lines 74-76):

```typescript
      // Memory — static routes MUST come before :memId
      { path: 'memory', name: 'MemoryBases', component: () => import('../views/memory/MemoryBases.vue') },
      { path: 'memory/browse', name: 'MemoryBrowse', component: () => import('../views/memory/MemoryBrowse.vue') },
      { path: 'memory/traits', name: 'MemoryTraits', component: () => import('../views/memory/MemoryTraits.vue') },
      { path: 'memory/stats', name: 'MemoryStats', component: () => import('../views/memory/MemoryStats.vue') },
      { path: 'memory/:memId', name: 'MemoryBaseDetail', component: () => import('../views/memory/MemoryBaseDetail.vue') },
```

- [ ] **Step 2: Update sidebar**

In `lakeon-console/src/layouts/ConsoleLayout.vue`, replace the memory menu block (around line 137-141):

```html
          <template v-if="activeRail === 'memory'">
            <div class="nav-group">
              <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">记忆库</router-link>
              <router-link to="/memory/browse" class="nav-item" active-class="active" @click="sidebarOpen = false">记忆浏览</router-link>
              <router-link to="/memory/traits" class="nav-item" active-class="active" @click="sidebarOpen = false">反思洞察</router-link>
              <router-link to="/memory/stats" class="nav-item" active-class="active" @click="sidebarOpen = false">用量统计</router-link>
            </div>
          </template>
```

- [ ] **Step 3: Verify dev server loads**

Run: `cd lakeon-console && npm run dev`
Check: sidebar shows 4 memory menu items, clicking browse/traits/stats doesn't crash (pages will be empty placeholders until Tasks 4-6).

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/router/index.ts lakeon-console/src/layouts/ConsoleLayout.vue
git commit -m "feat(console): add memory browse/traits/stats routes and sidebar"
```

---

## Task 4: 记忆浏览 Page

**Files:**
- Create: `lakeon-console/src/views/memory/MemoryBrowse.vue`

- [ ] **Step 1: Create MemoryBrowse.vue**

This is the largest page. Key features:
- MemoryBaseSelector at top
- Type filter buttons (all + 6 types)
- Search box (empty = list mode, text = recall mode)
- Memory card list with metadata display
- Pagination (list mode only)
- Delete with `window.confirm()`

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">记忆浏览</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <!-- Type filters -->
      <div style="display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 16px;">
        <button
          v-for="t in ['all', ...MEMORY_TYPES]" :key="t"
          @click="typeFilter = t === 'all' ? '' : t; currentPage = 1; load()"
          class="btn btn-sm"
          :style="typeFilter === (t === 'all' ? '' : t)
            ? `background: ${t === 'all' ? '#1890ff' : MEMORY_TYPE_COLORS[t]?.text}; color: #fff;`
            : `background: ${t === 'all' ? '#f5f5f5' : MEMORY_TYPE_COLORS[t]?.bg}; color: ${t === 'all' ? '#333' : MEMORY_TYPE_COLORS[t]?.text};`"
        >
          {{ t === 'all' ? '全部' : MEMORY_TYPE_LABELS[t] || t }}
        </button>
      </div>

      <!-- Search -->
      <div style="display: flex; gap: 8px; margin-bottom: 16px;">
        <input v-model="searchQuery" class="form-input" placeholder="语义搜索记忆..." style="flex: 1;"
               @keyup.enter="currentPage = 1; load()" />
        <button class="btn btn-primary" @click="currentPage = 1; load()">搜索</button>
        <button v-if="searchQuery" class="btn" @click="searchQuery = ''; currentPage = 1; load()">清除</button>
      </div>

      <!-- Loading -->
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>

      <!-- Empty -->
      <p v-else-if="memories.length === 0" style="text-align: center; color: #999; padding: 40px 0;">
        {{ searchQuery ? '未找到匹配的记忆' : '暂无记忆' }}
      </p>

      <!-- Memory cards -->
      <div v-else style="display: flex; flex-direction: column; gap: 12px;">
        <div v-for="m in memories" :key="m.id"
             class="card" style="padding: 16px; cursor: pointer;"
             @click="expandedId = expandedId === m.id ? null : m.id">
          <div style="display: flex; align-items: flex-start; justify-content: space-between;">
            <div style="flex: 1; min-width: 0;">
              <!-- Type badge -->
              <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; margin-bottom: 8px;"
                    :style="`background: ${MEMORY_TYPE_COLORS[m.memory_type]?.bg}; color: ${MEMORY_TYPE_COLORS[m.memory_type]?.text};`">
                {{ MEMORY_TYPE_LABELS[m.memory_type] || m.memory_type }}
              </span>

              <!-- Content -->
              <p style="margin: 0; font-size: 14px; line-height: 1.6;"
                 :style="expandedId !== m.id ? 'display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;' : ''">
                {{ m.content }}
              </p>

              <!-- Metadata (expanded) -->
              <div v-if="expandedId === m.id && m.metadata && Object.keys(m.metadata).length > 0"
                   style="margin-top: 8px; padding: 8px 12px; background: #fafafa; border-radius: 4px; font-size: 12px; color: #666;">
                <div v-for="(v, k) in m.metadata" :key="k" style="margin-bottom: 2px;">
                  <strong>{{ k }}:</strong> {{ v }}
                </div>
              </div>

              <!-- Footer -->
              <div style="display: flex; gap: 16px; margin-top: 8px; font-size: 12px; color: #999;">
                <span>重要性: {{ Math.round(m.importance * 100) }}%</span>
                <span>访问: {{ m.access_count }}次</span>
                <span>{{ new Date(m.created_at).toLocaleString() }}</span>
              </div>
            </div>

            <!-- Delete -->
            <button class="btn btn-sm" style="color: #cf1322; margin-left: 12px; flex-shrink: 0;"
                    @click.stop="handleDelete(m.id)">删除</button>
          </div>
        </div>
      </div>

      <!-- Pagination (list mode only) -->
      <div v-if="!searchQuery && total > PAGE_SIZE" style="display: flex; justify-content: center; gap: 8px; margin-top: 20px;">
        <button class="btn btn-sm" :disabled="currentPage <= 1" @click="currentPage--; load()">上一页</button>
        <span style="line-height: 32px; font-size: 13px; color: #666;">
          {{ (currentPage - 1) * PAGE_SIZE + 1 }}-{{ Math.min(currentPage * PAGE_SIZE, total) }} / {{ total }}
        </span>
        <button class="btn btn-sm" :disabled="currentPage * PAGE_SIZE >= total" @click="currentPage++; load()">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { listMemories, recallMemories, deleteMemory, type MemoryItem } from '@/api/memory'
import { MEMORY_TYPES, MEMORY_TYPE_COLORS, MEMORY_TYPE_LABELS } from '@/constants/memory'

const PAGE_SIZE = 20

const baseId = ref('')
const typeFilter = ref('')
const searchQuery = ref('')
const memories = ref<MemoryItem[]>([])
const total = ref(0)
const currentPage = ref(1)
const loading = ref(false)
const expandedId = ref<number | null>(null)

function onBaseChange(id: string) {
  baseId.value = id
  currentPage.value = 1
  load()
}

async function load() {
  if (!baseId.value) return
  loading.value = true
  try {
    if (searchQuery.value.trim()) {
      // Recall mode
      const types = typeFilter.value ? [typeFilter.value] : undefined
      const { data } = await recallMemories(baseId.value, searchQuery.value, PAGE_SIZE, types)
      memories.value = data.memories
      total.value = data.memories.length
    } else {
      // List mode
      const { data } = await listMemories(baseId.value, {
        memory_type: typeFilter.value || undefined,
        offset: (currentPage.value - 1) * PAGE_SIZE,
        limit: PAGE_SIZE,
      })
      memories.value = data.memories
      total.value = data.total
    }
  } catch (e) {
    console.error('Failed to load memories', e)
    memories.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function handleDelete(memoryId: number) {
  if (!window.confirm('确定删除该记忆？')) return
  try {
    await deleteMemory(baseId.value, memoryId)
    memories.value = memories.value.filter(m => m.id !== memoryId)
    total.value--
  } catch (e) {
    console.error('Delete failed', e)
  }
}
</script>
```

- [ ] **Step 2: Verify page loads**

Run dev server, navigate to `/memory/browse`, select a base, verify type buttons render and list loads.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/memory/MemoryBrowse.vue
git commit -m "feat(console): add memory browse page with type filter and search"
```

---

## Task 5: 反思洞察 Page

**Files:**
- Create: `lakeon-console/src/views/memory/MemoryTraits.vue`

- [ ] **Step 1: Create MemoryTraits.vue**

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">反思洞察</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>
      <p v-else-if="traits.length === 0" style="text-align: center; color: #999; padding: 40px 0;">
        暂无洞察。请先执行记忆反思（digest）。
      </p>

      <template v-else>
        <!-- Main stages: core, established, emerging -->
        <div v-for="stage in TRAIT_STAGE_ORDER" :key="stage">
          <template v-if="groupByStage(stage).length > 0">
            <div style="display: flex; align-items: center; gap: 8px; margin: 24px 0 12px;">
              <h2 style="font-size: 16px; font-weight: 600; margin: 0;">{{ TRAIT_STAGE_LABELS[stage] }}</h2>
              <span style="font-size: 12px; color: #999; background: #f5f5f5; padding: 1px 8px; border-radius: 10px;">
                {{ groupByStage(stage).length }}
              </span>
            </div>
            <div style="display: flex; flex-direction: column; gap: 12px;">
              <div v-for="trait in groupByStage(stage)" :key="trait.id" class="card" style="padding: 16px;">
                <!-- Stage badge + subtype -->
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
                  <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px;"
                        :style="`background: ${TRAIT_STAGE_COLORS[trait.trait_stage]?.bg}; color: ${TRAIT_STAGE_COLORS[trait.trait_stage]?.text};`">
                    {{ TRAIT_STAGE_LABELS[trait.trait_stage] || trait.trait_stage }}
                  </span>
                  <span v-if="trait.trait_subtype" style="font-size: 12px; color: #999;">{{ trait.trait_subtype }}</span>
                </div>

                <!-- Content -->
                <p style="margin: 0 0 12px; font-size: 14px; line-height: 1.6;">{{ trait.content }}</p>

                <!-- Confidence bar -->
                <div style="margin-bottom: 12px;">
                  <div style="display: flex; justify-content: space-between; font-size: 12px; color: #999; margin-bottom: 4px;">
                    <span>置信度</span>
                    <span>{{ Math.round(trait.confidence * 100) }}%</span>
                  </div>
                  <div style="height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden;">
                    <div style="height: 100%; border-radius: 3px; transition: width 0.3s;"
                         :style="`width: ${Math.round(trait.confidence * 100)}%; background: ${confidenceColor(trait.confidence)};`" />
                  </div>
                </div>

                <!-- Lifecycle progress -->
                <div style="display: flex; align-items: center; gap: 0; margin-bottom: 12px;">
                  <template v-for="(s, i) in lifecycleStages" :key="s">
                    <div style="display: flex; flex-direction: column; align-items: center; flex: 1;">
                      <div style="width: 12px; height: 12px; border-radius: 50%; border: 2px solid;"
                           :style="lifecycleStepStyle(trait.trait_stage, s)" />
                      <span style="font-size: 10px; color: #999; margin-top: 2px;">{{ TRAIT_STAGE_LABELS[s] }}</span>
                    </div>
                    <div v-if="i < lifecycleStages.length - 1"
                         style="flex: 1; height: 2px; margin-top: -14px;"
                         :style="`background: ${isStageReached(trait.trait_stage, lifecycleStages[i + 1]) ? '#1890ff' : '#e0e0e0'};`" />
                  </template>
                </div>

                <!-- Stats -->
                <div style="display: flex; gap: 16px; font-size: 12px; color: #999;">
                  <span>+{{ trait.reinforcement_count }} / -{{ trait.contradiction_count }}</span>
                  <span>{{ new Date(trait.created_at).toLocaleDateString() }}</span>
                </div>
              </div>
            </div>
          </template>
        </div>

        <!-- Earlier stages (collapsed) -->
        <div v-if="earlierTraits.length > 0" style="margin-top: 24px;">
          <button @click="showEarlier = !showEarlier"
                  style="display: flex; align-items: center; gap: 8px; background: none; border: none; cursor: pointer; padding: 0; font-size: 16px; font-weight: 600; color: #666;">
            <span style="transition: transform 0.2s;" :style="showEarlier ? 'transform: rotate(90deg);' : ''">▸</span>
            Earlier
            <span style="font-size: 12px; color: #999; background: #f5f5f5; padding: 1px 8px; border-radius: 10px;">
              {{ earlierTraits.length }}
            </span>
          </button>
          <div v-if="showEarlier" style="display: flex; flex-direction: column; gap: 12px; margin-top: 12px;">
            <!-- Same card template but simplified (no lifecycle bar) -->
            <div v-for="trait in earlierTraits" :key="trait.id" class="card" style="padding: 16px; opacity: 0.7;">
              <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
                <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; background: #f5f5f5; color: #999;">
                  {{ TRAIT_STAGE_LABELS[trait.trait_stage] || trait.trait_stage }}
                </span>
              </div>
              <p style="margin: 0; font-size: 14px; color: #666;">{{ trait.content }}</p>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { listTraits, type Trait } from '@/api/memory'
import { TRAIT_STAGE_ORDER, TRAIT_EARLIER_STAGES, TRAIT_STAGE_COLORS, TRAIT_STAGE_LABELS } from '@/constants/memory'

const lifecycleStages = ['trend', 'candidate', 'emerging', 'established', 'core']
const stageIndex = (s: string) => lifecycleStages.indexOf(s)

const baseId = ref('')
const traits = ref<Trait[]>([])
const loading = ref(false)
const showEarlier = ref(false)

function onBaseChange(id: string) {
  baseId.value = id
  loadTraits()
}

async function loadTraits() {
  if (!baseId.value) return
  loading.value = true
  try {
    const { data } = await listTraits(baseId.value)
    traits.value = data
  } catch (e) {
    console.error('Failed to load traits', e)
    traits.value = []
  } finally {
    loading.value = false
  }
}

function groupByStage(stage: string) {
  return traits.value.filter(t => t.trait_stage === stage)
}

const earlierTraits = computed(() =>
  traits.value.filter(t => TRAIT_EARLIER_STAGES.includes(t.trait_stage as any))
)

function confidenceColor(v: number): string {
  if (v >= 0.8) return '#52c41a'
  if (v >= 0.5) return '#1890ff'
  if (v >= 0.3) return '#faad14'
  return '#f5222d'
}

function isStageReached(current: string, target: string): boolean {
  return stageIndex(current) >= stageIndex(target)
}

function lifecycleStepStyle(current: string, step: string): string {
  const reached = isStageReached(current, step)
  const isCurrent = current === step
  if (isCurrent) return 'background: #1890ff; border-color: #1890ff;'
  if (reached) return 'background: #1890ff; border-color: #1890ff; opacity: 0.5;'
  return 'background: #fff; border-color: #d9d9d9;'
}
</script>
```

- [ ] **Step 2: Verify page loads**

Navigate to `/memory/traits`, select a base with traits, verify stage groups and lifecycle bars render.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/memory/MemoryTraits.vue
git commit -m "feat(console): add memory traits page with lifecycle visualization"
```

---

## Task 6: 用量统计 Page

**Files:**
- Create: `lakeon-console/src/views/memory/MemoryStats.vue`

- [ ] **Step 1: Create MemoryStats.vue**

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">用量统计</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>

      <template v-else-if="stats">
        <!-- Summary cards -->
        <div style="display: flex; gap: 16px; margin-bottom: 32px;">
          <div class="card" style="flex: 1; padding: 20px; text-align: center;">
            <div style="font-size: 32px; font-weight: 600; color: #1890ff;">{{ stats.total }}</div>
            <div style="font-size: 13px; color: #999; margin-top: 4px;">总记忆数</div>
          </div>
          <div class="card" style="flex: 1; padding: 20px; text-align: center;">
            <div style="font-size: 32px; font-weight: 600; color: #722ed1;">{{ stats.trait_count }}</div>
            <div style="font-size: 13px; color: #999; margin-top: 4px;">Trait 数</div>
          </div>
        </div>

        <!-- Type distribution -->
        <div class="card" style="padding: 20px;">
          <h3 style="font-size: 15px; font-weight: 600; margin: 0 0 16px;">类型分布</h3>
          <div v-if="sortedTypes.length === 0" style="text-align: center; color: #999; padding: 20px;">暂无数据</div>
          <div v-else style="display: flex; flex-direction: column; gap: 10px;">
            <div v-for="item in sortedTypes" :key="item.type" style="display: flex; align-items: center; gap: 12px;">
              <span style="width: 56px; font-size: 12px; text-align: right;"
                    :style="`color: ${MEMORY_TYPE_COLORS[item.type]?.text || '#666'};`">
                {{ MEMORY_TYPE_LABELS[item.type] || item.type }}
              </span>
              <div style="flex: 1; height: 20px; background: #f5f5f5; border-radius: 4px; overflow: hidden;">
                <div style="height: 100%; border-radius: 4px; transition: width 0.5s;"
                     :style="`width: ${maxCount ? (item.count / maxCount * 100) : 0}%; background: ${MEMORY_TYPE_COLORS[item.type]?.text || '#999'};`" />
              </div>
              <span style="width: 32px; font-size: 13px; color: #333; font-weight: 500;">{{ item.count }}</span>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { getMemoryStats, type MemoryStats } from '@/api/memory'
import { MEMORY_TYPE_COLORS, MEMORY_TYPE_LABELS } from '@/constants/memory'

const baseId = ref('')
const stats = ref<MemoryStats | null>(null)
const loading = ref(false)

function onBaseChange(id: string) {
  baseId.value = id
  loadStats()
}

async function loadStats() {
  if (!baseId.value) return
  loading.value = true
  try {
    const { data } = await getMemoryStats(baseId.value)
    stats.value = data
  } catch (e) {
    console.error('Failed to load stats', e)
    stats.value = null
  } finally {
    loading.value = false
  }
}

const sortedTypes = computed(() => {
  if (!stats.value?.by_type) return []
  return Object.entries(stats.value.by_type)
    .map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count)
})

const maxCount = computed(() => {
  if (sortedTypes.value.length === 0) return 0
  return sortedTypes.value[0].count
})
</script>
```

- [ ] **Step 2: Verify page loads**

Navigate to `/memory/stats`, select a base, verify summary cards and bar chart render.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/memory/MemoryStats.vue
git commit -m "feat(console): add memory stats page with type distribution chart"
```

---

## Task 7: Simplify MemoryBaseDetail.vue

**Files:**
- Modify: `lakeon-console/src/views/memory/MemoryBaseDetail.vue`

- [ ] **Step 1: Remove 记忆/特征/图谱 tabs**

In `MemoryBaseDetail.vue`, these features now have standalone pages. Remove:
- The "记忆" tab button and its content section (type filters, memory table, pagination)
- The "特征" tab button and its content section (traits list)
- The "图谱" tab button and its content section (graph visualization)
- Keep only "概览" and "接入" tabs

In the tab bar (around line 21-27), replace with:
```html
    <div v-if="base" class="tab-bar" style="margin-top: 20px;">
      <button class="tab-item" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">概览</button>
      <button class="tab-item" :class="{ active: activeTab === 'settings' }" @click="activeTab = 'settings'">接入</button>
    </div>
```

Remove the corresponding `v-if="activeTab === 'memories'"`, `v-if="activeTab === 'traits'"`, and `v-if="activeTab === 'graph'"` template blocks and their associated script refs/functions (memoryLoading, traitsLoading, memories, traits, typeFilter, searchQuery, pagination logic, etc.).

Import `MEMORY_TYPE_COLORS` from `@/constants/memory` for any remaining type color references in the overview tab.

- [ ] **Step 2: Verify detail page still works**

Navigate to `/memory/:memId`, verify overview and settings tabs work.

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/memory/MemoryBaseDetail.vue
git commit -m "refactor(console): simplify MemoryBaseDetail — remove tabs now in standalone pages"
```

---

## Task 8: Build and Deploy Console

**Files:** No new files — uses existing Railway auto-deploy.

- [ ] **Step 1: Verify build passes**

```bash
cd lakeon-console && npm run build
```

Expected: Build succeeds with no errors.

- [ ] **Step 2: Commit all remaining changes and push**

```bash
git add -A
git push origin main
```

Railway auto-deploys on push to main.

- [ ] **Step 3: Verify on production**

Navigate to `https://console.dbay.cloud`, log in, check:
- Sidebar has 4 memory menu items
- `/memory/browse` shows type filters, search, memory cards
- `/memory/traits` shows staged trait cards with lifecycle bars
- `/memory/stats` shows summary cards and bar chart
- `/memory/:memId` only has 概览 + 接入 tabs
