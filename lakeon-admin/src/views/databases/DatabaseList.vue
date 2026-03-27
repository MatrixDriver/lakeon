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
      <button
        v-if="selectedIds.size > 0"
        class="btn btn-danger btn-small"
        @click="confirmBatchDelete"
        :disabled="deleting"
      >
        {{ deleting ? '删除中...' : `批量删除 (${selectedIds.size})` }}
      </button>
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width: 40px;">
              <input type="checkbox" :checked="allSelected" @change="toggleAll" />
            </th>
            <th>名称</th>
            <th>租户</th>
            <th>状态</th>
            <th>状态信息</th>
            <th>规格</th>
            <th>存储上限</th>
            <th>Compute Pod</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="db in databases" :key="db.id" :class="{ 'row-selected': selectedIds.has(db.id) }">
            <td>
              <input type="checkbox" :checked="selectedIds.has(db.id)" @change="toggleSelect(db.id)" />
            </td>
            <td><a class="db-link" @click="router.push(`/databases/${db.id}`)">{{ db.name }}</a></td>
            <td>
              {{ tenantStore.name(db.tenant_id) }}
              <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ db.tenant_id }}</span>
            </td>
            <td>
              <span class="status-dot" :class="statusClass(db.status)"></span>
              {{ db.status }}
            </td>
            <td class="error-cell">{{ db.status_message || '-' }}</td>
            <td>{{ db.compute_size || '-' }}</td>
            <td>{{ db.storage_limit_gb ? db.storage_limit_gb + ' GB' : '-' }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ db.compute_pod_name || '-' }}</td>
            <td>{{ formatDate(db.created_at) }}</td>
            <td>
              <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteOne(db)">删除</button>
            </td>
          </tr>
          <tr v-if="databases.length === 0">
            <td colspan="10" class="empty-state">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Delete Confirm Dialog -->
    <div class="dialog-overlay" v-if="showDeleteDialog" @click.self="showDeleteDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3 style="color: #e53e3e;">确认删除</h3>
          <button class="dialog-close" @click="showDeleteDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p>确定要删除以下 <strong>{{ deleteTargetIds.length }}</strong> 个数据库吗？</p>
          <p style="color: #e53e3e; font-size: 13px;">此操作会删除计算节点和存储数据，不可恢复。</p>
          <ul style="font-size: 13px; max-height: 200px; overflow-y: auto; margin: 8px 0;">
            <li v-for="id in deleteTargetIds" :key="id">
              {{ dbNameById(id) }} <span style="color: #999;">({{ id }})</span>
            </li>
          </ul>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showDeleteDialog = false">取消</button>
          <button class="btn btn-danger" @click="executeBatchDelete" :disabled="deleting">
            {{ deleting ? '删除中...' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'
import { useTenantStore } from '../../stores/tenants'

const router = useRouter()
const tenantStore = useTenantStore()

interface Database {
  id: string
  name: string
  tenant_id: string
  status: string
  status_message?: string
  compute_size?: string
  storage_limit_gb?: number
  compute_pod_name?: string
  last_active_at?: string
  created_at: string
}

const databases = ref<Database[]>([])
const statusFilter = ref('')
const tenantFilter = ref('')

// Selection state
const selectedIds = ref<Set<string>>(new Set())
const showDeleteDialog = ref(false)
const deleteTargetIds = ref<string[]>([])
const deleting = ref(false)

const allSelected = computed(() => {
  return databases.value.length > 0 && databases.value.every(db => selectedIds.value.has(db.id))
})

function toggleAll() {
  if (allSelected.value) {
    databases.value.forEach(db => selectedIds.value.delete(db.id))
  } else {
    databases.value.forEach(db => selectedIds.value.add(db.id))
  }
  selectedIds.value = new Set(selectedIds.value)
}

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
  }
  selectedIds.value = new Set(selectedIds.value)
}

function dbNameById(id: string): string {
  return databases.value.find(db => db.id === id)?.name ?? id
}

function confirmDeleteOne(db: Database) {
  deleteTargetIds.value = [db.id]
  showDeleteDialog.value = true
}

function confirmBatchDelete() {
  deleteTargetIds.value = Array.from(selectedIds.value)
  showDeleteDialog.value = true
}

async function executeBatchDelete() {
  deleting.value = true
  try {
    const res = await adminApi.batchDeleteDatabases(deleteTargetIds.value)
    const result = res.data
    if (result.errors?.length > 0) {
      alert(`删除完成：成功 ${result.deleted} 个，失败 ${result.errors.length} 个\n${result.errors.map((e: { id: string; error: string }) => e.error).join('\n')}`)
    }
    showDeleteDialog.value = false
    selectedIds.value = new Set()
    await loadDatabases()
  } catch (e) {
    console.error('Failed to batch delete', e)
    alert('批量删除失败')
  } finally {
    deleting.value = false
  }
}

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
    selectedIds.value = new Set()
  } catch (e) {
    console.error('Failed to load databases', e)
  }
}

onMounted(() => { tenantStore.load(); loadDatabases() })
</script>

<style scoped>
.row-selected {
  background-color: #fff5f5;
}
.btn-danger {
  background-color: #e53e3e;
  color: white;
  border: none;
  padding: 6px 16px;
  border-radius: 4px;
  cursor: pointer;
}
.btn-danger:hover {
  background-color: #c53030;
}
.btn-danger:disabled {
  background-color: #feb2b2;
  cursor: not-allowed;
}
.db-link {
  color: #0052d9;
  cursor: pointer;
  text-decoration: none;
}
.db-link:hover {
  text-decoration: underline;
}

.error-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #e53e3e;
  font-size: 13px;
}
</style>
