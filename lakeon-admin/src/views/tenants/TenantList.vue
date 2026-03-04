<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">租户管理</h1>
    </div>

    <div class="action-toolbar">
      <input
        type="text"
        class="search-input"
        placeholder="搜索租户名称..."
        v-model="searchText"
        style="width: 260px;"
      />
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
            <th>ID</th>
            <th>数据库数/配额</th>
            <th>存储配额(GB)</th>
            <th>计算配额(CU)</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in filteredTenants" :key="t.id" :class="{ 'row-selected': selectedIds.has(t.id) }">
            <td>
              <input type="checkbox" :checked="selectedIds.has(t.id)" @change="toggleSelect(t.id)" />
            </td>
            <td>{{ t.name }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ t.id }}</td>
            <td>{{ t.database_count ?? 0 }} / {{ t.max_databases ?? '-' }}</td>
            <td>{{ t.max_storage_gb ?? '-' }}</td>
            <td>{{ t.max_compute_cu ?? '-' }}</td>
            <td>{{ formatDate(t.created_at) }}</td>
            <td>
              <button class="btn btn-text btn-small" @click="openEditQuota(t)">编辑配额</button>
              <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteOne(t)">删除</button>
            </td>
          </tr>
          <tr v-if="filteredTenants.length === 0">
            <td colspan="8" class="empty-state">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Edit Quota Dialog -->
    <div class="dialog-overlay" v-if="showDialog" @click.self="showDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>编辑配额 - {{ editingTenant?.name }}</h3>
          <button class="dialog-close" @click="showDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">最大数据库数</label>
            <input type="number" class="form-input" v-model.number="quotaForm.max_databases" min="1" />
          </div>
          <div class="form-group">
            <label class="form-label">存储配额 (GB)</label>
            <input type="number" class="form-input" v-model.number="quotaForm.max_storage_gb" min="1" />
          </div>
          <div class="form-group">
            <label class="form-label">计算配额 (CU)</label>
            <input type="number" class="form-input" v-model.number="quotaForm.max_compute_cu" min="1" />
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showDialog = false">取消</button>
          <button class="btn btn-primary" @click="saveQuota" :disabled="saving">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Delete Confirm Dialog -->
    <div class="dialog-overlay" v-if="showDeleteDialog" @click.self="showDeleteDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3 style="color: #e53e3e;">确认删除</h3>
          <button class="dialog-close" @click="showDeleteDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p>确定要删除以下 <strong>{{ deleteTargetIds.length }}</strong> 个租户吗？</p>
          <p style="color: #e53e3e; font-size: 13px;">此操作会同时删除租户下的所有数据库和计算资源，不可恢复。</p>
          <ul style="font-size: 13px; max-height: 200px; overflow-y: auto; margin: 8px 0;">
            <li v-for="id in deleteTargetIds" :key="id">
              {{ tenantNameById(id) }} <span style="color: #999;">({{ id }})</span>
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
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'

interface Tenant {
  id: string
  name: string
  database_count?: number
  max_databases?: number
  max_storage_gb?: number
  max_compute_cu?: number
  created_at: string
}

const tenants = ref<Tenant[]>([])
const searchText = ref('')
const showDialog = ref(false)
const saving = ref(false)
const editingTenant = ref<Tenant | null>(null)
const quotaForm = ref({
  max_databases: 5,
  max_storage_gb: 10,
  max_compute_cu: 2,
})

// Selection state
const selectedIds = ref<Set<string>>(new Set())
const showDeleteDialog = ref(false)
const deleteTargetIds = ref<string[]>([])
const deleting = ref(false)

const filteredTenants = computed(() => {
  if (!searchText.value) return tenants.value
  const q = searchText.value.toLowerCase()
  return tenants.value.filter(t => t.name.toLowerCase().includes(q))
})

const allSelected = computed(() => {
  return filteredTenants.value.length > 0 && filteredTenants.value.every(t => selectedIds.value.has(t.id))
})

function toggleAll() {
  if (allSelected.value) {
    filteredTenants.value.forEach(t => selectedIds.value.delete(t.id))
  } else {
    filteredTenants.value.forEach(t => selectedIds.value.add(t.id))
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

function tenantNameById(id: string): string {
  return tenants.value.find(t => t.id === id)?.name ?? id
}

function confirmDeleteOne(t: Tenant) {
  deleteTargetIds.value = [t.id]
  showDeleteDialog.value = true
}

function confirmBatchDelete() {
  deleteTargetIds.value = Array.from(selectedIds.value)
  showDeleteDialog.value = true
}

async function executeBatchDelete() {
  deleting.value = true
  try {
    const res = await adminApi.batchDeleteTenants(deleteTargetIds.value)
    const result = res.data
    if (result.errors?.length > 0) {
      alert(`删除完成：成功 ${result.deleted} 个，失败 ${result.errors.length} 个\n${result.errors.map((e: { id: string; error: string }) => e.error).join('\n')}`)
    }
    showDeleteDialog.value = false
    selectedIds.value = new Set()
    await loadTenants()
  } catch (e) {
    console.error('Failed to batch delete', e)
    alert('批量删除失败')
  } finally {
    deleting.value = false
  }
}

function openEditQuota(t: Tenant) {
  editingTenant.value = t
  quotaForm.value = {
    max_databases: t.max_databases ?? 5,
    max_storage_gb: t.max_storage_gb ?? 10,
    max_compute_cu: t.max_compute_cu ?? 2,
  }
  showDialog.value = true
}

async function saveQuota() {
  if (!editingTenant.value) return
  saving.value = true
  try {
    await adminApi.updateQuota(editingTenant.value.id, quotaForm.value)
    showDialog.value = false
    await loadTenants()
  } catch (e) {
    console.error('Failed to update quota', e)
  } finally {
    saving.value = false
  }
}

async function loadTenants() {
  try {
    const res = await adminApi.listTenants()
    tenants.value = res.data
  } catch (e) {
    console.error('Failed to load tenants', e)
  }
}

onMounted(loadTenants)
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
</style>
