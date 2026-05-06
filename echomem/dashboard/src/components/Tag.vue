<script setup lang="ts">
defineProps<{ tone?: 'default' | 'accent' | 'mono' }>()
</script>

<template>
  <span :class="['tag', tone ?? 'default']"><slot /></span>
</template>

<style scoped>
.tag {
  display: inline-flex; align-items: center;
  padding: 2px var(--space-sm);
  border-radius: 10px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  color: var(--c-text-muted);
  letter-spacing: 0.2px;
}
.tag.accent {
  background: var(--c-accent-light);
  border-color: var(--c-accent);
  color: var(--c-accent-text);
}
.tag.mono { font-family: var(--font-mono); font-size: 11px; }
</style>
