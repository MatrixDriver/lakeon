<template>
  <div class="import-detail">
    <div class="detail-header">
      <button class="btn btn-text" @click="$emit('back')">← 返回</button>
      <span class="detail-title">导入任务 {{ task?.id }}</span>
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
      <table class="data-table" v-if="task.tables && task.tables.length > 0">
        <thead>
          <tr>
            <th>表名</th>
            <th>状态</th>
            <th>行数</th>
            <th>错误信息</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in task.tables" :key="t.id">
            <td>{{ t.schema_name }}.{{ t.table_name }}</td>
            <td><span class="status-tag" :class="statusClass(t.status)">{{ statusText(t.status) }}</span></td>
            <td>{{ t.row_count ?? '-' }}</td>
            <td class="error-cell">{{ t.error_message || '-' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="loading-text">加载中...</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { importApi, type ImportTask } from '../../api/import'
import { formatDate } from '../../utils/format'

const props = defineProps<{ dbId: string; taskId: string }>()
const emit = defineEmits<{ back: []; updated: [] }>()

const task = ref<ImportTask | null>(null)
const actionLoading = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

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
.error-cell { font-size: 12px; color: #d4380d; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.loading-text { color: #8a8e99; font-size: 13px; padding: 20px 0; }
.status-tag { display: inline-block; padding: 1px 8px; border-radius: 2px; font-size: 12px; }
.tag-green { background-color: #f6ffed; color: #52c41a; }
.tag-blue { background-color: #e6f7ff; color: #0073e6; }
.tag-red { background-color: #fff1f0; color: #d4380d; }
.tag-orange { background-color: #fff7e6; color: #d46b08; }
.tag-gray { background-color: #f0f0f0; color: #8a8e99; }
</style>
