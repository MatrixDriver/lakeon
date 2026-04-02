<template>
  <div class="pipeline-node input-node">
    <Handle type="source" :position="Position.Bottom" />
    <div class="node-header">
      <svg class="node-icon-svg" viewBox="0 0 20 20" width="16" height="16" fill="currentColor">
        <path d="M4 3a2 2 0 00-2 2v2a2 2 0 002 2h12a2 2 0 002-2V5a2 2 0 00-2-2H4zm0 6a2 2 0 00-2 2v2a2 2 0 002 2h12a2 2 0 002-2v-2a2 2 0 00-2-2H4zm1-4h2v2H5V5zm0 6h2v2H5v-2z"/>
      </svg>
      <span class="node-label">输入数据集</span>
    </div>
    <div class="node-sub">{{ data.datasetName || 'OBS / 数据集' }}</div>
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'

defineProps<{
  data: any
  selected?: boolean
}>()
</script>

<style scoped>
.input-node {
  border: 2px solid #22c55e;
  border-radius: 8px;
  padding: 10px 14px;
  min-width: 160px;
  font-size: 12px;
  position: relative;
  background: #f0fdf4;
  color: #166534;
  transition: box-shadow 0.15s;
}
.input-node.selected {
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.25);
}
.node-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.node-icon-svg {
  flex-shrink: 0;
}
.node-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.node-sub {
  font-size: 10px;
  opacity: 0.7;
  margin-top: 2px;
}

/* 连接点样式 — 与其他节点一致的大圆点 */
.input-node :deep(.vue-flow__handle) {
  width: 12px;
  height: 12px;
  background: #22c55e;
  border: 2px solid #fff;
  border-radius: 50%;
  transition: all 0.15s ease;
}
.input-node :deep(.vue-flow__handle:hover) {
  width: 16px;
  height: 16px;
  background: #16a34a;
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.2);
  cursor: crosshair;
}
.input-node :deep(.vue-flow__handle-bottom) {
  bottom: -6px;
}
</style>
