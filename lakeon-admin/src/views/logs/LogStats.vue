<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">日志统计</h1>
    </div>

    <!-- Filter -->
    <div class="filter-bar">
      <select class="form-select" v-model="since" @change="load">
        <option value="1h">最近 1 小时</option>
        <option value="6h">最近 6 小时</option>
        <option value="24h">最近 24 小时</option>
        <option value="7d">最近 7 天</option>
      </select>
    </div>

    <div v-if="loading" class="empty-state">加载中...</div>
    <template v-else>
      <!-- Stats Matrix -->
      <div class="section-card">
        <div class="section-header"><h3>组件日志级别分布</h3></div>
        <div v-if="matrixRows.length === 0" class="empty-state">暂无统计数据</div>
        <div v-else class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>组件</th>
                <th class="level-col error-col">ERROR</th>
                <th class="level-col warn-col">WARN</th>
                <th class="level-col info-col">INFO</th>
                <th class="level-col debug-col">DEBUG</th>
                <th class="level-col">合计</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in matrixRows" :key="row.component" class="matrix-row">
                <td>
                  <span class="component-badge" :style="{ color: componentColor(row.component) }">{{ row.component }}</span>
                </td>
                <td class="count-cell error-count">{{ row.error || 0 }}</td>
                <td class="count-cell warn-count">{{ row.warn || 0 }}</td>
                <td class="count-cell info-count">{{ row.info || 0 }}</td>
                <td class="count-cell debug-count">{{ row.debug || 0 }}</td>
                <td class="count-cell total-count">{{ rowTotal(row) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Slow Operations Top 10 -->
      <div class="section-card">
        <div class="section-header"><h3>慢操作 Top 10</h3></div>
        <div v-if="slowOps.length === 0" class="empty-state">暂无慢操作数据</div>
        <div v-else class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 160px;">时间</th>
                <th style="width: 140px;">组件</th>
                <th style="width: 200px;">Request ID</th>
                <th>消息</th>
                <th style="width: 100px; text-align: right;">耗时 (ms)</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(op, idx) in slowOps" :key="idx" class="log-row">
                <td class="ts-cell">{{ formatTs(op.ts) }}</td>
                <td>
                  <span class="component-badge" :style="{ color: componentColor(op.component) }">{{ op.component }}</span>
                </td>
                <td>
                  <router-link
                    v-if="op.request_id"
                    :to="'/logs/trace/' + op.request_id"
                    class="request-id-link"
                  >{{ op.request_id }}</router-link>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="msg-cell">{{ op.msg }}</td>
                <td class="duration-cell">{{ op.duration_ms }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

interface MatrixRow {
  component: string
  error?: number
  warn?: number
  info?: number
  debug?: number
}

interface SlowOp {
  ts: string
  component: string
  request_id?: string
  msg: string
  duration_ms: number
}

interface StatsData {
  matrix?: MatrixRow[]
  slow_ops?: SlowOp[]
}

const since = ref('1h')
const loading = ref(false)
const matrixRows = ref<MatrixRow[]>([])
const slowOps = ref<SlowOp[]>([])

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

function rowTotal(row: MatrixRow): number {
  return (row.error ?? 0) + (row.warn ?? 0) + (row.info ?? 0) + (row.debug ?? 0)
}

async function load() {
  loading.value = true
  try {
    const { data } = await adminApi.logStats({ since: since.value })
    const stats = data as StatsData
    matrixRows.value = stats.matrix ?? []
    slowOps.value = stats.slow_ops ?? []
  } catch (e) {
    console.error('logStats failed', e)
    matrixRows.value = []
    slowOps.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
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

.section-card {
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
  margin-bottom: 20px;
}
.section-header {
  padding: 12px 16px;
  border-bottom: 1px solid #ebebeb;
}
.section-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #4a3728;
}
.empty-state {
  padding: 40px;
  text-align: center;
  color: #999;
  font-size: 14px;
}
.table-wrapper { overflow: auto; }
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
  vertical-align: middle;
}
.matrix-row:hover { background: #fdf9f5; }
.matrix-row:nth-child(even) { background: #fafafa; }
.matrix-row:nth-child(even):hover { background: #fdf9f5; }

.level-col { text-align: right; width: 80px; }
.count-cell { text-align: right; font-family: monospace; font-weight: 600; }
.error-count { color: #e74c3c; }
.warn-count { color: #f39c12; }
.info-count { color: #2ecc71; }
.debug-count { color: #95a5a6; }
.total-count { color: #2c3e50; }
.error-col { color: #e74c3c; }
.warn-col { color: #f39c12; }
.info-col { color: #2ecc71; }
.debug-col { color: #95a5a6; }

.component-badge { font-size: 12px; font-weight: 600; }
.ts-cell { color: #666; font-family: monospace; font-size: 12px; white-space: nowrap; }
.msg-cell { color: #3d3d3d; }
.text-muted { color: #bbb; }
.duration-cell { text-align: right; font-family: monospace; font-weight: 700; color: #c67d3a; }
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
.log-row:hover { background: #fdf9f5; }
.log-row:nth-child(even) { background: #fafafa; }
.log-row:nth-child(even):hover { background: #fdf9f5; }
</style>
