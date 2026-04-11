<template>
  <div class="pipeline-node review-node" :class="{ selected, paused: isPaused }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">👤</span>
      <span class="node-label">{{ data.step?.component || '人工审核' }}</span>
    </div>
    <div class="node-sub">HUMAN_REVIEW — 需人工确认后继续</div>
    <div v-if="isPaused" class="pause-badge">等待审核</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{ data: any; selected?: boolean }>()

const isPaused = computed(() => props.data.runStatus === 'PAUSED')
</script>

<style scoped>
.review-node {
  border: 2px solid #9a5b25; background: color-mix(in oklch, var(--c-accent) 10%, #fff); border-radius: 8px;
  padding: 10px 14px; min-width: 180px; font-size: 12px; position: relative;
}
.review-node.selected { box-shadow: 0 0 0 3px rgba(168, 85, 247, 0.25); }
.review-node.paused { animation: pulse-border 2s infinite; }
@keyframes pulse-border {
  0%, 100% { border-color: #9a5b25; }
  50% { border-color: #eab308; box-shadow: 0 0 8px rgba(234, 179, 8, 0.3); }
}
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #6b21a8; }
.node-icon { font-size: 14px; }
.node-sub { font-size: 10px; color: #7e22ce; opacity: 0.7; margin-top: 2px; }
.pause-badge {
  position: absolute; top: -8px; right: -8px;
  background: #eab308; color: #fff; font-size: 9px; font-weight: 700;
  padding: 2px 6px; border-radius: 4px;
}
.review-node :deep(.vue-flow__handle) { width: 12px; height: 12px; background: #b0bec5; border: 2px solid #fff; border-radius: 50%; transition: all 0.15s; }
.review-node :deep(.vue-flow__handle:hover) { width: 16px; height: 16px; background: #9a5b25; box-shadow: 0 0 0 4px rgba(168,85,247,0.2); cursor: crosshair; }
.review-node :deep(.vue-flow__handle-top) { top: -6px; }
.review-node :deep(.vue-flow__handle-bottom) { bottom: -6px; }
</style>
