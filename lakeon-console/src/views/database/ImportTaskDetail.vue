<template>
  <div class="import-detail">
    <div class="detail-header">
      <button class="btn btn-text" @click="$emit('back')">← 返回</button>
      <span class="detail-title">导入任务 {{ task?.id }}</span>
      <button class="btn-refresh" :class="{ spinning: refreshing }" @click="handleRefresh" title="刷新">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M13.65 2.35A7.96 7.96 0 0 0 8 0C3.58 0 0 3.58 0 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 8 14 6 6 0 1 1 8 2c1.66 0 3.14.69 4.22 1.78L9 7h7V0l-2.35 2.35z" fill="currentColor"/></svg>
      </button>
    </div>

    <div v-if="task" class="detail-body">
      <!-- Summary -->
      <div class="detail-summary">
        <div class="summary-grid">
          <div class="summary-item">
            <span class="item-label">源数据库</span>
            <span class="item-value">{{ task.source_host }}:{{ task.source_port }}/{{ task.source_dbname }}</span>
          </div>
          <div class="summary-item">
            <span class="item-label">导入模式</span>
            <span class="item-value">{{ modeLabel }}</span>
          </div>
          <div v-if="task.mode !== 'SYNC'" class="summary-item">
            <span class="item-label">冲突策略</span>
            <span class="item-value">{{ task.conflict_strategy === 'APPEND' ? '追加' : '覆盖' }}</span>
          </div>
          <div class="summary-item">
            <span class="item-label">状态</span>
            <span class="status-tag" :class="statusClass(task.status)">{{ statusText(task.status) }}</span>
          </div>
          <div class="summary-item">
            <span class="item-label">创建时间</span>
            <span class="item-value">{{ formatDate(task.created_at) }}</span>
          </div>
          <div class="summary-item" v-if="task.started_at">
            <span class="item-label">开始时间</span>
            <span class="item-value">{{ formatDate(task.started_at) }}</span>
          </div>
        </div>

        <!-- Actions -->
        <div class="detail-actions">
          <template v-if="isSync">
            <button v-if="['SYNCING','CATCHING_UP'].includes(task.status)" class="btn btn-default btn-small" :disabled="actionLoading" @click="handlePause">暂停同步</button>
            <button v-if="task.status === 'PAUSED'" class="btn btn-primary btn-small" :disabled="actionLoading" @click="handleResume">恢复同步</button>
            <button v-if="['SYNCING','CATCHING_UP','PAUSED'].includes(task.status)" class="btn btn-default btn-small btn-danger-text" :disabled="actionLoading" @click="showStopDialog = true">停止同步</button>
          </template>
          <template v-else>
            <button v-if="task.status === 'RUNNING'" class="btn btn-default btn-small" :disabled="actionLoading" @click="handlePause">暂停</button>
            <button v-if="task.status === 'PAUSED'" class="btn btn-primary btn-small" :disabled="actionLoading" @click="handleResume">恢复</button>
            <button v-if="['RUNNING','PAUSED','PENDING'].includes(task.status)" class="btn btn-default btn-small" :disabled="actionLoading" @click="handleCancel">取消</button>
            <button v-if="['FAILED','PARTIAL'].includes(task.status)" class="btn btn-primary btn-small" :disabled="actionLoading" @click="handleRetry">重试失败</button>
          </template>
        </div>
      </div>

      <!-- Progress (non-sync) -->
      <div v-if="!isSync" class="progress-section">
        <div class="progress-text">进度: {{ task.completed_tables || 0 }} / {{ task.total_tables }} 张表</div>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPct + '%' }"></div>
        </div>
      </div>

      <!-- Sync Status Panel -->
      <div v-if="isSync && ['SYNCING','CATCHING_UP','PAUSED'].includes(task.status)" class="sync-status-panel">
        <h4 class="section-title">同步状态</h4>
        <div class="summary-grid">
          <div class="summary-item">
            <span class="item-label">同步状态</span>
            <span class="status-tag" :class="statusClass(task.status)">{{ statusText(task.status) }}</span>
          </div>
          <div class="summary-item">
            <span class="item-label">复制延迟</span>
            <span class="item-value" :class="{ 'text-warn': task.replay_lag_seconds && task.replay_lag_seconds > 60 }">
              {{ task.replay_lag_seconds != null ? formatLag(task.replay_lag_seconds) : '-' }}
            </span>
          </div>
          <div class="summary-item">
            <span class="item-label">WAL 占用</span>
            <span class="item-value" :class="{ 'text-warn': task.wal_warning }">
              {{ task.wal_retained_bytes != null ? formatBytes(task.wal_retained_bytes) : '-' }}
              <span v-if="task.wal_warning" class="warn-icon" title="WAL 占用过高">!</span>
            </span>
          </div>
          <div class="summary-item">
            <span class="item-label">最后同步</span>
            <span class="item-value">{{ task.last_sync_at ? formatDate(task.last_sync_at) : '-' }}</span>
          </div>
        </div>
      </div>

      <!-- Table list -->
      <div class="section-card" v-if="task.tables && task.tables.length > 0">
        <TableToolbar v-model="tableSearch" placeholder="搜索表名" :loading="refreshing" @refresh="handleRefresh" />
        <table class="data-table" v-if="filteredTables.length > 0">
          <thead>
            <tr>
              <th>表名</th>
              <th>状态</th>
              <th v-if="isSync">同步状态</th>
              <th>{{ isSync ? '已同步行数' : '行数' }}</th>
              <th>错误信息</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in pagedTables" :key="t.id">
              <td>{{ t.schema_name }}.{{ t.table_name }}</td>
              <td><span class="status-tag" :class="statusClass(t.status)">{{ statusText(t.status) }}</span></td>
              <td v-if="isSync">{{ syncStateText(t.sync_state) }}</td>
              <td>{{ (isSync ? t.synced_rows : t.row_count) ?? '-' }}</td>
              <td class="error-cell">{{ t.error_message || '-' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state"><p>无匹配结果</p></div>
        <TableFooter
          v-if="filteredTables.length > 0"
          :total="filteredTables.length"
          v-model:pageSize="tablePageSize"
          v-model:currentPage="tableCurrentPage"
        />
      </div>
    </div>

    <div v-else class="loading-text">加载中...</div>

    <!-- Stop Sync Dialog -->
    <div v-if="showStopDialog" class="dialog-overlay" @click.self="showStopDialog = false">
      <div class="dialog-box" style="width: 420px;">
        <div class="dialog-header">
          <h3>停止同步</h3>
          <button class="dialog-close" @click="showStopDialog = false">&times;</button>
        </div>
        <div class="dialog-body" style="padding: 20px 24px;">
          <div class="form-group">
            <label class="checkbox-item">
              <input type="checkbox" v-model="stopCleanup" />
              清理源库资源（Publication、Replication Slot）
            </label>
          </div>
          <div class="hint-text">
            勾选后将删除源库上的 Publication 和 Replication Slot，释放 WAL 空间。
            不勾选则仅停止订阅。
          </div>
        </div>
        <div class="dialog-footer" style="padding: 12px 24px; border-top: 1px solid #ebebeb; display: flex; justify-content: flex-end; gap: 8px;">
          <button class="btn btn-default" @click="showStopDialog = false">取消</button>
          <button class="btn btn-primary" :disabled="actionLoading" @click="handleStop">
            {{ actionLoading ? '停止中...' : '确认停止' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { importApi, type ImportTask } from '../../api/import'
import { formatDate } from '../../utils/format'
import TableToolbar from '../../components/TableToolbar.vue'
import TableFooter from '../../components/TableFooter.vue'

const props = defineProps<{ dbId: string; taskId: string }>()
const emit = defineEmits<{ back: []; updated: [] }>()

const task = ref<ImportTask | null>(null)
const actionLoading = ref(false)
const refreshing = ref(false)
const showStopDialog = ref(false)
const stopCleanup = ref(true)
let pollTimer: ReturnType<typeof setInterval> | null = null

const isSync = computed(() => task.value?.mode === 'SYNC')
const modeLabel = computed(() => {
  if (!task.value) return ''
  if (task.value.mode === 'FULL') return '整库导入'
  if (task.value.mode === 'SELECTIVE') return '按表选择'
  return '持续同步'
})

const tableSearch = ref('')
const tablePageSize = ref(10)
const tableCurrentPage = ref(1)

const filteredTables = computed(() => {
  if (!task.value?.tables) return []
  const q = tableSearch.value.toLowerCase()
  if (!q) return task.value.tables
  return task.value.tables.filter(t =>
    t.table_name.toLowerCase().includes(q) || t.schema_name.toLowerCase().includes(q)
  )
})

const pagedTables = computed(() => {
  const start = (tableCurrentPage.value - 1) * tablePageSize.value
  return filteredTables.value.slice(start, start + tablePageSize.value)
})

const progressPct = computed(() => {
  if (!task.value || !task.value.total_tables) return 0
  return Math.round((task.value.completed_tables / task.value.total_tables) * 100)
})

function statusClass(status: string): string {
  switch (status) {
    case 'COMPLETED': return 'tag-green'
    case 'RUNNING': case 'SYNCING': return 'tag-blue'
    case 'CATCHING_UP': return 'tag-cyan'
    case 'FAILED': return 'tag-red'
    case 'PARTIAL': return 'tag-orange'
    case 'PAUSED': case 'CANCELLED': return 'tag-gray'
    default: return ''
  }
}

function statusText(status: string): string {
  switch (status) {
    case 'PENDING': return '等待中'
    case 'RUNNING': return '导入中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'PARTIAL': return '部分完成'
    case 'PAUSED': return '已暂停'
    case 'CANCELLED': return '已取消'
    case 'SYNCING': return '同步中'
    case 'CATCHING_UP': return '追赶中'
    default: return status
  }
}

function syncStateText(state: string | null): string {
  if (!state) return '-'
  switch (state) {
    case 'i': return '初始化中'
    case 'd': return '复制数据中'
    case 'f': return '完成初始复制'
    case 's': return '同步中'
    case 'r': return '实时同步'
    default: return state
  }
}

function formatLag(seconds: number): string {
  if (seconds < 1) return '< 1s'
  if (seconds < 60) return Math.round(seconds) + 's'
  if (seconds < 3600) return Math.round(seconds / 60) + 'min'
  return (seconds / 3600).toFixed(1) + 'h'
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1073741824) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}

async function fetchTask() {
  try {
    const res = await importApi.get(props.dbId, props.taskId)
    task.value = res.data
  } catch (e) {
    console.error('Failed to fetch import task', e)
  }
}

function startPoll() {
  stopPoll()
  pollTimer = setInterval(async () => {
    await fetchTask()
    if (task.value && !['RUNNING', 'PENDING', 'SYNCING', 'CATCHING_UP'].includes(task.value.status)) {
      stopPoll()
    }
  }, 5000)
}

function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function handleRefresh() {
  refreshing.value = true
  await fetchTask()
  setTimeout(() => { refreshing.value = false }, 500)
}

async function handlePause() {
  actionLoading.value = true
  try { await importApi.pause(props.dbId, props.taskId); await fetchTask(); emit('updated') } finally { actionLoading.value = false }
}
async function handleResume() {
  actionLoading.value = true
  try { await importApi.resume(props.dbId, props.taskId); await fetchTask(); startPoll(); emit('updated') } finally { actionLoading.value = false }
}
async function handleCancel() {
  if (!confirm('确定要取消此导入任务吗？')) return
  actionLoading.value = true
  try { await importApi.cancel(props.dbId, props.taskId); await fetchTask(); emit('updated') } finally { actionLoading.value = false }
}
async function handleRetry() {
  actionLoading.value = true
  try { await importApi.retry(props.dbId, props.taskId); await fetchTask(); startPoll(); emit('updated') } finally { actionLoading.value = false }
}
async function handleStop() {
  actionLoading.value = true
  try { await importApi.stop(props.dbId, props.taskId, stopCleanup.value); await fetchTask(); showStopDialog.value = false; emit('updated') } finally { actionLoading.value = false }
}

const activeStatuses = ['RUNNING', 'PENDING', 'SYNCING', 'CATCHING_UP']

onMounted(() => {
  fetchTask().then(() => {
    if (task.value && activeStatuses.includes(task.value.status)) startPoll()
  })
})

onUnmounted(() => stopPoll())

watch(() => props.taskId, () => {
  fetchTask().then(() => {
    if (task.value && activeStatuses.includes(task.value.status)) startPoll()
    else stopPoll()
  })
})
</script>

<style scoped>
.import-detail { padding: 0; }
.detail-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.detail-title { font-size: 15px; font-weight: 600; color: #191919; }
.btn-text { background: none; border: none; color: #0073e6; cursor: pointer; padding: 0; font-size: 14px; }
.btn-text:hover { text-decoration: underline; }
.btn-refresh { background: none; border: none; color: #8a8e99; cursor: pointer; padding: 4px; border-radius: 4px; display: inline-flex; align-items: center; transition: color 0.2s; }
.btn-refresh:hover { color: #0073e6; background: #f0f5ff; }
.btn-refresh.spinning svg { animation: spin 0.6s ease; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.detail-summary { border: 1px solid #dfe1e6; border-radius: 2px; padding: 16px; margin-bottom: 16px; }
.summary-grid { display: flex; flex-wrap: wrap; gap: 16px 32px; margin-bottom: 12px; }
.summary-item { display: flex; flex-direction: column; gap: 2px; }
.item-label { font-size: 12px; color: #8a8e99; }
.item-value { font-size: 14px; color: #191919; }
.detail-actions { display: flex; gap: 8px; }
.progress-section { margin-bottom: 16px; }
.progress-text { font-size: 13px; color: #575d6c; margin-bottom: 6px; }
.progress-bar { height: 8px; background: #f0f0f0; border-radius: 4px; overflow: hidden; }
.progress-fill { height: 100%; background: #0073e6; border-radius: 4px; transition: width 0.3s; }
.error-cell { font-size: 12px; color: #d4380d; max-width: 480px; word-break: break-word; white-space: pre-wrap; }
.loading-text { color: #8a8e99; font-size: 13px; padding: 20px 0; }
.status-tag { display: inline-block; padding: 1px 8px; border-radius: 2px; font-size: 12px; }
.tag-green { background-color: #f6ffed; color: #52c41a; }
.tag-blue { background-color: #e6f7ff; color: #0073e6; }
.tag-red { background-color: #fff1f0; color: #d4380d; }
.tag-orange { background-color: #fff7e6; color: #d46b08; }
.tag-gray { background-color: #f0f0f0; color: #8a8e99; }
.section-card { border: 1px solid #ebebeb; border-radius: 2px; overflow: hidden; background: #fff; }
.empty-state { padding: 20px; text-align: center; color: #8a8e99; font-size: 13px; }
.tag-cyan { background-color: #e6fffb; color: #13c2c2; }
.sync-status-panel { border: 1px solid #dfe1e6; border-radius: 2px; padding: 16px; margin-bottom: 16px; }
.section-title { font-size: 14px; font-weight: 600; color: #191919; margin: 0 0 12px 0; }
.text-warn { color: #d46b08; }
.warn-icon { display: inline-block; width: 16px; height: 16px; line-height: 16px; text-align: center; background: #faad14; color: #fff; border-radius: 50%; font-size: 11px; font-weight: bold; margin-left: 4px; }
.btn-danger-text { color: #d4380d; }
.btn-danger-text:hover { background: #fff1f0; border-color: #d4380d; }
.hint-text { color: #8a8e99; font-size: 12px; margin-top: 8px; }
.checkbox-item { display: flex; align-items: center; gap: 6px; font-size: 14px; cursor: pointer; }
</style>
