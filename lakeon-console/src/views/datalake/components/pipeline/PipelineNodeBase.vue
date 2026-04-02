<template>
  <div
    class="pipeline-node"
    :class="{ selected: selected }"
    :style="nodeStyle"
  >
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <svg class="node-icon-svg" viewBox="0 0 24 24" width="14" height="14" fill="none" :stroke="colors.text" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="iconPath" /></svg>
      <span class="node-label">{{ data.label || data.step?.component }}</span>
    </div>
    <div class="node-category">{{ categoryLabel }}</div>
    <!-- 运行监控模式下的 metrics 气泡 -->
    <div v-if="metricsText" class="node-metrics">{{ metricsText }}</div>
    <!-- 多输出端口（条件分支） -->
    <Handle
      v-if="!hasBranches"
      type="source"
      :position="Position.Bottom"
    />
    <Handle
      v-for="(branch, i) in branches"
      :key="branch"
      type="source"
      :id="'branch-' + branch"
      :position="Position.Bottom"
      :style="{ left: `${(i + 1) * 100 / (branches.length + 1)}%` }"
    />
    <div v-if="hasBranches" class="branch-labels">
      <span v-for="branch in branches" :key="branch" class="branch-label">{{ branch }}</span>
    </div>
    <!-- checkpoint 标识 -->
    <div v-if="data.step?.checkpoint" class="checkpoint-badge" title="Checkpoint">CP</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { categoryColors, categoryLabels, categoryIcons } from './nodeStyles'
import { parseMetrics, parseOutputBranches, type ComponentCategory } from '@/api/pipeline'

const props = defineProps<{
  data: any
  selected?: boolean
}>()

const category = computed<ComponentCategory>(() => props.data.category || props.data.step?.category || 'DATA_PREP')
const colors = computed(() => categoryColors[category.value] || categoryColors.DATA_PREP)
const iconPath = computed(() => categoryIcons[category.value] || categoryIcons.DATA_PREP)
const categoryLabel = computed(() => categoryLabels[category.value] || category.value)

const branches = computed(() => parseOutputBranches(props.data.step?.output_branches_raw || null)
  || props.data.step?.output_branches || [])
const hasBranches = computed(() => branches.value.length > 0)

const metricsText = computed(() => {
  const m = parseMetrics(props.data.metrics || null)
  if (m.input_count != null && m.output_count != null) {
    return `${m.input_count} → ${m.output_count}${m.retention ? ` (${m.retention})` : ''}`
  }
  return ''
})

const nodeStyle = computed(() => ({
  background: colors.value.bg,
  borderColor: colors.value.border,
  color: colors.value.text,
}))
</script>

<style scoped>
.pipeline-node {
  border: 2px solid; border-radius: 8px; padding: 10px 14px;
  min-width: 180px; font-size: 12px; position: relative;
  transition: box-shadow 0.15s;
}
.pipeline-node.selected { box-shadow: 0 0 0 3px rgba(42, 77, 106, 0.25); }
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.node-icon { font-size: 14px; }
.node-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-category { font-size: 10px; opacity: 0.7; margin-top: 2px; }
.node-metrics {
  margin-top: 4px; font-size: 10px; padding: 2px 6px;
  background: rgba(0,0,0,0.05); border-radius: 3px; display: inline-block;
}
.branch-labels {
  display: flex; justify-content: space-around; margin-top: 4px;
  font-size: 9px; opacity: 0.6;
}
.checkpoint-badge {
  position: absolute; top: -6px; right: -6px;
  background: #2a4d6a; color: #fff; font-size: 8px; font-weight: 700;
  padding: 1px 4px; border-radius: 3px;
}

/* 连接点样式 — 增大尺寸，hover 高亮 */
.pipeline-node :deep(.vue-flow__handle) {
  width: 12px; height: 12px;
  background: #b0bec5;
  border: 2px solid #fff;
  border-radius: 50%;
  transition: all 0.15s ease;
}
.pipeline-node :deep(.vue-flow__handle:hover) {
  width: 16px; height: 16px;
  background: #2a4d6a;
  box-shadow: 0 0 0 4px rgba(42, 77, 106, 0.2);
  cursor: crosshair;
}
.pipeline-node :deep(.vue-flow__handle.connecting) {
  background: #e67e22;
}
.pipeline-node :deep(.vue-flow__handle-top) {
  top: -6px;
}
.pipeline-node :deep(.vue-flow__handle-bottom) {
  bottom: -6px;
}
</style>
