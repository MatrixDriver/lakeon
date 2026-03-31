<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">操作日志</h1>
    </div>

    <div class="action-toolbar">
      <input
        type="text"
        class="search-input"
        placeholder="按租户 ID 筛选..."
        v-model="tenantFilter"
        style="width: 220px;"
      />
      <select class="form-select" v-model="typeFilter" style="width: 140px;">
        <option value="">全部类型</option>
        <option value="CREATE">创建</option>
        <option value="SUSPEND">挂起</option>
        <option value="RESUME">唤醒</option>
        <option value="DELETE">删除</option>
        <option value="UPDATE">更新</option>
        <option value="IMPORT">导入</option>
        <option value="RESET_PASSWORD">重置密码</option>
      </select>
      <select class="form-select" v-model="statusFilter" style="width: 140px;">
        <option value="">全部状态</option>
        <option value="SUCCESS">成功</option>
        <option value="FAILED">失败</option>
        <option value="IN_PROGRESS">进行中</option>
      </select>
      <button class="btn btn-default btn-small" @click="loadOperations">筛选</button>
      <button class="btn btn-default btn-small" @click="exportCsv">导出 CSV</button>
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>数据库</th>
            <th>租户ID</th>
            <th>操作类型</th>
            <th>状态</th>
            <th>耗时(ms)</th>
            <th>开始时间</th>
            <th>错误信息</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="op in operations" :key="op.id">
            <td>{{ op.database_name || '-' }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ op.tenant_id }}</td>
            <td>
              {{ OP_LABELS[op.type] || op.type }}
              <span v-if="op.type === 'RESUME' && op.resume_type" class="resume-type-tag" :class="'rt-' + op.resume_type.toLowerCase()">
                {{ op.resume_type === 'WARM' ? '热启动' : '冷启动' }}
              </span>
            </td>
            <td :class="{ 'status-failed': op.status === 'FAILED' }">
              <span class="status-dot" :class="opStatusClass(op.status)"></span>
              {{ STATUS_LABELS[op.status] || op.status }}
            </td>
            <td>{{ op.duration_ms ?? '-' }}</td>
            <td>{{ formatDate(op.started_at) }}</td>
            <td class="error-cell">{{ op.error_message || '-' }}</td>
          </tr>
          <tr v-if="operations.length === 0">
            <td colspan="7" class="empty-state">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div class="pagination">
      <button class="page-btn" :disabled="page <= 0" @click="prevPage">上一页</button>
      <span class="page-info">第 {{ page + 1 }} 页</span>
      <button class="page-btn" :disabled="operations.length < pageSize" @click="nextPage">下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'

interface Operation {
  id: string
  database_name?: string
  tenant_id: string
  type: string
  status: string
  duration_ms?: number
  started_at: string
  error_message?: string
  resume_type?: string
}

const OP_LABELS: Record<string, string> = {
  CREATE: '创建',
  RESUME: '唤醒',
  SUSPEND: '挂起',
  DELETE: '删除',
  IMPORT: '导入',
  UPDATE: '更新',
  RESET_PASSWORD: '重置密码',
  BACKUP: '备份',
  RESTORE: '恢复',
  SWITCH_BRANCH: '切换分支',
}

const STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  IN_PROGRESS: '进行中',
}

const operations = ref<Operation[]>([])
const tenantFilter = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const page = ref(0)
const pageSize = 20

function opStatusClass(status: string): string {
  switch (status) {
    case 'SUCCESS': return 'dot-green'
    case 'FAILED': return 'dot-red'
    case 'IN_PROGRESS': return 'dot-blue'
    default: return 'dot-gray'
  }
}

async function loadOperations() {
  try {
    const params: Record<string, string | number> = {
      page: page.value,
      size: pageSize,
    }
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    if (typeFilter.value) params.type = typeFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    const res = await adminApi.listOperations(params)
    const items = res.data.data || res.data || []
    operations.value = items.map((op: any) => ({
      id: op.id,
      database_name: op.databaseName ?? op.database_name,
      tenant_id: op.tenantId ?? op.tenant_id,
      type: op.operationType ?? op.type,
      status: op.status,
      duration_ms: op.durationMs ?? op.duration_ms,
      started_at: op.startedAt ?? op.started_at,
      error_message: op.errorMessage ?? op.error_message,
      resume_type: op.resumeType ?? op.resume_type,
    }))
  } catch (e) {
    console.error('Failed to load operations', e)
  }
}

async function exportCsv() {
  // Fetch all pages for current filters
  const allOps: Operation[] = []
  let p = 0
  while (true) {
    const params: Record<string, string | number> = { page: p, size: 100 }
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    if (typeFilter.value) params.type = typeFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    const res = await adminApi.listOperations(params)
    const items = (res.data.data || res.data || []).map((op: any) => ({
      id: op.id,
      database_name: op.databaseName ?? op.database_name ?? '',
      tenant_id: op.tenantId ?? op.tenant_id ?? '',
      type: op.operationType ?? op.type ?? '',
      status: op.status ?? '',
      duration_ms: op.durationMs ?? op.duration_ms ?? '',
      started_at: op.startedAt ?? op.started_at ?? '',
      error_message: op.errorMessage ?? op.error_message ?? '',
      resume_type: op.resumeType ?? op.resume_type ?? '',
    }))
    allOps.push(...items)
    if (items.length < 100) break
    p++
  }

  const header = '数据库,租户ID,操作类型,唤醒类型,状态,耗时(ms),开始时间,错误信息'
  const rows = allOps.map(op => {
    const typeLabel = OP_LABELS[op.type] || op.type
    const statusLabel = STATUS_LABELS[op.status] || op.status
    const resumeLabel = op.type === 'RESUME' && op.resume_type
      ? (op.resume_type === 'WARM' ? '热启动' : '冷启动') : ''
    return [op.database_name, op.tenant_id, typeLabel, resumeLabel, statusLabel, op.duration_ms, op.started_at, op.error_message]
      .map(v => `"${String(v ?? '').replace(/"/g, '""')}"`)
      .join(',')
  })
  const csv = '\uFEFF' + header + '\n' + rows.join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `operations_${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    loadOperations()
  }
}

function nextPage() {
  page.value++
  loadOperations()
}

onMounted(loadOperations)
</script>

<style scoped>
.status-failed {
  color: #e6393d;
}

.error-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #e6393d;
}

.resume-type-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
}

.rt-warm {
  background: #f6ffed;
  color: #389e0d;
}

.rt-cold {
  background: #fdf5ed;
  color: #9a5b25;
}
</style>
