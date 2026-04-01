<template>
  <div class="yaml-editor-wrap">
    <div class="yaml-toolbar">
      <span class="yaml-filename">pipeline.yaml</span>
      <button class="btn btn-text btn-small" @click="formatYaml">格式化</button>
    </div>
    <div ref="editorContainer" class="yaml-editor-container"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { EditorState } from '@codemirror/state'
import { yaml } from '@codemirror/lang-yaml'
import { oneDark } from '@codemirror/theme-one-dark'

const props = defineProps<{ value: string }>()
const emit = defineEmits<{ 'update:value': [value: string] }>()

const editorContainer = ref<HTMLElement | null>(null)
let view: EditorView | null = null
let updating = false

onMounted(() => {
  if (!editorContainer.value) return

  const state = EditorState.create({
    doc: props.value,
    extensions: [
      basicSetup,
      yaml(),
      oneDark,
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !updating) {
          emit('update:value', update.state.doc.toString())
        }
      }),
      EditorView.theme({
        '&': { height: '100%', fontSize: '13px' },
        '.cm-scroller': { overflow: 'auto' },
      }),
    ],
  })

  view = new EditorView({ state, parent: editorContainer.value })
})

watch(() => props.value, (newVal) => {
  if (!view) return
  const current = view.state.doc.toString()
  if (current !== newVal) {
    updating = true
    view.dispatch({
      changes: { from: 0, to: current.length, insert: newVal },
    })
    updating = false
  }
})

function formatYaml() {
  // Re-emit current value to trigger DAG round-trip formatting
  emit('update:value', props.value)
}

onBeforeUnmount(() => {
  view?.destroy()
})
</script>

<style scoped>
.yaml-editor-wrap { display: flex; flex-direction: column; height: 100%; }
.yaml-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 12px; background: #282c34; color: #abb2bf; font-size: 12px;
}
.yaml-filename { font-family: monospace; }
.yaml-editor-container { flex: 1; overflow: hidden; }
</style>
