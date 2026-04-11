<template>
  <div class="page-container">
    <!-- Back link -->
    <div style="margin-bottom: 16px;">
      <router-link to="/datalake" class="back-link">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
        返回作业列表
      </router-link>
    </div>

    <div v-if="loading" style="text-align: center; padding: 40px; color: #999;">加载中...</div>

    <template v-if="job">
      <!-- Header -->
      <div class="detail-header">
        <div>
          <h1 class="page-title" style="margin-bottom: 4px;">{{ job.name }}</h1>
          <span class="job-id-mono">{{ job.id }}</span>
        </div>
        <div class="detail-header-actions">
          <button
            v-if="canCancel"
            class="btn btn-default"
            style="color: #c6333a; border-color: #c6333a;"
            @click="handleCancel"
          >取消作业</button>
          <button
            v-if="canResubmit"
            class="btn btn-default"
            @click="handleResubmit"
          >重跑作业</button>
          <button
            v-if="canResubmit"
            class="btn btn-primary"
            @click="handleEditAndRerun"
          >编辑并重跑</button>
        </div>
      </div>

      <!-- Info bar (compact) -->
      <div class="info-bar">
        <div class="info-item">
          <span class="status-dot" :class="'dot-' + statusColor(job.status)"></span>
          {{ statusText(job.status) }}
        </div>
        <div class="info-item">
          <span class="type-tag" :class="'type-tag-' + job.type.toLowerCase()">{{ typeLabel(job.type) }}</span>
        </div>
        <div class="info-item"><span class="info-label-inline">创建</span> {{ formatTime(job.createdAt) }}</div>
        <div class="info-item"><span class="info-label-inline">时长</span> {{ duration }}</div>
        <div class="info-item" v-if="job.startedAt"><span class="info-label-inline">开始</span> {{ formatTime(job.startedAt) }}</div>
        <div class="info-item" v-if="job.finishedAt"><span class="info-label-inline">结束</span> {{ formatTime(job.finishedAt) }}</div>
        <div class="info-item" v-if="job.coreHours"><span class="info-label-inline">CPU</span> {{ job.coreHours }} core·h</div>
        <div class="info-item" v-if="job.gpuHours"><span class="info-label-inline">GPU</span> {{ job.gpuHours }} GPU·h</div>
      </div>

      <!-- Error message -->
      <div v-if="job.errorMessage" class="error-banner">
        <strong>错误信息：</strong>{{ job.errorMessage }}
      </div>

      <!-- K8s info -->
      <div v-if="job.k8sJobName || job.rayJobName || job.cciNamespace" class="section">
        <h3 class="section-title">运行信息</h3>
        <div class="kv-list">
          <div v-if="job.cciNamespace" class="kv-row">
            <span class="kv-key">命名空间</span>
            <span class="kv-val mono">{{ job.cciNamespace }}</span>
          </div>
          <div v-if="job.k8sJobName" class="kv-row">
            <span class="kv-key">K8s Job</span>
            <span class="kv-val mono">{{ job.k8sJobName }}</span>
          </div>
          <div v-if="job.rayJobName" class="kv-row">
            <span class="kv-key">Ray Job</span>
            <span class="kv-val mono">{{ job.rayJobName }}</span>
          </div>
          <div v-if="job.baseImage" class="kv-row">
            <span class="kv-key">镜像</span>
            <span class="kv-val mono">{{ job.baseImage }}</span>
          </div>
        </div>
      </div>

      <!-- Logs section -->
      <div class="section">
        <div class="section-header">
          <h3 class="section-title">运行日志</h3>
          <div style="display: flex; gap: 8px;">
            <button v-if="!streaming && canStream" class="btn btn-small btn-default" @click="startStream">
              实时日志
            </button>
            <button v-if="streaming" class="btn btn-small btn-default" @click="stopStream" style="color:#c6333a;">
              停止
            </button>
            <label class="auto-scroll-label">
              <input type="checkbox" v-model="autoScroll" />
              自动滚动
            </label>
          </div>
        </div>
        <div ref="logContainer" class="log-container">
          <div v-if="logLines.length === 0" style="color: #999; padding: 16px;">
            {{ streaming ? '等待日志输出...' : (job && TERMINAL.includes(job.status) ? '暂无日志记录' : '点击"实时日志"查看运行输出') }}
          </div>
          <pre v-else class="log-content">{{ logLines.join('\n') }}</pre>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDatalakeJob, cancelDatalakeJob, resubmitDatalakeJob, type DatalakeJob, type DatalakeJobStatus, type DatalakeJobType } from '../../api/datalake'

const route = useRoute()
const router = useRouter()
const jobId = route.params.jobId as string

const job = ref<DatalakeJob | null>(null)
const loading = ref(false)
const logLines = ref<string[]>([])
const streaming = ref(false)
const autoScroll = ref(true)
const logContainer = ref<HTMLElement | null>(null)

let abortController: AbortController | null = null
let pollTimer: ReturnType<typeof setInterval> | null = null

const TERMINAL: DatalakeJobStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED']

const canCancel = computed(() => job.value && !TERMINAL.includes(job.value.status))
const canResubmit = computed(() => job.value && TERMINAL.includes(job.value.status))
const canStream = computed(() => job.value && !TERMINAL.includes(job.value.status))

const duration = computed(() => {
  if (!job.value) return '-'
  const start = job.value.startedAt ? new Date(job.value.startedAt).getTime()
    : (job.value.createdAt ? new Date(job.value.createdAt).getTime() : null)
  const end = job.value.finishedAt ? new Date(job.value.finishedAt).getTime() : (start ? Date.now() : null)
  if (!start || !end) return '-'
  const sec = Math.floor((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
})

function statusColor(status: DatalakeJobStatus) {
  const map: Record<string, string> = {
    PENDING: 'gray', STARTING: 'blue', RUNNING: 'blue',
    SUCCEEDED: 'green', FAILED: 'red', CANCELLED: 'gray',
  }
  return map[status] || 'gray'
}

function statusText(status: DatalakeJobStatus) {
  const map: Record<string, string> = {
    PENDING: '等待中', STARTING: '启动中', RUNNING: '运行中',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return map[status] || status
}

function typeLabel(type: DatalakeJobType) {
  return { PYTHON: 'Python', RAY: 'Ray', FINETUNE: '微调' }[type] || type
}

function formatTime(t: string | null) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function loadJob() {
  if (!job.value) loading.value = true  // only show loading on first load
  try {
    const res = await getDatalakeJob(jobId)
    job.value = res.data
  } catch (e: any) {
    if (e.response?.status === 404) {
      alert('作业不存在')
      router.push('/datalake')
    }
  } finally {
    loading.value = false
  }
}

async function handleCancel() {
  if (!confirm(`确认取消作业"${job.value?.name}"？`)) return
  try {
    await cancelDatalakeJob(jobId)
    await loadJob()
  } catch (e: any) {
    alert('取消失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function handleResubmit() {
  if (!confirm(`确认重跑作业"${job.value?.name}"？将创建新作业。`)) return
  try {
    const { data } = await resubmitDatalakeJob(jobId)
    router.push(`/datalake/jobs/${data.id}`)
  } catch (e: any) {
    alert('重跑失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

function handleEditAndRerun() {
  if (!job.value) return
  try {
    const spec = JSON.parse(job.value.spec || '{}')
    // Navigate to creation page with spec pre-filled via query params
    router.push({
      path: '/datalake/jobs/new',
      query: { from: job.value.id }
    })
    // Store spec in sessionStorage for the creation page to pick up
    sessionStorage.setItem('datalake_job_prefill', JSON.stringify(spec))
  } catch {
    router.push('/datalake/jobs/new')
  }
}

async function startStream() {
  if (streaming.value) return
  streaming.value = true
  logLines.value = []

  const apiKey = localStorage.getItem('lakeon_api_key') || ''
  const url = `https://api.dbay.cloud:8443/api/v1/datalake/jobs/${jobId}/logs`
  abortController = new AbortController()

  try {
    const response = await fetch(url, {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Accept': 'text/event-stream',
      },
      signal: abortController.signal,
    })

    if (!response.ok || !response.body) {
      streaming.value = false
      logLines.value.push(`[错误] 无法连接日志流 (HTTP ${response.status})`)
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trimStart()
          if (data) logLines.value.push(data)
        }
      }

      if (autoScroll.value) {
        await nextTick()
        scrollToBottom()
      }
    }
  } catch (e: any) {
    if (e.name !== 'AbortError') {
      logLines.value.push(`[日志流结束]`)
    }
  } finally {
    streaming.value = false
    // Refresh job status after stream ends
    loadJob()
  }
}

function stopStream() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  streaming.value = false
}

function scrollToBottom() {
  if (logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

// Auto-start log streaming: always open by default
let logRetryCount = 0
async function autoLoadLogs() {
  if (!job.value || streaming.value) return
  // Skip if we already have real log content
  if (logLines.value.length > 0 && !logLines.value.every(l => l.startsWith('['))) return

  if (TERMINAL.includes(job.value.status)) {
    // Terminal job: load persisted logs from OBS via SSE
    if (job.value.logObsPath) {
      logLines.value = []
      await startStream()
      // If no real logs arrived (OBS upload may still be in progress), retry after delay
      if (logLines.value.length === 0 && logRetryCount < 3) {
        logRetryCount++
        setTimeout(() => { loadJob().then(() => autoLoadLogs()) }, 3000)
      }
    }
  } else if (job.value.status === 'RUNNING') {
    logLines.value = []
    startStream()
  }
  // PENDING/STARTING: wait for next poll
}

// Auto-refresh job status while not terminal
onMounted(() => {
  loadJob().then(() => autoLoadLogs())
  pollTimer = setInterval(() => {
    if (!job.value) return
    if (TERMINAL.includes(job.value.status)) {
      // Terminal: one final attempt to load logs, then stop polling
      autoLoadLogs()
      if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
      return
    }
    loadJob().then(() => autoLoadLogs())
  }, 5000) // 5s for faster response
})

onUnmounted(() => {
  stopStream()
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #666;
  text-decoration: none;
  font-size: 14px;
}

.back-link:hover {
  color: #9a5b25;
}

.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 24px;
}

.detail-header-actions {
  display: flex;
  gap: 8px;
}

.job-id-mono {
  font-family: monospace;
  font-size: 12px;
  color: #bbb;
}

.info-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 16px;
  padding: 10px 16px;
  background: #fafbfc;
  border: 1px solid #eee;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 13px;
}
.info-item {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.info-label-inline {
  color: #999;
  font-size: 12px;
}

.info-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}

.info-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.error-banner {
  padding: 12px 16px;
  background: color-mix(in oklch, var(--cs-severe) 8%, #fff);
  border: 1px solid #ffccc7;
  border-radius: 6px;
  color: #c6333a;
  font-size: 13px;
  margin-bottom: 24px;
  word-break: break-all;
}

.section {
  margin-bottom: 24px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #2c3e50;
  margin: 0;
}

.kv-list {
  background: #fafbfc;
  border: 1px solid #eee;
  border-radius: 6px;
  overflow: hidden;
}

.kv-row {
  display: flex;
  padding: 8px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.kv-row:last-child {
  border-bottom: none;
}

.kv-key {
  width: 100px;
  flex-shrink: 0;
  color: #999;
  font-size: 13px;
}

.kv-val {
  color: #333;
  font-size: 13px;
  word-break: break-all;
}

.mono {
  font-family: monospace;
}

.log-container {
  background: #1e1e1e;
  border-radius: 6px;
  min-height: 200px;
  max-height: 500px;
  overflow-y: auto;
  padding: 0;
}

.log-content {
  color: #d4d4d4;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  margin: 0;
  padding: 16px;
  white-space: pre-wrap;
  word-break: break-all;
}

.auto-scroll-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #666;
  cursor: pointer;
  user-select: none;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.dot-green { background-color: #386b47; }
.dot-blue { background-color: #2a4d6a; }
.dot-red { background-color: #c6333a; }
.dot-gray { background-color: #d9d9d9; }

.type-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}

.type-tag-python {
  background: color-mix(in oklch, var(--cs-warn) 10%, #fff);
  color: #9a5b25;
  border: 1px solid #ffe58f;
}

.type-tag-ray {
  background: #f0f7ff;
  color: #9a5b25;
  border: 1px solid #b3d4f7;
}

.type-tag-finetune {
  background: color-mix(in oklch, var(--c-accent) 10%, #fff);
  color: #9a5b25;
  border: 1px solid #d3adf7;
}
</style>
