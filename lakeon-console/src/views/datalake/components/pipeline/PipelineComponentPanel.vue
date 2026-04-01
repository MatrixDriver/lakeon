<template>
  <div class="component-panel">
    <div class="panel-header">
      <span class="panel-title">组件库</span>
    </div>
    <div class="panel-placeholder">
      <p>拖拽组件到画布</p>
      <p class="hint">完整组件面板将在后续版本实现</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { PipelineComponent } from '@/api/pipeline'

defineProps<{
  components: PipelineComponent[]
}>()

defineEmits<{
  'drag-start': [component: PipelineComponent]
}>()
</script>

<style scoped>
.component-panel {
  width: 220px; border-right: 1px solid #e8e4df; background: #fff;
  display: flex; flex-direction: column; overflow-y: auto;
}
.panel-header {
  padding: 12px 14px; border-bottom: 1px solid #e8e4df;
  font-size: 13px; font-weight: 600; color: #2c3e50;
}
.panel-placeholder {
  padding: 20px 14px; text-align: center; color: #999; font-size: 12px;
}
.hint { font-size: 11px; color: #ccc; margin-top: 4px; }
</style>
