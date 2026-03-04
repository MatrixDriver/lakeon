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
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
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
          <tr v-for="t in filteredTenants" :key="t.id">
            <td>{{ t.name }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ t.id }}</td>
            <td>{{ t.database_count ?? 0 }} / {{ t.max_databases ?? '-' }}</td>
            <td>{{ t.max_storage_gb ?? '-' }}</td>
            <td>{{ t.max_compute_cu ?? '-' }}</td>
            <td>{{ formatDate(t.created_at) }}</td>
            <td>
              <button class="btn btn-text btn-small" @click="openEditQuota(t)">编辑配额</button>
            </td>
          </tr>
          <tr v-if="filteredTenants.length === 0">
            <td colspan="7" class="empty-state">暂无数据</td>
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

const filteredTenants = computed(() => {
  if (!searchText.value) return tenants.value
  const q = searchText.value.toLowerCase()
  return tenants.value.filter(t => t.name.toLowerCase().includes(q))
})

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
