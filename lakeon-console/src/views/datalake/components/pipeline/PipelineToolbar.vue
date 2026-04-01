<template>
  <div class="pipeline-toolbar">
    <div class="toolbar-left">
      <button class="tb-btn" @click="$emit('undo')" title="撤销" :disabled="!canUndo">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><path d="M2 8l4-4v2.5C10 6.5 13 8 14 12c-1.5-3-4-4-8-4V10L2 8z"/></svg>
      </button>
      <button class="tb-btn" @click="$emit('redo')" title="重做" :disabled="!canRedo">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><path d="M14 8l-4-4v2.5C6 6.5 3 8 2 12c1.5-3 4-4 8-4V10l4-2z"/></svg>
      </button>
      <div class="tb-sep"></div>
      <button class="tb-btn" @click="$emit('autoLayout')" title="自动布局">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><rect x="1" y="1" width="5" height="5" rx="1"/><rect x="10" y="1" width="5" height="5" rx="1"/><rect x="5.5" y="10" width="5" height="5" rx="1"/><line x1="3.5" y1="6" x2="3.5" y2="10" stroke="currentColor" stroke-width="1"/><line x1="12.5" y1="6" x2="12.5" y2="10" stroke="currentColor" stroke-width="1"/></svg>
      </button>
      <button class="tb-btn" @click="$emit('fitView')" title="适应视图">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2" y="2" width="12" height="12" rx="1"/><line x1="2" y1="8" x2="5" y2="8"/><line x1="11" y1="8" x2="14" y2="8"/><line x1="8" y1="2" x2="8" y2="5"/><line x1="8" y1="11" x2="8" y2="14"/></svg>
      </button>
    </div>
    <div class="toolbar-center">
      <div class="view-switch">
        <button class="vs-btn" :class="{ active: mode === 'dag' }" @click="$emit('update:mode', 'dag')">DAG</button>
        <button class="vs-btn" :class="{ active: mode === 'yaml' }" @click="$emit('update:mode', 'yaml')">YAML</button>
      </div>
    </div>
    <div class="toolbar-right">
      <button class="btn btn-secondary" @click="$emit('saveDraft')">保存草稿</button>
      <button class="btn btn-primary" @click="$emit('publish')">发布版本</button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  mode: 'dag' | 'yaml'
  canUndo: boolean
  canRedo: boolean
}>()

defineEmits<{
  undo: []
  redo: []
  autoLayout: []
  fitView: []
  saveDraft: []
  publish: []
  'update:mode': [mode: 'dag' | 'yaml']
}>()
</script>

<style scoped>
.pipeline-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 12px; background: #fff; border-bottom: 1px solid #e8e4df;
}
.toolbar-left, .toolbar-right { display: flex; align-items: center; gap: 4px; }
.tb-btn {
  padding: 4px 8px; border: none; background: transparent; cursor: pointer;
  color: #666; border-radius: 4px; transition: all 0.12s;
  display: flex; align-items: center;
}
.tb-btn:hover:not(:disabled) { background: #f5f3f0; color: #2c3e50; }
.tb-btn:disabled { opacity: 0.3; cursor: default; }
.tb-sep { width: 1px; height: 16px; background: #e8e4df; margin: 0 4px; }
.view-switch { display: flex; border: 1px solid #e8e4df; border-radius: 4px; overflow: hidden; }
.vs-btn {
  padding: 3px 12px; border: none; background: #fff; cursor: pointer;
  font-size: 12px; color: #666; transition: all 0.12s;
}
.vs-btn:not(:last-child) { border-right: 1px solid #e8e4df; }
.vs-btn.active { background: #2a4d6a; color: #fff; }
</style>
