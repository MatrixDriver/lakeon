<template>
  <div>
    <!-- Tabs -->
    <div class="tab-bar" style="margin-top: 20px; border-bottom: 1px solid #e5e5e5; display: flex; gap: 0;">
      <div v-for="tab in tabs" :key="tab.key"
           class="tab-item"
           :class="{ active: activeTab === tab.key }"
           @click="activeTab = tab.key">
        {{ tab.label }}
      </div>
    </div>

    <!-- Overview Tab -->
    <div v-if="activeTab === 'overview'" style="margin-top: 24px;">
      <div class="section-card" style="max-width: 600px;">
        <div class="section-header">概览</div>
        <div style="padding: 16px; display: grid; grid-template-columns: 120px 1fr; gap: 12px; font-size: 14px;">
          <span style="color: #999;">名称</span><span>{{ kb.name }}</span>
          <span style="color: #999;">描述</span><span>{{ kb.description || '-' }}</span>
          <span style="color: #999;">类型</span><span>数据表知识库</span>
          <span style="color: #999;">关联数据库</span><span>{{ kb.source_database_id || '-' }}</span>
          <span style="color: #999;">关联表</span>
          <span>
            <span v-for="t in (kb.table_names || [])" :key="t" class="table-name-badge">{{ t }}</span>
            <span v-if="!kb.table_names?.length" style="color: #999;">-</span>
          </span>
          <span style="color: #999;">状态</span>
          <span><span class="status-tag" :class="'tag-' + (kb.status === 'READY' ? 'green' : 'blue')">{{ kb.status === 'READY' ? '就绪' : kb.status }}</span></span>
          <span style="color: #999;">创建时间</span><span>{{ kb.created_at ? new Date(kb.created_at).toLocaleString('zh-CN') : '-' }}</span>
        </div>
      </div>
    </div>

    <!-- Tables Tab -->
    <div v-if="activeTab === 'tables'" style="margin-top: 24px;">
      <div v-if="tablesLoading" style="color: #999; font-size: 14px;">加载中...</div>
      <div v-else-if="tablesError" style="color: #e6393d; font-size: 14px;">{{ tablesError }}</div>
      <div v-else-if="tableInfoList.length === 0" class="empty-state" style="margin-top: 48px; text-align: center;">
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="#ccc" stroke-width="1.5">
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
          <line x1="3" y1="9" x2="21" y2="9"/>
          <line x1="3" y1="15" x2="21" y2="15"/>
          <line x1="9" y1="3" x2="9" y2="21"/>
        </svg>
        <p style="color: #999; margin-top: 12px;">暂无关联数据表</p>
      </div>
      <div v-else style="display: flex; flex-direction: column; gap: 16px; max-width: 800px;">
        <div v-for="tbl in tableInfoList" :key="tbl.table_name" class="table-card">
          <div class="table-card-header" @click="toggleTable(tbl.table_name)">
            <div style="display: flex; align-items: center; gap: 8px;">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#c67d3a" stroke-width="2">
                <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                <line x1="3" y1="9" x2="21" y2="9"/>
                <line x1="3" y1="15" x2="21" y2="15"/>
                <line x1="9" y1="3" x2="9" y2="21"/>
              </svg>
              <span style="font-weight: 600; font-size: 14px;">{{ tbl.table_name }}</span>
              <span style="font-size: 12px; color: #999; margin-left: 4px;">{{ tbl.columns.length }} 列</span>
            </div>
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#999" stroke-width="2"
                 style="transition: transform 0.2s;"
                 :style="{ transform: expandedTables.has(tbl.table_name) ? 'rotate(180deg)' : 'rotate(0deg)' }">
              <polyline points="6 9 12 15 18 9"/>
            </svg>
          </div>
          <div v-if="expandedTables.has(tbl.table_name)" class="table-card-body">
            <table class="col-table">
              <thead>
                <tr>
                  <th>列名</th>
                  <th>类型</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="col in tbl.columns" :key="col.name">
                  <td style="font-family: monospace; font-size: 13px;">{{ col.name }}</td>
                  <td style="font-size: 12px; color: #666;">{{ col.type }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>

    <!-- Query Tab -->
    <div v-if="activeTab === 'query'" style="margin-top: 24px; max-width: 800px; display: flex; flex-direction: column; height: calc(100vh - 260px); min-height: 400px;">
      <!-- Top controls row -->
      <div style="display: flex; align-items: center; justify-content: flex-end; margin-bottom: 12px; flex-shrink: 0;">
        <button v-if="chatMessages.length > 0" class="btn btn-text" style="font-size: 13px; color: #999;" @click="clearChat">清除对话</button>
      </div>

      <!-- Chat message list -->
      <div ref="chatContainer" class="chat-container">
        <div v-if="chatMessages.length === 0" class="chat-empty">
          <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="#ccc" stroke-width="1.5">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <p style="color: #bbb; margin-top: 10px; font-size: 14px;">用自然语言查询数据表</p>
          <p style="color: #ccc; font-size: 12px; margin-top: 4px;">AI 将把自然语言转换为 SQL 并执行</p>
        </div>

        <div v-for="(msg, idx) in chatMessages" :key="idx" class="chat-message-row" :class="msg.role">
          <!-- User bubble -->
          <div v-if="msg.role === 'user'" class="chat-bubble user-bubble">
            {{ msg.content }}
          </div>

          <!-- Assistant result (SQL + table) -->
          <div v-if="msg.role === 'assistant'" class="chat-bubble assistant-bubble">
            <div v-if="msg.error" style="color: #e6393d; font-size: 13px;">{{ msg.error }}</div>
            <template v-else>
              <!-- SQL block -->
              <div v-if="msg.sql" style="margin-bottom: 12px;">
                <div style="font-size: 11px; color: #888; margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.5px;">生成的 SQL</div>
                <pre class="sql-block">{{ msg.sql }}</pre>
              </div>
              <!-- Results table -->
              <div v-if="msg.columns && msg.columns.length > 0">
                <div style="font-size: 11px; color: #888; margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.5px;">
                  查询结果 ({{ msg.rows?.length ?? 0 }} 行)
                </div>
                <div class="result-table-wrapper">
                  <table class="result-table">
                    <thead>
                      <tr>
                        <th v-for="col in msg.columns" :key="col">{{ col }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(row, ri) in msg.rows" :key="ri">
                        <td v-for="(cell, ci) in row" :key="ci">{{ cell ?? 'NULL' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
              <div v-else-if="!msg.sql" style="font-size: 13px; color: #999;">未返回结果</div>
              <!-- Token info -->
              <div v-if="msg.model" style="margin-top: 10px; font-size: 11px; color: #bbb;">
                {{ msg.model }} · {{ msg.tokens?.input ?? 0 }}+{{ msg.tokens?.output ?? 0 }} tokens
              </div>
            </template>
          </div>

          <!-- Loading indicator -->
          <div v-if="msg.role === 'loading'" class="chat-bubble assistant-bubble loading-bubble">
            <span class="loading-dot"></span>
            <span class="loading-dot"></span>
            <span class="loading-dot"></span>
          </div>
        </div>
      </div>

      <!-- Chat input -->
      <div class="chat-input-row" style="flex-shrink: 0;">
        <input
          ref="chatInput"
          v-model="queryInput"
          class="form-input chat-input"
          placeholder="例如：统计每个用户的订单数量..."
          :disabled="isQuerying"
          @keyup.enter="handleQuery"
        />
        <button class="btn btn-primary" style="flex-shrink: 0;" :disabled="!queryInput.trim() || isQuerying" @click="handleQuery">
          {{ isQuerying ? '查询中...' : '发送' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, watch } from 'vue'
import { getTableInfo, searchKnowledge, type KnowledgeBase, type TableSearchResult } from '../../api/knowledge'

const props = defineProps<{ kb: KnowledgeBase }>()

const _validTabs = ['overview', 'tables', 'query']
const _hashTab = window.location.hash.replace('#', '')
const activeTab = ref(_validTabs.includes(_hashTab) ? _hashTab : 'overview')
watch(activeTab, (tab) => { window.location.hash = tab })
const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'tables', label: '数据表' },
  { key: 'query', label: '查询' },
]

// Tables tab state
const tablesLoading = ref(false)
const tablesError = ref('')
const tableInfoList = ref<{ table_name: string; columns: { name: string; type: string }[] }[]>([])
const expandedTables = ref(new Set<string>())

function toggleTable(name: string) {
  if (expandedTables.value.has(name)) {
    expandedTables.value.delete(name)
  } else {
    expandedTables.value.add(name)
  }
}

async function loadTableInfo() {
  tablesLoading.value = true
  tablesError.value = ''
  try {
    const resp = await getTableInfo(props.kb.id)
    tableInfoList.value = resp.data.tables
    // auto-expand first table if only one
    if (tableInfoList.value.length === 1) {
      expandedTables.value.add(tableInfoList.value[0]!.table_name)
    }
  } catch (e: any) {
    tablesError.value = '加载数据表信息失败: ' + (e.response?.data?.error?.message || e.message)
  } finally {
    tablesLoading.value = false
  }
}

// Query tab state
interface ChatMessage {
  role: 'user' | 'assistant' | 'loading'
  content: string
  sql?: string
  columns?: string[]
  rows?: any[][]
  model?: string
  tokens?: { input: number; output: number }
  error?: string
}

const chatMessages = ref<ChatMessage[]>([])
const queryInput = ref('')
const isQuerying = ref(false)
const chatContainer = ref<HTMLElement | null>(null)
const chatInput = ref<HTMLInputElement | null>(null)

function clearChat() {
  chatMessages.value = []
}

function scrollChatToBottom() {
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  }
}

async function handleQuery() {
  const query = queryInput.value.trim()
  if (!query || isQuerying.value) return

  isQuerying.value = true
  queryInput.value = ''

  chatMessages.value.push({ role: 'user', content: query })
  chatMessages.value.push({ role: 'loading', content: '' })
  await nextTick()
  scrollChatToBottom()

  try {
    const resp = await searchKnowledge(props.kb.id, query)
    const data = resp.data as any

    chatMessages.value.pop()

    if (data.type === 'sql') {
      const result = data as TableSearchResult
      chatMessages.value.push({
        role: 'assistant',
        content: query,
        sql: result.sql,
        columns: result.columns,
        rows: result.rows,
        model: result.model,
        tokens: result.tokens,
      })
    } else {
      // Fallback: unexpected shape
      chatMessages.value.push({
        role: 'assistant',
        content: query,
        error: '返回了非预期的响应格式',
      })
    }
  } catch (e: any) {
    chatMessages.value.pop()
    chatMessages.value.push({
      role: 'assistant',
      content: query,
      error: '查询出错: ' + (e.response?.data?.error?.message || e.message),
    })
  } finally {
    isQuerying.value = false
    await nextTick()
    scrollChatToBottom()
    chatInput.value?.focus()
  }
}

onMounted(() => {
  loadTableInfo()
})
</script>

<style scoped>
.tab-bar {
  display: flex;
  gap: 0;
}
.tab-item {
  padding: 10px 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.tab-item:hover {
  color: #333;
}
.tab-item.active {
  color: #9a5b25;
  font-weight: 600;
  border-bottom-color: #c67d3a;
}

.table-name-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  background: #f0f7ff;
  color: #9a5b25;
  border: 1px solid #b3d4f7;
  margin-right: 6px;
  font-family: monospace;
}

.table-card {
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.table-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  cursor: pointer;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  transition: background 0.15s;
}
.table-card-header:hover {
  background: #f0f7ff;
}
.table-card-body {
  padding: 12px 16px;
}
.col-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.col-table th {
  text-align: left;
  padding: 6px 10px;
  color: #666;
  font-weight: 500;
  background: #f9f9f9;
  border-bottom: 1px solid #eee;
}
.col-table td {
  padding: 6px 10px;
  border-bottom: 1px solid #f5f5f5;
  color: #333;
}
.col-table tr:last-child td {
  border-bottom: none;
}

/* Chat */
.chat-container {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #e5e5e5;
  border-radius: 10px 10px 0 0;
  padding: 16px;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
}
.chat-message-row {
  display: flex;
}
.chat-message-row.user {
  justify-content: flex-end;
}
.chat-message-row.assistant,
.chat-message-row.loading {
  justify-content: flex-start;
}
.chat-bubble {
  max-width: 92%;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.5;
}
.user-bubble {
  background: #9a5b25;
  color: #fff;
  border-bottom-right-radius: 3px;
}
.assistant-bubble {
  background: #fff;
  border: 1px solid #e5e5e5;
  border-bottom-left-radius: 3px;
  width: 100%;
  max-width: 100%;
}
.loading-bubble {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 12px 16px;
}
.loading-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #bbb;
  animation: dot-bounce 1.2s infinite ease-in-out;
}
.loading-dot:nth-child(2) { animation-delay: 0.2s; }
.loading-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-5px); opacity: 1; }
}
.chat-input-row {
  display: flex;
  gap: 8px;
  border: 1px solid #e5e5e5;
  border-top: none;
  border-radius: 0 0 10px 10px;
  padding: 10px 12px;
  background: #fff;
}
.chat-input {
  flex: 1;
  border-radius: 6px;
}

.sql-block {
  background: #1e1e2e;
  color: #cdd6f4;
  border-radius: 6px;
  padding: 10px 14px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
.result-table-wrapper {
  overflow-x: auto;
  border-radius: 6px;
  border: 1px solid #e5e5e5;
}
.result-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.result-table th {
  text-align: left;
  padding: 7px 12px;
  background: #f5f5f5;
  color: #555;
  font-weight: 600;
  border-bottom: 1px solid #e0e0e0;
  white-space: nowrap;
}
.result-table td {
  padding: 6px 12px;
  border-bottom: 1px solid #f0f0f0;
  color: #333;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-table tr:last-child td {
  border-bottom: none;
}
.result-table tr:hover td {
  background: #fafafa;
}
</style>
