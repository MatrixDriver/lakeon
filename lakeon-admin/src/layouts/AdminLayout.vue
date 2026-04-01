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
            <div class="nav-group-title">租户</div>
            <router-link to="/tenants" class="nav-item" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
              <span>租户管理</span>
            </router-link>
          </div>
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
              <span>日志查看</span>
            </router-link>
            <router-link to="/logs/search" class="nav-item nav-subitem" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
              <span>日志搜索</span>
            </router-link>
            <router-link to="/logs/trace" class="nav-item nav-subitem" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
              <span>调用链追踪</span>
            </router-link>
            <router-link to="/logs/errors" class="nav-item nav-subitem" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              <span>错误概览</span>
            </router-link>
            <router-link to="/logs/stats" class="nav-item nav-subitem" active-class="active" @click="sidebarOpen = false">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>
              <span>日志统计</span>
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

.console-header {
  height: 48px;
  background-color: #2a4d6a;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  flex-shrink: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
}

.logo-brand {
  color: #c67d3a;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
  text-decoration: none;
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.logo-tagline {
  color: rgba(255, 255, 255, 0.4);
  font-size: 12px;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.cmd-k-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 6px;
  color: rgba(255, 255, 255, 0.5);
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
  height: 28px;
}

.cmd-k-btn:hover {
  background: rgba(255, 255, 255, 0.14);
  color: rgba(255, 255, 255, 0.8);
}

.cmd-k-text {
  font-size: 11px;
  opacity: 0.7;
}

.header-divider-small {
  width: 1px;
  height: 14px;
  background: rgba(255, 255, 255, 0.15);
}

.header-user {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #c67d3a;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-username {
  color: rgba(255, 255, 255, 0.9);
  font-size: 14px;
}

.header-nav-btn {
  background: transparent;
  border: none;
  padding: 0;
  color: rgba(255, 255, 255, 0.75);
  font-size: 14px;
  cursor: pointer;
  transition: color 0.2s;
}

.header-nav-btn:hover {
  color: #fff;
}

.console-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.console-sidebar {
  width: 220px;
  background-color: #fff;
  border-right: 1px solid #e8e4df;
  flex-shrink: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.sidebar-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24px 20px 20px;
  border-bottom: 1px solid #e8e4df;
  font-size: 18px;
  font-weight: 700;
  color: #2c3e50;
  line-height: 1.3;
}

.sidebar-search-icon {
  color: #8a8e99;
  cursor: pointer;
  flex-shrink: 0;
}

.sidebar-search-icon:hover {
  color: #2c3e50;
}

.sidebar-nav {
  flex: 1;
  padding: 0;
}

.nav-group {
  padding: 8px 0;
  border-bottom: 1px solid #e8e4df;
}

.nav-group:last-child {
  border-bottom: none;
}

.nav-group-title {
  padding: 16px 24px 8px;
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
  line-height: 1.4;
  text-transform: uppercase;
  letter-spacing: 0.8px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 24px;
  height: 32px;
  color: #333;
  text-decoration: none;
  font-size: 14px;
  border-right: 2px solid transparent;
  transition: all 0.15s;
}

.nav-item svg {
  flex-shrink: 0;
  opacity: 0.5;
}

.nav-item:hover {
  color: #9a5b25;
  background-color: #f8f5f1;
}

.nav-item:hover svg {
  opacity: 0.8;
}

.nav-item.active {
  color: #2a4d6a;
  font-weight: 600;
  border-right-color: #2a4d6a;
  background-color: #f0f4f8;
}

.nav-item.active svg {
  opacity: 1;
}

.nav-subitem {
  padding-left: 28px;
  font-size: 12px;
}

.sidebar-collapse {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 16px 12px;
  color: #c2c6cc;
  cursor: pointer;
}

.sidebar-collapse:hover {
  color: #9a5b25;
}

.console-main {
  flex: 1;
  background-color: #fff;
  overflow-y: auto;
  padding: 24px 24px 24px 32px;
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
