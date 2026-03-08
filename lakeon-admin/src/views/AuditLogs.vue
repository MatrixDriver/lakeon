<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">SQL 审计日志</h1>
    </div>

    <div class="action-toolbar">
      <input
        type="text"
        class="search-input"
        placeholder="按租户 ID 筛选..."
        v-model="tenantFilter"
        style="width: 220px;"
      />
      <input
        type="text"
        class="search-input"
        placeholder="按数据库 ID 筛选..."
        v-model="dbFilter"
        style="width: 220px;"
      />
      <select class="form-select" v-model="typeFilter" style="width: 140px;">
        <option value="">全部类型</option>
        <option value="DDL">DDL</option>
        <option value="DML">DML</option>
        <option value="SELECT">SELECT</option>
      </select>
      <button class="btn btn-default btn-small" @click="loadLogs">筛选</button>
      <button class="btn btn-default btn-small" @click="exportCsv">导出 CSV</button>
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>租户ID</th>
            <th>数据库ID</th>
            <th>用户</th>
            <th>类型</th>
            <th>对象</th>
            <th>SQL语句</th>
            <th>耗时(ms)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in logs" :key="log.id">
            <td>{{ formatDate(log.timestamp) }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ log.tenant_id }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ log.database_id }}</td>
            <td>{{ log.user_name || '-' }}</td>
            <td>
              <span class="status-dot" :class="typeStatusClass(log.statement_type)"></span>
              {{ log.statement_type }}
            </td>
            <td>{{ log.object_name || '-' }}</td>
            <td class="sql-cell" :title="log.statement || ''">{{ log.statement || '-' }}</td>
            <td>{{ log.duration ?? '-' }}</td>
          </tr>
          <tr v-if="logs.length === 0">
            <td colspan="8" class="empty-state">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div class="pagination">
      <button class="page-btn" :disabled="page <= 0" @click="prevPage">上一页</button>
      <span class="page-info">第 {{ page + 1 }} 页 / 共 {{ totalPages }} 页 ({{ total }} 条)</span>
      <button class="page-btn" :disabled="page + 1 >= totalPages" @click="nextPage">下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../api/admin'
import { formatDate } from '../utils/format'

interface AuditLog {
  id: string
  database_id: string
  tenant_id: string
  timestamp: string
  user_name?: string
  statement?: string
  statement_type: string
  object_name?: string
  client_addr?: string
  duration?: number
}

const logs = ref<AuditLog[]>([])
const tenantFilter = ref('')
const dbFilter = ref('')
const typeFilter = ref('')
const page = ref(0)
const pageSize = 20
const total = ref(0)
const totalPages = ref(0)

function typeStatusClass(type: string): string {
  switch (type) {
    case 'DDL': return 'dot-green'
    case 'DML': return 'dot-orange'
    case 'SELECT': return 'dot-blue'
    default: return 'dot-gray'
  }
}

async function loadLogs() {
  try {
    const params: Record<string, string | number> = {
      page: page.value,
      size: pageSize,
    }
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    if (dbFilter.value.trim()) params.db_id = dbFilter.value.trim()
    if (typeFilter.value) params.type = typeFilter.value
    const res = await adminApi.auditLogs(params)
    logs.value = res.data.data || []
    total.value = res.data.total || 0
    totalPages.value = res.data.total_pages || 0
  } catch (e) {
    console.error('Failed to load audit logs', e)
  }
}

async function exportCsv() {
  const allLogs: AuditLog[] = []
  let p = 0
  while (true) {
    const params: Record<string, string | number> = { page: p, size: 100 }
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    if (dbFilter.value.trim()) params.db_id = dbFilter.value.trim()
    if (typeFilter.value) params.type = typeFilter.value
    const res = await adminApi.auditLogs(params)
    const items = res.data.data || []
    allLogs.push(...items)
    if (items.length < 100) break
    p++
  }

  const header = '时间,租户ID,数据库ID,用户,类型,对象,SQL语句,耗时(ms)'
  const rows = allLogs.map(log =>
    [log.timestamp, log.tenant_id, log.database_id, log.user_name, log.statement_type,
     log.object_name, log.statement, log.duration]
      .map(v => `"${String(v ?? '').replace(/"/g, '""')}"`)
      .join(',')
  )
  const csv = '\uFEFF' + header + '\n' + rows.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `audit_logs_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    loadLogs()
  }
}

function nextPage() {
  page.value++
  loadLogs()
}

onMounted(loadLogs)
</script>

<style scoped>
.sql-cell {
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: monospace;
  font-size: 12px;
}

.dot-orange {
  background-color: #d46b08;
}
</style>
