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
            <span class="item-value">{{ task.mode === 'FULL' ? '整库' : '按表' }}</span>
          </div>
          <div class="summary-item">
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
          <button v-if="task.status === 'RUNNING'" class="btn btn-default btn-small" :disabled="actionLoading" @click="handlePause">暂停</button>
          <button v-if="task.status === 'PAUSED'" class="btn btn-primary btn-small" :disabled="actionLoading" @click="handleResume">恢复</button>
          <button v-if="['RUNNING','PAUSED','PENDING'].includes(task.status)" class="btn btn-default btn-small" :disabled="actionLoading" @click="handleCancel">取消</button>
          <button v-if="['FAILED','PARTIAL'].includes(task.status)" class="btn btn-primary btn-small" :disabled="actionLoading" @click="handleRetry">重试失败</button>
        </div>
      </div>

      <!-- Progress -->
      <div class="progress-section">
        <div class="progress-text">进度: {{ task.completed_tables || 0 }} / {{ task.total_tables }} 张表</div>
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: progressPct + '%' }"></div>
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
              <th>行数</th>
              <th>错误信息</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in pagedTables" :key="t.id">
              <td>{{ t.schema_name }}.{{ t.table_name }}</td>
              <td><span class="status-tag" :class="statusClass(t.status)">{{ statusText(t.status) }}</span></td>
              <td>{{ t.row_count ?? '-' }}</td>
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
let pollTimer: ReturnType<typeof setInterval> | null = null

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
    case 'RUNNING': return 'tag-blue'
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
    default: return status
  }
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
    if (task.value && !['RUNNING', 'PENDING'].includes(task.value.status)) {
      stopPoll()
    }
  }, 3000)
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

onMounted(() => {
  fetchTask().then(() => {
    if (task.value && ['RUNNING', 'PENDING'].includes(task.value.status)) startPoll()
  })
})

onUnmounted(() => stopPoll())

watch(() => props.taskId, () => {
  fetchTask().then(() => {
    if (task.value && ['RUNNING', 'PENDING'].includes(task.value.status)) startPoll()
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
</style>
