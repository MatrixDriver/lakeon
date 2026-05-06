<script setup lang="ts">
import { useRoute, RouterLink, RouterView } from 'vue-router'
import { computed } from 'vue'
const route = useRoute()
const subs = [
  { to: '/cognition/timeline', label: '时间流' },
  { to: '/cognition/summary',  label: '摘要' },
  { to: '/cognition/graph',    label: '关系' },
  { to: '/cognition/skill',    label: 'Skill' },
]
const isActive = (to: string) => computed(() => route.path === to).value
</script>

<template>
  <div class="cog">
    <nav class="subs">
      <RouterLink v-for="s in subs" :key="s.to" :to="s.to" custom v-slot="{ navigate }">
        <a :class="['sub', { active: isActive(s.to) }]" @click="navigate">{{ s.label }}</a>
      </RouterLink>
    </nav>
    <RouterView />
  </div>
</template>

<style scoped>
.cog { display: flex; flex-direction: column; gap: var(--space-lg); }
.subs { display: flex; gap: var(--space-lg); border-bottom: 1px solid var(--c-divider); }
.sub {
  padding: var(--space-sm) 0;
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  border-bottom: 1.5px solid transparent;
  cursor: pointer; text-decoration: none;
}
.sub:hover { color: var(--c-text); }
.sub.active { color: var(--c-primary); border-bottom-color: var(--c-accent); font-weight: var(--fw-medium); }
</style>
