<template>
  <div class="pipeline-node fanout-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">⑂</span>
      <span class="node-label">{{ data.step?.component || 'Fan-out' }}</span>
    </div>
    <div class="node-sub">1 → N 裂变</div>
    <div v-if="metricsText" class="node-metrics">{{ metricsText }}</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { parseMetrics } from '@/api/pipeline'

const props = defineProps<{ data: any; selected?: boolean }>()

const metricsText = computed(() => {
  const m = parseMetrics(props.data.metrics || null)
  if (m.output_count != null) return `→ ${m.output_count} items`
  return ''
})
</script>

<style scoped>
.fanout-node {
  border: 2px solid #f97316; background: #fff7ed; border-radius: 8px;
  padding: 10px 14px; min-width: 160px; font-size: 12px; position: relative;
}
.fanout-node.selected { box-shadow: 0 0 0 3px rgba(249, 115, 22, 0.25); }
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #9a3412; }
.node-icon { font-size: 16px; }
.node-sub { font-size: 10px; color: #c2410c; opacity: 0.7; margin-top: 2px; }
.node-metrics { margin-top: 4px; font-size: 10px; padding: 2px 6px; background: rgba(0,0,0,0.05); border-radius: 3px; }
</style>
