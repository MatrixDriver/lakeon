<template>
  <div>
    <div class="page-header" style="margin-bottom:12px">
      <div class="header-actions">
        <label class="auto-refresh-toggle">
          <input type="checkbox" v-model="autoRefresh" />
          <span>自动刷新</span>
        </label>
        <button class="btn-refresh" @click="load" :disabled="loading">
          {{ loading ? '加载中...' : '刷新' }}
        </button>
      </div>
    </div>

    <!-- Filter Bar -->
    <div class="filter-bar">
      <select class="form-select" v-model="since" @change="load">
        <option value="1h">最近 1 小时</option>
        <option value="6h">最近 6 小时</option>
        <option value="24h">最近 24 小时</option>
        <option value="7d">最近 7 天</option>
      </select>
      <select class="form-select" v-model="component" @change="load">
        <option value="">全部组件</option>
        <option value="lakeon-api">lakeon-api</option>
        <option value="knowledge-pipeline">knowledge-pipeline</option>
        <option value="pageserver">pageserver</option>
        <option value="safekeeper">safekeeper</option>
        <option value="proxy">proxy</option>
      </select>
    </div>

    <!-- Summary Cards -->
    <div class="summary-cards">
      <div class="summary-card">
        <div class="summary-value text-error">{{ totalErrors }}</div>
        <div class="summary-label">总错误数</div>
      </div>
      <div class="summary-card">
        <div class="summary-value text-warn">{{ totalWarns }}</div>
        <div class="summary-label">总警告数</div>
      </div>
      <div class="summary-card">
        <div class="summary-value">{{ affectedComponents }}</div>
        <div class="summary-label">受影响组件数</div>
      </div>
    </div>

    <!-- Error List grouped by component -->
    <div v-if="loading" class="empty-state">加载中...</div>
    <div v-else-if="groupedEntries.length === 0" class="empty-state">暂无错误数据</div>
    <template v-else>
      <div v-for="group in groupedEntries" :key="group.component" class="component-group">
        <div class="group-header">
          <span class="group-component" :style="{ color: componentColor(group.component) }">{{ group.component }}</span>
          <span class="group-count">{{ group.entries.length }} 条</span>
        </div>
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 80px;">级别</th>
                <th style="width: 160px;">时间</th>
                <th style="width: 200px;">Request ID</th>
                <th>消息</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(e, idx) in group.entries" :key="idx" class="log-row">
                <td><span class="level-badge" :class="'level-' + (e.level || '').toLowerCase()">{{ e.level }}</span></td>
                <td class="ts-cell">{{ formatTs(e.ts) }}</td>
                <td>
                  <router-link
                    v-if="e.request_id"
                    :to="'/logs/trace/' + e.request_id"
                    class="request-id-link"
                  >{{ e.request_id }}</router-link>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="msg-cell">{{ e.msg }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { adminApi } from '../../api/admin'

interface ErrorEntry {
  ts: string
  level: string
  component: string
  request_id?: string
  msg: string
}

interface ComponentGroup {
  component: string
  entries: ErrorEntry[]
}

const since = ref('1h')
const component = ref('')
const loading = ref(false)
const autoRefresh = ref(false)
const entries = ref<ErrorEntry[]>([])
let refreshTimer: number | null = null

const groupedEntries = computed<ComponentGroup[]>(() => {
  const map = new Map<string, ErrorEntry[]>()
  for (const e of entries.value) {
    const key = e.component || 'unknown'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(e)
  }
  return Array.from(map.entries()).map(([comp, list]) => ({ component: comp, entries: list }))
})

const totalErrors = computed(() => entries.value.filter(e => e.level === 'ERROR').length)
const totalWarns = computed(() => entries.value.filter(e => e.level === 'WARN').length)
const affectedComponents = computed(() => new Set(entries.value.map(e => e.component)).size)

function formatTs(ts: string): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}

function componentColor(c: string): string {
  if (c === 'lakeon-api') return '#2a4d6a'
  if (c === 'knowledge-pipeline') return '#3a7d5c'
  if (c === 'pageserver') return '#8b6914'
  return '#666'
}

async function load() {
  loading.value = true
  try {
    const params: { since?: string; component?: string } = { since: since.value }
    if (component.value) params.component = component.value
    const { data } = await adminApi.logErrors(params)
    entries.value = Array.isArray(data) ? data : (data.entries ?? [])
  } catch (e) {
    console.error('logErrors failed', e)
    entries.value = []
  } finally {
    loading.value = false
  }
}

function setupAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer)
  if (autoRefresh.value) {
    refreshTimer = window.setInterval(load, 30000)
  }
}

import { watch } from 'vue'
watch(autoRefresh, setupAutoRefresh)

onMounted(() => {
  load()
  setupAutoRefresh()
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.auto-refresh-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
}
.btn-refresh {
  height: 30px;
  padding: 0 14px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #4a3728;
  cursor: pointer;
}
.btn-refresh:hover { border-color: #c67d3a; color: #9a5b25; }
.btn-refresh:disabled { opacity: 0.6; cursor: default; }

.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  padding: 10px 16px;
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
.form-select:focus { outline: none; border-color: #c67d3a; }

.summary-cards {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3xl);
  padding: var(--space-lg) 0 var(--space-xl);
  flex-wrap: wrap;
  margin-bottom: var(--space-lg);
}
.summary-card {
  background: transparent;
  border: none;
  padding: 0;
  min-width: 96px;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.summary-value {
  font-family: var(--font-display);
  font-size: 30px;
  font-weight: 500;
  line-height: 1;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
}
.summary-label {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}
.text-error { color: var(--cs-severe); }
.text-warn { color: var(--cs-warn); }

.empty-state {
  padding: 60px;
  text-align: center;
  color: #999;
  font-size: 14px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
}

.component-group { margin-bottom: 20px; }
.group-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 4px;
  margin-bottom: 4px;
}
.group-component { font-size: 14px; font-weight: 700; }
.group-count { font-size: 12px; color: #999; }

.table-wrapper {
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
  overflow: auto;
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
.log-row:hover { background: #fdf9f5; }
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
.level-error { background: color-mix(in oklch, var(--cs-severe) 10%, #fff); color: var(--cs-severe); }
.level-warn  { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: var(--cs-warn); }
.level-info  { background: color-mix(in oklch, var(--c-success) 12%, #fff); color: #386b47; }
.level-debug { background: var(--c-bg-alt); color: var(--c-text-3); }
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
</style>
