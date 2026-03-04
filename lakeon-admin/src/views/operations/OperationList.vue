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
        <option value="CREATE">CREATE</option>
        <option value="SUSPEND">SUSPEND</option>
        <option value="RESUME">RESUME</option>
        <option value="DELETE">DELETE</option>
        <option value="UPDATE">UPDATE</option>
      </select>
      <select class="form-select" v-model="statusFilter" style="width: 140px;">
        <option value="">全部状态</option>
        <option value="SUCCESS">SUCCESS</option>
        <option value="FAILED">FAILED</option>
        <option value="IN_PROGRESS">IN_PROGRESS</option>
      </select>
      <button class="btn btn-default btn-small" @click="loadOperations">筛选</button>
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
            <td>{{ op.type }}</td>
            <td :class="{ 'status-failed': op.status === 'FAILED' }">
              <span class="status-dot" :class="opStatusClass(op.status)"></span>
              {{ op.status }}
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
    }))
  } catch (e) {
    console.error('Failed to load operations', e)
  }
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
</style>
