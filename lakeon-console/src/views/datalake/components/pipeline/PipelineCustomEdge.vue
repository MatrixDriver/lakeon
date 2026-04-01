<template>
  <BaseEdge :id="id" :path="path[0]" :marker-end="markerEnd" :style="edgeStyle" />
  <EdgeLabelRenderer v-if="data?.label">
    <div
      class="edge-label"
      :style="{
        position: 'absolute',
        transform: `translate(-50%, -50%) translate(${path[1]}px,${path[2]}px)`,
        pointerEvents: 'all',
      }"
    >
      {{ data.label }}
    </div>
  </EdgeLabelRenderer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@vue-flow/core'

const props = defineProps<EdgeProps>()

const path = computed(() => getBezierPath({
  sourceX: props.sourceX,
  sourceY: props.sourceY,
  targetX: props.targetX,
  targetY: props.targetY,
  sourcePosition: props.sourcePosition,
  targetPosition: props.targetPosition,
}))

const edgeStyle = computed(() => ({
  stroke: props.data?.animated ? '#3b82f6' : '#b0aaA0',
  strokeWidth: 2,
}))
</script>

<style scoped>
.edge-label {
  font-size: 10px; color: #666; background: #fff;
  padding: 1px 6px; border-radius: 3px; border: 1px solid #e8e4df;
  white-space: nowrap;
}
</style>
