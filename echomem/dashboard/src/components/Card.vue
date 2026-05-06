<script setup lang="ts">
defineProps<{ as?: 'div' | 'article' | 'section' }>()
</script>

<template>
  <component :is="as ?? 'div'" class="card"><slot /></component>
</template>

<style scoped>
.card {
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}
</style>
