<template>
  <div class="console-layout">
    <!-- Top Navigation Bar -->
    <header class="console-header">
      <div class="header-left">
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
        <span class="logo-brand">Lakeon</span>
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
        <span class="header-nav-item">备案</span>
        <span class="header-nav-item">资源</span>
        <span class="header-nav-item">费用</span>
        <span class="header-nav-item">企业</span>
        <span class="header-nav-item">工具</span>
        <span class="header-nav-item">工单</span>
        <span class="header-divider-small"></span>
        <span class="header-nav-item header-username">Admin</span>
        <button class="header-nav-item header-nav-btn" @click="handleLogout">退出</button>
      </div>
    </header>

    <div class="console-body">
      <!-- Left Sidebar -->
      <aside class="console-sidebar">
        <div class="sidebar-title">
          <span>Lakeon 运维</span>
          <svg class="sidebar-search-icon" viewBox="0 0 16 16" width="16" height="16" fill="currentColor">
            <path d="M11.742 10.344a6.5 6.5 0 1 0-1.397 1.398h-.001l3.85 3.85a1 1 0 0 0 1.415-1.414l-3.85-3.85zm-5.242.156a5 5 0 1 1 0-10 5 5 0 0 1 0 10z"/>
          </svg>
        </div>
        <nav class="sidebar-nav">
          <div class="nav-group">
            <div class="nav-group-title">总览</div>
            <router-link to="/dashboard" class="nav-item" active-class="active">仪表盘</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">运维管理</div>
            <router-link to="/tenants" class="nav-item" active-class="active">租户管理</router-link>
            <router-link to="/databases" class="nav-item" active-class="active">数据库实例</router-link>
            <router-link to="/operations" class="nav-item" active-class="active">操作日志</router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-title">系统</div>
            <router-link to="/system" class="nav-item" active-class="active">组件健康</router-link>
            <router-link to="/metrics" class="nav-item" active-class="active">应用指标</router-link>
            <router-link to="/logs" class="nav-item" active-class="active">日志查看</router-link>
            <router-link to="/alerts" class="nav-item" active-class="active">告警管理</router-link>
            <router-link to="/infra" class="nav-item" active-class="active">基础设施</router-link>
            <router-link to="/cost" class="nav-item" active-class="active">成本监控</router-link>
            <router-link to="/cloud" class="nav-item" active-class="active">云资源</router-link>
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
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAdminAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAdminAuthStore()

function handleLogout() {
  authStore.logout()
  router.push('/login')
}
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
</style>
