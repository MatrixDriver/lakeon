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

      <!-- Single Sidebar -->
      <aside class="sidebar" :class="{ open: sidebarOpen }">
        <nav class="sidebar-nav">
          <!-- 数据库 -->
          <div class="nav-group">
            <div class="nav-group-title">数据库</div>
            <router-link to="/dashboard" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v6c0 1.66 3.58 3 8 3s8-1.34 8-3V6"/><path d="M4 12v6c0 1.66 3.58 3 8 3s8-1.34 8-3v-6"/></svg>
              数据库
            </router-link>
            <router-link to="/timetravel" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
              时间旅行
            </router-link>
            <router-link to="/sql" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
              SQL 编辑器
            </router-link>
            <router-link to="/import" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
              数据迁移
            </router-link>
          </div>

          <!-- 知识库 -->
          <div class="nav-group">
            <div class="nav-group-title">知识库</div>
            <router-link to="/knowledge" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
              知识库
            </router-link>
            <router-link to="/knowledge/search" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
              原文搜索
            </router-link>
            <router-link to="/knowledge/chat" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
              Wiki 对话
            </router-link>
          </div>

          <!-- 记忆库 -->
          <div class="nav-group">
            <div class="nav-group-title">记忆库</div>
            <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/><line x1="10" y1="21" x2="14" y2="21"/></svg>
              记忆库
            </router-link>
            <router-link to="/memory/browse" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>
              记忆浏览
            </router-link>
          </div>

          <!-- 数据源 -->
          <div class="nav-group">
            <div class="nav-group-title">数据源</div>
            <router-link to="/datalake/connections" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
              OBS 连接
            </router-link>
          </div>

          <!-- 数据湖 -->
          <div class="nav-group">
            <div class="nav-group-title">数据湖</div>
            <router-link to="/datalake/pipelines" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="8" cy="6" r="1.5" fill="currentColor"/><circle cx="14" cy="12" r="1.5" fill="currentColor"/><circle cx="10" cy="18" r="1.5" fill="currentColor"/></svg>
              生产线
            </router-link>
            <router-link to="/datalake/components" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>
              组件库
            </router-link>
            <router-link to="/datalake/datasets" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              数据集
            </router-link>
            <router-link to="/datalake/jobs" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
              作业管理
            </router-link>
            <router-link to="/datalake/notebook" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
              Notebook
            </router-link>
          </div>

          <!-- Separator -->
          <div class="nav-separator"></div>

          <!-- Settings & Monitoring -->
          <div class="nav-group nav-group-bottom">
            <router-link to="/monitor" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
              监控面板
            </router-link>
            <router-link to="/logs" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
              日志管理
            </router-link>
            <router-link to="/usage" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20V10"/><path d="M18 20V4"/><path d="M6 20v-4"/></svg>
              资源用量
            </router-link>
            <router-link to="/recycle-bin" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              回收站
            </router-link>
            <router-link to="/apikey" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.78 7.78 5.5 5.5 0 0 1 7.78-7.78zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>
              API Key
            </router-link>
            <router-link to="/account" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
              账户
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
  width: 220px;
  background-color: #fff;
  border-right: 1px solid var(--c-border);
  flex-shrink: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.sidebar-nav {
  flex: 1;
  padding: var(--space-md) 0 var(--space-lg);
}

.nav-group {
  padding: var(--space-xs) 0 var(--space-sm);
}

.nav-group + .nav-group {
  border-top: 1px solid var(--c-border-light);
  margin-top: var(--space-xs);
  padding-top: var(--space-sm);
}

.nav-group-title {
  padding: var(--space-sm) var(--space-xl) var(--space-xs);
  font-family: var(--font-sans);
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  color: var(--c-text-3);
  letter-spacing: 0.1em;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  height: 32px;
  padding: 0 var(--space-xl);
  color: var(--c-text-2);
  text-decoration: none;
  font-family: var(--font-sans);
  font-size: 13px;
  transition: background 160ms ease-out, color 160ms ease-out;
}

.nav-item svg {
  flex-shrink: 0;
  opacity: 0.6;
  transition: opacity 160ms ease-out;
}

.nav-item:hover {
  color: var(--c-text);
  background-color: var(--c-hover);
}

.nav-item:hover svg {
  opacity: 0.85;
}

.nav-item.active,
.nav-item.router-link-active {
  color: var(--c-primary);
  font-weight: 600;
  background-color: color-mix(in oklch, var(--c-accent) 8%, #fff);
}

.nav-item.active:hover,
.nav-item.router-link-active:hover {
  background-color: color-mix(in oklch, var(--c-accent) 12%, #fff);
}

.nav-item.active svg,
.nav-item.router-link-active svg {
  opacity: 1;
  color: var(--c-accent);
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
    top: 48px;
    left: 0;
    bottom: 0;
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
    inset: 48px 0 0 0;
    background: rgba(0, 0, 0, 0.3);
    z-index: 199;
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
