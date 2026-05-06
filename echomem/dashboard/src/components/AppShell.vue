<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import Button from './Button.vue'
import Icon from './Icon.vue'
import Banner from './Banner.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const route = useRoute()

const tabs = [
  { to: '/',          label: '总览' },
  { to: '/memory',    label: '记忆' },
  { to: '/cognition', label: '认知' },
  { to: '/status',    label: '状态' },
]

const isActive = (to: string) => {
  if (to === '/') return route.path === '/'
  return route.path.startsWith(to)
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">echomem</div>
      <Button variant="primary" @click="ui.toggleQuickIngest(true)">
        <Icon name="plus" :size="14" /> Quick ingest
      </Button>
    </header>
    <nav class="tabs">
      <RouterLink v-for="t in tabs" :key="t.to" :to="t.to" custom v-slot="{ navigate }">
        <a :class="['tab', { active: isActive(t.to) }]" @click="navigate">{{ t.label }}</a>
      </RouterLink>
    </nav>
    <Banner v-if="ui.banner"
            :kind="ui.banner.kind" :text="ui.banner.text"
            :retry-label="ui.banner.retry ? '重试' : undefined"
            @retry="ui.banner?.retry?.()" />
    <main class="main"><slot /></main>
  </div>
</template>

<style scoped>
.shell { min-height: 100%; display: flex; flex-direction: column; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  height: var(--top-bar-h);
  padding: 0 var(--space-lg);
  background: var(--c-bg); border-bottom: 1px solid var(--c-border);
}
.brand {
  font-family: var(--font-display);
  font-weight: var(--fw-semibold);
  font-size: var(--fs-lg);
  color: var(--c-primary);
}
.tabs {
  display: flex; gap: var(--space-xl);
  padding: 0 var(--space-lg);
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-border);
}
.tab {
  display: inline-flex; align-items: center;
  height: var(--tabs-h);
  padding: 0 0;
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  border-bottom: 1.5px solid transparent;
  cursor: pointer;
  text-decoration: none;
}
.tab:hover { color: var(--c-text); }
.tab.active { color: var(--c-primary); border-bottom-color: var(--c-accent); font-weight: var(--fw-medium); }
.main { flex: 1; max-width: var(--content-max); width: 100%; margin: 0 auto; padding: var(--space-xl) var(--space-lg); }
</style>
