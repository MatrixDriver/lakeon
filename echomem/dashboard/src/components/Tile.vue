<script setup lang="ts">
defineProps<{ value: number | string; label: string }>()
</script>

<template>
  <div class="tile">
    <div class="num">{{ value }}</div>
    <div class="lbl">{{ label }}</div>
  </div>
</template>

<style scoped>
.tile {
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: var(--space-md) var(--space-lg);
}
.tile .num {
  font-family: var(--font-display);
  font-size: var(--fs-h2);
  font-weight: var(--fw-medium);
  color: var(--c-primary);
  line-height: 1.1;
}
.tile .lbl {
  font-size: var(--fs-xs);
  color: var(--c-text-muted);
  margin-top: 4px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}
</style>
