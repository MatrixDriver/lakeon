<template>
  <div class="sql-editor">
    <div class="editor-toolbar">
      <div class="toolbar-left">
        <button class="toolbar-btn run-btn" @click="executeQuery" :disabled="executing" title="执行 (Ctrl+Enter)">
          <span v-if="executing" class="spinner"></span>
          <span v-else>▶</span>
          {{ executing ? '执行中...' : '执行' }}
        </button>
        <span class="toolbar-hint">Ctrl+Enter 执行</span>
      </div>
      <div class="toolbar-right">
        <button class="toolbar-btn" @click="formatSql" title="格式化">格式化</button>
        <button class="toolbar-btn" @click="clearEditor" title="清空">清空</button>
      </div>
    </div>
    <div ref="editorContainer" class="editor-container"></div>
    <div class="result-panel" v-if="result || resultError">
      <div class="result-header">
        <span v-if="result" class="result-info">
          {{ result.is_select ? `${result.row_count} 行` : `影响 ${result.row_count} 行` }}
          · {{ result.execution_time_ms }}ms
        </span>
        <span v-if="resultError" class="result-error-text">{{ resultError }}</span>
        <button class="toolbar-btn" @click="clearResult" title="关闭">✕</button>
      </div>
      <div v-if="result && result.is_select && result.columns.length > 0" class="result-table-wrapper">
        <table class="result-table">
          <thead>
            <tr>
              <th v-for="col in result.columns" :key="col">{{ col }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in pagedResultRows" :key="i">
              <td v-for="(cell, j) in row" :key="j">{{ formatCell(cell) }}</td>
            </tr>
          </tbody>
        </table>
        <TableFooter
          v-if="result.rows.length > 0"
          :total="result.rows.length"
          v-model:pageSize="resultPageSize"
          v-model:currentPage="resultCurrentPage"
          :pageSizeOptions="[20, 50, 100]"
        />
      </div>
      <div v-else-if="result && !result.is_select" class="result-message">
        语句执行成功，影响 {{ result.row_count }} 行
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { EditorView, keymap, placeholder } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { sql, PostgreSQL } from '@codemirror/lang-sql'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { autocompletion } from '@codemirror/autocomplete'
import { syntaxHighlighting, defaultHighlightStyle, bracketMatching } from '@codemirror/language'
import { databaseApi, type QueryResult } from '../api/database'
import TableFooter from './TableFooter.vue'

const props = defineProps<{
  dbId: string
  schema?: Record<string, string[]>
}>()

const editorContainer = ref<HTMLElement | null>(null)
const executing = ref(false)
const result = ref<QueryResult | null>(null)
const resultError = ref('')
const resultPageSize = ref(20)
const resultCurrentPage = ref(1)

const pagedResultRows = computed(() => {
  if (!result.value) return []
  const start = (resultCurrentPage.value - 1) * resultPageSize.value
  return result.value.rows.slice(start, start + resultPageSize.value)
})

let editorView: EditorView | null = null

function buildSchema(): Record<string, string[]> {
  return props.schema || {}
}

function createEditor() {
  if (!editorContainer.value) return

  const runQuery = keymap.of([{
    key: 'Ctrl-Enter',
    mac: 'Cmd-Enter',
    run: () => { executeQuery(); return true },
  }])

  const state = EditorState.create({
    doc: '',
    extensions: [
      runQuery,
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap]),
      sql({
        dialect: PostgreSQL,
        upperCaseKeywords: true,
        schema: buildSchema(),
      }),
      autocompletion(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      bracketMatching(),
      placeholder('输入 SQL 语句...'),
      EditorView.lineWrapping,
      EditorView.theme({
        '&': { fontSize: '13px', height: '100%' },
        '.cm-editor': { height: '100%' },
        '.cm-scroller': { overflow: 'auto', fontFamily: "'JetBrains Mono', 'Fira Code', 'SF Mono', 'Menlo', monospace" },
        '.cm-content': { padding: '8px 0' },
        '.cm-gutters': { backgroundColor: '#f8f9fa', borderRight: '1px solid #e8e8e8', color: '#999', minWidth: '36px' },
        '.cm-activeLineGutter': { backgroundColor: '#e8f0fe' },
        '.cm-activeLine': { backgroundColor: '#f5f8ff' },
        '.cm-tooltip.cm-tooltip-autocomplete': { border: '1px solid #d9d9d9', borderRadius: '4px' },
        '.cm-tooltip.cm-tooltip-autocomplete > ul > li[aria-selected]': { background: '#0073e6', color: '#fff' },
      }),
      EditorView.updateListener.of(() => {}),
    ],
  })

  editorView = new EditorView({ state, parent: editorContainer.value })
}

watch(() => props.schema, () => {
  if (!editorView) return
  const doc = editorView.state.doc.toString()
  const cursor = editorView.state.selection.main.head
  editorView.destroy()
  createEditor()
  if (doc) {
    editorView!.dispatch({
      changes: { from: 0, to: editorView!.state.doc.length, insert: doc },
      selection: { anchor: Math.min(cursor, doc.length) },
    })
  }
}, { deep: true })

async function executeQuery() {
  if (!editorView || executing.value) return
  const doc = editorView.state.doc.toString().trim()
  if (!doc) return

  // Use selection if exists, otherwise full doc
  const sel = editorView.state.selection.main
  const query = sel.from !== sel.to
    ? editorView.state.sliceDoc(sel.from, sel.to).trim()
    : doc

  if (!query) return

  executing.value = true
  result.value = null
  resultError.value = ''
  resultCurrentPage.value = 1

  try {
    const res = await databaseApi.executeQuery(props.dbId, query)
    result.value = res.data
  } catch (e: any) {
    resultError.value = e?.response?.data?.message || e?.message || '执行失败'
  } finally {
    executing.value = false
  }
}

function formatCell(cell: unknown): string {
  if (cell === null || cell === undefined) return 'NULL'
  if (typeof cell === 'object') return JSON.stringify(cell)
  return String(cell)
}

function formatSql() {
  if (!editorView) return
  const doc = editorView.state.doc.toString()
  // Simple formatting: uppercase keywords, add newlines
  const keywords = ['SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'ORDER BY', 'GROUP BY', 'HAVING', 'LIMIT', 'OFFSET', 'JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'INNER JOIN', 'ON', 'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE FROM', 'CREATE TABLE', 'ALTER TABLE', 'DROP TABLE']
  let formatted = doc
  for (const kw of keywords) {
    const regex = new RegExp('\\b' + kw.replace(' ', '\\s+') + '\\b', 'gi')
    formatted = formatted.replace(regex, '\n' + kw)
  }
  formatted = formatted.replace(/^\n+/, '').replace(/\n{3,}/g, '\n\n')
  editorView.dispatch({
    changes: { from: 0, to: editorView.state.doc.length, insert: formatted },
  })
}

function clearEditor() {
  if (!editorView) return
  editorView.dispatch({
    changes: { from: 0, to: editorView.state.doc.length, insert: '' },
  })
  clearResult()
}

function clearResult() {
  result.value = null
  resultError.value = ''
}

onMounted(createEditor)
onBeforeUnmount(() => { editorView?.destroy() })

defineExpose({ executeQuery })
</script>

<style scoped>
.sql-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  border-bottom: 1px solid #e8e8e8;
  background: #fafafa;
  flex-shrink: 0;
}

.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
  color: #333;
  transition: all 0.15s;
}

.toolbar-btn:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.toolbar-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.run-btn {
  background: #0073e6;
  color: #fff;
  border-color: #0073e6;
  font-weight: 500;
}

.run-btn:hover:not(:disabled) {
  background: #005bb5;
  color: #fff;
}

.toolbar-hint {
  font-size: 12px;
  color: #999;
}

.editor-container {
  flex: 1;
  min-height: 120px;
  overflow: hidden;
}

.result-panel {
  border-top: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
  max-height: 50%;
  min-height: 80px;
}

.result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: #fafafa;
  border-bottom: 1px solid #e8e8e8;
  flex-shrink: 0;
}

.result-info {
  font-size: 13px;
  color: #52c41a;
  font-weight: 500;
}

.result-error-text {
  font-size: 13px;
  color: #e6393d;
}

.result-table-wrapper {
  flex: 1;
  overflow: auto;
}

.result-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.result-table th {
  position: sticky;
  top: 0;
  background: #f5f7fa;
  padding: 6px 10px;
  text-align: left;
  font-weight: 600;
  color: #333;
  border-bottom: 1px solid #e8e8e8;
  white-space: nowrap;
}

.result-table td {
  padding: 5px 10px;
  border-bottom: 1px solid #f0f0f0;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #333;
}

.result-table tbody tr:hover {
  background: #f5f8ff;
}

.result-message {
  padding: 16px;
  font-size: 14px;
  color: #52c41a;
}

.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
