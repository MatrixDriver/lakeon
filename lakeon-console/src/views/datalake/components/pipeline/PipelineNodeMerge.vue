<template>
  <div class="pipeline-node merge-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">⊕</span>
      <span class="node-label">合并</span>
    </div>
    <div class="node-sub">N → 1 汇聚</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
defineProps<{ data: any; selected?: boolean }>()
</script>

<style scoped>
.merge-node {
  border: 2px solid #0ea5e9; background: #f0f9ff; border-radius: 8px;
  padding: 10px 14px; min-width: 140px; font-size: 12px; position: relative;
}
.merge-node.selected { box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.25); }
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #0369a1; }
.node-icon { font-size: 16px; }
.node-sub { font-size: 10px; color: #0284c7; opacity: 0.7; margin-top: 2px; }
.merge-node :deep(.vue-flow__handle) { width: 12px; height: 12px; background: #b0bec5; border: 2px solid #fff; border-radius: 50%; transition: all 0.15s; }
.merge-node :deep(.vue-flow__handle:hover) { width: 16px; height: 16px; background: #0ea5e9; box-shadow: 0 0 0 4px rgba(14,165,233,0.2); cursor: crosshair; }
.merge-node :deep(.vue-flow__handle-top) { top: -6px; }
.merge-node :deep(.vue-flow__handle-bottom) { bottom: -6px; }
</style>
