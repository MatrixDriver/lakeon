<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">作业管理</h1>
      <div class="page-header-actions">
        <ViewToggle v-model="viewMode" />
        <button class="btn btn-primary" @click="router.push('/datalake/jobs/new')">提交作业</button>
      </div>
    </div>

    <!-- Status filter tabs -->
    <div class="status-tabs">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        class="status-tab"
        :class="{ active: statusFilter === tab.value }"
        @click="statusFilter = tab.value"
      >
        {{ tab.label }}
        <span v-if="tab.value && statusCounts[tab.value]" class="tab-count">{{ statusCounts[tab.value] }}</span>
      </button>
    </div>

    <!-- Job list -->

    <!-- Card view -->
    <div v-if="viewMode === 'card' && searchedJobs.length > 0" class="card-grid">
      <ResourceCard
        v-for="job in searchedJobs"
        :key="job.id"
        :name="job.name"
        :status="job.status"
        :statusLabel="statusText(job.status)"
        :meta="[typeLabel(job.type), duration(job), formatTime(job.createdAt)]"
        @click="router.push(`/datalake/jobs/${job.id}`)"
      />
      <div class="card-create" @click="router.push('/datalake/jobs/new')">
        + 提交作业
      </div>
    </div>

    <!-- Table view -->
    <div v-if="viewMode === 'table' && searchedJobs.length > 0" class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>作业名称</th>
            <th>类型</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>运行时长</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="job in searchedJobs" :key="job.id">
            <td>
              <router-link :to="`/datalake/jobs/${job.id}`" class="job-name-link">
                {{ job.name }}
              </router-link>
              <div class="job-id-hint">{{ job.id }}</div>
            </td>
            <td>
              <span class="type-tag" :class="'type-tag-' + job.type.toLowerCase()">{{ typeLabel(job.type) }}</span>
            </td>
            <td>
              <span class="status-dot" :class="'dot-' + statusColor(job.status)"></span>
              {{ statusText(job.status) }}
            </td>
            <td style="color: #999;">{{ formatTime(job.createdAt) }}</td>
            <td style="color: #666;">{{ duration(job) }}</td>
            <td>
              <button
                v-if="canCancel(job)"
                class="btn btn-text btn-small btn-danger-text"
                @click="handleCancel(job)"
              >取消</button>
              <router-link
                :to="`/datalake/jobs/${job.id}`"
                class="btn btn-text btn-small btn-accent-text"
              >详情</router-link>
              <button
                v-if="job.status === 'FAILED' || job.status === 'CANCELLED' || job.status === 'SUCCEEDED'"
                class="btn btn-text btn-small btn-accent-text"
                @click="handleResubmit(job)"
              >重跑</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-if="searchedJobs.length === 0 && !loading" class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/>
        <line x1="8" y1="21" x2="16" y2="21"/>
        <line x1="12" y1="17" x2="12" y2="21"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">
        {{ statusFilter ? '没有符合条件的作业' : '还没有数据湖作业' }}
      </p>
      <p style="color: #999; font-size: 13px;">提交 Python、Ray 或微调作业，在 Serverless 容器中运行</p>
    </div>

    <!-- Loading -->
    <div v-if="loading" style="text-align: center; padding: 40px; color: #999;">加载中...</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  listDatalakeJobs,
  cancelDatalakeJob,
  resubmitDatalakeJob,
  type DatalakeJob,
  type DatalakeJobStatus,
  type DatalakeJobType,
} from '../../api/datalake'
import ViewToggle from '../../components/ViewToggle.vue'
import ResourceCard from '../../components/ResourceCard.vue'

const router = useRouter()

const viewMode = ref<'card' | 'table'>('card')
const jobs = ref<DatalakeJob[]>([])
const loading = ref(false)
const jobSearch = ref('')
const statusFilter = ref<string>('')

const statusTabs: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'PENDING', label: '等待中' },
  { value: 'SUCCEEDED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
]

const filteredJobs = computed(() => {
  if (!statusFilter.value) return jobs.value
  return jobs.value.filter(j => j.status === statusFilter.value)
})

const searchedJobs = computed(() => {
  if (!jobSearch.value) return filteredJobs.value
  const q = jobSearch.value.toLowerCase()
  return filteredJobs.value.filter(j => j.name.toLowerCase().includes(q))
})

const statusCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const j of jobs.value) {
    counts[j.status] = (counts[j.status] || 0) + 1
  }
  return counts
})

const TERMINAL_STATUSES: DatalakeJobStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED']

function canCancel(job: DatalakeJob) {
  return !TERMINAL_STATUSES.includes(job.status)
}

function statusColor(status: DatalakeJobStatus) {
  const map: Record<string, string> = {
    PENDING: 'gray',
    STARTING: 'blue',
    RUNNING: 'blue',
    SUCCEEDED: 'green',
    FAILED: 'red',
    CANCELLED: 'gray',
  }
  return map[status] || 'gray'
}

function statusText(status: DatalakeJobStatus) {
  const map: Record<string, string> = {
    PENDING: '等待中',
    STARTING: '启动中',
    RUNNING: '运行中',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return map[status] || status
}

function typeLabel(type: DatalakeJobType) {
  const map: Record<string, string> = { PYTHON: 'Python', RAY: 'Ray', FINETUNE: '微调' }
  return map[type] || type
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function duration(job: DatalakeJob) {
  const start = job.startedAt ? new Date(job.startedAt).getTime() : null
  const end = job.finishedAt ? new Date(job.finishedAt).getTime() : (start ? Date.now() : null)
  if (!start || !end) return '-'
  const sec = Math.floor((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

async function loadJobs() {
  loading.value = true
  try {
    const res = await listDatalakeJobs()
    jobs.value = res.data
  } catch (e: any) {
    console.error('Failed to load datalake jobs:', e)
  } finally {
    loading.value = false
  }
}

async function handleCancel(job: DatalakeJob) {
  if (!confirm(`确认取消作业"${job.name}"？`)) return
  try {
    await cancelDatalakeJob(job.id)
    await loadJobs()
  } catch (e: any) {
    alert('取消失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function handleResubmit(job: DatalakeJob) {
  if (!confirm(`确认重跑作业"${job.name}"？将创建新作业。`)) return
  try {
    await resubmitDatalakeJob(job.id)
    await loadJobs()
  } catch (e: any) {
    alert('重跑失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  loadJobs()
  // Auto-refresh every 10s if there are active jobs
  pollTimer = setInterval(() => {
    const hasActive = jobs.value.some(j => !TERMINAL_STATUSES.includes(j.status))
    if (hasActive) loadJobs()
  }, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.status-tabs {
  display: flex;
  gap: 4px;
  margin-top: 16px;
  border-bottom: 1px solid #e5e5e5;
}

.status-tab {
  padding: 8px 16px;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  transition: all 0.15s;
}

.status-tab:hover {
  color: #333;
}

.status-tab.active {
  color: #9a5b25;
  border-bottom-color: #c67d3a;
  font-weight: 500;
}

.tab-count {
  display: inline-block;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  background: #f0f0f0;
  font-size: 11px;
  margin-left: 4px;
  padding: 0 5px;
}

.status-tab.active .tab-count {
  background: #e8f3ff;
  color: #9a5b25;
}

.job-name-link {
  color: #9a5b25;
  text-decoration: none;
  font-weight: 500;
}

.job-name-link:hover {
  text-decoration: underline;
}

.job-id-hint {
  font-size: 11px;
  color: #bbb;
  margin-top: 2px;
  font-family: monospace;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.dot-green { background-color: #52c41a; }
.dot-blue { background-color: #1890ff; }
.dot-red { background-color: #e6393d; }
.dot-gray { background-color: #d9d9d9; }

.type-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}

.type-tag-python {
  background: #fff7e6;
  color: #d48806;
  border: 1px solid #ffe58f;
}

.type-tag-ray {
  background: #f0f7ff;
  color: #9a5b25;
  border: 1px solid #b3d4f7;
}

.type-tag-finetune {
  background: #f9f0ff;
  color: #722ed1;
  border: 1px solid #d3adf7;
}

.type-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  flex: 1;
  justify-content: center;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
  user-select: none;
}

.type-radio:hover {
  border-color: #c67d3a;
  color: #9a5b25;
}

.type-radio.selected {
  border-color: #c67d3a;
  background: #e8f3ff;
  color: #9a5b25;
  font-weight: 500;
}

.form-row {
  display: flex;
  gap: 12px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 16px;
}
.card-create {
  border: 1px dashed #d5d0ca;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.card-create:hover { border-color: #94a3b8; }
@media (max-width: 768px) {
  .card-grid { grid-template-columns: 1fr; }
}
</style>
