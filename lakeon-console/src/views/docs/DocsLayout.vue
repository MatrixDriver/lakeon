<template>
  <div class="docs-shell">
    <aside class="docs-sidebar">
      <div class="docs-sidebar-inner">
        <div class="docs-side-eyebrow">{{ t('文档', 'Documentation') }}</div>

        <nav class="docs-nav">
          <div class="docs-nav-group">
            <span class="docs-nav-title">{{ t('开始使用', 'Using DBay') }}</span>
            <router-link to="/docs" class="docs-nav-link" exact-active-class="is-active">{{ t('概览', 'Overview') }}</router-link>
            <router-link to="/docs/console" class="docs-nav-link" active-class="is-active">{{ t('Console 简介', 'Console') }}</router-link>
            <router-link to="/docs/cli" class="docs-nav-link" active-class="is-active">{{ t('CLI 简介', 'CLI') }}</router-link>
            <router-link to="/docs/mcp" class="docs-nav-link" active-class="is-active">{{ t('MCP 工具', 'MCP Tools') }}</router-link>
          </div>
          <div class="docs-nav-group">
            <span class="docs-nav-title">{{ t('参考文档', 'Reference') }}</span>
            <router-link to="/docs/rest-api" class="docs-nav-link" active-class="is-active">REST API</router-link>
            <router-link to="/docs/python-sdk" class="docs-nav-link" active-class="is-active">Python SDK</router-link>
          </div>
        </nav>
      </div>
    </aside>

    <main class="docs-main">
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
  align-items: flex-start;
  min-height: calc(100vh - 64px);
  background: var(--c-bg-alt);
}

.docs-sidebar {
  width: 260px;
  flex-shrink: 0;
  position: sticky;
  top: 64px;
  height: calc(100vh - 64px);
  overflow-y: auto;
  border-right: 1px solid var(--c-border-light);
  background: #fff;
}

.docs-sidebar-inner {
  padding: clamp(32px, 4vw, 48px) clamp(20px, 2vw, 28px);
}

.docs-side-eyebrow {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-xl);
}

.docs-nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
}

.docs-nav-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.docs-nav-title {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
  margin-bottom: var(--space-sm);
}

.docs-nav-link {
  display: block;
  padding: 8px 0;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 400;
  color: var(--c-text-2);
  text-decoration: none;
  border-left: 2px solid transparent;
  padding-left: 12px;
  margin-left: -12px;
  transition: color 160ms ease-out, border-color 160ms ease-out;
}

.docs-nav-link:hover {
  color: var(--c-primary);
}

.docs-nav-link.is-active {
  color: var(--c-accent-text);
  font-weight: 500;
}

.docs-main {
  flex: 1;
  min-width: 0;
  background: var(--c-bg-alt);
}

@media (max-width: 900px) {
  .docs-shell {
    flex-direction: column;
  }
  .docs-sidebar {
    width: 100%;
    position: static;
    height: auto;
    border-right: none;
    border-bottom: 1px solid var(--c-border-light);
  }
  .docs-sidebar-inner {
    padding: var(--space-xl);
  }
  .docs-nav {
    flex-direction: row;
    flex-wrap: wrap;
    gap: var(--space-lg);
  }
  .docs-nav-group {
    flex: 1;
    min-width: 160px;
  }
}
</style>
