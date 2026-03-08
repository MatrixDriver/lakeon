<template>
  <div class="page-db-detail" v-if="database">
    <div v-if="copyTip" class="copy-toast">{{ copyTip }}</div>
    <div class="breadcrumb">
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
            <span class="meta-item" v-if="database.status === 'RUNNING'">连接数: {{ database.active_connections || 0 }}</span>
          </div>
        </div>
        <div class="summary-actions">
          <router-link
            :to="`/databases/${database.id}/manager`"
            class="btn btn-primary"
          >管理数据库</router-link>
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
              :class="{ 'copy-btn-ok': copiedField === 'uri' }"
              @click="handleCopy(database.connection_uri, 'uri')"
            >{{ copiedField === 'uri' ? '已复制 ✓' : '复制' }}</button>
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
                <button
                  class="copy-btn"
                  :class="{ 'copy-btn-ok': copiedField === field.label }"
                  @click="handleCopy(field.value, field.label)"
                >{{ copiedField === field.label ? '已复制 ✓' : '复制' }}</button>
              </div>
            </div>
            <div class="info-row">
              <span class="info-label">密码</span>
              <div class="info-value-row" v-if="newPassword">
                <code class="password-value">{{ newPassword }}</code>
                <button
                  class="copy-btn"
                  :class="{ 'copy-btn-ok': copiedField === 'password' }"
                  @click="handleCopy(newPassword!, 'password')"
                >{{ copiedField === 'password' ? '已复制 ✓' : '复制' }}</button>
              </div>
              <div class="info-value-row" v-else>
                <code class="password-masked">••••••••</code>
                <button class="btn btn-small btn-default" :disabled="resettingPassword" @click="handleResetPassword">
                  {{ resettingPassword ? '重置中...' : '重置密码' }}
                </button>
              </div>
            </div>
          </div>
          <div v-if="newPassword" class="password-warning">
            请立即复制密码，刷新页面后将无法再次查看。
          </div>
        </div>
      </div>

      <!-- Tab 2: Branches -->
      <div v-if="activeTab === 'branches'" class="tab-content">
        <div class="tab-toolbar">
          <button class="btn btn-primary btn-small" @click="preselectedParentId = ''; showBranchDialog = true">创建分支</button>
        </div>

        <!-- Branch Tree Visualization -->
        <BranchTreeView
          v-if="treeNodes.length > 0"
          :nodes="treeNodes"
          :activeBranchId="activeBranchId"
          @select="handleTreeSelect"
          @activate="handleActivateBranch"
          @create="handleTreeCreate"
          @delete="handleDeleteBranch"
        />

        <div class="section-card">
          <TableToolbar v-model="branchSearch" placeholder="搜索分支名称" :loading="branchesLoading" @refresh="fetchBranches" />
          <div class="table-wrapper">
            <table class="data-table" v-if="filteredBranches.length > 0">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>父分支</th>
                  <th>LSN</th>
                  <th>大小</th>
                  <th>状态</th>
                  <th>创建时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="branch in pagedBranches"
                  :key="branch.id"
                  :class="{ 'row-highlight': selectedBranchId === branch.id }"
                >
                  <td>
                    {{ branch.name }}
                    <span v-if="branch.is_default" class="default-tag">默认</span>
                    <span v-if="isActiveBranch(branch)" class="active-tag">活跃</span>
                  </td>
                  <td>{{ branch.parent_branch || '-' }}</td>
                  <td class="mono-text">{{ branch.last_record_lsn || '-' }}</td>
                  <td>{{ formatSize(branch.current_logical_size_bytes) }}</td>
                  <td>{{ branch.status }}</td>
                  <td>{{ formatDate(branch.created_at) }}</td>
                  <td class="action-cell">
                    <button
                      v-if="!isActiveBranch(branch)"
                      class="btn btn-small btn-text"
                      :disabled="activatingBranch"
                      @click="handleActivateBranch(branch.id)"
                    >切换</button>
                    <button
                      v-if="!branch.is_default"
                      class="btn btn-small btn-text btn-danger-text"
                      @click="handleDeleteBranch(branch.id)"
                    >删除</button>
                    <span v-if="branch.is_default && isActiveBranch(branch)" class="text-muted">-</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty-state">
              <p v-if="branchesLoading">加载中...</p>
              <p v-else>暂无分支</p>
            </div>
          </div>
          <TableFooter
            v-if="filteredBranches.length > 0"
            :total="filteredBranches.length"
            v-model:pageSize="branchPageSize"
            v-model:currentPage="branchCurrentPage"
          />
        </div>

        <CreateBranchDialog
          :visible="showBranchDialog"
          :branches="branches"
          :preselectedParentId="preselectedParentId"
          :dbId="dbId"
          @close="showBranchDialog = false"
          @created="handleBranchCreated"
        />
      </div>

      <!-- Tab 3: Operations -->
      <div v-if="activeTab === 'operations'" class="tab-content">
        <div class="tab-toolbar">
          <select v-model="opTypeFilter" class="form-select filter-select" @change="opsCurrentPage = 1; fetchOperations()">
            <option value="">全部操作</option>
            <option value="CREATE">创建</option>
            <option value="SUSPEND">挂起</option>
            <option value="RESUME">恢复</option>
            <option value="DELETE">删除</option>
            <option value="UPDATE">更新</option>
            <option value="IMPORT">导入</option>
          </select>
        </div>
        <div class="section-card">
          <div class="ops-refresh-bar">
            <button class="toolbar-icon-btn" @click="fetchOperations" :disabled="opsLoading" :title="opsLoading ? '加载中...' : '刷新'">
              <svg :class="{ spinning: opsLoading }" viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
                <path d="M13.65 2.35a8 8 0 1 0 1.77 5.15h-2.02a6 6 0 1 1-1.13-3.87L10 6h6V0l-2.35 2.35z"/>
              </svg>
            </button>
          </div>
          <div class="table-wrapper">
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
          </div>
          <TableFooter
            v-if="opsTotalElements > 0"
            :total="opsTotalElements"
            v-model:pageSize="opsPageSize"
            v-model:currentPage="opsCurrentPage"
          />
        </div>
      </div>
      <!-- Tab 4: Import -->
      <div v-if="activeTab === 'import'" class="tab-content">
        <div v-if="selectedImportTaskId">
          <ImportTaskDetail
            :dbId="dbId"
            :taskId="selectedImportTaskId"
            @back="selectedImportTaskId = null; fetchImportTasks()"
            @updated="fetchImportTasks"
          />
        </div>
        <div v-else>
          <div class="tab-toolbar">
            <button class="btn btn-primary btn-small" @click="showImportWizard = true">导入数据</button>
          </div>
          <div class="section-card">
            <TableToolbar v-model="importSearch" placeholder="搜索任务ID或源数据库" :loading="importLoading" @refresh="fetchImportTasks" />
            <div class="table-wrapper">
              <table class="data-table" v-if="filteredImports.length > 0">
                <thead>
                  <tr>
                    <th>任务ID</th>
                    <th>源数据库</th>
                    <th>模式</th>
                    <th>进度</th>
                    <th>状态</th>
                    <th>创建时间</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="task in pagedImports" :key="task.id"
                      class="clickable-row" @click="selectedImportTaskId = task.id">
                    <td>{{ task.id }}</td>
                    <td>{{ task.source_host }}:{{ task.source_port }}/{{ task.source_dbname }}</td>
                    <td>{{ task.mode === 'FULL' ? '整库' : '按表' }}</td>
                    <td>{{ task.completed_tables }}/{{ task.total_tables }}</td>
                    <td>
                      <span class="status-tag" :class="importStatusClass(task.status)">
                        {{ importStatusText(task.status) }}
                      </span>
                    </td>
                    <td>{{ formatDate(task.created_at) }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="empty-state">
                <p v-if="importLoading">加载中...</p>
                <p v-else>暂无导入任务</p>
              </div>
            </div>
            <TableFooter
              v-if="filteredImports.length > 0"
              :total="filteredImports.length"
              v-model:pageSize="importPageSize"
              v-model:currentPage="importCurrentPage"
            />
          </div>
        </div>

        <ImportWizard
          :dbId="dbId"
          :visible="showImportWizard"
          @close="showImportWizard = false"
          @created="handleImportCreated"
        />
      </div>
    </div>
  </div>

  <!-- Loading -->
  <div v-else class="page-loading">
    <p>{{ loadError ? '加载失败' : '加载中...' }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import { branchApi, type Branch, type BranchTreeNode } from '../../api/branch'
import { operationApi, type OperationLog } from '../../api/operation'
import { importApi, type ImportTask } from '../../api/import'
import ImportWizard from './ImportWizard.vue'
import ImportTaskDetail from './ImportTaskDetail.vue'
import CreateBranchDialog from './CreateBranchDialog.vue'
import BranchTreeView from '../../components/BranchTreeView.vue'
import TableToolbar from '../../components/TableToolbar.vue'
import TableFooter from '../../components/TableFooter.vue'
import { copyToClipboard } from '../../utils/clipboard'
import { formatDuration, formatDate } from '../../utils/format'

const route = useRoute()
const dbId = computed(() => route.params.id as string)

const database = ref<Database | null>(null)
const loadError = ref(false)
const actionLoading = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const newPassword = ref<string | null>(null)
const resettingPassword = ref(false)

const activeTab = ref('info')
const tabs = [
  { key: 'info', label: '基本信息' },
  { key: 'branches', label: '分支' },
  { key: 'operations', label: '操作历史' },
  { key: 'import', label: '导入' },
]

// Branches
const branches = ref<Branch[]>([])
const branchesLoading = ref(false)
const showBranchDialog = ref(false)
const preselectedParentId = ref('')
const branchSearch = ref('')
const branchPageSize = ref(10)
const branchCurrentPage = ref(1)
const treeNodes = ref<BranchTreeNode[]>([])
const activatingBranch = ref(false)

const filteredBranches = computed(() => {
  const q = branchSearch.value.toLowerCase()
  if (!q) return branches.value
  return branches.value.filter(b => b.name.toLowerCase().includes(q))
})
const pagedBranches = computed(() => {
  const start = (branchCurrentPage.value - 1) * branchPageSize.value
  return filteredBranches.value.slice(start, start + branchPageSize.value)
})

// Import
const importTasks = ref<ImportTask[]>([])
const importLoading = ref(false)
const showImportWizard = ref(false)
const selectedImportTaskId = ref<string | null>(null)
const importSearch = ref('')
const importPageSize = ref(10)
const importCurrentPage = ref(1)

const filteredImports = computed(() => {
  const q = importSearch.value.toLowerCase()
  if (!q) return importTasks.value
  return importTasks.value.filter(t =>
    t.id.toLowerCase().includes(q) ||
    t.source_host.toLowerCase().includes(q) ||
    t.source_dbname.toLowerCase().includes(q)
  )
})
const pagedImports = computed(() => {
  const start = (importCurrentPage.value - 1) * importPageSize.value
  return filteredImports.value.slice(start, start + importPageSize.value)
})

// Operations
const operations = ref<OperationLog[]>([])
const opsLoading = ref(false)
const opTypeFilter = ref('')
const opsPageSize = ref(10)
const opsCurrentPage = ref(1)
const opsTotalElements = ref(0)

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

const copyTip = ref('')
let copyTipTimer: ReturnType<typeof setTimeout> | null = null

// Track which field was just copied (for inline button feedback)
const copiedField = ref('')
let copiedTimer: ReturnType<typeof setTimeout> | null = null

async function handleCopy(text: string, fieldKey?: string) {
  const ok = await copyToClipboard(text)
  copyTip.value = ok ? '已复制' : '复制失败'
  if (copyTipTimer) clearTimeout(copyTipTimer)
  copyTipTimer = setTimeout(() => { copyTip.value = '' }, 2000)

  if (ok && fieldKey) {
    copiedField.value = fieldKey
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copiedField.value = '' }, 1500)
  }
}

async function handleResetPassword() {
  if (!confirm('确定要重置密码吗？旧密码将立即失效。')) return
  resettingPassword.value = true
  try {
    const res = await databaseApi.resetPassword(dbId.value)
    newPassword.value = res.data.password
  } catch (e) {
    console.error('Failed to reset password', e)
  } finally {
    resettingPassword.value = false
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

const selectedBranchId = ref('')

const activeBranchId = computed(() => {
  if (!database.value) return ''
  // The active branch is the one whose neon_timeline_id matches the database's current timeline
  const dbTimelineId = database.value.neon_timeline_id
  const active = branches.value.find(b => b.neon_timeline_id && b.neon_timeline_id === dbTimelineId)
  // Fallback: default branch
  return active?.id || branches.value.find(b => b.is_default)?.id || ''
})

function isActiveBranch(branch: Branch): boolean {
  return branch.id === activeBranchId.value
}

function formatSize(bytes: number | null): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

// Branches
async function fetchBranches() {
  branchesLoading.value = true
  try {
    const [listRes, treeRes] = await Promise.all([
      branchApi.list(dbId.value),
      branchApi.getTree(dbId.value),
    ])
    branches.value = listRes.data
    treeNodes.value = treeRes.data.nodes
  } catch (e) {
    console.error('Failed to load branches', e)
  } finally {
    branchesLoading.value = false
  }
}

function handleBranchCreated() {
  fetchBranches()
}

function handleTreeSelect(branchId: string) {
  selectedBranchId.value = selectedBranchId.value === branchId ? '' : branchId
}

function handleTreeCreate(parentBranchId: string) {
  preselectedParentId.value = parentBranchId
  showBranchDialog.value = true
}

async function handleActivateBranch(branchId: string) {
  if (!confirm('确定要切换到该分支吗？当前计算节点将重启。')) return
  activatingBranch.value = true
  try {
    await branchApi.activate(dbId.value, branchId)
    await fetchDatabase()
    await fetchBranches()
  } catch (e) {
    console.error('Failed to activate branch', e)
  } finally {
    activatingBranch.value = false
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
    const params: Record<string, unknown> = { page: opsCurrentPage.value - 1, size: opsPageSize.value }
    if (opTypeFilter.value) params.type = opTypeFilter.value
    const res = await operationApi.getByDatabase(dbId.value, params as { type?: string; page?: number; size?: number })
    operations.value = res.data.content
    opsTotalElements.value = res.data.totalElements
  } catch (e) {
    console.error('Failed to load operations', e)
  } finally {
    opsLoading.value = false
  }
}

watch(opsPageSize, () => { opsCurrentPage.value = 1; fetchOperations() })
watch(opsCurrentPage, () => { fetchOperations() })

// Import
async function fetchImportTasks() {
  importLoading.value = true
  try {
    const res = await importApi.list(dbId.value)
    importTasks.value = res.data
  } catch (e) {
    console.error('Failed to load import tasks', e)
  } finally {
    importLoading.value = false
  }
}

function importStatusClass(status: string): string {
  switch (status) {
    case 'COMPLETED': return 'tag-green'
    case 'RUNNING': return 'tag-blue'
    case 'FAILED': return 'tag-red'
    case 'PARTIAL': return 'tag-orange'
    case 'PAUSED': case 'CANCELLED': return 'tag-gray'
    default: return ''
  }
}

function importStatusText(status: string): string {
  switch (status) {
    case 'PENDING': return '等待中'
    case 'RUNNING': return '导入中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'PARTIAL': return '部分完成'
    case 'PAUSED': return '已暂停'
    case 'CANCELLED': return '已取消'
    default: return status
  }
}

function handleImportCreated(task: ImportTask) {
  showImportWizard.value = false
  selectedImportTaskId.value = task.id
  fetchImportTasks()
}

watch([branchSearch, branchPageSize], () => { branchCurrentPage.value = 1 })
watch([importSearch, importPageSize], () => { importCurrentPage.value = 1 })

watch(activeTab, (tab) => {
  if (tab === 'branches' && branches.value.length === 0) fetchBranches()
  if (tab === 'operations' && operations.value.length === 0) fetchOperations()
  if (tab === 'import' && importTasks.value.length === 0) fetchImportTasks()
})

onMounted(() => {
  fetchDatabase()
  pollTimer = setInterval(fetchDatabase, 15000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.page-db-detail {
  padding: 4px;
}

.page-loading {
  text-align: center;
  padding: 60px 20px;
  color: #8a8e99;
  font-size: 14px;
}

/* Summary Card */
.summary-card {
  background: #fff;
  border-radius: 2px;
  border: 1px solid #dfe1e6;
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
  font-size: 18px;
  font-weight: 700;
  color: #191919;
  margin: 0 0 8px;
}

.summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.meta-item {
  font-size: 14px;
  color: #575d6c;
}

.summary-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.summary-bottom {
  border-top: 1px solid #dfe1e6;
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
  font-size: 14px;
  color: #8a8e99;
  min-width: 72px;
  flex-shrink: 0;
}

.field-value {
  font-size: 14px;
  color: #191919;
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
  color: #191919;
  background: #f2f3f5;
  padding: 4px 8px;
  border-radius: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

/* Tabs */
.tabs-wrapper {
  background: #fff;
  border-radius: 2px;
  border: 1px solid #ebebeb;
  overflow: hidden;
}

.tab-header {
  display: flex;
  border-bottom: 1px solid #ebebeb;
  padding: 0 20px;
}

.tab-btn {
  background: none;
  border: none;
  padding: 14px 20px;
  font-size: 14px;
  color: #575d6c;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: #0073e6;
}

.tab-btn.active {
  color: #191919;
  border-bottom-color: #0073e6;
  font-weight: 600;
}

.tab-content {
  padding: 20px;
}

.tab-toolbar {
  margin-bottom: 16px;
}

/* Info card */
.info-card {
  border: 1px solid #dfe1e6;
  border-radius: 2px;
  padding: 20px;
}

.info-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
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
  font-size: 14px;
  color: #8a8e99;
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
  color: #191919;
  background: #f2f3f5;
  padding: 4px 8px;
  border-radius: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.copy-toast {
  position: fixed;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  background: #222;
  color: #fff;
  padding: 8px 20px;
  border-radius: 4px;
  font-size: 14px;
  z-index: 9999;
  pointer-events: none;
}

.copy-btn-ok {
  background: #f6ffed !important;
  border-color: #52c41a !important;
  color: #52c41a !important;
}

.password-masked {
  color: #8a8e99;
}

.password-value {
  color: #d4380d;
  font-weight: 600;
}

.password-warning {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fff7e6;
  border: 1px solid #ffd591;
  border-radius: 2px;
  color: #d46b08;
  font-size: 13px;
}

.default-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 2px;
  font-size: 12px;
  background-color: #e6f7ff;
  color: #0073e6;
  margin-left: 6px;
}

.active-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 2px;
  font-size: 12px;
  background-color: #f6ffed;
  color: #52c41a;
  margin-left: 6px;
}

.row-highlight {
  background-color: #f0f5ff !important;
}

.mono-text {
  font-family: monospace;
  font-size: 12px;
}

.action-cell {
  display: flex;
  gap: 4px;
  align-items: center;
}

/* Import tab */
.clickable-row { cursor: pointer; }
.clickable-row:hover { background: #f5f5f5; }
.tag-blue { background-color: #e6f7ff; color: #0073e6; }
.tag-orange { background-color: #fff7e6; color: #d46b08; }
.tag-gray { background-color: #f0f0f0; color: #8a8e99; }

/* Filter select */
.filter-select {
  width: 180px;
  height: 32px;
  font-size: 14px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
}

/* Operations refresh bar */
.ops-refresh-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 6px 10px;
  border-bottom: 1px solid #ebebeb;
}

.toolbar-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 2px;
  background: none;
  color: #575d6c;
  cursor: pointer;
  transition: all 0.15s;
}

.toolbar-icon-btn:hover:not(:disabled) {
  color: #0073e6;
  background: #f0f5ff;
}

.toolbar-icon-btn:disabled {
  color: #c2c6cc;
  cursor: not-allowed;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.spinning {
  animation: spin 0.8s linear infinite;
}

.section-card {
  border: 1px solid #ebebeb;
  border-radius: 2px;
  overflow: hidden;
  background: #fff;
}

@media (max-width: 768px) {
  .summary-top {
    flex-direction: column;
    gap: 16px;
  }

  .summary-actions {
    width: 100%;
  }

  .summary-meta {
    gap: 8px 16px;
  }

  .summary-field {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .uri-text {
    font-size: 12px;
    max-width: 100%;
  }

  .tab-header {
    padding: 0 12px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }

  .tab-btn {
    padding: 12px 14px;
    font-size: 13px;
    white-space: nowrap;
  }

  .tab-content {
    padding: 16px 12px;
  }

  .info-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .info-label {
    min-width: auto;
  }

  .filter-select {
    width: 100%;
  }

  .summary-card {
    padding: 16px;
  }
}
</style>
