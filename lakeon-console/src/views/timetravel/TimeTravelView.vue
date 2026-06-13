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
      <div class="branch-version-layout branch-graph-layout">
        <div class="branch-graph-panel">
          <div class="branch-graph-header">
            <div>
              <span class="branch-graph-title">数据库分支图</span>
              <span class="branch-count">{{ branches.length }} 个分支</span>
            </div>
            <div class="branch-graph-actions">
              <span class="legend-item"><span class="branch-status-dot dot-green"></span>运行中</span>
              <span class="legend-item"><span class="branch-status-dot dot-yellow"></span>已挂起</span>
              <span class="legend-item"><span class="branch-status-dot dot-gray"></span>未启动</span>
              <button class="btn btn-small btn-text" @click="fetchBranches">刷新</button>
              <button class="btn btn-primary btn-small" @click="preselectedParentId = selectedBranchId || ''; showBranchDialog = true">新建分支</button>
            </div>
          </div>

          <div v-if="branchesLoading" class="branch-graph-loading">加载中...</div>
          <div v-else-if="branches.length === 0" class="branch-graph-empty">暂无分支</div>
          <div
            v-else
            class="branch-graph-canvas"
          >
            <div
              class="branch-graph-stage"
              :style="{ width: `${branchGraphSize.width}px`, height: `${branchGraphSize.height}px` }"
            >
              <svg class="branch-graph-edges" :viewBox="`0 0 ${branchGraphSize.width} ${branchGraphSize.height}`" preserveAspectRatio="none">
                <path
                  v-for="edge in branchGraphEdges"
                  :key="edge.id"
                  class="branch-graph-edge"
                  :d="edge.path"
                />
              </svg>
              <button
                v-for="node in branchGraphNodes"
                :key="node.branch.id"
                class="branch-node"
                :class="{
                  'branch-node-selected': selectedBranchId === node.branch.id,
                  'branch-node-default': node.branch.is_default,
                }"
                :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${node.width}px`, height: `${node.height}px` }"
                @click="selectBranchForVersions(node.branch.id)"
              >
                <span class="branch-node-name-row">
                  <span class="branch-node-name">{{ node.branch.name }}</span>
                  <span v-if="node.branch.is_default" class="default-tag">默认</span>
                </span>
                <code class="branch-node-id">{{ node.branch.id.slice(0, 12) }}</code>
                <span class="branch-node-meta">
                  <span
                    class="branch-status-dot"
                    :class="node.statusClass"
                  ></span>
                  {{ node.statusLabel }} · {{ formatSize(node.branch.current_logical_size_bytes) }}
                </span>
                <span class="branch-node-lsn">LSN {{ node.branch.last_record_lsn || node.branch.ancestor_lsn || '-' }}</span>
              </button>
            </div>
          </div>
        </div>

        <aside class="branch-detail-panel">
          <template v-if="selectedBranchObj">
            <div class="branch-detail-header">
              <div>
                <span class="branch-detail-kicker">选中分支</span>
                <h3 class="branch-detail-title">{{ selectedBranchObj.name }}</h3>
              </div>
              <span class="compute-status-label" :class="computeStatusClass(selectedBranchObj)">
                {{ computeStatusLabel(selectedBranchObj) }}
              </span>
            </div>

            <section class="branch-detail-section">
              <h4 class="branch-section-title">节点上下文</h4>
              <dl class="branch-detail-grid">
                <div>
                  <dt>父分支</dt>
                  <dd>{{ selectedBranchObj.parent_branch || 'workspace root' }}</dd>
                </div>
                <div>
                  <dt>创建</dt>
                  <dd>{{ formatDateTime(selectedBranchObj.created_at) }}</dd>
                </div>
                <div>
                  <dt>LSN</dt>
                  <dd class="mono-text">{{ selectedBranchObj.last_record_lsn || selectedBranchObj.ancestor_lsn || '-' }}</dd>
                </div>
                <div>
                  <dt>大小</dt>
                  <dd>{{ formatSize(selectedBranchObj.current_logical_size_bytes) }}</dd>
                </div>
              </dl>
              <div v-if="selectedBranchObj.connection_uri" class="branch-uri-card">
                <span class="branch-uri-bar-label">连接串</span>
                <code class="branch-uri-bar-value">{{ selectedBranchObj.connection_uri }}</code>
                <button class="btn btn-small btn-default" @click="copyUri(selectedBranchObj.connection_uri)">复制</button>
              </div>
              <div class="branch-detail-actions" v-if="!selectedBranchObj.is_default">
                <button class="btn btn-small btn-text" @click="handlePromoteBranch(selectedBranchObj.id)">提升为默认</button>
                <button class="btn btn-small btn-danger-text" @click="handleDeleteBranch(selectedBranchObj.id)">删除分支</button>
              </div>
            </section>

            <section class="branch-detail-section branch-version-section">
              <div class="version-timeline-header">
                <span class="version-timeline-title">版本历史</span>
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
            </section>
          </template>
          <div v-else class="version-timeline-placeholder">
            <p>选择左侧分支查看版本历史</p>
          </div>
        </aside>
      </div>

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
import { formatDate, formatSize } from '../../utils/format'
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

const GRAPH_NODE_WIDTH = 230
const GRAPH_NODE_HEIGHT = 104
const GRAPH_COLUMN_GAP = 116
const GRAPH_ROW_GAP = 52
const GRAPH_PADDING_X = 36
const GRAPH_PADDING_Y = 56

interface BranchGraphNode {
  branch: Branch
  x: number
  y: number
  width: number
  height: number
  statusLabel: string
  statusClass: string
}

interface BranchGraphEdge {
  id: string
  path: string
}

function computeStatusLabel(branch: Branch): string {
  if (branch.compute_status === 'RUNNING') return '运行中'
  if (branch.compute_status === 'SUSPENDED') return '已挂起'
  return '未启动'
}

function computeStatusClass(branch: Branch): string {
  if (branch.compute_status === 'RUNNING') return 'status-running'
  if (branch.compute_status === 'SUSPENDED') return 'status-suspended'
  return 'status-idle'
}

function computeStatusDotClass(branch: Branch): string {
  if (branch.compute_status === 'RUNNING') return 'dot-green'
  if (branch.compute_status === 'SUSPENDED') return 'dot-yellow'
  return 'dot-gray'
}

const branchGraphNodes = computed<BranchGraphNode[]>(() => {
  const byId = new Map(branches.value.map(branch => [branch.id, branch]))
  const depthCache = new Map<string, number>()
  const orderedBranches = [...branches.value].sort((a, b) => {
    if (a.is_default !== b.is_default) return a.is_default ? -1 : 1
    return new Date(a.created_at).getTime() - new Date(b.created_at).getTime()
  })

  function getDepth(branch: Branch, seen = new Set<string>()): number {
    if (depthCache.has(branch.id)) return depthCache.get(branch.id)!
    const parentId = branch.parent_branch_id
    if (!parentId || !byId.has(parentId) || seen.has(parentId)) {
      depthCache.set(branch.id, 0)
      return 0
    }
    seen.add(branch.id)
    const depth = getDepth(byId.get(parentId)!, seen) + 1
    depthCache.set(branch.id, depth)
    return depth
  }

  const columns = new Map<number, Branch[]>()
  for (const branch of orderedBranches) {
    const depth = getDepth(branch)
    const column = columns.get(depth) || []
    column.push(branch)
    columns.set(depth, column)
  }

  return orderedBranches.map(branch => {
    const depth = getDepth(branch)
    const column = columns.get(depth) || []
    const row = column.findIndex(item => item.id === branch.id)
    return {
      branch,
      x: GRAPH_PADDING_X + depth * (GRAPH_NODE_WIDTH + GRAPH_COLUMN_GAP),
      y: GRAPH_PADDING_Y + Math.max(row, 0) * (GRAPH_NODE_HEIGHT + GRAPH_ROW_GAP),
      width: GRAPH_NODE_WIDTH,
      height: GRAPH_NODE_HEIGHT,
      statusLabel: computeStatusLabel(branch),
      statusClass: computeStatusDotClass(branch),
    }
  })
})

const branchGraphNodeMap = computed(() => {
  return new Map(branchGraphNodes.value.map(node => [node.branch.id, node]))
})

const branchGraphEdges = computed<BranchGraphEdge[]>(() => {
  return branchGraphNodes.value.flatMap(node => {
    const parentId = node.branch.parent_branch_id
    const parent = parentId ? branchGraphNodeMap.value.get(parentId) : null
    if (!parent) return []
    const startX = parent.x + parent.width
    const startY = parent.y + parent.height / 2
    const endX = node.x
    const endY = node.y + node.height / 2
    const midX = startX + Math.max(44, (endX - startX) / 2)
    return [{
      id: `${parent.branch.id}-${node.branch.id}`,
      path: `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`,
    }]
  })
})

const branchGraphSize = computed(() => {
  const maxX = Math.max(...branchGraphNodes.value.map(node => node.x + node.width), 680)
  const maxY = Math.max(...branchGraphNodes.value.map(node => node.y + node.height), 420)
  return {
    width: maxX + GRAPH_PADDING_X,
    height: maxY + GRAPH_PADDING_Y,
  }
})

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
    const selectedStillExists = res.data.some(branch => branch.id === selectedBranchId.value)
    if (!selectedStillExists && res.data.length > 0) {
      const defaultBranch = res.data.find(branch => branch.is_default) || res.data[0]!
      selectBranchForVersions(defaultBranch.id)
    }
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
  color: #2c3e50;
  margin: 0 0 8px;
}

.page-desc {
  color: var(--c-text-2);
  font-size: 13px;
  line-height: 1.55;
  margin: 0 0 20px;
  padding: 10px 14px;
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
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
  color: #64748b;
  font-weight: 500;
  flex-shrink: 0;
}

.db-select {
  width: 280px;
  height: 32px;
  padding: 0 8px;
  font-size: 14px;
  border: 1px solid #c2c6cc;
  border-radius: 4px;
}

.db-select:focus {
  border-color: #c67d3a;
  outline: none;
}

/* Empty state */
.empty-state-box {
  text-align: center;
  padding: 60px 20px;
  color: #8a8e99;
  font-size: 14px;
  border: 1px solid #ebebeb;
  border-radius: 4px;
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
  border-radius: 4px;
  background: #fff;
  min-height: 400px;
  margin-bottom: 16px;
}

.branch-graph-layout {
  min-height: 640px;
  overflow: hidden;
}

.branch-graph-panel {
  flex: 1;
  min-width: 0;
  border-right: 1px solid var(--c-border-light);
  display: flex;
  flex-direction: column;
}

.branch-graph-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-border-light);
  background: #fff;
}

.branch-graph-title {
  display: block;
  font-size: 16px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1.2;
}

.branch-count {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--c-text-3);
}

.branch-graph-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--c-text-2);
  white-space: nowrap;
}

.branch-graph-loading,
.branch-graph-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 420px;
  color: var(--c-text-3);
  font-size: 13px;
}

.branch-graph-canvas {
  flex: 1;
  overflow: auto;
  background-color: #fff;
  background-image:
    linear-gradient(var(--c-border-light) 1px, transparent 1px),
    linear-gradient(90deg, var(--c-border-light) 1px, transparent 1px);
  background-size: 32px 32px;
}

.branch-graph-stage {
  position: relative;
}

.branch-graph-edges {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.branch-graph-edge {
  fill: none;
  stroke: #b8c3d1;
  stroke-width: 2.5;
  stroke-linecap: round;
}

.branch-node {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 7px;
  padding: 16px 18px;
  border: 1px solid #d8dee7;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 10px 24px rgba(42, 77, 106, 0.08);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.branch-node::before {
  content: '';
  position: absolute;
  left: -1px;
  top: -1px;
  bottom: -1px;
  width: 5px;
  border-radius: 7px 0 0 7px;
  background: var(--c-accent);
}

.branch-node:hover {
  border-color: color-mix(in oklch, var(--c-accent) 45%, #d8dee7);
  box-shadow: 0 14px 30px rgba(42, 77, 106, 0.12);
  transform: translateY(-1px);
}

.branch-node-selected {
  border-color: var(--c-accent);
  box-shadow: 0 0 0 3px color-mix(in oklch, var(--c-accent) 14%, transparent), 0 14px 30px rgba(42, 77, 106, 0.12);
}

.branch-node-default {
  background: color-mix(in oklch, var(--c-accent-light) 34%, #fff);
}

.branch-node-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
}

.branch-node-name {
  color: var(--c-text);
  font-size: 15px;
  font-weight: 700;
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-node-id,
.branch-node-lsn {
  max-width: 100%;
  color: var(--c-text-2);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  line-height: 1.2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-node-meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  color: var(--c-text-2);
  font-size: 12px;
  line-height: 1.2;
  white-space: nowrap;
}

.branch-detail-panel {
  width: 410px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 18px;
  background: var(--c-bg-alt);
  overflow-y: auto;
}

.branch-detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.branch-detail-kicker {
  display: block;
  color: var(--c-text-3);
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 3px;
}

.branch-detail-title {
  margin: 0;
  color: var(--c-text);
  font-size: 18px;
  font-weight: 700;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.branch-detail-section {
  border: 1px solid var(--c-border-light);
  border-radius: 7px;
  background: #fff;
  padding: 16px;
}

.branch-section-title {
  margin: 0 0 14px;
  color: var(--c-text);
  font-size: 15px;
  font-weight: 700;
}

.branch-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 16px;
  margin: 0;
}

.branch-detail-grid dt {
  margin: 0 0 4px;
  color: var(--c-text-3);
  font-size: 12px;
}

.branch-detail-grid dd {
  margin: 0;
  color: var(--c-text);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.branch-uri-card {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  background: var(--c-bg-alt);
}

.branch-detail-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.branch-version-section {
  flex: 1;
  min-height: 300px;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
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
  color: #2c3e50;
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
  background: #f8f5f1;
}

.branch-list-item-selected {
  background: color-mix(in oklch, var(--c-accent) 8%, #fff);
}

.branch-list-item-selected:hover {
  background: color-mix(in oklch, var(--c-accent) 12%, #fff);
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

.dot-green { background: var(--c-success); }
.dot-yellow { background: var(--cs-warn); }
.dot-gray { background: var(--c-text-3); }
.dot-blue { background: var(--c-primary); }
.dot-red { background: var(--cs-severe); }

.compute-status-label {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
}

.status-running {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}

.status-suspended {
  background: color-mix(in oklch, var(--cs-warn) 10%, #fff);
  color: var(--cs-warn);
}

.status-idle {
  background: var(--c-bg-alt);
  color: var(--c-text-3);
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
  color: #64748b;
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
  color: #9a5b25;
}

.branch-item-name {
  font-size: 13px;
  font-weight: 500;
  color: #2c3e50;
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
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  background-color: color-mix(in oklch, var(--c-accent) 12%, #fff);
  color: var(--c-accent-text);
  margin-left: 6px;
}

.active-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  background-color: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
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
  background: var(--c-bg-alt);
  border-bottom: 1px solid var(--c-border-light);
}

.branch-uri-bar-label {
  font-size: 12px;
  color: #64748b;
  flex-shrink: 0;
  font-weight: 500;
}

.branch-uri-bar-value {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #2c3e50;
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
  border-radius: 4px;
  font-size: 12px;
}

.branch-status-active {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}

.branch-status-creating {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}

.branch-status-error {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
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
  color: #2c3e50;
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
  background: #9a5b25;
  box-shadow: 0 0 0 1px #c67d3a;
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
  color: #2c3e50;
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
  color: #64748b;
  background: #f2f3f5;
  padding: 1px 6px;
  border-radius: 4px;
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
  padding: 10px 14px;
  background: color-mix(in oklch, var(--cs-warn) 6%, #fff);
  border: 1px solid color-mix(in oklch, var(--cs-warn) 25%, var(--c-border-light));
  border-radius: 4px;
  font-size: 13px;
  color: var(--cs-warn);
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
  background: #fdf5ed;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.squash-confirm-bar {
  margin-top: 16px;
  padding: 12px 16px;
  background: color-mix(in oklch, var(--cs-warn) 6%, #fff);
  border: 1px solid color-mix(in oklch, var(--cs-warn) 25%, var(--c-border-light));
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.squash-confirm-text {
  font-size: 13px;
  color: var(--cs-warn);
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
  color: #2c3e50;
}

.diff-overlay-loading {
  text-align: center;
  padding: 40px 24px;
  color: #64748b;
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
  color: #64748b;
  padding: 8px 0;
  user-select: none;
}

.branch-table-summary:hover {
  color: #9a5b25;
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
  border-radius: 4px;
}

.search-input:focus {
  border-color: #c67d3a;
  outline: none;
}

.section-card {
  border: 1px solid #ebebeb;
  border-radius: 4px;
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
