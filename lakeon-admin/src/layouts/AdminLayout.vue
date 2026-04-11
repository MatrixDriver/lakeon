<template>
  <div class="console-layout" @keydown.meta.k.prevent="cmdOpen = true" @keydown.ctrl.k.prevent="cmdOpen = true">
    <!-- Top Navigation Bar -->
    <header class="console-header">
      <div class="header-left">
        <button class="mobile-menu-btn" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z"/>
          </svg>
        </button>
        <router-link to="/databases" class="logo-brand">DBay<span class="logo-tagline">SRE</span></router-link>
      </div>
      <div class="header-right">
        <button class="cmd-k-btn" @click="cmdOpen = true">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <span class="cmd-k-text">&#8984;K</span>
        </button>
        <span class="header-divider-small"></span>
        <div class="header-user">
          <span class="user-avatar">A</span>
          <span class="header-username">Admin</span>
        </div>
        <button class="header-nav-btn" @click="handleLogout">退出</button>
      </div>
    </header>

    <div class="console-body">
      <!-- Mobile sidebar overlay -->
      <div v-if="sidebarOpen" class="sidebar-overlay" @click="sidebarOpen = false"></div>

      <!-- Left Sidebar -->
      <aside class="console-sidebar" :class="{ open: sidebarOpen }">
        <div class="sidebar-title">
          <span>DBay 运维</span>
          <svg class="sidebar-search-icon" viewBox="0 0 16 16" width="16" height="16" fill="currentColor">
            <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85zm-5.242.156a5 5 0 1 1 0-10 5 5 0 0 1 0 10z"/>
          </svg>
        </div>
        <nav class="sidebar-nav">
          <div class="nav-group">
            <div class="nav-group-title">数据服务</div>
            <router-link to="/databases" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
              <span>数据库</span>
            </router-link>
            <router-link to="/knowledge" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
              <span>知识库</span>
            </router-link>
            <router-link to="/wiki-agent" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a7 7 0 0 1 4.9 11.9L21 18l-2.9 2.9-4.1-4.1A7 7 0 1 1 12 2z"/><circle cx="12" cy="9" r="2"/><path d="M9 14h6"/></svg>
              <span>Wiki Agent</span>
            </router-link>
            <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/><line x1="9" y1="21" x2="15" y2="21"/></svg>
              <span>记忆库</span>
            </router-link>
            <router-link to="/datalake" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
              <span>数据湖</span>
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">租户</div>
            <router-link to="/tenants" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
              <span>租户管理</span>
            </router-link>
            <router-link to="/invite-codes" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              <span>邀请码</span>
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">基础设施</div>
            <router-link to="/infra" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>
              <span>基础设施</span>
            </router-link>
            <router-link to="/cloud" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/></svg>
              <span>华为云控制台</span>
            </router-link>
            <router-link to="/cost" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
              <span>成本监控</span>
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">日志审计</div>
            <router-link to="/operations" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
              <span>操作日志</span>
            </router-link>
            <router-link to="/audit" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
              <span>审计日志</span>
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">运行时监控</div>
            <router-link to="/system" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
              <span>组件健康</span>
            </router-link>
            <router-link to="/metrics" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
              <span>应用指标</span>
            </router-link>
            <router-link to="/logs" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
              <span>日志诊断</span>
            </router-link>
            <router-link to="/alerts" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
              <span>告警管理</span>
            </router-link>
          </div>
        </nav>
        <div class="sidebar-collapse">
          <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
            <path d="M10 12l-4-4 4-4"/>
          </svg>
        </div>
      </aside>

      <!-- Main Content -->
      <main class="console-main">
        <router-view />
      </main>

      <!-- AI Chat Panel -->
      <AiChatPanel ref="aiChatRef" />
    </div>

    <!-- Command Palette -->
    <CommandPalette v-if="cmdOpen" @close="cmdOpen = false" />
  </div>
</template>

<script setup lang="ts">
import { ref, provide } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminAuthStore } from '../stores/auth'
import AiChatPanel from '../components/AiChatPanel.vue'
import CommandPalette from '../components/CommandPalette.vue'

const router = useRouter()
const authStore = useAdminAuthStore()
const sidebarOpen = ref(false)
const cmdOpen = ref(false)
const aiChatRef = ref<InstanceType<typeof AiChatPanel>>()

function handleLogout() {
  authStore.logout()
  router.push('/login')
}

function openAiDiagnose(resourceType: string, resourceId: string, question: string) {
  aiChatRef.value?.openWithContext(resourceType, resourceId, question)
}

provide('openAiDiagnose', openAiDiagnose)
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
  font-size: 22px;
  font-weight: 500;
  letter-spacing: -0.01em;
  text-decoration: none;
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  line-height: 1;
}

.logo-tagline {
  font-family: var(--font-sans);
  color: rgb(255 255 255 / 0.5);
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.14em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.cmd-k-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  background: rgb(255 255 255 / 0.06);
  border: 1px solid rgb(255 255 255 / 0.14);
  border-radius: 4px;
  color: rgb(255 255 255 / 0.55);
  padding: 4px var(--space-md);
  font-family: var(--font-sans);
  font-size: 12px;
  cursor: pointer;
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
  font-family: var(--font-sans);
}

.header-username {
  color: rgb(255 255 255 / 0.88);
  font-size: 13px;
  font-weight: 500;
}

.header-nav-btn {
  background: transparent;
  border: none;
  padding: 0;
  color: rgb(255 255 255 / 0.7);
  font-family: var(--font-sans);
  font-size: 13px;
  cursor: pointer;
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
.console-sidebar {
  width: 232px;
  background-color: #fff;
  border-right: 1px solid var(--c-border);
  flex-shrink: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.sidebar-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-xl) var(--space-xl) var(--space-lg);
  border-bottom: 1px solid var(--c-border-light);
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 500;
  color: var(--c-primary);
  line-height: 1.2;
  letter-spacing: -0.005em;
}

.sidebar-search-icon {
  color: var(--c-text-3);
  cursor: pointer;
  flex-shrink: 0;
  transition: color 160ms ease-out;
}

.sidebar-search-icon:hover {
  color: var(--c-accent-text);
}

.sidebar-nav {
  flex: 1;
  padding: var(--space-sm) 0 var(--space-lg);
}

.nav-group {
  padding: var(--space-sm) 0 var(--space-md);
}

.nav-group + .nav-group {
  border-top: 1px solid var(--c-border-light);
  margin-top: var(--space-xs);
  padding-top: var(--space-md);
}

.nav-group-title {
  padding: var(--space-md) var(--space-xl) var(--space-xs);
  font-family: var(--font-sans);
  font-size: 10px;
  font-weight: 500;
  color: var(--c-text-3);
  line-height: 1.4;
  text-transform: uppercase;
  letter-spacing: 0.1em;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: 0 var(--space-xl);
  height: 34px;
  color: var(--c-text-2);
  text-decoration: none;
  font-size: 13px;
  font-weight: 400;
  transition: background 160ms ease-out, color 160ms ease-out;
  position: relative;
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

.nav-item.active {
  color: var(--c-primary);
  font-weight: 600;
  background-color: color-mix(in oklch, var(--c-accent) 8%, #fff);
}

.nav-item.active:hover {
  background-color: color-mix(in oklch, var(--c-accent) 12%, #fff);
}

.nav-item.active svg {
  opacity: 1;
  color: var(--c-accent);
}

.sidebar-collapse {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: var(--space-md) var(--space-md);
  color: var(--c-text-3);
  cursor: pointer;
  border-top: 1px solid var(--c-border-light);
}

.sidebar-collapse:hover {
  color: var(--c-accent-text);
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

  .cmd-k-btn {
    display: none;
  }

  .header-right {
    gap: 12px;
  }

  .logo-brand {
    margin-right: 0;
  }

  .console-sidebar {
    position: fixed;
    top: 48px;
    left: 0;
    bottom: 0;
    z-index: 200;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }

  .console-sidebar.open {
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

  .sidebar-collapse {
    display: none;
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
