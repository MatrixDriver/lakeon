<template>
  <div class="docs-shell">
    <aside class="docs-sidebar">
      <nav class="sidebar-nav">
        <div class="nav-group">
          <span class="nav-label">{{ t('入门', 'Getting Started') }}</span>
          <router-link to="/docs" exact-active-class="active" class="nav-link">{{ t('快速开始', 'Quick Start') }}</router-link>
          <router-link to="/docs/deploy" active-class="active" class="nav-link">{{ t('部署指南', 'Deployment') }}</router-link>
        </div>
        <div class="nav-group">
          <span class="nav-label">{{ t('参考文档', 'Reference') }}</span>
          <router-link to="/docs/rest-api" active-class="active" class="nav-link">REST API</router-link>
          <router-link to="/docs/python-sdk" active-class="active" class="nav-link">Python SDK</router-link>
          <router-link to="/docs/mcp" active-class="active" class="nav-link">MCP 工具</router-link>
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
  background: var(--pub-bg);
  color: var(--pub-text);
}
.docs-sidebar {
  width: 220px;
  flex-shrink: 0;
  border: 1px solid var(--pub-border);
  background: var(--pub-surface);
  padding: 48px 0 80px;
  position: sticky;
  top: 0;
  height: 100vh;
  overflow-y: auto;
}
.sidebar-nav { padding: 0 16px; }
.nav-group { margin-bottom: 24px; }
.nav-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--pub-text-4);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  display: block;
  margin-bottom: 6px;
  padding: 0 8px;
}
.nav-link {
  display: block;
  padding: 6px 8px;
  font-size: 13px;
  color: var(--pub-text-2);
  text-decoration: none;
  border-radius: 5px;
  transition: color 0.15s, background 0.15s;
  margin-bottom: 2px;
}
.nav-link:hover { color: var(--pub-text); background: var(--pub-hover); }
.nav-link.active { color: var(--pub-code); background: var(--pub-accent-bg); }
.docs-content {
  flex: 1;
  min-width: 0;
  padding: 48px 48px 80px;
  max-width: 800px;
}
@media (max-width: 768px) {
  .docs-sidebar { display: none; }
  .docs-content { padding: 32px 20px 60px; }
}
</style>
