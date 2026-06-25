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
            <svg class="rail-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <template v-if="mode.icon === 'database'">
                <ellipse cx="12" cy="5" rx="7" ry="3" />
                <path d="M5 5v6c0 1.66 3.13 3 7 3s7-1.34 7-3V5" />
                <path d="M5 11v6c0 1.66 3.13 3 7 3s7-1.34 7-3v-6" />
              </template>
              <template v-else-if="mode.icon === 'lbfs'">
                <path d="M4 7.5A2.5 2.5 0 0 1 6.5 5H10l2 2h5.5A2.5 2.5 0 0 1 20 9.5v7A2.5 2.5 0 0 1 17.5 19h-11A2.5 2.5 0 0 1 4 16.5v-9Z" />
                <path d="M8 12h8" />
                <path d="M8 15h5" />
              </template>
              <template v-else>
                <circle cx="12" cy="12" r="3" />
                <path d="M19 12a7 7 0 0 0-.08-1l2.08-1.6-2-3.46-2.44.98a7.5 7.5 0 0 0-1.72-1L14.5 3h-5l-.34 2.92a7.5 7.5 0 0 0-1.72 1L5 5.94l-2 3.46L5.08 11A7 7 0 0 0 5 12a7 7 0 0 0 .08 1L3 14.6l2 3.46 2.44-.98a7.5 7.5 0 0 0 1.72 1L9.5 21h5l.34-2.92a7.5 7.5 0 0 0 1.72-1l2.44.98 2-3.46L18.92 13c.05-.33.08-.66.08-1Z" />
              </template>
            </svg>
            <span class="rail-label">{{ mode.shortLabel }}</span>
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
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
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
  id: 'data' | 'lbfs' | 'ops'
  label: string
  shortLabel: string
  icon: 'database' | 'lbfs' | 'ops'
  description: string
  to: string
  match: string[]
  groups: NavGroup[]
}

const workspaceModes: WorkspaceMode[] = [
  {
    id: 'data',
    label: '数据',
    shortLabel: '数据',
    icon: 'database',
    description: 'Lakebase 数据库工作台',
    to: '/dashboard',
    match: ['/dashboard', '/databases', '/timetravel', '/sql', '/import', '/cdf'],
    groups: [
      {
        title: '数据目录',
        items: [
          { label: '数据总览', to: '/dashboard', icon: '▣' },
        ],
      },
      {
        title: '数据库',
        items: [
          { label: '数据库', to: '/dashboard', icon: '▣' },
          { label: '时间旅行', to: '/timetravel', icon: '◷' },
          { label: 'SQL 编辑器', to: '/sql', icon: '<>' },
          { label: '数据迁移', to: '/import', icon: '⇩' },
          { label: 'CDF', to: '/cdf', icon: '~' },
        ],
      },
    ],
  },
  {
    id: 'lbfs',
    label: 'FS',
    shortLabel: 'FS',
    icon: 'lbfs',
    description: 'LakebaseFS 文件与目录',
    to: '/lbfs',
    match: ['/lbfs'],
    groups: [
      {
        title: '文件系统',
        items: [
          { label: '浏览文件', to: '/lbfs', icon: '□' },
        ],
      },
    ],
  },
  {
    id: 'ops',
    label: '运维与账户',
    shortLabel: '运维',
    icon: 'ops',
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
  width: 72px;
  flex-shrink: 0;
  background: #f5f6f8;
  border-right: 1px solid #e3e7ec;
  padding: 8px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.rail-item {
  width: 100%;
  min-height: 72px;
  padding: 9px 4px 8px;
  border: 1px solid transparent;
  border-radius: 0;
  color: #657386;
  text-decoration: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0;
  text-align: center;
  transition: background 160ms ease-out, color 160ms ease-out, border-color 160ms ease-out, box-shadow 160ms ease-out;
}

.rail-icon {
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
}

.rail-label {
  display: block;
  max-width: 56px;
  overflow-wrap: anywhere;
}

.rail-item:hover {
  color: #26394d;
  background: #fff;
}

.rail-item.active {
  color: #21344a;
  background: #fff;
  border-color: transparent;
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
    width: 72px;
  }

  .rail-item {
    min-height: 70px;
    font-size: 13px;
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

</style>
