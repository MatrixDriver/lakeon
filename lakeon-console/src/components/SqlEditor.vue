<template>
  <div class="sql-editor">
    <div class="editor-area">
      <div ref="editorContainer" class="cm-container"></div>
      <div class="editor-toolbar">
        <button class="btn btn-primary btn-small" :disabled="executing" @click="runQuery">
          {{ executing ? '执行中...' : '执行' }}
        </button>
        <span class="shortcut-hint">Ctrl+Enter 执行</span>
      </div>
    </div>
    <div class="result-area">
      <div v-if="error" class="result-error">{{ error }}</div>
      <div v-else-if="!result" class="result-empty">执行 SQL 查看结果</div>
      <template v-else>
        <div class="result-header">
          <span v-if="result.is_select">返回 {{ result.row_count }} 行</span>
          <span v-else>影响 {{ result.row_count }} 行</span>
          <span class="result-time">{{ result.execution_time_ms }}ms</span>
        </div>
        <div v-if="result.is_select && result.columns.length > 0" class="result-table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th v-for="col in result.columns" :key="col">{{ col }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, ri) in result.rows" :key="ri">
                <td v-for="(cell, ci) in row" :key="ci" :title="cellTitle(cell)">
                  <span v-if="cell === null" class="null-value">NULL</span>
                  <span v-else>{{ formatCell(cell) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { sql, PostgreSQL } from '@codemirror/lang-sql'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { databaseApi, type QueryResult } from '../api/database'

const props = defineProps<{ dbId: string }>()
const emit = defineEmits<{ executed: [] }>()

const editorContainer = ref<HTMLElement>()
const executing = ref(false)
const result = ref<QueryResult | null>(null)
const error = ref('')
let editorView: EditorView | null = null

function formatCell(value: unknown): string {
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function cellTitle(value: unknown): string {
  if (value === null) return 'NULL'
  return formatCell(value)
}

function getEditorContent(): string {
  if (!editorView) return ''
  // Use selection if there is one, otherwise use all content
  const state = editorView.state
  const sel = state.selection.main
  if (sel.from !== sel.to) {
    return state.sliceDoc(sel.from, sel.to)
  }
  return state.doc.toString()
}

async function runQuery() {
  const sqlText = getEditorContent().trim()
  if (!sqlText) return

  executing.value = true
  error.value = ''
  result.value = null
  try {
    const res = await databaseApi.executeQuery(props.dbId, sqlText)
    result.value = res.data
    if (!res.data.is_select) {
      emit('executed')
    }
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } }; message?: string }
    error.value = err.response?.data?.message || err.message || '执行失败'
  } finally {
    executing.value = false
  }
}

onMounted(() => {
  if (!editorContainer.value) return

  const runKeymap = keymap.of([{
    key: 'Ctrl-Enter',
    mac: 'Cmd-Enter',
    run: () => { runQuery(); return true },
  }])

  const state = EditorState.create({
    doc: 'SELECT 1;',
    extensions: [
      sql({ dialect: PostgreSQL }),
      syntaxHighlighting(defaultHighlightStyle),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap]),
      runKeymap,
      EditorView.lineWrapping,
      EditorView.theme({
        '&': { height: '100%', fontSize: '13px' },
        '.cm-scroller': { overflow: 'auto' },
        '.cm-content': { padding: '8px 0', fontFamily: 'monospace' },
        '.cm-gutters': { background: '#f7f8fa', border: 'none', color: '#8a8e99' },
        '.cm-activeLineGutter': { background: '#e6f4ff' },
        '.cm-activeLine': { background: '#f7f8fa' },
      }),
      EditorView.updateListener.of(() => {}),
    ],
  })

  editorView = new EditorView({
    state,
    parent: editorContainer.value,
  })
})

onBeforeUnmount(() => {
  editorView?.destroy()
})
</script>

<style scoped>
.sql-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.editor-area {
  display: flex;
  flex-direction: column;
  border-bottom: 1px solid #dfe1e6;
  min-height: 160px;
  max-height: 40%;
}

.cm-container {
  flex: 1;
  overflow: auto;
  border-bottom: 1px solid #ebebeb;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  background: #f7f8fa;
}

.shortcut-hint {
  font-size: 12px;
  color: #8a8e99;
}

.result-area {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
}

.result-empty {
  padding: 40px 20px;
  text-align: center;
  color: #8a8e99;
  font-size: 14px;
}

.result-error {
  padding: 12px 16px;
  margin: 12px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 2px;
  color: #d4380d;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}

.result-header {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  font-size: 13px;
  color: #575d6c;
  border-bottom: 1px solid #ebebeb;
  background: #f7f8fa;
}

.result-time {
  color: #8a8e99;
}

.result-table-wrap {
  flex: 1;
  overflow: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th,
.data-table td {
  padding: 5px 10px;
  border-bottom: 1px solid #ebebeb;
  text-align: left;
  white-space: nowrap;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.data-table th {
  background: #f7f8fa;
  font-weight: 600;
  color: #575d6c;
  position: sticky;
  top: 0;
  z-index: 1;
}

.data-table tbody tr:hover {
  background: #f7f8fa;
}

.null-value {
  color: #c2c6cc;
  font-style: italic;
  font-size: 12px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 16px;
  border: 1px solid transparent;
  border-radius: 2px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-primary {
  background: #0073e6;
  color: #fff;
}

.btn-primary:hover {
  background: #005bb5;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-small {
  padding: 3px 10px;
  font-size: 13px;
}
</style>
