<template>
  <div class="docs-shell">
    <aside class="docs-sidebar">
      <nav class="sidebar-nav">
        <div class="nav-group">
          <span class="nav-group-title">{{ t('入门', 'GETTING STARTED') }}</span>
          <router-link to="/docs" class="nav-link" exact-active-class="router-link-exact-active">{{ t('快速开始', 'Quick Start') }}</router-link>
          <router-link to="/docs/deploy" class="nav-link">{{ t('部署指南', 'Deploy Guide') }}</router-link>
        </div>
        <div class="nav-group nav-group--spaced">
          <span class="nav-group-title">{{ t('参考文档', 'REFERENCE') }}</span>
          <router-link to="/docs/rest-api" class="nav-link">REST API</router-link>
          <router-link to="/docs/python-sdk" class="nav-link">Python SDK</router-link>
          <router-link to="/docs/mcp" class="nav-link">{{ t('MCP 接入', 'MCP Integration') }}</router-link>
        </div>
      </nav>
    </aside>
    <main class="docs-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.docs-shell {
  display: flex;
  min-height: 100vh;
  color: var(--pub-text);
}

.docs-sidebar {
  width: 240px;
  flex-shrink: 0;
  background: var(--pub-surface);
  border-right: 1px solid var(--pub-border);
  position: sticky;
  top: 52px;
  height: calc(100vh - 52px);
  overflow-y: auto;
  padding: 24px 16px;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
}

.nav-group {
  display: flex;
  flex-direction: column;
}

.nav-group--spaced {
  margin-top: 20px;
}

.nav-group-title {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--pub-text-3);
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 8px;
}

.nav-link {
  display: block;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: var(--pub-text-2);
  text-decoration: none;
  transition: color 0.15s, background 0.15s;
}

.nav-link:hover {
  background: var(--pub-hover);
}

.nav-link.router-link-exact-active,
.nav-link.router-link-active {
  color: var(--pub-primary);
  background: var(--pub-primary-light);
  font-weight: 600;
}

.docs-content {
  flex: 1;
  background: var(--pub-surface);
  padding: 32px 48px;
}

@media (max-width: 768px) {
  .docs-sidebar {
    display: none;
  }

  .docs-content {
    padding: 20px;
  }
}
</style>
