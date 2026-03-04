<template>
  <div class="console-layout">
    <!-- Top Navigation Bar -->
    <header class="console-header">
      <div class="header-left">
        <span class="logo">Lakeon</span>
        <span class="subtitle">Serverless PostgreSQL</span>
      </div>
      <div class="header-right">
        <span class="tenant-name">{{ authStore.tenantName || 'Tenant' }}</span>
        <button class="logout-btn" @click="handleLogout">退出</button>
      </div>
    </header>

    <div class="console-body">
      <!-- Left Sidebar -->
      <aside class="console-sidebar">
        <nav class="sidebar-nav">
          <router-link to="/dashboard" class="nav-item" active-class="active">
            <icon-bar-chart class="nav-icon" />
            <span class="nav-text">总览</span>
          </router-link>
          <router-link to="/databases" class="nav-item" active-class="active">
            <icon-data-source class="nav-icon" />
            <span class="nav-text">数据库实例</span>
          </router-link>
          <router-link to="/apikey" class="nav-item" active-class="active">
            <icon-lock class="nav-icon" />
            <span class="nav-text">API Key</span>
          </router-link>
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
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import {
  iconBarChart,
  iconDataSource,
  iconLock,
} from '@opentiny/vue-icon'

const IconBarChart = iconBarChart()
const IconDataSource = iconDataSource()
const IconLock = iconLock()

const router = useRouter()
const authStore = useAuthStore()

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
  background-color: #1a1a1a;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  flex-shrink: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.5px;
}

.subtitle {
  color: rgba(255, 255, 255, 0.6);
  font-size: 13px;
  padding-left: 12px;
  border-left: 1px solid rgba(255, 255, 255, 0.2);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.tenant-name {
  color: rgba(255, 255, 255, 0.85);
  font-size: 14px;
}

.logout-btn {
  background: transparent;
  border: none;
  color: rgba(255, 255, 255, 0.65);
  font-size: 13px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: color 0.2s, background-color 0.2s;
}

.logout-btn:hover {
  color: #fff;
  background-color: rgba(255, 255, 255, 0.1);
}

.console-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.console-sidebar {
  width: 200px;
  background-color: #fff;
  border-right: 1px solid #e8e8e8;
  flex-shrink: 0;
  overflow-y: auto;
}

.sidebar-nav {
  padding: 8px 0;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  height: 40px;
  color: #333;
  text-decoration: none;
  font-size: 14px;
  border-left: 3px solid transparent;
  transition: all 0.2s;
}

.nav-item:hover {
  background-color: #f5f7fa;
}

.nav-item.active {
  color: #0073e6;
  background-color: #e6f2ff;
  border-left-color: #0073e6;
}

.nav-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.nav-text {
  white-space: nowrap;
}

.console-main {
  flex: 1;
  background-color: #f5f5f5;
  overflow-y: auto;
  padding: 20px;
}
</style>
