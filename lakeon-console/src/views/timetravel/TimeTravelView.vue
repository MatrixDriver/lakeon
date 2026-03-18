<template>
  <div class="page-timetravel">
    <h2 class="page-title">时间旅行</h2>
    <p class="page-desc">管理数据库分支和版本，查看版本差异，回滚到历史版本。分支是数据库的 copy-on-write 快照，创建几乎零开销，各分支数据完全隔离。</p>

    <!-- Database selector -->
    <div class="db-selector">
      <label class="db-selector-label">选择数据库</label>
      <select v-model="selectedDbId" class="form-input form-select db-select" :disabled="dbListLoading">
        <option value="" disabled>{{ dbListLoading ? '加载中...' : '请选择数据库' }}</option>
        <option v-for="db in databases" :key="db.id" :value="db.id">{{ db.name }}</option>
      </select>
    </div>

    <!-- Empty state: no databases -->
    <div v-if="!dbListLoading && databases.length === 0" class="empty-state-box">
      <p>暂无数据库。请先创建一个数据库。</p>
      <router-link to="/dashboard" class="btn btn-primary">前往创建</router-link>
    </div>

    <!-- Empty state: no database selected -->
    <div v-else-if="!selectedDbId && !dbListLoading" class="empty-state-box">
      <p>请选择一个数据库以查看分支和版本。</p>
    </div>

    <!-- Branch + Version panel -->
    <template v-else-if="selectedDbId">
      <div class="branch-version-layout">
        <!-- Left: Branch list -->
        <div class="branch-list-panel">
          <div class="branch-list-header">
            <span class="branch-list-title">分支列表</span>
            <button class="btn btn-small btn-text" @click="fetchBranches">刷新</button>
          </div>
          <div v-if="branchesLoading" class="branch-list-loading">加载中...</div>
          <div v-else class="branch-list-items">
            <div
              v-for="branch in branches"
              :key="branch.id"
              class="branch-list-item"
              :class="{ 'branch-list-item-selected': selectedBranchId === branch.id }"
              @click="selectBranchForVersions(branch.id)"
            >
              <div class="branch-item-row">
                <span
                  class="branch-status-dot"
                  :class="branch.compute_status === 'RUNNING' ? 'dot-green' : branch.compute_status === 'SUSPENDED' ? 'dot-yellow' : 'dot-gray'"
                ></span>
                <span class="branch-item-name">{{ branch.name }}</span>
                <span v-if="branch.is_default" class="default-tag">默认</span>
                <span class="compute-status-label" :class="branch.compute_status === 'RUNNING' ? 'status-running' : branch.compute_status === 'SUSPENDED' ? 'status-suspended' : 'status-idle'">
                  {{ branch.compute_status === 'RUNNING' ? '运行中' : branch.compute_status === 'SUSPENDED' ? '已挂起' : '未启动' }}
                </span>
              </div>
              <div v-if="branch.connection_uri" class="branch-item-uri" @click.stop="copyUri(branch.connection_uri)" title="点击复制连接串">
                <code class="branch-uri-text">{{ branch.connection_uri.replace(/^postgres:\/\/[^@]+@/, '') }}</code>
                <span class="btn-copy-uri">⎘</span>
              </div>
              <div class="branch-item-meta">
                <span class="mono-text">{{ branch.last_record_lsn || '-' }}</span>
                <span>{{ formatSize(branch.current_logical_size_bytes) }}</span>
              </div>
              <div class="branch-item-actions" v-if="selectedBranchId === branch.id">
                <button
                  v-if="!branch.is_default"
                  class="btn btn-small btn-text"
                  @click.stop="handlePromoteBranch(branch.id)"
                >提升为默认</button>
                <button
                  v-if="!branch.is_default"
                  class="btn btn-small btn-text btn-danger-text"
                  @click.stop="handleDeleteBranch(branch.id)"
                >删除</button>
              </div>
            </div>
          </div>
          <div class="branch-list-footer">
            <button class="btn btn-small btn-default" style="width: 100%;" @click="preselectedParentId = ''; showBranchDialog = true">+ 新建分支</button>
          </div>
        </div>

        <!-- Right: Version timeline -->
        <div class="version-timeline-panel">
          <template v-if="selectedBranchId">
            <!-- Branch connection URI bar -->
            <div v-if="selectedBranchObj?.connection_uri" class="branch-uri-bar">
              <span class="branch-uri-bar-label">连接串</span>
              <code class="branch-uri-bar-value">{{ selectedBranchObj.connection_uri }}</code>
              <button class="btn btn-small btn-default" @click="copyUri(selectedBranchObj.connection_uri)">复制</button>
            </div>
            <div class="version-timeline-header">
              <span class="version-timeline-title">
                {{ branches.find(b => b.id === selectedBranchId)?.name || '' }} - 版本历史
              </span>
              <div class="version-header-actions">
                <button
                  v-if="!squashMode"
                  class="btn btn-default btn-small"
                  :disabled="versions.length < 3"
                  :title="versions.length < 3 ? '需要至少 3 个版本才能合并' : ''"
                  @click="enterSquashMode"
                >合并版本</button>
                <button v-if="squashMode" class="btn btn-default btn-small" @click="exitSquashMode">取消合并</button>
                <button class="btn btn-primary btn-small" @click="showVersionDialog = true">创建版本</button>
              </div>
            </div>
            <div v-if="versionsLoading" class="version-timeline-loading">加载中...</div>
            <div v-else-if="versions.length === 0" class="version-timeline-empty">
              <p>暂无版本。点击「创建版本」保存当前数据库状态。</p>
            </div>
            <div v-else class="version-list">
              <!-- Squash mode hint -->
              <div v-if="squashMode" class="squash-hint">
                <template v-if="!squashFrom">点击选择起始版本</template>
                <template v-else-if="!squashTo">点击选择结束版本</template>
              </div>
              <div
                v-for="(ver, idx) in versions"
                :key="ver.id"
                class="version-item"
                :class="{
                  'version-item-expanded': expandedVersionId === ver.id && !squashMode,
                  'version-item-squash-range': squashMode && isVersionInSquashRange(ver),
                  'version-item-squash-endpoint': squashMode && isSquashEndpoint(ver),
                }"
                @click="squashMode ? handleSquashVersionClick(ver) : undefined"
              >
                <div class="version-timeline-dot-line">
                  <span class="version-dot" :class="idx === 0 ? 'version-dot-latest' : ''"></span>
                  <span v-if="idx < versions.length - 1" class="version-line"></span>
                </div>
                <div class="version-content" @click="!squashMode ? toggleVersionExpand(ver.id) : undefined">
                  <div class="version-header-row">
                    <span class="version-name" :class="{ 'version-name-strikethrough': squashMode && isVersionInSquashRange(ver) }">{{ ver.name }}</span>
                    <span class="version-time">{{ formatDateTime(ver.created_at) }}</span>
                  </div>
                  <div v-if="ver.description" class="version-desc">{{ ver.description }}</div>
                  <div class="version-meta-row">
                    <code class="version-lsn">LSN {{ ver.lsn }}</code>
                    <span class="version-author">{{ ver.created_by }}</span>
                    <span class="version-ago">{{ timeAgo(ver.created_at) }}</span>
                  </div>
                  <div v-if="expandedVersionId === ver.id && !squashMode" class="version-actions">
                    <button class="btn btn-small btn-text" @click.stop="handleRestoreToVersion(ver)">回滚到此版本</button>
                    <button class="btn btn-small btn-danger-text" @click.stop="handleDeleteVersion(ver.id)">删除版本</button>
                  </div>
                </div>
              </div>
              <!-- Squash confirm bar -->
              <div v-if="squashMode && squashFrom && squashTo" class="squash-confirm-bar">
                <span class="squash-confirm-text">
                  合并 {{ squashFrom.name }} 到 {{ squashTo.name }}，中间 {{ squashVersionsBetween }} 个版本将被删除
                </span>
                <div class="squash-confirm-actions">
                  <button class="btn btn-default btn-small" @click="exitSquashMode">取消</button>
                  <button
                    class="btn btn-primary btn-small"
                    :disabled="squashLoading || squashVersionsBetween < 1"
                    @click="handleConfirmSquash"
                  >{{ squashLoading ? '合并中...' : '确认合并' }}</button>
                </div>
              </div>
            </div>

          </template>
          <div v-else class="version-timeline-placeholder">
            <p>选择左侧分支查看版本历史</p>
          </div>
        </div>
      </div>

      <!-- Branch table (collapsed, for detailed view) -->
      <details class="branch-table-details" open>
        <summary class="branch-table-summary">分支详情表格</summary>
        <div class="section-card" style="margin-top: 12px;">
          <div class="table-toolbar-row">
            <input v-model="branchSearch" class="form-input search-input" placeholder="搜索分支名称" />
            <button class="btn btn-small btn-default" @click="fetchBranches">刷新</button>
          </div>
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
                    <span
                      class="compute-status-label"
                      :class="branch.compute_status === 'RUNNING' ? 'status-running' : branch.compute_status === 'SUSPENDED' ? 'status-suspended' : 'status-idle'"
                    >{{ branch.compute_status === 'RUNNING' ? '运行中' : branch.compute_status === 'SUSPENDED' ? '已挂起' : '未启动' }}</span>
                  </td>
                  <td>{{ branch.parent_branch || '-' }}</td>
                  <td class="mono-text">{{ branch.last_record_lsn || '-' }}</td>
                  <td>{{ formatSize(branch.current_logical_size_bytes) }}</td>
                  <td>
                    <span class="branch-status-tag" :class="'branch-status-' + branch.status">
                      {{ branch.status === 'active' ? '活跃' : branch.status === 'creating' ? '创建中' : branch.status === 'error' ? '异常' : branch.status }}
                    </span>
                  </td>
                  <td>{{ formatDate(branch.created_at) }}</td>
                  <td class="action-cell">
                    <button
                      v-if="!branch.is_default"
                      class="btn btn-small btn-text btn-danger-text"
                      @click="handleDeleteBranch(branch.id)"
                    >删除</button>
                    <span v-if="branch.is_default" class="text-muted">-</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty-state">
              <p v-if="branchesLoading">加载中...</p>
              <p v-else>暂无分支</p>
            </div>
          </div>
        </div>
      </details>

      <CreateBranchDialog
        :visible="showBranchDialog"
        :branches="branches"
        :preselectedParentId="preselectedParentId"
        :dbId="selectedDbId"
        @close="showBranchDialog = false"
        @created="handleBranchCreated"
      />

      <CreateVersionDialog
        :visible="showVersionDialog"
        :dbId="selectedDbId"
        :branchId="selectedBranchId"
        :lastVersionName="versions.length > 0 ? versions[0]!.name : undefined"
        @close="showVersionDialog = false"
        @created="handleVersionCreated"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import { branchApi, type Branch } from '../../api/branch'
import { versionApi, type Version } from '../../api/version'
// diff feature temporarily removed — will return with AI-powered summaries
import CreateBranchDialog from '../database/CreateBranchDialog.vue'
import CreateVersionDialog from '../database/CreateVersionDialog.vue'
// SchemaDiffView temporarily removed
import { formatDate } from '../../utils/format'
import { useToast } from '../../composables/useToast'

const route = useRoute()
const router = useRouter()
const toast = useToast()

// Database list
const databases = ref<Database[]>([])
const dbListLoading = ref(false)
const selectedDbId = ref('')

// Branches
const branches = ref<Branch[]>([])
const branchesLoading = ref(false)
const showBranchDialog = ref(false)
const preselectedParentId = ref('')
const branchSearch = ref('')
const branchPageSize = ref(10)
const branchCurrentPage = ref(1)
const selectedBranchId = ref('')

// Versions
const versions = ref<Version[]>([])
const versionsLoading = ref(false)
const showVersionDialog = ref(false)
const expandedVersionId = ref('')
const versionBranchId = ref('')

// Schema Diff
// diff state removed

// Squash mode
const squashMode = ref(false)
const squashFrom = ref<Version | null>(null)
const squashTo = ref<Version | null>(null)
const squashLoading = ref(false)

const selectedBranchObj = computed(() => {
  return branches.value.find(b => b.id === selectedBranchId.value) || null
})

const filteredBranches = computed(() => {
  const q = branchSearch.value.toLowerCase()
  if (!q) return branches.value
  return branches.value.filter(b => b.name.toLowerCase().includes(q))
})

const pagedBranches = computed(() => {
  const start = (branchCurrentPage.value - 1) * branchPageSize.value
  return filteredBranches.value.slice(start, start + branchPageSize.value)
})

function formatSize(bytes: number | null): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function timeAgo(dateStr: string): string {
  const now = Date.now()
  const then = new Date(dateStr).getTime()
  const diff = now - then
  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return '刚刚'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return formatDate(dateStr)
}

function formatDateTime(dateStr: string): string {
  const d = new Date(dateStr)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// Fetch database list
async function fetchDatabases() {
  dbListLoading.value = true
  try {
    const res = await databaseApi.list()
    databases.value = res.data
    // Auto-select from query param or first
    const qDb = route.query.db as string
    if (qDb && res.data.some(d => d.id === qDb)) {
      selectedDbId.value = qDb
    } else if (res.data.length > 0 && !selectedDbId.value) {
      selectedDbId.value = res.data[0]!.id
    }
  } catch (e) {
    console.error('Failed to load databases', e)
  } finally {
    dbListLoading.value = false
  }
}

// Branches
async function fetchBranches() {
  if (!selectedDbId.value) return
  branchesLoading.value = true
  try {
    const res = await branchApi.list(selectedDbId.value)
    branches.value = res.data
  } catch (e) {
    console.error('Failed to load branches', e)
  } finally {
    branchesLoading.value = false
  }
}

function handleBranchCreated() {
  fetchBranches()
}

async function copyUri(uri: string) {
  try {
    await navigator.clipboard.writeText(uri)
    toast.success('连接串已复制')
  } catch (e) {
    console.error('Failed to copy URI', e)
  }
}

async function handleDeleteBranch(branchId: string) {
  if (!confirm('确定要删除该分支吗？此操作不可恢复。')) return
  try {
    await branchApi.delete(selectedDbId.value, branchId)
    if (selectedBranchId.value === branchId) {
      selectedBranchId.value = ''
      versions.value = []
    }
    await fetchBranches()
  } catch (e) {
    console.error('Failed to delete branch', e)
  }
}

async function handlePromoteBranch(branchId: string) {
  if (!confirm('确定要将此分支提升为默认分支吗？')) return
  try {
    await branchApi.promote(selectedDbId.value, branchId)
    await fetchBranches()
    toast.success('分支已提升为默认分支')
  } catch (e) {
    console.error('Failed to promote branch', e)
    toast.error('提升失败，请重试')
  }
}

// Versions
async function fetchVersions(branchId: string) {
  versionsLoading.value = true
  versionBranchId.value = branchId
  try {
    const res = await versionApi.list(selectedDbId.value, branchId)
    // API returns LSN ascending (oldest first), reverse for display (newest first)
    versions.value = [...res.data].reverse()
  } catch (e) {
    console.error('Failed to load versions', e)
    versions.value = []
  } finally {
    versionsLoading.value = false
  }
}

async function handleDeleteVersion(versionId: string) {
  if (!confirm('确定要删除该版本吗？此操作不可恢复。')) return
  try {
    await versionApi.delete(selectedDbId.value, versionBranchId.value, versionId)
    await fetchVersions(versionBranchId.value)
  } catch (e) {
    console.error('Failed to delete version', e)
  }
}

function handleVersionCreated() {
  if (versionBranchId.value) {
    fetchVersions(versionBranchId.value)
  }
}

async function handleRestoreToVersion(version: Version) {
  const branchName = branches.value.find(b => b.id === selectedBranchId.value)?.name || ''
  if (!confirm(`将 ${branchName} 回滚到 ${version.name}？回滚前的状态将自动保存为备份分支。`)) return
  try {
    await branchApi.restore(selectedDbId.value, selectedBranchId.value, { target_version_id: version.id })
    toast.success('回滚成功')
    await fetchBranches()
    await fetchVersions(selectedBranchId.value)
  } catch (e) {
    console.error('Failed to restore to version', e)
    toast.error('回滚失败，请重试')
  }
}

function selectBranchForVersions(branchId: string) {
  selectedBranchId.value = branchId
  expandedVersionId.value = ''
  exitSquashMode()
  // diff removed
  fetchVersions(branchId)
}

function toggleVersionExpand(versionId: string) {
  expandedVersionId.value = expandedVersionId.value === versionId ? '' : versionId
}

// Squash
function enterSquashMode() {
  squashMode.value = true
  squashFrom.value = null
  squashTo.value = null
}

function exitSquashMode() {
  squashMode.value = false
  squashFrom.value = null
  squashTo.value = null
}

function handleSquashVersionClick(ver: Version) {
  if (!squashMode.value) return
  if (!squashFrom.value) {
    squashFrom.value = ver
  } else if (!squashTo.value && ver.id !== squashFrom.value.id) {
    squashTo.value = ver
  } else {
    squashFrom.value = ver
    squashTo.value = null
  }
}

const squashVersionsBetween = computed(() => {
  if (!squashFrom.value || !squashTo.value) return 0
  const fromIdx = versions.value.findIndex(v => v.id === squashFrom.value!.id)
  const toIdx = versions.value.findIndex(v => v.id === squashTo.value!.id)
  if (fromIdx < 0 || toIdx < 0) return 0
  const minIdx = Math.min(fromIdx, toIdx)
  const maxIdx = Math.max(fromIdx, toIdx)
  return maxIdx - minIdx - 1
})

function isVersionInSquashRange(ver: Version): boolean {
  if (!squashFrom.value || !squashTo.value) return false
  const fromIdx = versions.value.findIndex(v => v.id === squashFrom.value!.id)
  const toIdx = versions.value.findIndex(v => v.id === squashTo.value!.id)
  const verIdx = versions.value.findIndex(v => v.id === ver.id)
  const minIdx = Math.min(fromIdx, toIdx)
  const maxIdx = Math.max(fromIdx, toIdx)
  return verIdx > minIdx && verIdx < maxIdx
}

function isSquashEndpoint(ver: Version): boolean {
  if (!squashFrom.value && !squashTo.value) return false
  return ver.id === squashFrom.value?.id || ver.id === squashTo.value?.id
}

async function handleConfirmSquash() {
  if (!squashFrom.value || !squashTo.value) return
  squashLoading.value = true
  try {
    const fromIdx = versions.value.findIndex(v => v.id === squashFrom.value!.id)
    const toIdx = versions.value.findIndex(v => v.id === squashTo.value!.id)
    const fromId = fromIdx > toIdx ? squashFrom.value.id : squashTo.value.id
    const toId = fromIdx > toIdx ? squashTo.value.id : squashFrom.value.id
    await versionApi.squash(selectedDbId.value, selectedBranchId.value, {
      from_version_id: fromId,
      to_version_id: toId,
    })
    toast.success('版本合并成功')
    exitSquashMode()
    await fetchVersions(selectedBranchId.value)
  } catch (e) {
    console.error('Failed to squash versions', e)
    toast.error('合并失败，请重试')
  } finally {
    squashLoading.value = false
  }
}

// When selectedDbId changes, update URL query and load data
watch(selectedDbId, async (newId) => {
  // Reset state
  branches.value = []
  versions.value = []
  selectedBranchId.value = ''
  expandedVersionId.value = ''
  exitSquashMode()
  // diff removed

  if (newId) {
    // Update URL without navigation
    router.replace({ query: { db: newId } })
    await fetchBranches()
  }
})

watch([branchSearch, branchPageSize], () => { branchCurrentPage.value = 1 })

onMounted(() => {
  fetchDatabases()
})
</script>

<style scoped>
.page-timetravel {
  padding: 4px;
}

.page-title {
  font-size: 18px;
  font-weight: 700;
  color: #191919;
  margin: 0 0 8px;
}

.page-desc {
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
  margin: 0 0 20px;
  padding: 8px 12px;
  background: #f8f9fa;
  border-radius: 6px;
  border-left: 3px solid #d1d5db;
}

/* Database selector */
.db-selector {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.db-selector-label {
  font-size: 14px;
  color: #575d6c;
  font-weight: 500;
  flex-shrink: 0;
}

.db-select {
  width: 280px;
  height: 32px;
  padding: 0 8px;
  font-size: 14px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
}

.db-select:focus {
  border-color: #0073e6;
  outline: none;
}

/* Empty state */
.empty-state-box {
  text-align: center;
  padding: 60px 20px;
  color: #8a8e99;
  font-size: 14px;
  border: 1px solid #ebebeb;
  border-radius: 2px;
  background: #fafbfc;
}

.empty-state-box .btn {
  margin-top: 12px;
}

/* Branch + Version split layout */
.branch-version-layout {
  display: flex;
  gap: 0;
  border: 1px solid #ebebeb;
  border-radius: 2px;
  background: #fff;
  min-height: 400px;
  margin-bottom: 16px;
}

.branch-list-panel {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid #ebebeb;
  display: flex;
  flex-direction: column;
}

.branch-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #ebebeb;
}

.branch-list-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.branch-list-loading {
  padding: 24px 16px;
  text-align: center;
  color: #8a8e99;
  font-size: 13px;
}

.branch-list-items {
  flex: 1;
  overflow-y: auto;
}

.branch-list-item {
  padding: 10px 16px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}

.branch-list-item:hover {
  background: #f5f7fa;
}

.branch-list-item-selected {
  background: #e6f0ff;
  border-left: 3px solid #0073e6;
  padding-left: 13px;
}

.branch-item-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.branch-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot-green { background: #52c41a; }
.dot-yellow { background: #faad14; }
.dot-gray { background: #c2c6cc; }
.dot-blue { background: #0073e6; }
.dot-red { background: #e6393d; }

.compute-status-label {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 2px;
  flex-shrink: 0;
}

.status-running {
  background: #f6ffed;
  color: #52c41a;
}

.status-suspended {
  background: #fffbe6;
  color: #d48806;
}

.status-idle {
  background: #f2f3f5;
  color: #8a8e99;
}

.branch-item-uri {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-left: 14px;
  margin-bottom: 2px;
}

.branch-uri-text {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 10px;
  color: #575d6c;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.btn-copy-uri {
  background: none;
  border: none;
  cursor: pointer;
  color: #8a8e99;
  font-size: 12px;
  padding: 0 2px;
  line-height: 1;
  flex-shrink: 0;
}

.btn-copy-uri:hover {
  color: #0073e6;
}

.branch-item-name {
  font-size: 13px;
  font-weight: 500;
  color: #191919;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-item-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #8a8e99;
  padding-left: 14px;
}

.branch-item-actions {
  display: flex;
  gap: 4px;
  padding-left: 14px;
  margin-top: 6px;
}

.branch-list-footer {
  padding: 12px 16px;
  border-top: 1px solid #ebebeb;
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

.mono-text {
  font-family: monospace;
  font-size: 12px;
}

/* Branch connection URI bar (right panel top) */
.branch-uri-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 20px;
  background: #f0f5ff;
  border-bottom: 1px solid #d6e4ff;
}

.branch-uri-bar-label {
  font-size: 12px;
  color: #575d6c;
  flex-shrink: 0;
  font-weight: 500;
}

.branch-uri-bar-value {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #191919;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  user-select: all;
}

/* Branch status tags */
.branch-status-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 2px;
  font-size: 12px;
}

.branch-status-active {
  background: #f6ffed;
  color: #52c41a;
}

.branch-status-creating {
  background: #e6f7ff;
  color: #1890ff;
}

.branch-status-error {
  background: #fff1f0;
  color: #e6393d;
}

/* Version timeline panel */
.version-timeline-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.version-timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid #ebebeb;
}

.version-timeline-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.version-header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.version-timeline-loading,
.version-timeline-empty,
.version-timeline-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: #8a8e99;
  font-size: 13px;
  padding: 40px 20px;
}

.version-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}

.version-item {
  display: flex;
  gap: 12px;
  position: relative;
}

.version-timeline-dot-line {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 16px;
  flex-shrink: 0;
  padding-top: 4px;
}

.version-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #c2c6cc;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #c2c6cc;
  flex-shrink: 0;
  z-index: 1;
}

.version-dot-latest {
  background: #0073e6;
  box-shadow: 0 0 0 1px #0073e6;
}

.version-line {
  width: 2px;
  flex: 1;
  background: #ebebeb;
  min-height: 20px;
}

.version-content {
  flex: 1;
  padding-bottom: 20px;
  cursor: pointer;
  min-width: 0;
}

.version-content:hover {
  background: #f9fafb;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.version-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.version-name {
  font-size: 14px;
  font-weight: 500;
  color: #191919;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-time {
  font-size: 12px;
  color: #8a8e99;
  flex-shrink: 0;
}

.version-meta-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}

.version-lsn {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #575d6c;
  background: #f2f3f5;
  padding: 1px 6px;
  border-radius: 2px;
}

.version-author {
  font-size: 12px;
  color: #8a8e99;
}

.version-ago {
  font-size: 12px;
  color: #8a8e99;
}

.version-desc {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.4;
  margin-top: 2px;
}

.version-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}

.version-item-expanded .version-content {
  background: #f9fafb;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

/* Squash mode */
.squash-hint {
  padding: 8px 16px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  font-size: 13px;
  color: #d48806;
  margin-bottom: 12px;
}

.version-item-squash-range {
  opacity: 0.45;
}

.version-item-squash-range .version-content {
  background: #fff1f0;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.version-name-strikethrough {
  text-decoration: line-through;
  color: #8a8e99;
}

.version-item-squash-endpoint .version-content {
  background: #e6f7ff;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.squash-confirm-bar {
  margin-top: 16px;
  padding: 12px 16px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.squash-confirm-text {
  font-size: 13px;
  color: #d48806;
}

.squash-confirm-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* Diff overlay */
.diff-overlay {
  margin-top: 16px;
  padding: 16px 20px;
  background: #fafbfc;
  border: 1px solid #ebebeb;
  border-radius: 4px;
}

.diff-overlay-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.diff-overlay-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.diff-overlay-loading {
  text-align: center;
  padding: 40px 24px;
  color: #575d6c;
  font-size: 15px;
}

.diff-loading-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #8a8e99;
}

/* Branch table details toggle */
.branch-table-details {
  margin-top: 16px;
}

.branch-table-summary {
  cursor: pointer;
  font-size: 13px;
  color: #575d6c;
  padding: 8px 0;
  user-select: none;
}

.branch-table-summary:hover {
  color: #0073e6;
}

.table-toolbar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid #ebebeb;
}

.search-input {
  width: 200px;
  height: 30px;
  padding: 0 8px;
  font-size: 13px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
}

.search-input:focus {
  border-color: #0073e6;
  outline: none;
}

.section-card {
  border: 1px solid #ebebeb;
  border-radius: 2px;
  overflow: hidden;
  background: #fff;
}

.row-highlight {
  background-color: #f0f5ff !important;
}

.action-cell {
  display: flex;
  gap: 4px;
  align-items: center;
}

.empty-state {
  text-align: center;
  padding: 24px;
  color: #8a8e99;
  font-size: 13px;
}

.text-muted {
  color: #ccc;
}

@media (max-width: 768px) {
  .branch-version-layout {
    flex-direction: column;
  }
  .branch-list-panel {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #ebebeb;
    max-height: 200px;
  }
  .db-selector {
    flex-direction: column;
    align-items: flex-start;
    gap: 6px;
  }
  .db-select {
    width: 100%;
  }
}
</style>
