# Console Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the DBay console from icon-rail+sidebar to a single-sidebar layout with ⌘K command palette, card/table view toggle, and streamlined navigation (11 items).

**Architecture:** Replace ConsoleLayout's icon-rail+sidebar (232px) with a single sidebar (~200px) grouped by product. Add a CommandPalette component for ⌘K. Convert list pages to support card/table view toggle. Absorb trimmed features (反思洞察→记忆浏览, 数据源→知识库详情 tab, etc).

**Tech Stack:** Vue 3.5 + TypeScript + custom CSS (no UI library). DM Sans font. Harbor warm palette (navy #2a4d6a + amber #c67d3a).

**Spec:** `docs/superpowers/specs/2026-03-31-console-redesign-design.md`

---

## File Structure

### New Files
- `src/components/CommandPalette.vue` — ⌘K command palette (search + actions)
- `src/components/ViewToggle.vue` — card/table view toggle button pair
- `src/components/ResourceCard.vue` — reusable card for database/kb/memory/dataset/notebook

### Modified Files
- `src/layouts/ConsoleLayout.vue` — remove icon rail, new single sidebar, update header
- `src/style.css` — remove icon-rail styles, add sidebar/card/command-palette variables
- `src/router/index.ts` — remove routes for trimmed pages (数据源→detail tab, etc)
- `src/views/dashboard/DashboardView.vue` — card/table toggle for database list
- `src/views/knowledge/KnowledgeBases.vue` — card/table toggle
- `src/views/memory/MemoryBases.vue` — card/table toggle
- `src/views/memory/MemoryBrowse.vue` — absorb 反思洞察 (TraitCard integration)
- `src/views/datalake/DatalakeDatasets.vue` — card/table toggle
- `src/views/datalake/DatalakeNotebookList.vue` — card/table toggle
- `src/views/datalake/DatalakeJobs.vue` — card/table toggle
- `src/views/knowledge/KnowledgeBaseDetail.vue` — add 数据源 tab
- `src/views/memory/MemoryBaseDetail.vue` — add 消息日志 + 用量统计 tabs

### Removed (routes only, keep files for now)
- `src/views/memory/MemoryTraits.vue` — content absorbed into MemoryBrowse
- `src/views/knowledge/KnowledgeDataSources.vue` — content absorbed into KnowledgeBaseDetail
- `src/views/memory/MemoryMessages.vue` — content absorbed into MemoryBaseDetail tab
- `src/views/memory/MemoryStats.vue` — content absorbed into MemoryBaseDetail tab

---

## Task 1: New ConsoleLayout — Single Sidebar + Header

**Files:**
- Modify: `src/layouts/ConsoleLayout.vue` (613 lines → rewrite)
- Modify: `src/style.css:1-60` (update CSS variables)

- [ ] **Step 1: Read current ConsoleLayout.vue fully**

Read the entire file to understand current template, script, and style sections.

- [ ] **Step 2: Rewrite template — remove icon rail, single sidebar**

Replace the template section. Key changes:
- Remove `.icon-rail` div entirely
- Remove `.console-sidebar` with dynamic `v-if` per rail
- Replace with single `.sidebar` containing all 4 product groups + settings
- Header: remove amber border-bottom, keep navy bg + logo + ⌘K + user avatar
- Add `@keydown.meta.k.prevent="openCommandPalette"` and `@keydown.ctrl.k.prevent="openCommandPalette"` on root

New template structure:
```html
<template>
  <div class="console-layout" @keydown.meta.k.prevent="cmdOpen = true" @keydown.ctrl.k.prevent="cmdOpen = true">
    <header class="console-header">
      <div class="header-left">
        <button class="mobile-menu-btn" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z"/>
          </svg>
        </button>
        <router-link to="/" class="logo-brand">DBay<span class="logo-tagline">数据港湾</span></router-link>
      </div>
      <div class="header-right">
        <button class="header-cmd" @click="cmdOpen = true">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
          <span class="cmd-key">⌘K</span>
        </button>
        <router-link to="/docs" class="header-nav-link">文档</router-link>
        <span class="header-divider"></span>
        <div class="header-user">
          <span class="user-avatar">{{ (authStore.tenantName || 'U').charAt(0).toUpperCase() }}</span>
          <span class="header-username">{{ authStore.tenantName || 'Tenant' }}</span>
        </div>
        <button class="header-logout" @click="handleLogout">退出</button>
      </div>
    </header>

    <!-- Trial Banner (keep as-is) -->
    <div v-if="authStore.isTrial" class="trial-banner">...</div>

    <div class="console-body">
      <div v-if="sidebarOpen" class="sidebar-overlay" @click="sidebarOpen = false"></div>
      <aside class="sidebar" :class="{ open: sidebarOpen }">
        <nav class="sidebar-nav">
          <div class="nav-group">
            <div class="nav-group-title">数据库</div>
            <router-link to="/dashboard" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- database icon --></svg>我的数据库
            </router-link>
            <router-link to="/timetravel" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- clock icon --></svg>时间旅行
            </router-link>
            <router-link to="/sql" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- code icon --></svg>SQL 编辑器
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">知识库</div>
            <router-link to="/knowledge" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- book icon --></svg>知识库
            </router-link>
            <router-link to="/knowledge/search" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- search icon --></svg>知识搜索
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">记忆库</div>
            <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- brain icon --></svg>记忆库
            </router-link>
            <router-link to="/memory/browse" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- browse icon --></svg>记忆浏览
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">数据湖</div>
            <router-link to="/datalake/datasets" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- table icon --></svg>数据集
            </router-link>
            <router-link to="/datalake/jobs" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- tool icon --></svg>作业管理
            </router-link>
            <router-link to="/datalake/notebook" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- monitor icon --></svg>Notebook
            </router-link>
          </div>
          <div class="nav-separator"></div>
          <div class="nav-group nav-group-bottom">
            <router-link to="/apikey" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- key icon --></svg>API Key
            </router-link>
            <router-link to="/account" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg><!-- user icon --></svg>账户
            </router-link>
          </div>
        </nav>
      </aside>
      <main class="console-main">
        <router-view />
      </main>
    </div>

    <CommandPalette v-if="cmdOpen" @close="cmdOpen = false" />
  </div>
</template>
```

- [ ] **Step 3: Rewrite script section**

Remove `activeRail`, `switchRail`, `railTitles`, `railDefaultRoutes` and the route-to-rail watcher. Add `cmdOpen` ref. Keep trial countdown logic, auth, logout.

```typescript
<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { tenantApi } from '../api/tenant'
import CommandPalette from '../components/CommandPalette.vue'

const router = useRouter()
const authStore = useAuthStore()
const sidebarOpen = ref(false)
const cmdOpen = ref(false)

function handleLogout() {
  authStore.logout()
  router.push('/')
}

// Trial countdown (keep existing logic)
const trialTimeLeft = ref('')
let trialTimer: ReturnType<typeof setInterval> | null = null
// ... (keep existing trial countdown code)

onMounted(async () => {
  // ... (keep existing trial refresh logic)
})

onUnmounted(() => {
  if (trialTimer) clearInterval(trialTimer)
})
</script>
```

- [ ] **Step 4: Rewrite scoped styles**

Replace all scoped styles. Key measurements:
- Header: 48px, background #2a4d6a, no border-bottom
- Sidebar: 200px wide, white background, border-right #e8e4df
- Nav group title: 10px uppercase, #94a3b8, letter-spacing 0.8px
- Nav item: 13px, 32px height, with 14px SVG icon, 8px gap
- Nav item active: color #2a4d6a, font-weight 600, background #f0f4f8, border-right 2px solid #2a4d6a
- Nav item hover: background #f8f5f1
- Main: flex 1, white background, padding 24px 28px
- Mobile: sidebar slides from left at 768px

```css
/* Full scoped CSS — see spec for exact color values */
.console-layout { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }

.console-header {
  height: 48px; background-color: #2a4d6a;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px; flex-shrink: 0; z-index: 100;
}

.header-left { display: flex; align-items: center; }

.logo-brand {
  color: #c67d3a; text-decoration: none; font-size: 17px; font-weight: 700;
  display: flex; align-items: baseline; gap: 8px;
}
.logo-tagline { font-size: 12px; font-weight: 400; color: rgba(255,255,255,0.4); letter-spacing: 0.5px; }

.header-right { display: flex; align-items: center; gap: 14px; }

.header-cmd {
  display: flex; align-items: center; gap: 6px;
  background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.1);
  border-radius: 6px; padding: 4px 10px; cursor: pointer; color: rgba(255,255,255,0.5);
  font-size: 12px; transition: all 0.15s;
}
.header-cmd:hover { background: rgba(255,255,255,0.12); color: rgba(255,255,255,0.7); }
.cmd-key { font-size: 11px; opacity: 0.6; }

.header-nav-link { color: rgba(255,255,255,0.6); font-size: 13px; text-decoration: none; }
.header-nav-link:hover { color: #fff; }
.header-divider { width: 1px; height: 14px; background: rgba(255,255,255,0.15); }

.header-user { display: flex; align-items: center; gap: 6px; }
.user-avatar {
  width: 24px; height: 24px; border-radius: 50%; background: #c67d3a;
  color: #fff; font-size: 11px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
.header-username { color: rgba(255,255,255,0.85); font-size: 13px; }
.header-logout {
  background: none; border: none; color: rgba(255,255,255,0.4);
  font-size: 12px; cursor: pointer; padding: 0;
}
.header-logout:hover { color: rgba(255,255,255,0.8); }

.console-body { display: flex; flex: 1; overflow: hidden; }

.sidebar {
  width: 200px; background: #fff; border-right: 1px solid #e8e4df;
  flex-shrink: 0; overflow-y: auto; display: flex; flex-direction: column;
}

.sidebar-nav { flex: 1; padding: 8px 0; }

.nav-group { padding: 4px 0; }
.nav-group-title {
  padding: 12px 16px 4px; font-size: 10px; font-weight: 600;
  color: #94a3b8; text-transform: uppercase; letter-spacing: 0.8px;
}
.nav-item {
  display: flex; align-items: center; gap: 8px;
  padding: 0 16px; height: 32px; color: #64748b; text-decoration: none;
  font-size: 13px; border-right: 2px solid transparent; transition: all 0.12s;
}
.nav-item svg { width: 14px; height: 14px; stroke: currentColor; fill: none; stroke-width: 2; flex-shrink: 0; }
.nav-item:hover { background: #f8f5f1; color: #2c3e50; }
.nav-item.active { color: #2a4d6a; font-weight: 600; background: #f0f4f8; border-right-color: #2a4d6a; }

.nav-separator { height: 1px; background: #e8e4df; margin: 8px 16px; }

.console-main { flex: 1; background: #fff; overflow-y: auto; padding: 24px 28px; }

/* Mobile */
.mobile-menu-btn { display: none; /* ... keep existing mobile styles */ }

@media (max-width: 768px) {
  .mobile-menu-btn { display: inline-flex; }
  .header-nav-link, .header-divider, .header-cmd .cmd-key { display: none; }
  .sidebar {
    position: fixed; top: 48px; left: 0; bottom: 0; z-index: 200;
    transform: translateX(-100%); transition: transform 0.25s ease;
  }
  .sidebar.open { transform: translateX(0); box-shadow: 4px 0 16px rgba(0,0,0,0.15); }
  .sidebar-overlay { display: block; position: fixed; inset: 48px 0 0 0; background: rgba(0,0,0,0.3); z-index: 199; }
  .console-main { padding: 16px; }
}
```

- [ ] **Step 5: Update style.css — remove icon-rail variables**

Remove all `.icon-rail`, `.rail-icon`, `.rail-label`, `.rail-separator` styles from style.css since they no longer exist.

- [ ] **Step 6: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS (no errors)

- [ ] **Step 7: Commit**

```bash
git add src/layouts/ConsoleLayout.vue src/style.css
git commit -m "feat(console): single sidebar layout, remove icon rail"
```

---

## Task 2: CommandPalette Component

**Files:**
- Create: `src/components/CommandPalette.vue`

- [ ] **Step 1: Create CommandPalette.vue**

Features: ⌘K opens, Esc closes, input search, grouped results (页面 / 操作), keyboard navigation (up/down/enter).

```vue
<template>
  <Teleport to="body">
    <div class="cmd-overlay" @click.self="$emit('close')" @keydown.esc="$emit('close')">
      <div class="cmd-box" ref="boxRef">
        <div class="cmd-input-wrap">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#94a3b8" stroke-width="2">
            <circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/>
          </svg>
          <input
            ref="inputRef"
            v-model="query"
            class="cmd-input"
            placeholder="搜索页面或操作..."
            @keydown.down.prevent="moveDown"
            @keydown.up.prevent="moveUp"
            @keydown.enter.prevent="execute"
          />
          <kbd class="cmd-esc">ESC</kbd>
        </div>
        <div class="cmd-results" v-if="filtered.length > 0">
          <template v-for="(group, gi) in groupedFiltered" :key="group.label">
            <div class="cmd-group-label">{{ group.label }}</div>
            <div
              v-for="(item, ii) in group.items"
              :key="item.id"
              class="cmd-item"
              :class="{ active: flatIndex(gi, ii) === activeIndex }"
              @click="executeItem(item)"
              @mouseenter="activeIndex = flatIndex(gi, ii)"
            >
              <span class="cmd-item-icon" v-html="item.icon"></span>
              <span class="cmd-item-label">{{ item.label }}</span>
              <span v-if="item.shortcut" class="cmd-item-shortcut">{{ item.shortcut }}</span>
            </div>
          </template>
        </div>
        <div v-else-if="query" class="cmd-empty">没有匹配结果</div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'

const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const inputRef = ref<HTMLInputElement>()
const query = ref('')
const activeIndex = ref(0)

interface CmdItem {
  id: string
  label: string
  icon: string
  group: '页面' | '操作'
  action: () => void
  shortcut?: string
}

const items: CmdItem[] = [
  // 页面
  { id: 'p-db', label: '我的数据库', icon: '⌂', group: '页面', action: () => go('/dashboard') },
  { id: 'p-tt', label: '时间旅行', icon: '◷', group: '页面', action: () => go('/timetravel') },
  { id: 'p-sql', label: 'SQL 编辑器', icon: '⟨⟩', group: '页面', action: () => go('/sql') },
  { id: 'p-kb', label: '知识库', icon: '⊞', group: '页面', action: () => go('/knowledge') },
  { id: 'p-ks', label: '知识搜索', icon: '⊕', group: '页面', action: () => go('/knowledge/search') },
  { id: 'p-mem', label: '记忆库', icon: '◉', group: '页面', action: () => go('/memory') },
  { id: 'p-browse', label: '记忆浏览', icon: '◎', group: '页面', action: () => go('/memory/browse') },
  { id: 'p-ds', label: '数据集', icon: '⊟', group: '页面', action: () => go('/datalake/datasets') },
  { id: 'p-jobs', label: '作业管理', icon: '⚙', group: '页面', action: () => go('/datalake/jobs') },
  { id: 'p-nb', label: 'Notebook', icon: '▤', group: '页面', action: () => go('/datalake/notebook') },
  { id: 'p-key', label: 'API Key', icon: '⚿', group: '页面', action: () => go('/apikey') },
  { id: 'p-acct', label: '账户设置', icon: '⊙', group: '页面', action: () => go('/account') },
  // 被收纳的页面 — 通过 ⌘K 仍可快速访问
  { id: 'p-monitor', label: '监控面板', icon: '▦', group: '页面', action: () => go('/monitor') },
  { id: 'p-logs', label: '日志管理', icon: '▧', group: '页面', action: () => go('/logs') },
  { id: 'p-backups', label: '备份管理', icon: '↺', group: '页面', action: () => go('/backups') },
  { id: 'p-import', label: '数据迁移', icon: '↓', group: '页面', action: () => go('/import') },
  { id: 'p-datasrc', label: '数据源', icon: '⇆', group: '页面', action: () => go('/knowledge/datasources') },
  // 操作
  { id: 'a-create-db', label: '创建数据库', icon: '+', group: '操作', action: () => { go('/dashboard'); emit('close') } },
  { id: 'a-create-kb', label: '创建知识库', icon: '+', group: '操作', action: () => { go('/knowledge'); emit('close') } },
  { id: 'a-create-mem', label: '创建记忆库', icon: '+', group: '操作', action: () => { go('/memory'); emit('close') } },
  { id: 'a-create-nb', label: '创建 Notebook', icon: '+', group: '操作', action: () => { go('/datalake/notebook'); emit('close') } },
  { id: 'a-create-key', label: '创建 API Key', icon: '+', group: '操作', action: () => { go('/apikey'); emit('close') } },
]

function go(path: string) {
  router.push(path)
  emit('close')
}

const filtered = computed(() => {
  if (!query.value) return items
  const q = query.value.toLowerCase()
  return items.filter(i => i.label.toLowerCase().includes(q) || i.id.includes(q))
})

const groupedFiltered = computed(() => {
  const groups: { label: string; items: CmdItem[] }[] = []
  const pages = filtered.value.filter(i => i.group === '页面')
  const actions = filtered.value.filter(i => i.group === '操作')
  if (pages.length) groups.push({ label: '页面', items: pages })
  if (actions.length) groups.push({ label: '操作', items: actions })
  return groups
})

function flatIndex(gi: number, ii: number): number {
  let idx = 0
  for (let g = 0; g < gi; g++) idx += groupedFiltered.value[g].items.length
  return idx + ii
}

const totalItems = computed(() => filtered.value.length)

function moveDown() { activeIndex.value = (activeIndex.value + 1) % totalItems.value }
function moveUp() { activeIndex.value = (activeIndex.value - 1 + totalItems.value) % totalItems.value }
function execute() {
  const item = filtered.value[activeIndex.value]
  if (item) item.action()
}
function executeItem(item: CmdItem) { item.action() }

onMounted(() => { nextTick(() => inputRef.value?.focus()) })
</script>

<style scoped>
.cmd-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: flex-start; justify-content: center;
  padding-top: 120px; z-index: 9999;
}
.cmd-box {
  width: 520px; max-width: 90vw; background: #fff; border-radius: 12px;
  box-shadow: 0 16px 48px rgba(0,0,0,0.15); overflow: hidden;
}
.cmd-input-wrap {
  display: flex; align-items: center; gap: 10px;
  padding: 14px 16px; border-bottom: 1px solid #e8e4df;
}
.cmd-input {
  flex: 1; border: none; outline: none; font-size: 15px; color: #2c3e50;
  font-family: inherit; background: none;
}
.cmd-input::placeholder { color: #94a3b8; }
.cmd-esc { font-size: 10px; color: #94a3b8; background: #f5f3f0; padding: 2px 6px; border-radius: 4px; border: 1px solid #e8e4df; }
.cmd-results { max-height: 360px; overflow-y: auto; padding: 8px 0; }
.cmd-group-label { padding: 8px 16px 4px; font-size: 10px; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.5px; }
.cmd-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 16px; cursor: pointer; transition: background 0.08s;
}
.cmd-item:hover, .cmd-item.active { background: #f8f5f1; }
.cmd-item-icon { width: 20px; text-align: center; color: #94a3b8; font-size: 14px; }
.cmd-item-label { flex: 1; font-size: 14px; color: #2c3e50; }
.cmd-item-shortcut { font-size: 11px; color: #94a3b8; }
.cmd-empty { padding: 24px 16px; text-align: center; color: #94a3b8; font-size: 14px; }
</style>
```

- [ ] **Step 2: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/components/CommandPalette.vue
git commit -m "feat(console): add ⌘K command palette component"
```

---

## Task 3: ViewToggle + ResourceCard Components

**Files:**
- Create: `src/components/ViewToggle.vue`
- Create: `src/components/ResourceCard.vue`

- [ ] **Step 1: Create ViewToggle.vue**

Small toggle component with grid/list icons.

```vue
<template>
  <div class="view-toggle">
    <button class="vt-btn" :class="{ active: modelValue === 'card' }" @click="$emit('update:modelValue', 'card')" title="卡片视图">
      <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><rect x="1" y="1" width="6" height="6" rx="1"/><rect x="9" y="1" width="6" height="6" rx="1"/><rect x="1" y="9" width="6" height="6" rx="1"/><rect x="9" y="9" width="6" height="6" rx="1"/></svg>
    </button>
    <button class="vt-btn" :class="{ active: modelValue === 'table' }" @click="$emit('update:modelValue', 'table')" title="表格视图">
      <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><rect x="1" y="1" width="14" height="3" rx="0.5"/><rect x="1" y="6" width="14" height="3" rx="0.5"/><rect x="1" y="11" width="14" height="3" rx="0.5"/></svg>
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{ modelValue: 'card' | 'table' }>()
defineEmits<{ 'update:modelValue': [value: 'card' | 'table'] }>()
</script>

<style scoped>
.view-toggle { display: inline-flex; border: 1px solid #e8e4df; border-radius: 4px; overflow: hidden; }
.vt-btn {
  padding: 4px 8px; background: #fff; border: none; cursor: pointer;
  color: #94a3b8; transition: all 0.12s; display: flex; align-items: center;
}
.vt-btn:not(:last-child) { border-right: 1px solid #e8e4df; }
.vt-btn.active { background: #2a4d6a; color: #fff; }
.vt-btn:hover:not(.active) { background: #f8f5f1; }
</style>
```

- [ ] **Step 2: Create ResourceCard.vue**

Reusable card for any resource (database, kb, memory, dataset, notebook).

```vue
<template>
  <div class="resource-card" @click="$emit('click')">
    <div class="rc-top">
      <span class="rc-name">{{ name }}</span>
      <span class="rc-status" :class="statusClass">{{ statusText }}</span>
    </div>
    <div class="rc-meta">
      <slot name="meta">
        <span v-for="(item, i) in meta" :key="i">{{ item }}</span>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string
  status?: string
  statusText?: string
  meta?: string[]
}>()

defineEmits<{ click: [] }>()

const statusClass = computed(() => {
  const s = props.status?.toLowerCase()
  if (s === 'running' || s === 'ready' || s === 'active') return 'status-on'
  if (s === 'error' || s === 'failed') return 'status-error'
  return 'status-off'
})
</script>

<style scoped>
.resource-card {
  border: 1px solid #e8e4df; border-radius: 8px; padding: 14px 16px;
  cursor: pointer; transition: box-shadow 0.15s;
}
.resource-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.rc-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.rc-name { font-size: 13px; font-weight: 600; color: #2c3e50; }
.rc-status { font-size: 10px; padding: 2px 6px; border-radius: 3px; }
.status-on { background: #ecfdf5; color: #16a34a; }
.status-off { background: #f5f3f0; color: #94a3b8; }
.status-error { background: #fef2f2; color: #e6393d; }
.rc-meta { font-size: 11px; color: #94a3b8; display: flex; gap: 12px; }
</style>
```

- [ ] **Step 3: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/components/ViewToggle.vue src/components/ResourceCard.vue
git commit -m "feat(console): add ViewToggle and ResourceCard components"
```

---

## Task 4: DashboardView — Card/Table Toggle

**Files:**
- Modify: `src/views/dashboard/DashboardView.vue` (682 lines)

- [ ] **Step 1: Read current DashboardView.vue**

Read the full file to understand the template structure, data loading, and existing table.

- [ ] **Step 2: Add view toggle and card grid to template**

Add imports for ViewToggle and ResourceCard. Add `viewMode` ref. In the page-header-actions area, add ViewToggle before the create button. Below the status bar, add a card grid view that shows when `viewMode === 'card'`, and wrap the existing table in `v-if="viewMode === 'table'"`.

Card grid: 2-column grid using ResourceCard for each database. Include a "+ 创建数据库" dashed placeholder card at the end.

Keep existing table code intact, just wrap with `v-if="viewMode === 'table'"`.

- [ ] **Step 3: Add script imports and state**

```typescript
import ViewToggle from '../../components/ViewToggle.vue'
import ResourceCard from '../../components/ResourceCard.vue'

const viewMode = ref<'card' | 'table'>('card')
```

- [ ] **Step 4: Add card grid scoped CSS**

```css
.db-card-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-top: 16px;
}

.db-card-create {
  border: 1px dashed #d5d0ca;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.db-card-create:hover { border-color: #94a3b8; }

@media (max-width: 768px) {
  .db-card-grid { grid-template-columns: 1fr; }
}
```

- [ ] **Step 5: Run type check + visual verify**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/views/dashboard/DashboardView.vue
git commit -m "feat(console): database list card/table view toggle"
```

---

## Task 5: Other List Pages — Card/Table Toggle

**Files:**
- Modify: `src/views/knowledge/KnowledgeBases.vue`
- Modify: `src/views/memory/MemoryBases.vue`
- Modify: `src/views/datalake/DatalakeDatasets.vue`
- Modify: `src/views/datalake/DatalakeNotebookList.vue`
- Modify: `src/views/datalake/DatalakeJobs.vue`

- [ ] **Step 1: Read all 5 files**

Read each file to understand its template structure and data model.

- [ ] **Step 2: Add card/table toggle to KnowledgeBases.vue**

Same pattern as Task 4: import ViewToggle + ResourceCard, add `viewMode` ref, add ViewToggle in header, add card grid with `v-if="viewMode === 'card'"`, wrap existing table with `v-if="viewMode === 'table'"`.

Card meta for knowledge base: type tag, embedding model, document count.

- [ ] **Step 3: Add card/table toggle to MemoryBases.vue**

Card meta for memory base: type tag, scene, memory count, trait count.

- [ ] **Step 4: Add card/table toggle to DatalakeDatasets.vue**

Card meta: source type, row count, size.

- [ ] **Step 5: Add card/table toggle to DatalakeNotebookList.vue**

Card meta: image tag, last modified time.

- [ ] **Step 6: Add card/table toggle to DatalakeJobs.vue**

Card meta: type, status, duration, created time.

- [ ] **Step 7: Add card grid CSS to each file**

Same `.db-card-grid` pattern (copy to each file's scoped styles, adapting class names).

- [ ] **Step 8: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/views/knowledge/KnowledgeBases.vue src/views/memory/MemoryBases.vue \
  src/views/datalake/DatalakeDatasets.vue src/views/datalake/DatalakeNotebookList.vue \
  src/views/datalake/DatalakeJobs.vue
git commit -m "feat(console): card/table toggle on all list pages"
```

---

## Task 6: Absorb 反思洞察 into 记忆浏览

**Files:**
- Modify: `src/views/memory/MemoryBrowse.vue` (156 lines)
- Reference: `src/views/memory/MemoryTraits.vue` (153 lines)
- Reference: `src/components/memory/TraitCard.vue` (58 lines)

- [ ] **Step 1: Read MemoryBrowse.vue, MemoryTraits.vue, TraitCard.vue**

Understand what MemoryTraits displays and how TraitCard renders.

- [ ] **Step 2: Integrate trait display into MemoryBrowse**

Add a collapsible "反思洞察" section at the top of MemoryBrowse (or as a tab). Import TraitCard, fetch traits alongside memories, display them in a grid or list above the memory timeline.

Key: the traits should feel like an organic part of the browse experience, not a separate page.

- [ ] **Step 3: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/views/memory/MemoryBrowse.vue
git commit -m "feat(console): absorb 反思洞察 into 记忆浏览"
```

---

## Task 7: Absorb 数据源 into KnowledgeBaseDetail

**Files:**
- Modify: `src/views/knowledge/KnowledgeBaseDetail.vue` (945 lines)
- Reference: `src/views/knowledge/KnowledgeDataSources.vue` (290 lines)

- [ ] **Step 1: Read both files**

Understand KnowledgeDataSources structure and what data/APIs it uses.

- [ ] **Step 2: Add "数据源" tab to KnowledgeBaseDetail**

Add a new tab in the existing tab header. When selected, render the data sources list (ported from KnowledgeDataSources.vue) filtered to the current knowledge base.

- [ ] **Step 3: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/views/knowledge/KnowledgeBaseDetail.vue
git commit -m "feat(console): absorb 数据源 into knowledge base detail tab"
```

---

## Task 8: Absorb 消息日志 + 用量统计 into MemoryBaseDetail

**Files:**
- Modify: `src/views/memory/MemoryBaseDetail.vue` (358 lines)
- Reference: `src/views/memory/MemoryMessages.vue` (265 lines)
- Reference: `src/views/memory/MemoryStats.vue` (88 lines)

- [ ] **Step 1: Read all three files**

Understand Messages and Stats components' data and rendering.

- [ ] **Step 2: Add "消息日志" and "用量统计" tabs to MemoryBaseDetail**

Add two new tabs in the detail page. Port the content from MemoryMessages and MemoryStats, filtered to the current memory base.

- [ ] **Step 3: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/views/memory/MemoryBaseDetail.vue
git commit -m "feat(console): absorb messages + stats into memory base detail tabs"
```

---

## Task 9: Update Router — Remove Trimmed Routes

**Files:**
- Modify: `src/router/index.ts`

- [ ] **Step 1: Read router/index.ts**

Identify routes to remove or redirect.

- [ ] **Step 2: Remove standalone routes, add redirects**

Remove these routes from the ConsoleLayout children:
- `/knowledge/datasources` → redirect to `/knowledge` (users access via detail tab)
- `/memory/traits` → redirect to `/memory/browse`
- `/memory/messages` → redirect to `/memory`
- `/memory/stats` → redirect to `/memory`

Keep `/monitor`, `/logs`, `/backups`, `/import` routes as-is (accessible via ⌘K).

Add redirect entries:
```typescript
{ path: '/knowledge/datasources', redirect: '/knowledge' },
{ path: '/memory/traits', redirect: '/memory/browse' },
{ path: '/memory/messages', redirect: '/memory' },
{ path: '/memory/stats', redirect: '/memory' },
```

- [ ] **Step 3: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/router/index.ts
git commit -m "feat(console): redirect trimmed routes to new locations"
```

---

## Task 10: Final Polish — Remove Header Amber Border + Cleanup

**Files:**
- Modify: `src/layouts/ConsoleLayout.vue` (remove any lingering amber border)
- Modify: `src/style.css` (clean up unused icon-rail styles)

- [ ] **Step 1: Verify no amber border-bottom on header**

Search for `border-bottom.*c67d3a` in ConsoleLayout.vue. If found, remove it. The header should have no bottom border (navy→white natural transition per spec).

- [ ] **Step 2: Clean up style.css**

Remove any remaining `.icon-rail`, `.rail-icon`, `.rail-label`, `.rail-separator` styles. Remove unused `.header-grid-icon`, `.header-console-text`, `.header-region` styles if still present.

- [ ] **Step 3: Run full type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/layouts/ConsoleLayout.vue src/style.css
git commit -m "chore(console): cleanup unused styles from redesign"
```

---

## Execution Summary

| Task | Description | Est. Complexity |
|------|-------------|-----------------|
| 1 | ConsoleLayout — single sidebar + header | Large (rewrite) |
| 2 | CommandPalette component | Medium (new file) |
| 3 | ViewToggle + ResourceCard components | Small (new files) |
| 4 | DashboardView card/table toggle | Medium |
| 5 | 5 other list pages card/table toggle | Medium (repetitive) |
| 6 | 反思洞察 → 记忆浏览 | Small-Medium |
| 7 | 数据源 → KnowledgeBaseDetail tab | Medium |
| 8 | 消息日志+用量 → MemoryBaseDetail tabs | Medium |
| 9 | Router cleanup + redirects | Small |
| 10 | Final polish + cleanup | Small |

Tasks 1-3 are the foundation. Tasks 4-5 build on them. Tasks 6-8 are independent feature absorptions. Tasks 9-10 are cleanup.
