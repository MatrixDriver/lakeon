<template>
  <div class="nb-cell" :class="{ active: isActive, running: isRunning }">
    <div class="nb-cell-header">
      <span class="nb-cell-label">In [{{ execCount || ' ' }}]</span>
      <div class="nb-cell-actions">
        <span v-if="durationMs != null" class="nb-cell-time">{{ (durationMs / 1000).toFixed(1) }}s</span>
        <button class="nb-cell-btn" @click="$emit('run')" :disabled="isRunning" title="Run (Shift+Enter)">
          {{ isRunning ? '...' : '▶' }}
        </button>
        <button class="nb-cell-btn" @click="$emit('delete')" title="Delete cell">✕</button>
      </div>
    </div>
    <div ref="editorEl" class="nb-cell-editor"></div>
    <div v-if="outputs.length > 0" class="nb-cell-output">
      <template v-for="(out, i) in outputs" :key="i">
        <pre v-if="out.type === 'stdout' || out.type === 'stderr'" class="nb-out-text" :class="{ stderr: out.type === 'stderr' }">{{ out.text }}</pre>
        <pre v-else-if="out.type === 'error'" class="nb-out-error">{{ out.traceback }}</pre>
        <div v-else-if="out.type === 'result' && out.html" class="nb-out-html" v-html="out.html"></div>
        <pre v-else-if="out.type === 'result'" class="nb-out-text">{{ out.text }}</pre>
        <div v-else-if="out.type === 'plotly'" :ref="el => mountPlotly(el as HTMLElement, out.data)" class="nb-out-plotly"></div>
        <img v-else-if="out.type === 'image'" class="nb-out-image" :src="'data:' + out.mime + ';base64,' + out.data" />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine, keymap } from '@codemirror/view'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'
import type { NotebookMessage } from '../../../api/notebook'

const props = defineProps<{
  code: string
  isActive: boolean
  isRunning: boolean
  execCount: number | null
  durationMs: number | null
  outputs: NotebookMessage[]
}>()

const emit = defineEmits<{
  'update:code': [value: string]
  'run': []
  'delete': []
  'focus': []
  'advance': []
}>()

const editorEl = ref<HTMLElement | null>(null)
let view: EditorView | null = null

onMounted(() => {
  if (!editorEl.value) return
  const shiftEnterRun = keymap.of([
    { key: 'Shift-Enter', run: () => { emit('run'); emit('advance'); return true } },
    { key: 'Mod-Enter', run: () => { emit('run'); return true } },
  ])
  const state = EditorState.create({
    doc: props.code,
    extensions: [
      lineNumbers(), highlightActiveLine(), python(), oneDark, shiftEnterRun,
      EditorView.updateListener.of(update => {
        if (update.docChanged) emit('update:code', update.state.doc.toString())
        if (update.focusChanged && update.view.hasFocus) emit('focus')
      }),
      EditorView.theme({ '&': { minHeight: '40px', maxHeight: '400px' }, '.cm-scroller': { overflow: 'auto' } }),
    ],
  })
  view = new EditorView({ state, parent: editorEl.value })
})

onUnmounted(() => view?.destroy())

function mountPlotly(el: HTMLElement | null, data: any) {
  if (!el || !data) return
  nextTick(() => {
    const P = (window as any).Plotly
    if (P && el) P.newPlot(el, data.data || [], data.layout || {}, { responsive: true })
  })
}
</script>

<style scoped>
.nb-cell { border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 8px; }
.nb-cell.active { border-color: #2563eb; border-width: 2px; }
.nb-cell.running { border-color: #f59e0b; }
.nb-cell-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 12px; background: #f8fafc; border-bottom: 1px solid #e5e7eb;
}
.nb-cell.active .nb-cell-header { background: #eff6ff; border-color: #bfdbfe; }
.nb-cell-label { font-family: monospace; font-size: 11px; color: #6b7280; }
.nb-cell.active .nb-cell-label { color: #2563eb; }
.nb-cell-actions { display: flex; align-items: center; gap: 6px; }
.nb-cell-time { font-size: 10px; color: #9ca3af; }
.nb-cell-btn {
  background: none; border: 1px solid #d1d5db; border-radius: 4px;
  padding: 1px 8px; font-size: 11px; cursor: pointer; color: #374151;
}
.nb-cell-btn:hover { background: #f3f4f6; }
.nb-cell-btn:disabled { opacity: 0.4; cursor: default; }
.nb-cell-editor { min-height: 40px; }
.nb-cell-output { border-top: 1px solid #e5e7eb; background: #f9fafb; padding: 8px 14px; }
.nb-out-text { margin: 0; font-family: monospace; font-size: 12px; color: #334155; white-space: pre-wrap; }
.nb-out-text.stderr { color: #d97706; }
.nb-out-error { margin: 0; font-family: monospace; font-size: 12px; color: #ef4444; white-space: pre-wrap; background: #fef2f2; padding: 8px; border-radius: 4px; }
.nb-out-html { overflow-x: auto; font-size: 12px; }
.nb-out-html :deep(table) { border-collapse: collapse; font-size: 12px; }
.nb-out-html :deep(th), .nb-out-html :deep(td) { padding: 3px 10px; border: 1px solid #e5e7eb; }
.nb-out-html :deep(th) { background: #f1f5f9; font-weight: 600; }
.nb-out-plotly { min-height: 300px; }
.nb-out-image { max-width: 100%; border-radius: 4px; }
</style>
