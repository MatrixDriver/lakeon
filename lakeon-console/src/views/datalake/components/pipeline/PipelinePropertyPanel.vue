<template>
  <div class="property-panel">
    <div class="panel-header">
      <span class="panel-title">属性</span>
    </div>
    <div class="panel-body">
      <div class="prop-row">
        <label>节点 ID</label>
        <span class="prop-value">{{ node?.id }}</span>
      </div>
      <div class="prop-row">
        <label>组件</label>
        <span class="prop-value">{{ node?.data?.step?.component || '—' }}</span>
      </div>
      <div class="panel-placeholder">
        <p class="hint">完整属性面板将在后续版本实现</p>
      </div>
      <button class="btn btn-text btn-small btn-danger-text" style="margin-top: 12px;" @click="node?.id && $emit('delete', node.id)">
        删除节点
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Node } from '@vue-flow/core'
import type { PipelineComponent } from '@/api/pipeline'

defineProps<{
  node: Node | null
  components: PipelineComponent[]
}>()

defineEmits<{
  'update:params': [nodeId: string, params: Record<string, any>]
  delete: [nodeId: string]
}>()
</script>

<style scoped>
.property-panel {
  width: 260px; border-left: 1px solid #e8e4df; background: #fff;
  display: flex; flex-direction: column; overflow-y: auto;
}
.panel-header {
  padding: 12px 14px; border-bottom: 1px solid #e8e4df;
  font-size: 13px; font-weight: 600; color: #2c3e50;
}
.panel-body { padding: 12px 14px; }
.prop-row { margin-bottom: 10px; }
.prop-row label { display: block; font-size: 11px; color: #999; margin-bottom: 2px; }
.prop-value { font-size: 13px; color: #333; }
.panel-placeholder { text-align: center; color: #999; font-size: 12px; margin-top: 16px; }
.hint { font-size: 11px; color: #ccc; }
</style>
