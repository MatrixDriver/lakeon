<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">日志搜索</h1>
    </div>

    <!-- Filter Bar -->
    <div class="filter-bar">
      <select class="form-select" v-model="filters.component">
        <option value="">全部组件</option>
        <option value="lakeon-api">lakeon-api</option>
        <option value="knowledge-pipeline">knowledge-pipeline</option>
        <option value="pageserver">pageserver</option>
        <option value="safekeeper">safekeeper</option>
        <option value="proxy">proxy</option>
      </select>
      <select class="form-select" v-model="filters.level">
        <option value="">全部级别</option>
        <option value="ERROR">ERROR</option>
        <option value="WARN">WARN</option>
        <option value="INFO">INFO</option>
        <option value="DEBUG">DEBUG</option>
      </select>
      <input
        type="text"
        class="search-input"
        placeholder="租户 ID"
        v-model="filters.tenant_id"
        @keyup.enter="search"
      />
      <select class="form-select" v-model="filters.since">
        <option value="1h">最近 1 小时</option>
        <option value="6h">最近 6 小时</option>
        <option value="24h">最近 24 小时</option>
        <option value="7d">最近 7 天</option>
      </select>
      <input
        type="text"
        class="search-input keyword-input"
        placeholder="关键字搜索..."
        v-model="filters.keyword"
        @keyup.enter="search"
      />
      <button class="btn btn-primary" @click="search" :disabled="loading">
        {{ loading ? '搜索中...' : '搜索' }}
      </button>
    </div>

    <!-- Results Table -->
    <div class="table-wrapper">
      <div v-if="loading" class="empty-state">加载中...</div>
      <div v-else-if="logs.length === 0" class="empty-state">暂无日志数据</div>
      <table v-else class="data-table">
        <thead>
          <tr>
            <th style="width: 160px;">时间</th>
            <th style="width: 80px;">级别</th>
            <th style="width: 140px;">组件</th>
            <th style="width: 200px;">Request ID</th>
            <th>消息</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="(log, idx) in logs" :key="idx">
            <tr class="log-row" :class="{ expanded: expandedIdx === idx }" @click="toggleExpand(idx)">
              <td class="ts-cell">{{ formatTs(log.ts) }}</td>
              <td><span class="level-badge" :class="'level-' + (log.level || '').toLowerCase()">{{ log.level }}</span></td>
              <td><span class="component-badge" :style="{ color: componentColor(log.component) }">{{ log.component }}</span></td>
              <td>
                <router-link
                  v-if="log.request_id"
                  :to="'/logs/trace/' + log.request_id"
                  class="request-id-link"
                  @click.stop
                >{{ log.request_id }}</router-link>
                <span v-else class="text-muted">—</span>
              </td>
              <td class="msg-cell">{{ truncate(log.msg, 120) }}</td>
            </tr>
            <tr v-if="expandedIdx === idx" class="expand-row">
              <td colspan="5">
                <div class="expand-content">
                  <div class="expand-section">
                    <span class="expand-label">完整消息</span>
                    <pre class="expand-pre">{{ log.msg }}</pre>
                  </div>
                  <div v-if="log.duration_ms != null" class="expand-section">
                    <span class="expand-label">耗时</span>
                    <span class="expand-value">{{ log.duration_ms }} ms</span>
                  </div>
                  <div v-if="log.extra" class="expand-section">
                    <span class="expand-label">附加字段</span>
                    <pre class="expand-pre">{{ JSON.stringify(log.extra, null, 2) }}</pre>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { adminApi } from '../../api/admin'

interface LogEntry {
  ts: string
  level: string
  component: string
  request_id?: string
  msg: string
  duration_ms?: number
  extra?: Record<string, unknown>
}

const filters = reactive({
  component: '',
  level: '',
  tenant_id: '',
  since: '1h',
  keyword: '',
})

const logs = ref<LogEntry[]>([])
const loading = ref(false)
const expandedIdx = ref<number | null>(null)

function formatTs(ts: string): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}

function truncate(s: string, n: number): string {
  if (!s) return ''
  return s.length > n ? s.slice(0, n) + '…' : s
}

function componentColor(c: string): string {
  if (c === 'lakeon-api') return '#2a4d6a'
  if (c === 'knowledge-pipeline') return '#3a7d5c'
  if (c === 'pageserver') return '#8b6914'
  return '#666'
}

function toggleExpand(idx: number) {
  expandedIdx.value = expandedIdx.value === idx ? null : idx
}

async function search() {
  loading.value = true
  expandedIdx.value = null
  try {
    const params: Record<string, string | number | undefined> = { limit: 200 }
    if (filters.component) params.component = filters.component
    if (filters.level) params.level = filters.level
    if (filters.tenant_id) params.tenant_id = filters.tenant_id
    if (filters.since) params.since = filters.since
    if (filters.keyword) params.keyword = filters.keyword
    const { data } = await adminApi.logSearch(params)
    logs.value = Array.isArray(data) ? data : (data.logs ?? [])
  } catch (e) {
    console.error('logSearch failed', e)
    logs.value = []
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
  padding: 12px 16px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
}
.form-select {
  height: 32px;
  padding: 0 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  background: #fafbfc;
  color: #2c3e50;
  cursor: pointer;
}
.form-select:focus {
  outline: none;
  border-color: #c67d3a;
}
.search-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #2c3e50;
  width: 140px;
}
.keyword-input {
  width: 200px;
}
.search-input:focus {
  outline: none;
  border-color: #c67d3a;
}
.btn-primary {
  height: 32px;
  padding: 0 16px;
  background: #c67d3a;
  color: #fff;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.btn-primary:hover { background: #a8662e; }
.btn-primary:disabled { opacity: 0.6; cursor: default; }

.table-wrapper {
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
  overflow: auto;
}
.empty-state {
  padding: 40px;
  text-align: center;
  color: #999;
  font-size: 14px;
}
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.data-table th {
  padding: 10px 14px;
  background: #f7f4ef;
  font-weight: 600;
  color: #4a3728;
  text-align: left;
  border-bottom: 1px solid #e8e2d9;
  white-space: nowrap;
}
.data-table td {
  padding: 9px 14px;
  border-bottom: 1px solid #f0ece6;
  vertical-align: top;
}
.log-row:hover { background: #fdf9f5; cursor: pointer; }
.log-row.expanded { background: #fef6ee; }
.log-row:nth-child(even) { background: #fafafa; }
.log-row:nth-child(even):hover { background: #fdf9f5; }

.ts-cell { color: #666; font-family: monospace; font-size: 12px; white-space: nowrap; }
.msg-cell { color: #3d3d3d; }
.text-muted { color: #bbb; }

.level-badge {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.4px;
}
.level-error { background: #fff0f0; color: #e74c3c; }
.level-warn  { background: #fff7e6; color: #f39c12; }
.level-info  { background: #f0fff4; color: #2ecc71; }
.level-debug { background: #f5f5f5; color: #95a5a6; }

.component-badge { font-size: 12px; font-weight: 600; }
.request-id-link {
  font-family: monospace;
  font-size: 11px;
  color: #2a4d6a;
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  display: block;
  max-width: 180px;
  white-space: nowrap;
}
.request-id-link:hover { text-decoration: underline; }

.expand-row td { padding: 0; }
.expand-content {
  padding: 12px 20px;
  background: #fef9f4;
  border-bottom: 1px solid #e8e2d9;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.expand-section { flex: 1; min-width: 200px; }
.expand-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: #9a7b5a;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}
.expand-value { font-size: 13px; color: #333; }
.expand-pre {
  margin: 0;
  font-size: 12px;
  color: #333;
  background: #fff;
  border: 1px solid #e8e2d9;
  border-radius: 4px;
  padding: 8px;
  overflow: auto;
  max-height: 200px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
