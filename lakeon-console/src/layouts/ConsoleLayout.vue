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
        <router-link to="/" class="logo-brand">DBay</router-link>
        <span class="header-divider"></span>
        <span class="header-console-text">控制台</span>
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
        <span class="header-nav-item header-username">{{ authStore.tenantName || 'Tenant' }}</span>
        <button class="header-nav-item header-nav-btn" @click="handleLogout">退出</button>
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

      <!-- Icon Rail (方案 C) -->
      <div class="icon-rail">
        <div class="rail-icon" :class="{ active: activeRail === 'db' }" @click="switchRail('db')" title="数据库">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <ellipse cx="12" cy="6" rx="8" ry="3"/>
            <path d="M4 6v6c0 1.66 3.58 3 8 3s8-1.34 8-3V6"/>
            <path d="M4 12v6c0 1.66 3.58 3 8 3s8-1.34 8-3v-6"/>
          </svg>
          <span class="rail-label">数据库</span>
        </div>
        <div class="rail-icon" :class="{ active: activeRail === 'kb' }" @click="switchRail('kb')" title="知识库">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
            <line x1="9" y1="7" x2="17" y2="7"/>
            <line x1="9" y1="11" x2="15" y2="11"/>
          </svg>
          <span class="rail-label">知识库</span>
        </div>
        <div class="rail-icon" :class="{ active: activeRail === 'memory' }" @click="switchRail('memory')" title="记忆库">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
            <line x1="10" y1="21" x2="14" y2="21"/>
            <line x1="9" y1="17" x2="15" y2="17"/>
          </svg>
          <span class="rail-label">记忆库</span>
        </div>
        <div class="rail-icon" :class="{ active: activeRail === 'datalake' }" @click="switchRail('datalake')" title="数据湖">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 10c2.5-2 5-2 7.5 0s5 2 7.5 0"/>
            <path d="M3 14c2.5-2 5-2 7.5 0s5 2 7.5 0"/>
            <path d="M3 14a9 9 0 0 0 18 0" stroke-linecap="round"/>
          </svg>
          <span class="rail-label">数据湖</span>
        </div>
        <div class="rail-separator"></div>
        <div class="rail-icon" :class="{ active: activeRail === 'settings' }" @click="switchRail('settings')" title="设置">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="3"/>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
          </svg>
          <span class="rail-label">设置</span>
        </div>
      </div>

      <!-- Left Sidebar -->
      <aside class="console-sidebar" :class="{ open: sidebarOpen }">
        <div class="sidebar-title">
          <span>{{ railTitles[activeRail] }}</span>
        </div>
        <nav class="sidebar-nav">
          <!-- 数据库菜单 -->
          <template v-if="activeRail === 'db'">
            <div class="nav-group">
              <router-link to="/dashboard" class="nav-item" active-class="active" @click="sidebarOpen = false">我的数据库</router-link>
              <router-link to="/timetravel" class="nav-item" active-class="active" @click="sidebarOpen = false">时间旅行</router-link>
              <router-link to="/sql" class="nav-item" active-class="active" @click="sidebarOpen = false">SQL 编辑器</router-link>
              <router-link to="/import" class="nav-item" active-class="active" @click="sidebarOpen = false">数据迁移</router-link>
            </div>
            <div class="nav-group">
              <div class="nav-group-title">监控运维</div>
              <router-link to="/monitor" class="nav-item" active-class="active" @click="sidebarOpen = false">监控面板</router-link>
              <router-link to="/logs" class="nav-item" active-class="active" @click="sidebarOpen = false">日志管理</router-link>
              <router-link to="/backups" class="nav-item" active-class="active" @click="sidebarOpen = false">备份管理</router-link>
            </div>
          </template>
          <!-- 知识库菜单 -->
          <template v-if="activeRail === 'kb'">
            <div class="nav-group">
              <router-link to="/knowledge" class="nav-item" active-class="active" @click="sidebarOpen = false">知识库</router-link>
              <router-link to="/knowledge/datasources" class="nav-item" active-class="active" @click="sidebarOpen = false">数据源</router-link>
              <router-link to="/knowledge/search" class="nav-item" active-class="active" @click="sidebarOpen = false">知识搜索</router-link>
            </div>
          </template>
          <!-- 记忆库菜单 -->
          <template v-if="activeRail === 'memory'">
            <div class="nav-group">
              <router-link to="/memory" class="nav-item" active-class="active" @click="sidebarOpen = false">记忆库</router-link>
              <router-link to="/memory/browse" class="nav-item" active-class="active" @click="sidebarOpen = false">记忆浏览</router-link>
              <router-link to="/memory/messages" class="nav-item" active-class="active" @click="sidebarOpen = false">消息日志</router-link>
              <router-link to="/memory/traits" class="nav-item" active-class="active" @click="sidebarOpen = false">反思洞察</router-link>
              <router-link to="/memory/stats" class="nav-item" active-class="active" @click="sidebarOpen = false">用量统计</router-link>
            </div>
          </template>
          <!-- 数据湖菜单 -->
          <template v-if="activeRail === 'datalake'">
            <div class="nav-group">
              <router-link to="/datalake/datasets" class="nav-item" active-class="active" @click="sidebarOpen = false">数据集</router-link>
              <router-link to="/datalake" class="nav-item" active-class="active" @click="sidebarOpen = false">作业管理</router-link>
            </div>
          </template>
          <!-- 设置菜单 -->
          <template v-if="activeRail === 'settings'">
            <div class="nav-group">
              <router-link to="/apikey" class="nav-item" active-class="active" @click="sidebarOpen = false">API Key</router-link>
              <router-link to="/usage" class="nav-item" active-class="active" @click="sidebarOpen = false">资源用量</router-link>
              <router-link to="/account" class="nav-item" active-class="active" @click="sidebarOpen = false">账户设置</router-link>
            </div>
            <div class="nav-group">
              <div class="nav-group-title">帮助</div>
              <router-link to="/help" class="nav-item" active-class="active" @click="sidebarOpen = false">使用指南</router-link>
            </div>
          </template>
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
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const sidebarOpen = ref(false)

type RailKey = 'db' | 'kb' | 'memory' | 'datalake' | 'settings'
const activeRail = ref<RailKey>('db')

const railTitles: Record<RailKey, string> = {
  db: '数据库',
  kb: '知识库',
  memory: '记忆库',
  datalake: '数据湖',
  settings: '设置',
}

const railDefaultRoutes: Record<RailKey, string> = {
  db: '/dashboard',
  kb: '/knowledge',
  memory: '/memory',
  datalake: '/datalake',
  settings: '/apikey',
}

function switchRail(rail: RailKey) {
  if (activeRail.value !== rail) {
    activeRail.value = rail
    router.push(railDefaultRoutes[rail])
  }
  sidebarOpen.value = false
}

// Sync rail selection based on current route
watch(() => route.path, (path) => {
  if (path.startsWith('/knowledge')) {
    activeRail.value = 'kb'
  } else if (path.startsWith('/memory')) {
    activeRail.value = 'memory'
  } else if (path.startsWith('/datalake')) {
    activeRail.value = 'datalake'
  } else if (['/apikey', '/usage', '/account', '/help'].some(p => path.startsWith(p))) {
    activeRail.value = 'settings'
  } else {
    activeRail.value = 'db'
  }
}, { immediate: true })

function handleLogout() {
  authStore.logout()
  router.push('/login')
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

onMounted(() => {
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
  color: #0073e6;
  text-decoration: none;
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

/* Icon Rail */
.icon-rail {
  width: 52px;
  background-color: #f5f6f8;
  border-right: 1px solid #e5e5e5;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 0;
  gap: 4px;
  flex-shrink: 0;
}

.rail-icon {
  width: 40px;
  height: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  gap: 2px;
  color: #666;
}

.rail-icon:hover {
  background-color: #e8eaed;
  color: #333;
}

.rail-icon.active {
  background-color: #0073e6;
  color: #fff;
}

.rail-label {
  font-size: 9px;
  line-height: 1;
}

.rail-separator {
  width: 28px;
  height: 1px;
  background-color: #d5d8dc;
  margin: 6px 0;
}

.console-sidebar {
  width: 180px;
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

.trial-banner {
  background: linear-gradient(90deg, #fff3cd, #ffeaa7);
  border-bottom: 1px solid #f0d78e;
  padding: 6px 16px;
  text-align: center;
  font-size: 13px;
  color: #856404;
  z-index: 100;
}
.trial-banner-content {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
}
.trial-banner-cta {
  color: #0073e6;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}
.trial-banner-cta:hover {
  text-decoration: underline;
}
</style>
