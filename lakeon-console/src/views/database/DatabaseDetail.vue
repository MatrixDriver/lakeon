<template>
  <div class="page-db-detail" v-if="database">
    <div class="breadcrumb">
      <router-link to="/dashboard" class="breadcrumb-link">总览</router-link>
      <span class="breadcrumb-sep">/</span>
      <router-link to="/databases" class="breadcrumb-link">数据库实例</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-item active">{{ database.name }}</span>
    </div>

    <!-- Resource Summary -->
    <div class="summary-card">
      <div class="summary-top">
        <div class="summary-main">
          <h2 class="db-title">{{ database.name }}</h2>
          <div class="summary-meta">
            <span class="meta-item">ID: {{ database.id }}</span>
            <span class="meta-item">
              <span class="status-dot" :class="statusClass(database.status)"></span>
              {{ statusText(database.status) }}
            </span>
            <span class="meta-item">规格: {{ database.compute_size }}</span>
            <span class="meta-item">挂起超时: {{ database.suspend_timeout }}</span>
          </div>
        </div>
        <div class="summary-actions">
          <button
            v-if="database.status === 'RUNNING'"
            class="btn btn-default"
            :disabled="actionLoading"
            @click="handleSuspend"
          >挂起</button>
          <button
            v-if="database.status === 'SUSPENDED'"
            class="btn btn-primary"
            :disabled="actionLoading"
            @click="handleResume"
          >恢复</button>
        </div>
      </div>

      <div class="summary-bottom">
        <div class="summary-field">
          <span class="field-label">连接地址</span>
          <div class="field-value-row">
            <code class="uri-text">{{ database.connection_uri || '-' }}</code>
            <button
              v-if="database.connection_uri"
              class="copy-btn"
              @click="handleCopy(database.connection_uri)"
            >复制</button>
          </div>
        </div>
        <div class="summary-field">
          <span class="field-label">存储用量</span>
          <span class="field-value">{{ database.storage_used_gb.toFixed(2) }} / {{ database.storage_limit_gb }} GB</span>
        </div>
      </div>
    </div>

    <!-- Tabs -->
    <div class="tabs-wrapper">
      <div class="tab-header">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="tab-btn"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- Tab 1: Basic Info -->
      <div v-if="activeTab === 'info'" class="tab-content">
        <div class="info-card">
          <h4 class="info-title">连接信息</h4>
          <div class="info-grid">
            <div class="info-row" v-for="field in connectionFields" :key="field.label">
              <span class="info-label">{{ field.label }}</span>
              <div class="info-value-row">
                <code>{{ field.value }}</code>
                <button class="copy-btn" @click="handleCopy(field.value)">复制</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab 2: Branches -->
      <div v-if="activeTab === 'branches'" class="tab-content">
        <div class="tab-toolbar">
          <button class="btn btn-primary btn-small" @click="showBranchDialog = true">创建分支</button>
        </div>
        <table class="data-table" v-if="branches.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>父分支</th>
              <th>状态</th>
              <th>计算状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="branch in branches" :key="branch.id">
              <td>
                {{ branch.name }}
                <span v-if="branch.is_default" class="default-tag">默认</span>
              </td>
              <td>{{ branch.parent_branch || '-' }}</td>
              <td>{{ branch.status }}</td>
              <td>{{ branch.compute_status }}</td>
              <td>{{ formatDate(branch.created_at) }}</td>
              <td>
                <button
                  v-if="!branch.is_default"
                  class="btn btn-small btn-text btn-danger-text"
                  @click="handleDeleteBranch(branch.id)"
                >删除</button>
                <span v-else class="text-muted">-</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">
          <p v-if="branchesLoading">加载中...</p>
          <p v-else>暂无分支</p>
        </div>

        <!-- Create Branch Dialog -->
        <div v-if="showBranchDialog" class="dialog-overlay" @click.self="showBranchDialog = false">
          <div class="dialog-box dialog-confirm">
            <div class="dialog-header">
              <h3>创建分支</h3>
              <button class="dialog-close" @click="showBranchDialog = false">&times;</button>
            </div>
            <div class="dialog-body">
              <div class="form-group">
                <label class="form-label">分支名称 <span class="required">*</span></label>
                <input v-model="branchName" class="form-input" placeholder="请输入分支名称" />
              </div>
            </div>
            <div class="dialog-footer">
              <button class="btn btn-default" @click="showBranchDialog = false">取消</button>
              <button
                class="btn btn-primary"
                :disabled="!branchName.trim() || branchCreating"
                @click="handleCreateBranch"
              >{{ branchCreating ? '创建中...' : '确定' }}</button>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab 3: Operations -->
      <div v-if="activeTab === 'operations'" class="tab-content">
        <div class="tab-toolbar">
          <select v-model="opTypeFilter" class="form-select filter-select" @change="fetchOperations">
            <option value="">全部操作</option>
            <option value="CREATE_DATABASE">创建数据库</option>
            <option value="DELETE_DATABASE">删除数据库</option>
            <option value="SUSPEND_DATABASE">挂起</option>
            <option value="RESUME_DATABASE">恢复</option>
            <option value="CREATE_BRANCH">创建分支</option>
            <option value="DELETE_BRANCH">删除分支</option>
          </select>
        </div>
        <table class="data-table" v-if="operations.length > 0">
          <thead>
            <tr>
              <th>操作类型</th>
              <th>状态</th>
              <th>开始时间</th>
              <th>完成时间</th>
              <th>耗时</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="op in operations" :key="op.id">
              <td>{{ op.operationType }}</td>
              <td>
                <span class="status-tag" :class="op.status === 'SUCCESS' ? 'tag-green' : 'tag-red'">
                  {{ op.status }}
                </span>
              </td>
              <td>{{ formatDate(op.startedAt) }}</td>
              <td>{{ op.completedAt ? formatDate(op.completedAt) : '-' }}</td>
              <td :class="durationColorClass(op.durationMs)">
                {{ formatDuration(op.durationMs) }}
              </td>
              <td>{{ op.errorMessage || '-' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">
          <p v-if="opsLoading">加载中...</p>
          <p v-else>暂无操作记录</p>
        </div>

        <!-- Pagination -->
        <div v-if="opsTotalPages > 1" class="pagination">
          <button
            class="page-btn"
            :disabled="opsPage === 0"
            @click="opsPage--; fetchOperations()"
          >上一页</button>
          <span class="page-info">{{ opsPage + 1 }} / {{ opsTotalPages }}</span>
          <button
            class="page-btn"
            :disabled="opsPage >= opsTotalPages - 1"
            @click="opsPage++; fetchOperations()"
          >下一页</button>
        </div>
      </div>
    </div>
  </div>

  <!-- Loading -->
  <div v-else class="page-loading">
    <p>{{ loadError ? '加载失败' : '加载中...' }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import { branchApi, type Branch } from '../../api/branch'
import { operationApi, type OperationLog } from '../../api/operation'
import { copyToClipboard } from '../../utils/clipboard'
import { formatDuration, formatDate } from '../../utils/format'

const route = useRoute()
const dbId = computed(() => route.params.id as string)

const database = ref<Database | null>(null)
const loadError = ref(false)
const actionLoading = ref(false)

const activeTab = ref('info')
const tabs = [
  { key: 'info', label: '基本信息' },
  { key: 'branches', label: '分支' },
  { key: 'operations', label: '操作历史' },
]

// Branches
const branches = ref<Branch[]>([])
const branchesLoading = ref(false)
const showBranchDialog = ref(false)
const branchName = ref('')
const branchCreating = ref(false)

// Operations
const operations = ref<OperationLog[]>([])
const opsLoading = ref(false)
const opTypeFilter = ref('')
const opsPage = ref(0)
const opsTotalPages = ref(0)

// Connection info parsing
const connectionFields = computed(() => {
  const uri = database.value?.connection_uri || ''
  if (!uri) return []

  try {
    const url = new URL(uri)
    return [
      { label: '主机', value: url.hostname },
      { label: '端口', value: url.port || '5432' },
      { label: '用户名', value: decodeURIComponent(url.username) },
      { label: '数据库', value: url.pathname.replace(/^\//, '') || 'postgres' },
      { label: '连接字符串', value: uri },
    ]
  } catch {
    return [{ label: '连接字符串', value: uri }]
  }
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

function durationColorClass(ms: number | null): string {
  if (ms === null) return ''
  if (ms < 1000) return 'duration-fast'
  if (ms <= 5000) return 'duration-medium'
  return 'duration-slow'
}

async function handleCopy(text: string) {
  const ok = await copyToClipboard(text)
  if (!ok) {
    console.warn('Copy failed')
  }
}

async function fetchDatabase() {
  try {
    const res = await databaseApi.get(dbId.value)
    database.value = res.data
  } catch {
    loadError.value = true
  }
}

async function pollUntilReady() {
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 2000))
    try {
      const res = await databaseApi.get(dbId.value)
      database.value = res.data
      if (['RUNNING', 'SUSPENDED', 'ERROR'].includes(res.data.status)) break
    } catch {
      break
    }
  }
}

async function handleSuspend() {
  actionLoading.value = true
  try {
    await databaseApi.suspend(dbId.value)
    await fetchDatabase()
    pollUntilReady()
  } catch (e) {
    console.error('Failed to suspend', e)
  } finally {
    actionLoading.value = false
  }
}

async function handleResume() {
  actionLoading.value = true
  try {
    await databaseApi.resume(dbId.value)
    await fetchDatabase()
    pollUntilReady()
  } catch (e) {
    console.error('Failed to resume', e)
  } finally {
    actionLoading.value = false
  }
}

// Branches
async function fetchBranches() {
  branchesLoading.value = true
  try {
    const res = await branchApi.list(dbId.value)
    branches.value = res.data
  } catch (e) {
    console.error('Failed to load branches', e)
  } finally {
    branchesLoading.value = false
  }
}

async function handleCreateBranch() {
  if (!branchName.value.trim()) return
  branchCreating.value = true
  try {
    await branchApi.create(dbId.value, { name: branchName.value.trim() })
    showBranchDialog.value = false
    branchName.value = ''
    await fetchBranches()
  } catch (e) {
    console.error('Failed to create branch', e)
  } finally {
    branchCreating.value = false
  }
}

async function handleDeleteBranch(branchId: string) {
  if (!confirm('确定要删除该分支吗？此操作不可恢复。')) return
  try {
    await branchApi.delete(dbId.value, branchId)
    await fetchBranches()
  } catch (e) {
    console.error('Failed to delete branch', e)
  }
}

// Operations
async function fetchOperations() {
  opsLoading.value = true
  try {
    const params: Record<string, unknown> = { page: opsPage.value, size: 10 }
    if (opTypeFilter.value) params.type = opTypeFilter.value
    const res = await operationApi.getByDatabase(dbId.value, params as { type?: string; page?: number; size?: number })
    operations.value = res.data.content
    opsTotalPages.value = res.data.totalPages
  } catch (e) {
    console.error('Failed to load operations', e)
  } finally {
    opsLoading.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'branches' && branches.value.length === 0) fetchBranches()
  if (tab === 'operations' && operations.value.length === 0) fetchOperations()
})

onMounted(fetchDatabase)
</script>

<style scoped>
.page-db-detail {
  padding: 4px;
}

.page-loading {
  text-align: center;
  padding: 60px 20px;
  color: #999;
  font-size: 14px;
}

/* Breadcrumb */
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

/* Summary Card */
.summary-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
  padding: 24px;
  margin-bottom: 20px;
}

.summary-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.db-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
  margin: 0 0 8px;
}

.summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.meta-item {
  font-size: 13px;
  color: #666;
}

.summary-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.summary-bottom {
  border-top: 1px solid #f0f0f0;
  padding-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary-field {
  display: flex;
  align-items: center;
  gap: 12px;
}

.field-label {
  font-size: 13px;
  color: #999;
  min-width: 64px;
  flex-shrink: 0;
}

.field-value {
  font-size: 14px;
  color: #333;
}

.field-value-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.uri-text {
  font-size: 13px;
  color: #333;
  background: #f5f5f5;
  padding: 4px 8px;
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.copy-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 10px;
  font-size: 12px;
  color: #0073e6;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}

.copy-btn:hover {
  border-color: #0073e6;
  background-color: #f0f7ff;
}

/* Status dot */
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.dot-green { background-color: #52c41a; }
.dot-gray { background-color: #d9d9d9; }
.dot-blue { background-color: #1890ff; }
.dot-red { background-color: #ff4d4f; }

/* Tabs */
.tabs-wrapper {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
  overflow: hidden;
}

.tab-header {
  display: flex;
  border-bottom: 1px solid #f0f0f0;
  padding: 0 16px;
}

.tab-btn {
  background: none;
  border: none;
  padding: 12px 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: #0073e6;
}

.tab-btn.active {
  color: #0073e6;
  border-bottom-color: #0073e6;
  font-weight: 500;
}

.tab-content {
  padding: 20px;
}

.tab-toolbar {
  margin-bottom: 16px;
}

/* Info card */
.info-card {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 20px;
}

.info-title {
  font-size: 15px;
  font-weight: 500;
  color: #333;
  margin: 0 0 16px;
}

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.info-label {
  font-size: 13px;
  color: #999;
  min-width: 80px;
  flex-shrink: 0;
}

.info-value-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.info-value-row code {
  font-size: 13px;
  color: #333;
  background: #f5f5f5;
  padding: 4px 8px;
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

/* Table */
.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  padding: 12px 16px;
  text-align: left;
  font-size: 13px;
  font-weight: 500;
  color: #666;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}

.data-table td {
  padding: 12px 16px;
  font-size: 14px;
  color: #333;
  border-bottom: 1px solid #f5f5f5;
}

.data-table tbody tr:hover {
  background-color: #fafafa;
}

.default-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background-color: #e6f7ff;
  color: #1890ff;
  border: 1px solid #91d5ff;
  margin-left: 6px;
}

.text-muted {
  color: #d9d9d9;
}

/* Status tags */
.status-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.tag-green {
  color: #52c41a;
  background-color: #f6ffed;
  border: 1px solid #b7eb8f;
}

.tag-red {
  color: #ff4d4f;
  background-color: #fff2f0;
  border: 1px solid #ffccc7;
}

/* Duration colors */
.duration-fast { color: #52c41a; }
.duration-medium { color: #fa8c16; }
.duration-slow { color: #ff4d4f; }

/* Pagination */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 16px 0 4px;
}

.page-btn {
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 12px;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  transition: all 0.2s;
}

.page-btn:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.page-info {
  font-size: 13px;
  color: #666;
}

/* Filter select */
.filter-select {
  width: 180px;
  height: 32px;
  font-size: 13px;
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
</style>
