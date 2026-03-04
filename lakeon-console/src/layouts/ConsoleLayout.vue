<template>
  <div class="console-layout">
    <!-- Top Navigation Bar -->
    <header class="console-header">
      <div class="header-left">
        <span class="logo-brand">Lakeon</span>
        <span class="header-divider"></span>
        <span class="header-console-text">控制台</span>
        <span class="header-region">
          <svg class="region-icon" viewBox="0 0 16 16" fill="currentColor" width="14" height="14">
            <path d="M8 1a5.5 5.5 0 0 0-5.5 5.5c0 3.038 5.5 8.5 5.5 8.5s5.5-5.462 5.5-8.5A5.5 5.5 0 0 0 8 1zm0 7.5a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"/>
          </svg>
          华北-北京四
        </span>
      </div>
      <div class="header-right">
        <span class="header-nav-item">{{ authStore.tenantName || 'Tenant' }}</span>
        <button class="header-nav-item header-nav-btn" @click="handleLogout">退出</button>
      </div>
    </header>

    <div class="console-body">
      <!-- Left Sidebar -->
      <aside class="console-sidebar">
        <div class="sidebar-title">
          <span class="sidebar-service-name">Lakeon 数据库</span>
        </div>
        <nav class="sidebar-nav">
          <div class="nav-group">
            <div class="nav-group-header">数据库服务</div>
            <router-link to="/dashboard" class="nav-item" active-class="active">
              <span class="nav-text">总览</span>
            </router-link>
            <router-link to="/databases" class="nav-item" active-class="active">
              <span class="nav-text">数据库实例</span>
            </router-link>
          </div>
          <div class="nav-group">
            <div class="nav-group-header">安全管理</div>
            <router-link to="/apikey" class="nav-item" active-class="active">
              <span class="nav-text">API Key</span>
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
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

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
  height: 50px;
  background-color: #000;
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
  gap: 0;
}

.logo-brand {
  color: #e6393d;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.5px;
  margin-right: 16px;
}

.header-divider {
  width: 1px;
  height: 16px;
  background-color: rgba(255, 255, 255, 0.2);
  margin-right: 16px;
}

.header-console-text {
  color: #fff;
  font-size: 14px;
  font-weight: 400;
  margin-right: 24px;
}

.header-region {
  display: flex;
  align-items: center;
  gap: 4px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
}

.region-icon {
  opacity: 0.7;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.header-nav-item {
  color: rgba(255, 255, 255, 0.75);
  font-size: 13px;
}

.header-nav-btn {
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 4px 0;
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
  border-right: 1px solid #e8e8e8;
  flex-shrink: 0;
  overflow-y: auto;
}

.sidebar-title {
  padding: 16px 20px 12px;
  border-bottom: 1px solid #e8e8e8;
}

.sidebar-service-name {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}

.sidebar-nav {
  padding: 4px 0;
}

.nav-group {
  margin-bottom: 4px;
}

.nav-group-header {
  padding: 12px 20px 6px;
  font-size: 13px;
  font-weight: 600;
  color: #333;
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 0 20px;
  height: 36px;
  color: #666;
  text-decoration: none;
  font-size: 14px;
  border-left: 3px solid transparent;
  transition: all 0.15s;
}

.nav-item:hover {
  color: #333;
  background-color: #f5f7fa;
}

.nav-item.active {
  color: #0073e6;
  background-color: #e6f2ff;
  border-left-color: #0073e6;
}

.nav-text {
  white-space: nowrap;
}

.console-main {
  flex: 1;
  background-color: #fff;
  overflow-y: auto;
  padding: 20px 24px;
}
</style>
