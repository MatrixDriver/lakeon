<template>
  <div class="view-toggle">
    <button class="vt-btn" :class="{ active: modelValue === 'card' }" @click="$emit('update:modelValue', 'card')" title="卡片视图">
      <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><rect x="1" y="1" width="6" height="6" rx="1"/><rect x="9" y="1" width="6" height="6" rx="1"/><rect x="1" y="9" width="6" height="6" rx="1"/><rect x="9" y="9" width="6" height="6" rx="1"/></svg>
    </button>
    <button class="vt-btn" :class="{ active: modelValue === 'table' }" @click="$emit('update:modelValue', 'table')" title="表格视图">
      <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><rect x="1" y="1" width="14" height="3" rx="0.5"/><rect x="1" y="6" width="14" height="3" rx="0.5"/><rect x="1" y="11" width="14" height="3" rx="0.5"/></svg>
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{ modelValue: 'card' | 'table' }>()
defineEmits<{ 'update:modelValue': [value: 'card' | 'table'] }>()
</script>

<style scoped>
.view-toggle { display: inline-flex; border: 1px solid #e8e4df; border-radius: 4px; overflow: hidden; }
.vt-btn {
  padding: 4px 8px; background: #fff; border: none; cursor: pointer;
  color: #94a3b8; transition: all 0.12s; display: flex; align-items: center;
}
.vt-btn:not(:last-child) { border-right: 1px solid #e8e4df; }
.vt-btn.active { background: #2a4d6a; color: #fff; }
.vt-btn:hover:not(.active) { background: #f8f5f1; }
</style>
