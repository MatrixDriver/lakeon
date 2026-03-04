<template>
  <div class="page-db-list">
    <div class="breadcrumb">
      <router-link to="/dashboard" class="breadcrumb-link">总览</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-item active">数据库实例</span>
    </div>

    <!-- Toolbar -->
    <div class="toolbar">
      <button class="btn btn-primary" @click="showCreateDialog = true">创建数据库</button>
      <input
        type="text"
        v-model="searchQuery"
        class="search-input"
        placeholder="搜索数据库名称..."
      />
    </div>

    <!-- Database Table -->
    <div class="section-card">
      <div class="table-wrapper">
        <table class="data-table" v-if="filteredDatabases.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>状态</th>
              <th>规格</th>
              <th>存储用量</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="db in filteredDatabases" :key="db.id">
              <td>
                <router-link :to="`/databases/${db.id}`" class="db-name-link">
                  {{ db.name }}
                </router-link>
              </td>
              <td>
                <span class="status-dot" :class="statusClass(db.status)"></span>
                {{ statusText(db.status) }}
              </td>
              <td>{{ db.compute_size }}</td>
              <td>
                <div class="storage-info">
                  <div class="storage-bar">
                    <div class="storage-fill" :style="{ width: storagePercent(db) + '%' }"></div>
                  </div>
                  <span class="storage-text">{{ db.storage_used_gb.toFixed(2) }} / {{ db.storage_limit_gb }} GB</span>
                </div>
              </td>
              <td>{{ formatDate(db.created_at) }}</td>
              <td>
                <div class="action-btns">
                  <button
                    v-if="db.status === 'RUNNING'"
                    class="btn btn-small btn-text"
                    :disabled="actionLoading[db.id]"
                    @click="handleSuspend(db)"
                  >挂起</button>
                  <button
                    v-if="db.status === 'SUSPENDED'"
                    class="btn btn-small btn-text"
                    :disabled="actionLoading[db.id]"
                    @click="handleResume(db)"
                  >恢复</button>
                  <button
                    class="btn btn-small btn-text btn-danger-text"
                    :disabled="actionLoading[db.id]"
                    @click="confirmDelete(db)"
                  >删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">
          <p v-if="loading">加载中...</p>
          <p v-else>暂无数据库实例</p>
        </div>
      </div>
    </div>

    <!-- Create Dialog -->
    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="showCreateDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>创建数据库</h3>
          <button class="dialog-close" @click="showCreateDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">名称 <span class="required">*</span></label>
            <input v-model="createForm.name" class="form-input" placeholder="请输入数据库名称" />
          </div>
          <div class="form-group">
            <label class="form-label">规格</label>
            <select v-model="createForm.compute_size" class="form-select">
              <option value="1cu">1 CU</option>
              <option value="2cu">2 CU</option>
              <option value="4cu">4 CU</option>
              <option value="8cu">8 CU</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">挂起超时</label>
            <select v-model="createForm.suspend_timeout" class="form-select">
              <option value="5m">5 分钟</option>
              <option value="10m">10 分钟</option>
              <option value="30m">30 分钟</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">存储上限</label>
            <select v-model="createForm.storage_limit_gb" class="form-select">
              <option :value="5">5 GB</option>
              <option :value="10">10 GB</option>
              <option :value="50">50 GB</option>
              <option :value="100">100 GB</option>
            </select>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreateDialog = false">取消</button>
          <button
            class="btn btn-primary"
            :disabled="!createForm.name.trim() || createLoading"
            @click="handleCreate"
          >
            {{ createLoading ? '创建中...' : '确定' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Delete Confirmation Dialog -->
    <div v-if="deleteTarget" class="dialog-overlay" @click.self="deleteTarget = null">
      <div class="dialog-box dialog-confirm">
        <div class="dialog-header">
          <h3>删除确认</h3>
          <button class="dialog-close" @click="deleteTarget = null">&times;</button>
        </div>
        <div class="dialog-body">
          <p class="confirm-text">
            确定要删除数据库 <strong>{{ deleteTarget.name }}</strong> 吗？此操作不可恢复。
          </p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="deleteLoading" @click="handleDelete">
            {{ deleteLoading ? '删除中...' : '确定删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { databaseApi, type Database } from '../../api/database'
import { formatDate } from '../../utils/format'

const databases = ref<Database[]>([])
const loading = ref(true)
const searchQuery = ref('')
const showCreateDialog = ref(false)
const createLoading = ref(false)
const deleteTarget = ref<Database | null>(null)
const deleteLoading = ref(false)
const actionLoading = reactive<Record<string, boolean>>({})

const createForm = reactive({
  name: '',
  compute_size: '1cu',
  suspend_timeout: '5m',
  storage_limit_gb: 10,
})

const filteredDatabases = computed(() => {
  const q = searchQuery.value.toLowerCase()
  if (!q) return databases.value
  return databases.value.filter(d => d.name.toLowerCase().includes(q))
})

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    default: return 'dot-red'
  }
}

function statusText(status: string): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'SUSPENDED': return '已挂起'
    case 'CREATING': return '创建中'
    default: return '异常'
  }
}

function storagePercent(db: Database): number {
  if (db.storage_limit_gb === 0) return 0
  return Math.min(100, (db.storage_used_gb / db.storage_limit_gb) * 100)
}

async function fetchDatabases() {
  loading.value = true
  try {
    const res = await databaseApi.list()
    databases.value = res.data
  } catch (e) {
    console.error('Failed to load databases', e)
  } finally {
    loading.value = false
  }
}

async function pollUntilReady(id: string) {
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 2000))
    try {
      const res = await databaseApi.get(id)
      const status = res.data.status
      if (['RUNNING', 'SUSPENDED', 'ERROR'].includes(status)) {
        break
      }
    } catch {
      break
    }
  }
  await fetchDatabases()
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  createLoading.value = true
  try {
    const res = await databaseApi.create({
      name: createForm.name.trim(),
      compute_size: createForm.compute_size,
      suspend_timeout: createForm.suspend_timeout,
      storage_limit_gb: createForm.storage_limit_gb,
    })
    showCreateDialog.value = false
    createForm.name = ''
    createForm.compute_size = '1cu'
    createForm.suspend_timeout = '5m'
    createForm.storage_limit_gb = 10
    await fetchDatabases()
    pollUntilReady(res.data.id)
  } catch (e) {
    console.error('Failed to create database', e)
  } finally {
    createLoading.value = false
  }
}

async function handleSuspend(db: Database) {
  actionLoading[db.id] = true
  try {
    await databaseApi.suspend(db.id)
    await fetchDatabases()
    pollUntilReady(db.id)
  } catch (e) {
    console.error('Failed to suspend', e)
  } finally {
    actionLoading[db.id] = false
  }
}

async function handleResume(db: Database) {
  actionLoading[db.id] = true
  try {
    await databaseApi.resume(db.id)
    await fetchDatabases()
    pollUntilReady(db.id)
  } catch (e) {
    console.error('Failed to resume', e)
  } finally {
    actionLoading[db.id] = false
  }
}

function confirmDelete(db: Database) {
  deleteTarget.value = db
}

async function handleDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await databaseApi.delete(deleteTarget.value.id)
    deleteTarget.value = null
    await fetchDatabases()
  } catch (e) {
    console.error('Failed to delete', e)
  } finally {
    deleteLoading.value = false
  }
}

onMounted(fetchDatabases)
</script>

<style scoped>
.page-db-list {
  padding: 4px;
}

.breadcrumb {
  margin-bottom: 20px;
  font-size: 14px;
  color: #999;
}

.breadcrumb-link {
  color: #0073e6;
  text-decoration: none;
}

.breadcrumb-link:hover {
  text-decoration: underline;
}

.breadcrumb-sep {
  margin: 0 8px;
  color: #d9d9d9;
}

.breadcrumb-item.active {
  color: #333;
  font-weight: 500;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.search-input {
  width: 260px;
  height: 34px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 12px;
  font-size: 14px;
  color: #333;
  outline: none;
  transition: border-color 0.2s;
}

.search-input:focus {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.search-input::placeholder {
  color: #bfbfbf;
}

/* Buttons */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 16px;
  font-size: 14px;
  border-radius: 4px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background-color: #0073e6;
  color: #fff;
  border-color: #0073e6;
}

.btn-primary:hover:not(:disabled) {
  background-color: #005bb5;
}

.btn-default {
  background-color: #fff;
  color: #333;
  border-color: #d9d9d9;
}

.btn-default:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.btn-danger {
  background-color: #ff4d4f;
  color: #fff;
  border-color: #ff4d4f;
}

.btn-danger:hover:not(:disabled) {
  background-color: #d9363e;
}

.btn-small {
  height: 28px;
  padding: 0 10px;
  font-size: 13px;
}

.btn-text {
  background: none;
  border: none;
  color: #0073e6;
  padding: 0 6px;
}

.btn-text:hover:not(:disabled) {
  color: #005bb5;
}

.btn-danger-text {
  color: #ff4d4f;
}

.btn-danger-text:hover:not(:disabled) {
  color: #d9363e;
}

.action-btns {
  display: flex;
  gap: 4px;
}

/* Table */
.section-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
  overflow: hidden;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  padding: 12px 20px;
  text-align: left;
  font-size: 13px;
  font-weight: 500;
  color: #666;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}

.data-table td {
  padding: 12px 20px;
  font-size: 14px;
  color: #333;
  border-bottom: 1px solid #f5f5f5;
}

.data-table tbody tr:hover {
  background-color: #fafafa;
}

.db-name-link {
  color: #0073e6;
  text-decoration: none;
  font-weight: 500;
}

.db-name-link:hover {
  text-decoration: underline;
}

/* Status dot */
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.dot-green { background-color: #52c41a; }
.dot-gray { background-color: #d9d9d9; }
.dot-blue { background-color: #1890ff; }
.dot-red { background-color: #ff4d4f; }

/* Storage bar */
.storage-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.storage-bar {
  width: 80px;
  height: 6px;
  background-color: #f0f0f0;
  border-radius: 3px;
  overflow: hidden;
  flex-shrink: 0;
}

.storage-fill {
  height: 100%;
  background-color: #0073e6;
  border-radius: 3px;
  transition: width 0.3s;
}

.storage-text {
  font-size: 13px;
  color: #666;
  white-space: nowrap;
}

.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: #999;
  font-size: 14px;
}

/* Dialog */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog-box {
  background: #fff;
  border-radius: 8px;
  width: 480px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
}

.dialog-confirm {
  width: 420px;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid #f0f0f0;
}

.dialog-header h3 {
  font-size: 16px;
  font-weight: 500;
  color: #333;
  margin: 0;
}

.dialog-close {
  background: none;
  border: none;
  font-size: 20px;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
}

.dialog-close:hover {
  color: #333;
}

.dialog-body {
  padding: 24px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 24px;
  border-top: 1px solid #f0f0f0;
}

.form-group {
  margin-bottom: 16px;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-label {
  display: block;
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
  font-weight: 500;
}

.required {
  color: #ff4d4f;
}

.form-input,
.form-select {
  width: 100%;
  height: 34px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 12px;
  font-size: 14px;
  color: #333;
  outline: none;
  transition: border-color 0.2s;
  background: #fff;
}

.form-input:focus,
.form-select:focus {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.confirm-text {
  font-size: 14px;
  color: #333;
  line-height: 1.6;
}
</style>
