<template>
  <div class="console-layout">
    <!-- Top Navigation Bar -->
    <header class="console-header">
      <div class="header-left">
        <button class="mobile-menu-btn" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M3 6h18v2H3V6zm0 5h18v2H3v-2zm0 5h18v2H3v-2z"/>
          </svg>
        </button>
        <div class="header-grid-icon">
          <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor">
            <rect x="1" y="1" width="4" height="4" rx="0.5" />
            <rect x="6" y="1" width="4" height="4" rx="0.5" />
            <rect x="11" y="1" width="4" height="4" rx="0.5" />
            <rect x="1" y="6" width="4" height="4" rx="0.5" />
            <rect x="6" y="6" width="4" height="4" rx="0.5" />
            <rect x="11" y="6" width="4" height="4" rx="0.5" />
            <rect x="1" y="11" width="4" height="4" rx="0.5" />
            <rect x="6" y="11" width="4" height="4" rx="0.5" />
            <rect x="11" y="11" width="4" height="4" rx="0.5" />
          </svg>
        </div>
        <span class="logo-brand">DBay</span>
        <span class="header-divider"></span>
        <span class="header-console-text">运维控制台</span>
        <span class="header-region">
          <svg class="region-icon" viewBox="0 0 16 16" fill="currentColor" width="14" height="14">
            <path d="M8 1a5.5 5.5 0 0 0-5.5 5.5c0 3.038 5.5 8.5 5.5 8.5s5.5-5.462 5.5-8.5A5.5 5.5 0 0 0 8 1zm0 7.5a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"/>
          </svg>
          华北-北京四
          <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor" style="opacity:0.5">
            <path d="M4 6l4 4 4-4"/>
          </svg>
        </span>
      </div>
      <div class="header-center">
        <div class="header-search">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor" class="search-icon">
            <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85zm-5.242.156a5 5 0 1 1 0-10 5 5 0 0 1 0 10z"/>
          </svg>
          <span class="search-placeholder">搜索云服务...</span>
        </div>
      </div>
      <div class="header-right">
        <span class="header-nav-item header-nav-desktop">备案</span>
        <span class="header-nav-item header-nav-desktop">资源</span>
        <span class="header-nav-item header-nav-desktop">费用</span>
        <span class="header-nav-item header-nav-desktop">企业</span>
        <span class="header-nav-item header-nav-desktop">工具</span>
        <span class="header-nav-item header-nav-desktop">工单</span>
        <span class="header-divider-small header-nav-desktop"></span>
        <span class="header-nav-item header-username">Admin</span>
        <button class="header-nav-item header-nav-btn" @click="handleLogout">退出</button>
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
            <div class="nav-group-title">总览</div>
            <router-link to="/dashboard" class="nav-item" active-class="active" @click="sidebarOpen = false">仪表盘</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">租户</div>
            <router-link to="/tenants" class="nav-item" active-class="active" @click="sidebarOpen = false">租户管理</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">数据服务</div>
            <router-link to="/databases" class="nav-item" active-class="active" @click="sidebarOpen = false">数据库</router-link>
            <router-link to="/knowledge" class="nav-item" active-class="active" @click="sidebarOpen = false">知识库</router-link>
            <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">记忆库</router-link>
            <router-link to="/datalake" class="nav-item" active-class="active" @click="sidebarOpen = false">数据湖</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">基础设施</div>
            <router-link to="/infra" class="nav-item" active-class="active" @click="sidebarOpen = false">基础设施</router-link>
            <router-link to="/cloud" class="nav-item" active-class="active" @click="sidebarOpen = false">华为云控制台</router-link>
            <router-link to="/cost" class="nav-item" active-class="active" @click="sidebarOpen = false">成本监控</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">日志审计</div>
            <router-link to="/operations" class="nav-item" active-class="active" @click="sidebarOpen = false">操作日志</router-link>
            <router-link to="/audit" class="nav-item" active-class="active" @click="sidebarOpen = false">审计日志</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">运行时监控</div>
            <router-link to="/system" class="nav-item" active-class="active" @click="sidebarOpen = false">组件健康</router-link>
            <router-link to="/metrics" class="nav-item" active-class="active" @click="sidebarOpen = false">应用指标</router-link>
            <router-link to="/logs" class="nav-item" active-class="active" @click="sidebarOpen = false">日志查看</router-link>
            <router-link to="/alerts" class="nav-item" active-class="active" @click="sidebarOpen = false">告警管理</router-link>
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
  </div>
</template>

<script setup lang="ts">
import { ref, provide } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminAuthStore } from '../stores/auth'
import AiChatPanel from '../components/AiChatPanel.vue'

const router = useRouter()
const authStore = useAdminAuthStore()
const sidebarOpen = ref(false)
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
  background-color: #000;
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

.header-grid-icon {
  color: rgba(255, 255, 255, 0.6);
  margin-right: 12px;
  display: flex;
  align-items: center;
  cursor: pointer;
}

.header-grid-icon:hover {
  color: #fff;
}

.logo-brand {
  color: #e6393d;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
  margin-right: 20px;
}

.header-divider {
  width: 1px;
  height: 16px;
  background-color: rgba(255, 255, 255, 0.2);
  margin-right: 20px;
}

.header-console-text {
  color: #fff;
  font-size: 15px;
  font-weight: 500;
  margin-right: 24px;
}

.header-region {
  display: flex;
  align-items: center;
  gap: 4px;
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
  cursor: pointer;
}

.region-icon {
  opacity: 0.85;
}

.header-center {
  flex: 1;
  display: flex;
  justify-content: center;
  max-width: 360px;
  margin: 0 auto;
}

.header-search {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  padding: 6px 14px;
  width: 240px;
  cursor: pointer;
}

.header-search .search-icon {
  color: rgba(255, 255, 255, 0.5);
}

.search-placeholder {
  color: rgba(255, 255, 255, 0.4);
  font-size: 13px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
}

.header-nav-item {
  color: rgba(255, 255, 255, 0.75);
  font-size: 14px;
  cursor: pointer;
}

.header-nav-item:hover {
  color: #fff;
}

.header-divider-small {
  width: 1px;
  height: 14px;
  background: rgba(255, 255, 255, 0.15);
}

.header-username {
  color: rgba(255, 255, 255, 0.9);
}

.header-nav-btn {
  background: transparent;
  border: none;
  padding: 0;
  transition: color 0.2s;
}

.console-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.console-sidebar {
  width: 220px;
  background-color: #fff;
  border-right: 1px solid #e5e5e5;
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
  border-bottom: 1px solid #e5e5e5;
  font-size: 18px;
  font-weight: 700;
  color: #191919;
  line-height: 1.3;
}

.sidebar-search-icon {
  color: #8a8e99;
  cursor: pointer;
  flex-shrink: 0;
}

.sidebar-search-icon:hover {
  color: #191919;
}

.sidebar-nav {
  flex: 1;
  padding: 0;
}

.nav-group {
  padding: 8px 0;
  border-bottom: 1px solid #e5e5e5;
}

.nav-group:last-child {
  border-bottom: none;
}

.nav-group-title {
  padding: 16px 24px 8px;
  font-size: 14px;
  font-weight: 700;
  color: #191919;
  line-height: 1.4;
}

.nav-item {
  display: block;
  padding: 0 24px;
  height: 44px;
  line-height: 44px;
  color: #333;
  text-decoration: none;
  font-size: 14px;
  border-left: 3px solid transparent;
  transition: all 0.15s;
}

.nav-item:hover {
  color: #0073e6;
  background-color: #f5f7fa;
}

.nav-item.active {
  color: #0073e6;
  font-weight: 600;
  border-left-color: #0073e6;
  background-color: transparent;
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
  color: #0073e6;
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

  .header-grid-icon,
  .header-divider,
  .header-console-text,
  .header-region,
  .header-center,
  .header-nav-desktop {
    display: none !important;
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
