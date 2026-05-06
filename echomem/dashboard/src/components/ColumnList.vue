<script setup lang="ts">
defineProps<{
  title: string
  items: { id: string; label: string; sub?: string }[]
  activeId?: string | null
}>()
defineEmits<{ select: [id: string] }>()
</script>

<template>
  <div class="col">
    <header class="head">{{ title }}</header>
    <ul class="list">
      <li v-for="it in items" :key="it.id"
          :class="['cell', { active: it.id === activeId }]"
          @click="$emit('select', it.id)">
        <div class="lbl">{{ it.label }}</div>
        <div v-if="it.sub" class="sub">{{ it.sub }}</div>
      </li>
      <li v-if="items.length === 0" class="empty">无</li>
    </ul>
  </div>
</template>

<style scoped>
.col {
  border-right: 1px solid var(--c-border);
  padding: var(--space-md) var(--space-sm);
  overflow-y: auto;
  display: flex; flex-direction: column;
  min-width: 0;
}
.col:last-child { border-right: none; }
.head {
  font-size: var(--fs-xs);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--c-text-muted);
  padding: 0 var(--space-sm);
  margin-bottom: var(--space-sm);
}
.list { list-style: none; margin: 0; padding: 0; }
.cell {
  padding: var(--space-sm); border-radius: var(--radius-sm);
  cursor: pointer; margin-bottom: 2px;
  border: 1px solid transparent;
  font-size: var(--fs-sm);
}
.cell:hover { background: var(--c-bg-alt); }
.cell.active { background: var(--c-accent-light); color: var(--c-accent-text); border-color: var(--c-accent); }
.cell .sub { font-size: var(--fs-xs); color: var(--c-text-muted); margin-top: 2px; }
.empty { color: var(--c-text-faint); font-size: var(--fs-xs); padding: var(--space-sm); }
</style>
