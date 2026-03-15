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
        <button class="toolbar-btn ai-btn" :class="{ active: showAi }" @click="showAi = !showAi" title="AI SQL 助手">
          AI
        </button>
        <button class="toolbar-btn" :class="{ active: showHistory }" @click="toggleHistory" title="查询历史">
          历史 <span v-if="historyTotal > 0" class="history-count">{{ historyTotal > 99 ? '99+' : historyTotal }}</span>
        </button>
        <button class="toolbar-btn" @click="formatSql" title="格式化">格式化</button>
        <button class="toolbar-btn" @click="clearEditor" title="清空">清空</button>
      </div>
    </div>
    <!-- AI SQL Assistant Panel -->
    <div v-if="showAi" class="ai-panel">
      <div class="ai-header">
        <span class="ai-title">AI SQL 助手</span>
        <select v-model="aiModel" class="ai-model-select">
          <option v-for="m in aiModels" :key="m.id" :value="m.id">
            {{ m.name }} (输入¥{{ m.input_price }}/M · 输出¥{{ m.output_price }}/M)
          </option>
        </select>
      </div>
      <div class="ai-input-row">
        <input
          v-model="aiPrompt"
          class="ai-input"
          :placeholder="'描述你想查询的内容，如：查询最近7天注册的用户数'"
          @keyup.enter="generateSql"
          :disabled="aiLoading"
        />
        <button class="toolbar-btn run-btn" @click="generateSql" :disabled="aiLoading || !aiPrompt.trim()">
          {{ aiLoading ? '生成中...' : '生成 SQL' }}
        </button>
      </div>
      <div v-if="aiError" class="ai-error">{{ aiError }}</div>
      <div v-if="aiTokenInfo" class="ai-tokens">
        {{ aiTokenInfo }}
      </div>
      <div v-if="selectedModelInfo" class="ai-model-desc">{{ selectedModelInfo.desc }}</div>
    </div>
    <!-- Query History Panel -->
    <div v-if="showHistory" class="history-panel">
      <div class="history-header">
        <span class="history-title">查询历史</span>
        <div class="history-actions">
          <input
            v-model="historySearch"
            class="history-search"
            placeholder="搜索 SQL..."
            @input="debouncedFetchHistory"
          />
          <button v-if="historyTotal > 0" class="toolbar-btn" style="font-size: 12px; padding: 2px 8px;" @click="clearHistory">清空</button>
        </div>
      </div>
      <div v-if="historyLoading" class="history-empty">加载中...</div>
      <div v-else-if="queryHistory.length === 0" class="history-empty">暂无查询记录</div>
      <div v-else class="history-list">
        <div
          v-for="item in queryHistory"
          :key="item.id"
          class="history-item"
          @click="loadFromHistory(item)"
        >
          <div class="history-sql">{{ item.sql.length > 120 ? item.sql.slice(0, 120) + '...' : item.sql }}</div>
          <div class="history-meta">
            <span v-if="item.success" class="history-ok">{{ item.row_count ?? 0 }}行 · {{ item.duration_ms ?? 0 }}ms</span>
            <span v-else class="history-fail">失败</span>
            <span class="history-date">{{ formatHistoryDate(item.created_at) }}</span>
          </div>
        </div>
        <div v-if="historyTotal > queryHistory.length" class="history-more" @click="loadMoreHistory">
          加载更多 ({{ queryHistory.length }}/{{ historyTotal }})
        </div>
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
import { databaseApi, type QueryResult, type QueryHistoryItem, type AiModel } from '../api/database'
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

// ── AI SQL Assistant ──
const showAi = ref(false)
const aiPrompt = ref('')
const aiLoading = ref(false)
const aiError = ref('')
const aiTokenInfo = ref('')
const aiModels = ref<AiModel[]>([
  { id: 'deepseek-ai/DeepSeek-V3.2', name: 'DeepSeek V3.2', input_price: 2.0, output_price: 3.0, desc: '综合能力强，性价比高' },
  { id: 'Qwen/Qwen3-Coder-480B-A35B-Instruct', name: 'Qwen3 Coder 480B', input_price: 8.0, output_price: 16.0, desc: '最强代码模型，SQL 生成质量最高' },
  { id: 'Qwen/Qwen3-Coder-30B-A3B-Instruct', name: 'Qwen3 Coder 30B', input_price: 0.7, output_price: 2.8, desc: '轻量代码模型，速度快价格低' },
])
const aiModel = ref('deepseek-ai/DeepSeek-V3.2')

const selectedModelInfo = computed(() => aiModels.value.find(m => m.id === aiModel.value))

async function generateSql() {
  if (!aiPrompt.value.trim() || aiLoading.value) return
  aiLoading.value = true
  aiError.value = ''
  aiTokenInfo.value = ''
  try {
    const res = await databaseApi.generateSql(props.dbId, aiPrompt.value, aiModel.value)
    const data = res.data
    if (data.error) {
      aiError.value = data.error
    } else if (data.sql && editorView) {
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: data.sql },
      })
      // Show token usage and cost
      const model = selectedModelInfo.value
      if (data.input_tokens && model) {
        const inputCost = (data.input_tokens / 1_000_000) * model.input_price
        const outputCost = ((data.output_tokens || 0) / 1_000_000) * model.output_price
        const totalCost = inputCost + outputCost
        aiTokenInfo.value = `${data.input_tokens} + ${data.output_tokens || 0} tokens · ¥${totalCost.toFixed(4)}`
      }
    }
  } catch (e: any) {
    aiError.value = e?.response?.data?.error?.message || e?.message || '生成失败'
  } finally {
    aiLoading.value = false
  }
}

// ── Query History (server-side) ──
const showHistory = ref(false)
const historyLoading = ref(false)
const historySearch = ref('')
const historyPage = ref(0)
const historyTotal = ref(0)
const queryHistory = ref<QueryHistoryItem[]>([])

let debounceTimer: ReturnType<typeof setTimeout> | null = null
function debouncedFetchHistory() {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => { historyPage.value = 0; fetchHistory() }, 300)
}

async function fetchHistory() {
  historyLoading.value = true
  try {
    const res = await databaseApi.getQueryHistory(props.dbId, {
      page: historyPage.value, size: 50, q: historySearch.value || undefined
    })
    const data = res.data
    if (historyPage.value === 0) {
      queryHistory.value = data.items
    } else {
      queryHistory.value.push(...data.items)
    }
    historyTotal.value = data.total
  } catch { /* ignore */ }
  historyLoading.value = false
}

function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value) {
    historyPage.value = 0
    historySearch.value = ''
    fetchHistory()
  }
}

function loadMoreHistory() {
  historyPage.value++
  fetchHistory()
}

function loadFromHistory(item: QueryHistoryItem) {
  if (!editorView) return
  editorView.dispatch({
    changes: { from: 0, to: editorView.state.doc.length, insert: item.sql },
  })
  showHistory.value = false
}

async function clearHistory() {
  try {
    await databaseApi.clearQueryHistory(props.dbId)
  } catch { /* ignore */ }
  queryHistory.value = []
  historyTotal.value = 0
}

function formatHistoryDate(ts: string): string {
  const d = new Date(ts)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) + ' ' +
    d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

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
    // History auto-saved by server on executeQuery
    if (showHistory.value) fetchHistory()
  } catch (e: any) {
    const data = e?.response?.data
    const msg = data?.error?.message || data?.message || e?.message || '执行失败'
    resultError.value = msg.replace(/^SQL execution failed:\s*/, '')
    if (showHistory.value) fetchHistory()
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

/* History */
.toolbar-btn.active {
  background: #e6f0ff;
  border-color: #0073e6;
  color: #0073e6;
}

.history-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  border-radius: 9px;
  background: #0073e6;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  padding: 0 4px;
  margin-left: 4px;
}

.history-panel {
  border-bottom: 1px solid #e8e8e8;
  background: #fafbfc;
  max-height: 240px;
  overflow-y: auto;
  flex-shrink: 0;
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
}

.history-title {
  font-size: 13px;
  font-weight: 600;
  color: #191919;
}

/* AI Assistant */
.ai-btn {
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border-color: #667eea;
  font-weight: 600;
}

.ai-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #5a6fd6, #6a4190);
  color: #fff;
  border-color: #5a6fd6;
}

.ai-btn.active {
  background: linear-gradient(135deg, #5a6fd6, #6a4190);
  border-color: #5a6fd6;
  color: #fff;
}

.ai-panel {
  border-bottom: 1px solid #e8e8e8;
  background: #f8f7ff;
  padding: 10px 12px;
  flex-shrink: 0;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.ai-title {
  font-size: 13px;
  font-weight: 600;
  color: #5a6fd6;
}

.ai-model-select {
  font-size: 12px;
  padding: 3px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  outline: none;
  max-width: 400px;
}

.ai-model-select:focus {
  border-color: #667eea;
}

.ai-input-row {
  display: flex;
  gap: 8px;
}

.ai-input {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  outline: none;
}

.ai-input:focus {
  border-color: #667eea;
}

.ai-error {
  margin-top: 6px;
  font-size: 12px;
  color: #e6393d;
}

.ai-tokens {
  margin-top: 6px;
  font-size: 11px;
  color: #52c41a;
}

.ai-model-desc {
  margin-top: 4px;
  font-size: 11px;
  color: #999;
}

.history-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-search {
  width: 180px;
  padding: 3px 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 12px;
  outline: none;
}

.history-search:focus {
  border-color: #0073e6;
}

.history-empty {
  padding: 24px;
  text-align: center;
  color: #999;
  font-size: 13px;
}

.history-more {
  padding: 8px 12px;
  text-align: center;
  font-size: 12px;
  color: #0073e6;
  cursor: pointer;
}

.history-more:hover {
  background: #e6f0ff;
}

.history-list {
  padding: 4px 0;
}

.history-item {
  padding: 8px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.1s;
}

.history-item:hover { background: #e6f0ff; }
.history-item:last-child { border-bottom: none; }

.history-sql {
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', monospace;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.history-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}

.history-ok { color: #52c41a; }
.history-fail { color: #cf1322; }
.history-date { color: #999; }
</style>
