<template>
  <div class="console-layout" @keydown.meta.k.prevent="cmdOpen = true" @keydown.ctrl.k.prevent="cmdOpen = true">
    <CommandPalette v-if="cmdOpen" @close="cmdOpen = false" />

    <!-- Top Navigation Bar -->
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
        <button class="cmd-k-btn" @click="cmdOpen = true">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <span class="cmd-k-text">&#8984;K</span>
        </button>
        <router-link to="/docs" class="header-nav-link">文档</router-link>
        <span class="header-divider-small"></span>
        <div class="header-user">
          <span class="user-avatar">{{ (authStore.tenantName || 'U').charAt(0).toUpperCase() }}</span>
          <span class="header-username">{{ authStore.tenantName || 'Tenant' }}</span>
        </div>
        <button class="header-nav-btn" @click="handleLogout">退出</button>
      </div>
    </header>

    <!-- Trial Banner -->
    <div v-if="authStore.isTrial" class="trial-banner">
      <div class="trial-banner-content">
        <span class="trial-banner-text">
          体验模式 — 只读演示环境<template v-if="trialTimeLeft">，剩余 {{ trialTimeLeft }}</template>
        </span>
        <router-link to="/login" class="trial-banner-cta" @click="authStore.logout()">
          注册账号，解锁全部功能
        </router-link>
      </div>
    </div>

    <div class="console-body">
      <!-- Mobile sidebar overlay -->
      <div v-if="sidebarOpen" class="sidebar-overlay" @click="sidebarOpen = false"></div>

      <!-- Workspace navigation -->
      <aside class="sidebar" :class="{ open: sidebarOpen }">
        <nav class="workspace-rail" aria-label="一级工作区">
          <router-link
            v-for="mode in workspaceModes"
            :key="mode.id"
            :to="mode.to"
            class="rail-item"
            :class="{ active: activeMode.id === mode.id }"
            :aria-current="activeMode.id === mode.id ? 'page' : undefined"
            @click="sidebarOpen = false"
          >
            <span>{{ mode.shortLabel }}</span>
          </router-link>
        </nav>

        <nav class="sidebar-nav" :aria-label="activeMode.label">
          <div class="side-title">
            <span>{{ activeMode.label }}</span>
            <small>{{ activeMode.description }}</small>
          </div>

          <div v-for="group in activeMode.groups" :key="group.title" class="nav-group">
            <div v-if="group.title" class="nav-group-title">{{ group.title }}</div>
            <router-link
              v-for="item in group.items"
              :key="item.label"
              :to="item.to"
              custom
              v-slot="{ href, navigate, isActive }"
            >
              <a
                :href="href"
                class="nav-item"
                :class="{ active: isNavItemActive(item, isActive), 'nav-sub-item': item.sub }"
                @click="(event) => handleNavClick(item, navigate, event)"
              >
                <span class="nav-marker" aria-hidden="true"></span>
                {{ item.label }}
              </a>
            </router-link>
          </div>
        </nav>
      </aside>

      <!-- Main Content -->
      <main class="console-main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { tenantApi } from '../api/tenant'
import CommandPalette from '../components/CommandPalette.vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const sidebarOpen = ref(false)
const cmdOpen = ref(false)

type NavItem = {
  label: string
  to: string
  icon?: string
  sub?: boolean
}

type NavGroup = {
  title?: string
  items: NavItem[]
}

type WorkspaceMode = {
  id: 'database' | 'agent' | 'knowledge' | 'memory' | 'ops'
  label: string
  shortLabel: string
  description: string
  to: string
  match: string[]
  groups: NavGroup[]
}

const workspaceModes: WorkspaceMode[] = [
  {
    id: 'database',
    label: '数据库工作台',
    shortLabel: '数据',
    description: '实例、分支、SQL 与迁移',
    to: '/dashboard',
    match: ['/dashboard', '/databases', '/timetravel', '/sql', '/import', '/connectors', '/datalake'],
    groups: [
      {
        title: '数据库',
        items: [
          { label: '数据库', to: '/dashboard', icon: '▣' },
          { label: '时间旅行', to: '/timetravel', icon: '◷' },
          { label: 'SQL 编辑器', to: '/sql', icon: '<>' },
          { label: '数据迁移', to: '/import', icon: '⇩' },
        ],
      },
      {
        title: '数据源',
        items: [
          { label: '连接器', to: '/connectors', icon: '◇' },
        ],
      },
      {
        title: '数据湖',
        items: [
          { label: '生产线', to: '/datalake/pipelines', icon: '≡' },
          { label: '组件库', to: '/datalake/components', icon: '▦' },
          { label: '数据集', to: '/datalake/datasets', icon: '▤' },
          { label: '作业管理', to: '/datalake/jobs', icon: '▶' },
          { label: 'Notebook', to: '/datalake/notebook', icon: '▧' },
        ],
      },
    ],
  },
  {
    id: 'agent',
    label: '智能体数据平台',
    shortLabel: '智能体',
    description: '任务、证据与治理审计',
    to: '/agent-state',
    match: ['/agent-state', '/agentfs'],
    groups: [
      {
        title: '工作状态',
        items: [
          { label: '工作台总览', to: '/agent-state', icon: '▢' },
          { label: '任务运行', to: '/agent-state#tasks', sub: true },
          { label: '任务概览', to: '/agent-state#detail', sub: true },
          { label: '执行阶段', to: '/agent-state#stages', sub: true },
          { label: '证据包', to: '/agent-state#evidence', sub: true },
          { label: '分支图', to: '/agent-state#branches', sub: true },
          { label: '治理审计', to: '/agent-state#audit', sub: true },
          { label: '运行输出', to: '/agent-state#outputs', sub: true },
        ],
      },
      {
        title: '智能体文件',
        items: [
          { label: '浏览文件', to: '/agentfs', icon: '□' },
        ],
      },
    ],
  },
  {
    id: 'knowledge',
    label: '知识库',
    shortLabel: '知识',
    description: '文档、检索与对话',
    to: '/knowledge',
    match: ['/knowledge'],
    groups: [
      {
        title: '知识库',
        items: [
          { label: '知识库', to: '/knowledge', icon: '▤' },
          { label: '原文搜索', to: '/knowledge/search', icon: '⌕' },
          { label: 'Wiki 对话', to: '/knowledge/chat', icon: '□' },
        ],
      },
    ],
  },
  {
    id: 'memory',
    label: '记忆库',
    shortLabel: '记忆',
    description: '记忆库与结构化浏览',
    to: '/memory',
    match: ['/memory'],
    groups: [
      {
        title: '记忆库',
        items: [
          { label: '记忆库', to: '/memory', icon: '◎' },
          { label: '记忆浏览', to: '/memory/browse', icon: '▦' },
        ],
      },
    ],
  },
  {
    id: 'ops',
    label: '运维与账户',
    shortLabel: '运维',
    description: '监控、权限与账户',
    to: '/monitor',
    match: ['/monitor', '/logs', '/usage', '/recycle-bin', '/apikey', '/account'],
    groups: [
      {
        title: '运维',
        items: [
          { label: '监控面板', to: '/monitor', icon: '◫' },
          { label: '日志管理', to: '/logs', icon: '≡' },
          { label: '资源用量', to: '/usage', icon: '▥' },
          { label: '回收站', to: '/recycle-bin', icon: '⌫' },
        ],
      },
      {
        title: '账户',
        items: [
          { label: 'API Key', to: '/apikey', icon: '⚿' },
          { label: '账户', to: '/account', icon: '◉' },
          { label: '数据活动', to: '/account/activity', icon: '◷' },
        ],
      },
    ],
  },
]

const defaultWorkspaceMode = workspaceModes[0] as WorkspaceMode

const activeMode = computed<WorkspaceMode>(() => {
  const path = route.path
  return workspaceModes.find((mode) => mode.match.some((prefix) => path.startsWith(prefix)))
    || defaultWorkspaceMode
})

function handleLogout() {
  authStore.logout()
  router.push('/')
}

function isNavItemActive(item: NavItem, isActive: boolean) {
  if (item.to.includes('#')) {
    return `${route.path}${route.hash}` === item.to
  }
  return isActive && !item.sub
}

function handleNavClick(item: NavItem, navigate: (event?: MouseEvent) => void, event: MouseEvent) {
  navigate(event)
  sidebarOpen.value = false
  scrollToHashTarget(item.to)
}

function scrollToHashTarget(to: string) {
  const hash = to.includes('#') ? to.slice(to.indexOf('#')) : ''
  if (!hash) return

  window.setTimeout(() => {
    const target = document.getElementById(decodeURIComponent(hash.slice(1)))
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, 80)
}

// Trial countdown
const trialTimeLeft = ref('')
let trialTimer: ReturnType<typeof setInterval> | null = null

function updateTrialCountdown() {
  if (!authStore.isTrial || !authStore.trialExpiresAt) {
    trialTimeLeft.value = ''
    return
  }
  const diff = new Date(authStore.trialExpiresAt).getTime() - Date.now()
  if (diff <= 0) {
    trialTimeLeft.value = '已过期'
    return
  }
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  trialTimeLeft.value = `${h}h ${m}m`
}

onMounted(async () => {
  // Refresh trial state from server
  if (authStore.apiKey) {
    try {
      const res = await tenantApi.me()
      const t = res.data
      if (t.trial) {
        authStore.setTrialState(true, t.expires_at)
      } else {
        authStore.setTrialState(false)
      }
    } catch {
      // ignore — will use cached state
    }
  }

  // Trial countdown timer
  if (authStore.isTrial) {
    updateTrialCountdown()
    trialTimer = setInterval(updateTrialCountdown, 60000)
  }
})

onUnmounted(() => {
  if (trialTimer) clearInterval(trialTimer)
})
</script>

<style scoped>
.console-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

/* ══════════════════════════════════════════
   Top header
   ══════════════════════════════════════════ */
.console-header {
  height: 52px;
  background-color: var(--c-primary);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-xl);
  flex-shrink: 0;
  z-index: 100;
  border-bottom: 1px solid color-mix(in oklch, var(--c-primary) 80%, black);
}

.header-left {
  display: flex;
  align-items: center;
}

.logo-brand {
  font-family: var(--font-display);
  color: var(--c-accent);
  text-decoration: none;
  font-size: 22px;
  font-weight: 500;
  letter-spacing: -0.01em;
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  line-height: 1;
}

.logo-tagline {
  font-family: var(--font-sans);
  font-size: 10px;
  font-weight: 500;
  color: rgb(255 255 255 / 0.5);
  text-transform: uppercase;
  letter-spacing: 0.14em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

/* Command palette button */
.cmd-k-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  background: rgb(255 255 255 / 0.06);
  border: 1px solid rgb(255 255 255 / 0.14);
  border-radius: 4px;
  color: rgb(255 255 255 / 0.55);
  padding: 4px var(--space-md);
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 12px;
  transition: background 160ms ease-out, color 160ms ease-out;
  height: 28px;
}

.cmd-k-btn:hover {
  background: rgb(255 255 255 / 0.12);
  color: rgb(255 255 255 / 0.9);
}

.cmd-k-text {
  font-family: var(--font-mono);
  font-size: 11px;
  opacity: 0.75;
  letter-spacing: 0.02em;
}

.header-nav-link {
  color: rgb(255 255 255 / 0.7);
  font-family: var(--font-sans);
  font-size: 13px;
  text-decoration: none;
  transition: color 160ms ease-out;
}

.header-nav-link:hover {
  color: #fff;
}

.header-divider-small {
  width: 1px;
  height: 16px;
  background: rgb(255 255 255 / 0.16);
}

.header-user {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.user-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--c-accent);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-family: var(--font-sans);
}

.header-username {
  color: rgb(255 255 255 / 0.88);
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
}

.header-nav-btn {
  background: transparent;
  border: none;
  color: rgb(255 255 255 / 0.7);
  font-family: var(--font-sans);
  font-size: 13px;
  cursor: pointer;
  padding: 0;
  transition: color 160ms ease-out;
}

.header-nav-btn:hover {
  color: #fff;
}

/* ══════════════════════════════════════════
   Body layout
   ══════════════════════════════════════════ */
.console-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* ══════════════════════════════════════════
   Sidebar
   ══════════════════════════════════════════ */
.sidebar {
  width: 236px;
  background-color: #fff;
  border-right: 1px solid var(--c-border);
  flex-shrink: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: row;
}

.workspace-rail {
  width: 64px;
  flex-shrink: 0;
  background: #f7f8fa;
  border-right: 1px solid #e3e7ec;
  padding: 12px 7px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.rail-item {
  width: 100%;
  min-height: 36px;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: 5px;
  color: #718095;
  text-decoration: none;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0;
  white-space: nowrap;
  transition: background 160ms ease-out, color 160ms ease-out, border-color 160ms ease-out, box-shadow 160ms ease-out;
}

.rail-item:hover {
  color: #26394d;
  background: #fff;
  border-color: #e0e5eb;
}

.rail-item.active {
  color: var(--c-primary);
  background: #fff;
  border-color: color-mix(in oklch, var(--c-accent) 28%, #dfe5ec);
  font-weight: 700;
  box-shadow: inset 3px 0 0 var(--c-accent);
}

.sidebar-nav {
  flex: 1;
  min-width: 0;
  background: #fff;
  padding: 0 0 var(--space-lg);
}

.side-title {
  min-height: 68px;
  padding: 0 18px;
  border-bottom: 1px solid #edf0f3;
  color: #23364a;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 4px;
  font-family: var(--font-sans);
  font-size: 16px;
  font-weight: 700;
}

.side-title small {
  color: #8793a5;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.25;
  letter-spacing: 0;
}

.nav-group {
  padding: 12px 0;
}

.nav-group + .nav-group {
  border-top: 1px solid var(--c-border-light);
  margin-top: 0;
  padding-top: var(--space-sm);
}

.nav-group-title {
  padding: 0 18px 8px;
  font-family: var(--font-sans);
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  color: #9aa5b4;
  letter-spacing: 0.12em;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 0 18px;
  color: #516174;
  text-decoration: none;
  font-family: var(--font-sans);
  font-size: 13px;
  transition: background 160ms ease-out, color 160ms ease-out;
}

.nav-marker {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #c3cbd6;
  flex-shrink: 0;
  transition: background 160ms ease-out, transform 160ms ease-out;
}

.nav-item:hover {
  color: #23364a;
  background-color: #f7f9fb;
}

.nav-item:hover .nav-marker {
  background: #8d9aad;
}

.nav-item.active,
.nav-item.router-link-active {
  color: var(--c-primary);
  font-weight: 600;
  background-color: color-mix(in oklch, var(--c-accent) 6%, #fff);
  box-shadow: inset 3px 0 0 var(--c-accent);
}

.nav-item.active:hover,
.nav-item.router-link-active:hover {
  background-color: color-mix(in oklch, var(--c-accent) 9%, #fff);
}

.nav-item.active .nav-marker,
.nav-item.router-link-active .nav-marker {
  background: var(--c-accent);
  transform: scale(1.1);
}

.nav-sub-item {
  padding-left: 32px;
  color: #617187;
  font-size: 12px;
}

.nav-sub-item .nav-marker {
  width: 4px;
  height: 4px;
  background: #d3d9e1;
}

.nav-separator {
  height: 1px;
  background-color: var(--c-border-light);
  margin: var(--space-xs) var(--space-xl);
}

.nav-group-bottom {
  padding-bottom: var(--space-sm);
}

.nav-group-bottom + .nav-group,
.nav-separator + .nav-group {
  border-top: none;
  margin-top: 0;
  padding-top: var(--space-xs);
}

/* ══════════════════════════════════════════
   Main area — warm background
   ══════════════════════════════════════════ */
.console-main {
  flex: 1;
  background-color: var(--c-bg-alt);
  overflow-y: auto;
  padding: var(--space-2xl) var(--space-2xl) var(--space-4xl) var(--space-2xl);
}

/* Mobile hamburger button - hidden on desktop */
.mobile-menu-btn {
  display: none;
  align-items: center;
  justify-content: center;
  background: none;
  border: none;
  color: rgba(255, 255, 255, 0.75);
  cursor: pointer;
  padding: 4px;
  margin-right: 8px;
}

.mobile-menu-btn:hover {
  color: #fff;
}

/* Mobile sidebar overlay */
.sidebar-overlay {
  display: none;
}

/* ============================
   Mobile Responsive (< 768px)
   ============================ */
@media (max-width: 768px) {
  .mobile-menu-btn {
    display: inline-flex;
  }

  .header-nav-link,
  .cmd-k-btn {
    display: none !important;
  }

  .header-divider-small {
    display: none;
  }

  .header-right {
    gap: 12px;
  }

  .logo-brand {
    margin-right: 0;
  }

  .sidebar {
    position: fixed;
    top: 52px;
    left: 0;
    bottom: 0;
    width: min(320px, 86vw);
    z-index: 200;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }

  .sidebar.open {
    transform: translateX(0);
    box-shadow: 4px 0 16px rgba(0, 0, 0, 0.15);
  }

  .sidebar-overlay {
    display: block;
    position: fixed;
    inset: 52px 0 0 0;
    background: rgba(0, 0, 0, 0.3);
    z-index: 199;
  }

  .workspace-rail {
    width: 68px;
  }

  .rail-item {
    min-height: 36px;
  }

  .console-main {
    padding: 16px;
  }
}

/* Small phones */
@media (max-width: 480px) {
  .header-username {
    max-width: 80px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .console-main {
    padding: 12px;
  }
}

.trial-banner {
  background: color-mix(in oklch, var(--c-accent) 7%, #fff);
  border-bottom: 1px solid color-mix(in oklch, var(--c-accent) 25%, var(--c-border-light));
  padding: 6px var(--space-xl);
  text-align: center;
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-accent-text);
  z-index: 100;
}
.trial-banner-content {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-lg);
}
.trial-banner-text {
  letter-spacing: 0.02em;
}
.trial-banner-cta {
  color: var(--c-accent-text);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
  white-space: nowrap;
  transition: color 160ms ease-out;
}
.trial-banner-cta:hover {
  color: var(--c-accent-hover);
}
</style>
