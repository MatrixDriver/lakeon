<script setup lang="ts">
defineProps<{ title: string; body?: string }>()
</script>

<template>
  <div class="empty">
    <h3 class="title">{{ title }}</h3>
    <p v-if="body" class="body">{{ body }}</p>
    <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped>
.empty {
  padding: var(--space-3xl) var(--space-2xl);
  max-width: 560px;
  margin: 0 auto;
}
.title { font-size: var(--fs-h3); margin-bottom: var(--space-sm); }
.body {
  color: var(--c-text-muted);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
  white-space: pre-wrap;
}
.actions { margin-top: var(--space-lg); display: flex; gap: var(--space-sm); }
</style>
