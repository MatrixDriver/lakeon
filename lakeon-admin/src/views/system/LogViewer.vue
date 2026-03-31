<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">日志查看</h1>
    </div>

    <div class="section-card">
      <div class="controls-row">
        <div class="control-group">
          <label class="control-label">组件</label>
          <select v-model="selectedComponent" class="form-input control-select" @change="fetchLogs">
            <option value="lakeon-api">API</option>
            <option value="pageserver">Pageserver</option>
            <option value="safekeeper">Safekeeper</option>
            <option value="proxy">Proxy</option>
          </select>
        </div>
        <div class="control-group">
          <label class="control-label">Compute Pod</label>
          <div style="display:flex;gap:6px;align-items:center">
            <input v-model="computeDbId" class="form-input control-input" placeholder="数据库 ID" @keyup.enter="fetchComputeLogs" />
            <button class="btn btn-default btn-small" @click="fetchComputeLogs" :disabled="!computeDbId">查看</button>
          </div>
        </div>
        <div class="control-group">
          <label class="control-label">行数</label>
          <input v-model.number="tailLines" type="number" class="form-input control-input-small" min="50" max="2000" />
        </div>
        <div class="control-group">
          <label class="control-label">搜索</label>
          <input v-model="searchText" class="form-input control-input" placeholder="关键词过滤..." />
        </div>
        <div class="control-actions">
          <button class="btn btn-primary btn-small" @click="fetchLogs" :disabled="loading">
            {{ loading ? '加载中...' : '刷新' }}
          </button>
          <button class="btn btn-default btn-small" @click="downloadLogs" :disabled="downloading">
            {{ downloading ? '导出中...' : '导出全量' }}
          </button>
          <label class="auto-refresh-label">
            <input type="checkbox" v-model="autoRefresh" />
            自动刷新
          </label>
        </div>
      </div>

      <div class="terminal" ref="terminalRef">
        <div v-if="loading && !logLines.length" class="terminal-empty">Loading...</div>
        <div v-for="(line, i) in filteredLines" :key="i" class="terminal-line" :class="{ 'highlight': searchText && line.toLowerCase().includes(searchText.toLowerCase()) }">{{ line }}</div>
        <div v-if="!loading && !logLines.length" class="terminal-empty">No logs available</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import client from '../../api/client'

const selectedComponent = ref('lakeon-api')
const computeDbId = ref('')
const tailLines = ref(200)
const searchText = ref('')
const loading = ref(false)
const downloading = ref(false)
const autoRefresh = ref(false)
const logContent = ref('')
const terminalRef = ref<HTMLElement | null>(null)
let refreshTimer: number | null = null

const logLines = computed(() => {
  if (!logContent.value) return []
  return logContent.value.split('\n')
})

const filteredLines = computed(() => logLines.value)

async function fetchLogs() {
  loading.value = true
  try {
    const { data } = await client.get(`/logs/${selectedComponent.value}`, {
      params: { tail: tailLines.value },
      transformResponse: [(d: string) => d],
    })
    logContent.value = data
    await nextTick()
    scrollToBottom()
  } catch (e) {
    logContent.value = 'Failed to fetch logs: ' + (e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}

async function fetchComputeLogs() {
  if (!computeDbId.value) return
  const podName = 'compute-' + computeDbId.value.replace(/_/g, '-')
  selectedComponent.value = podName
  await fetchLogs()
}

async function downloadLogs() {
  downloading.value = true
  try {
    const { data } = await client.get(`/logs/${selectedComponent.value}`, {
      params: { tail: 0 },
      transformResponse: [(d: string) => d],
    })
    const blob = new Blob([data], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    const ts = new Date().toISOString().slice(0, 19).replace(/:/g, '-')
    a.href = url
    a.download = `${selectedComponent.value}-${ts}.log`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    alert('导出失败: ' + (e instanceof Error ? e.message : String(e)))
  } finally {
    downloading.value = false
  }
}

function scrollToBottom() {
  if (terminalRef.value) {
    terminalRef.value.scrollTop = terminalRef.value.scrollHeight
  }
}

watch(autoRefresh, (val) => {
  if (val) {
    refreshTimer = window.setInterval(fetchLogs, 5000)
  } else if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})

onMounted(() => { fetchLogs() })
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<style scoped>
.controls-row {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.control-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.control-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}
.control-select { width: 140px; height: 32px; }
.control-input { width: 160px; height: 32px; }
.control-input-small { width: 80px; height: 32px; }
.control-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 2px;
}
.auto-refresh-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
  white-space: nowrap;
}
.terminal {
  background: #1a1a1a;
  border-radius: 6px;
  padding: 16px;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #e0e0e0;
  max-height: 600px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.terminal-line { padding: 1px 0; }
.terminal-line.highlight {
  background: rgba(255, 235, 59, 0.2);
  color: #ffeb3b;
}
.terminal-empty {
  color: #666;
  font-style: italic;
}
</style>
