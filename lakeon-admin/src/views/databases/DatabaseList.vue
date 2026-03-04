<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">数据库实例</h1>
    </div>

    <div class="action-toolbar">
      <select class="form-select" v-model="statusFilter" style="width: 160px;" @change="loadDatabases">
        <option value="">全部状态</option>
        <option value="RUNNING">RUNNING</option>
        <option value="SUSPENDED">SUSPENDED</option>
        <option value="CREATING">CREATING</option>
        <option value="ERROR">ERROR</option>
      </select>
      <input
        type="text"
        class="search-input"
        placeholder="按租户 ID 筛选..."
        v-model="tenantFilter"
        style="width: 260px;"
        @keyup.enter="loadDatabases"
      />
      <button class="btn btn-default btn-small" @click="loadDatabases">筛选</button>
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>租户ID</th>
            <th>状态</th>
            <th>规格</th>
            <th>存储上限</th>
            <th>Compute Pod</th>
            <th>最后活跃</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="db in databases" :key="db.id">
            <td>{{ db.name }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ db.tenant_id }}</td>
            <td>
              <span class="status-dot" :class="statusClass(db.status)"></span>
              {{ db.status }}
            </td>
            <td>{{ db.spec || '-' }}</td>
            <td>{{ db.storage_limit || '-' }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ db.compute_pod || '-' }}</td>
            <td>{{ formatDate(db.last_active_at) }}</td>
            <td>{{ formatDate(db.created_at) }}</td>
          </tr>
          <tr v-if="databases.length === 0">
            <td colspan="8" class="empty-state">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'

interface Database {
  id: string
  name: string
  tenant_id: string
  status: string
  spec?: string
  storage_limit?: string
  compute_pod?: string
  last_active_at?: string
  created_at: string
}

const databases = ref<Database[]>([])
const statusFilter = ref('')
const tenantFilter = ref('')

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    case 'ERROR': return 'dot-red'
    default: return 'dot-gray'
  }
}

async function loadDatabases() {
  try {
    const params: Record<string, string> = {}
    if (statusFilter.value) params.status = statusFilter.value
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    const res = await adminApi.listDatabases(params)
    databases.value = res.data
  } catch (e) {
    console.error('Failed to load databases', e)
  }
}

onMounted(loadDatabases)
</script>
