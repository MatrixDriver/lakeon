<template>
  <div>
    <header class="page-header">
      <div>
        <h1 class="page-title">租户管理</h1>
        <p class="page-subtitle">查看与管理 Lakeon 上的所有租户、配额与启用状态</p>
      </div>
    </header>

    <!-- Status line -->
    <div class="tenant-status-line">
      <span class="sl-item sl-total">
        <span class="sl-num">{{ tenants.length }}</span>
        <span class="sl-lbl">个租户</span>
      </span>
      <span class="sl-sep" aria-hidden="true"></span>
      <span class="sl-item">
        <span class="sl-chip sl-chip-enabled"></span>
        <span class="sl-lbl">启用</span>
        <span class="sl-num">{{ enabledCount }}</span>
      </span>
      <span class="sl-item" :class="{ 'sl-alert': disabledCount > 0 }">
        <span class="sl-chip sl-chip-disabled"></span>
        <span class="sl-lbl">禁用</span>
        <span class="sl-num">{{ disabledCount }}</span>
      </span>
    </div>

    <div class="tenant-toolbar">
      <input
        type="text"
        class="search-input"
        placeholder="搜索租户名称"
        v-model="searchText"
      />
      <div v-if="selectedIds.size > 0" class="tenant-batch-inline">
        <span class="tenant-batch-count">已选择 {{ selectedIds.size }} 个</span>
        <button
          class="btn btn-danger btn-small"
          @click="confirmBatchDelete"
          :disabled="deleting"
        >{{ deleting ? '删除中…' : '批量删除' }}</button>
      </div>
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th class="th-check">
              <input type="checkbox" :checked="allSelected" @change="toggleAll" />
            </th>
            <th>名称</th>
            <th>状态</th>
            <th>数据库数 / 配额</th>
            <th>存储配额</th>
            <th>计算配额</th>
            <th>创建于</th>
            <th class="th-actions"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="t in filteredTenants"
            :key="t.id"
            :class="{
              'row-selected': selectedIds.has(t.id),
              'row-disabled': t.disabled
            }"
          >
            <td>
              <input type="checkbox" :checked="selectedIds.has(t.id)" @change="toggleSelect(t.id)" />
            </td>
            <td class="td-name">
              <div class="td-name-title">{{ t.name }}</div>
              <div class="td-sub-mono">{{ t.id }}</div>
            </td>
            <td>
              <span class="status-chip" :class="t.disabled ? 'chip-disabled' : 'chip-enabled'">
                <span class="chip-dot"></span>{{ t.disabled ? '已禁用' : '已启用' }}
              </span>
            </td>
            <td class="td-quota">
              <span class="td-quota-use">{{ t.database_count ?? 0 }}</span>
              <span class="td-quota-sep">/</span>
              <span class="td-quota-max">{{ t.max_databases ?? '—' }}</span>
            </td>
            <td class="td-compact">{{ t.max_storage_gb ? t.max_storage_gb + ' GB' : '—' }}</td>
            <td class="td-compact">{{ t.max_compute_cu ? t.max_compute_cu + ' CU' : '—' }}</td>
            <td class="td-date">{{ formatDate(t.created_at) }}</td>
            <td class="td-actions">
              <div class="row-actions">
                <button class="row-btn row-btn-accent" @click="openEditQuota(t)">编辑配额</button>
                <button
                  class="row-btn"
                  :class="t.disabled ? 'row-btn-enable' : 'row-btn-disable'"
                  @click="toggleDisabled(t)"
                >{{ t.disabled ? '启用' : '禁用' }}</button>
                <button class="row-btn row-btn-danger" @click="confirmDeleteOne(t)">删除</button>
              </div>
            </td>
          </tr>
          <tr v-if="filteredTenants.length === 0">
            <td colspan="8" class="empty-row">
              <div class="empty-title">{{ searchText ? '没有匹配的租户' : '还没有任何租户' }}</div>
              <div class="empty-sub">{{ searchText ? '试试其它关键词' : '邀请码页面可以生成新租户的入口' }}</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Edit Quota Dialog -->
    <div class="dialog-overlay" v-if="showDialog" @click.self="showDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>编辑配额 · {{ editingTenant?.name }}</h3>
          <button class="dialog-close" @click="showDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p class="dialog-lede">调整该租户能创建的资源上限。减少配额不会影响已存在的资源。</p>
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
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Delete Confirm Dialog -->
    <div class="dialog-overlay" v-if="showDeleteDialog" @click.self="showDeleteDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>删除租户</h3>
          <button class="dialog-close" @click="showDeleteDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p class="dialog-lede">
            将永久删除以下 <strong>{{ deleteTargetIds.length }}</strong> 个租户、租户下的所有数据库和计算资源，此操作不可恢复。
          </p>
          <ul class="dialog-list">
            <li v-for="id in deleteTargetIds" :key="id">
              <span class="dialog-list-name">{{ tenantNameById(id) }}</span>
              <span class="dialog-list-id">{{ id }}</span>
            </li>
          </ul>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showDeleteDialog = false">取消</button>
          <button class="btn btn-danger" @click="executeBatchDelete" :disabled="deleting">
            {{ deleting ? '删除中…' : '删除' }}
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
  disabled?: boolean
  disabled_at?: string
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

const selectedIds = ref<Set<string>>(new Set())
const showDeleteDialog = ref(false)
const deleteTargetIds = ref<string[]>([])
const deleting = ref(false)

const filteredTenants = computed(() => {
  if (!searchText.value) return tenants.value
  const q = searchText.value.toLowerCase()
  return tenants.value.filter(t => t.name.toLowerCase().includes(q))
})

const enabledCount = computed(() => tenants.value.filter(t => !t.disabled).length)
const disabledCount = computed(() => tenants.value.filter(t => !!t.disabled).length)

const allSelected = computed(() =>
  filteredTenants.value.length > 0 && filteredTenants.value.every(t => selectedIds.value.has(t.id))
)

function toggleAll() {
  if (allSelected.value) {
    filteredTenants.value.forEach(t => selectedIds.value.delete(t.id))
  } else {
    filteredTenants.value.forEach(t => selectedIds.value.add(t.id))
  }
  selectedIds.value = new Set(selectedIds.value)
}

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
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

async function toggleDisabled(t: Tenant) {
  try {
    if (t.disabled) {
      await adminApi.enableTenant(t.id)
    } else {
      await adminApi.disableTenant(t.id)
    }
    await loadTenants()
  } catch (e) {
    console.error('Failed to toggle tenant status', e)
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
/* Status line */
.tenant-status-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-xl);
  padding: var(--space-md) 0 var(--space-lg);
  margin-bottom: var(--space-lg);
  border-bottom: 1px solid var(--c-border-light);
}

.sl-item {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-sm);
  font-size: 13px;
  color: var(--c-text-2);
}

.sl-item.sl-alert .sl-num { color: var(--cs-severe); }

.sl-total .sl-num {
  font-family: var(--font-display);
  font-size: 22px;
}

.sl-num {
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.sl-lbl { color: var(--c-text-2); }

.sl-sep {
  width: 1px;
  height: 18px;
  background: var(--c-border);
  align-self: center;
}

.sl-chip {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  transform: translateY(-1px);
}

.sl-chip-enabled { background: var(--c-success); }
.sl-chip-disabled { background: var(--cs-severe); }

/* Toolbar */
.tenant-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
}

.tenant-toolbar .search-input {
  width: 300px;
}

.tenant-batch-inline {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-md);
  font-size: 13px;
  color: var(--c-text-2);
}

.tenant-batch-count {
  font-weight: 500;
  color: var(--c-text);
}

/* Status chip */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
  padding: 3px 10px;
  border-radius: 10px;
  background: var(--c-bg-alt);
  color: var(--c-text-2);
  white-space: nowrap;
}

.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-text-3);
}

.chip-enabled {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}
.chip-enabled .chip-dot { background: var(--c-success); }

.chip-disabled {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}
.chip-disabled .chip-dot { background: var(--cs-severe); }

/* Table row tints */
.data-table tbody tr.row-selected {
  background: color-mix(in oklch, var(--c-accent) 6%, #fff);
}

.data-table tbody tr.row-disabled {
  background: color-mix(in oklch, var(--cs-severe) 3%, #fff);
}

.data-table tbody tr.row-disabled:hover {
  background: color-mix(in oklch, var(--cs-severe) 6%, #fff);
}

/* Table cells */
.td-name {
  min-width: 180px;
  max-width: 240px;
}

.td-name-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.td-sub-mono {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-text-3);
  margin-top: 2px;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.td-quota {
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.td-quota-use {
  font-weight: 600;
  color: var(--c-text);
  font-family: var(--font-display);
  font-size: 15px;
}

.td-quota-sep {
  color: var(--c-border);
  margin: 0 var(--space-xs);
}

.td-quota-max {
  color: var(--c-text-3);
  font-size: 13px;
}

.td-compact {
  font-variant-numeric: tabular-nums;
  color: var(--c-text-2);
  white-space: nowrap;
}

.td-date {
  color: var(--c-text-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  font-size: 12px;
}

.th-check,
.th-actions {
  width: 1%;
}

/* Row actions — hidden until hover */
.td-actions {
  text-align: right;
  width: 1%;
}

.row-actions {
  display: inline-flex;
  gap: var(--space-sm);
  opacity: 0;
  transition: opacity 180ms ease-out;
}

.data-table tbody tr:hover .row-actions,
.data-table tbody tr:focus-within .row-actions {
  opacity: 1;
}

.row-btn {
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  background: none;
  border: none;
  padding: 4px 8px;
  border-radius: 3px;
  cursor: pointer;
  transition: background 120ms ease-out, color 120ms ease-out;
}

.row-btn-accent {
  color: var(--c-accent-text);
}
.row-btn-accent:hover {
  background: var(--c-accent-light);
}

.row-btn-disable {
  color: var(--c-text-3);
}
.row-btn-disable:hover {
  color: var(--cs-warn);
  background: color-mix(in oklch, var(--cs-warn) 8%, #fff);
}

.row-btn-enable {
  color: #386b47;
}
.row-btn-enable:hover {
  background: color-mix(in oklch, var(--c-success) 10%, #fff);
}

.row-btn-danger {
  color: var(--c-text-3);
}
.row-btn-danger:hover {
  color: var(--cs-severe);
  background: color-mix(in oklch, var(--cs-severe) 8%, #fff);
}

/* Empty state */
.empty-row {
  padding: var(--space-4xl) var(--space-xl);
  text-align: center;
  background: #fff;
}

.empty-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 18px;
  color: var(--c-text);
}

.empty-sub {
  margin-top: var(--space-xs);
  font-size: 13px;
  color: var(--c-text-3);
}

/* Dialog list (for delete confirm) */
.dialog-lede {
  margin-bottom: var(--space-md);
  line-height: 1.55;
}

.dialog-list {
  list-style: none;
  padding: var(--space-sm) 0;
  margin: 0;
  max-height: 200px;
  overflow-y: auto;
  font-size: 13px;
  border-top: 1px solid var(--c-border-light);
  border-bottom: 1px solid var(--c-border-light);
}

.dialog-list li {
  padding: 6px 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dialog-list-name {
  color: var(--c-text);
  font-weight: 500;
}

.dialog-list-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-text-3);
}

@media (max-width: 768px) {
  .tenant-toolbar .search-input {
    width: 100%;
  }

  .tenant-batch-inline {
    margin-left: 0;
    width: 100%;
    justify-content: space-between;
  }
}
</style>
