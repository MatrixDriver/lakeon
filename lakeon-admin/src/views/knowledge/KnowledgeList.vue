<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">知识库管理</h1>
    </div>

    <!-- Tabs -->
    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'bases' }" @click="activeTab = 'bases'">知识库列表</div>
      <div class="tab-item" :class="{ active: activeTab === 'tasks' }" @click="activeTab = 'tasks'; loadTasks()">写入任务队列</div>
      <div class="tab-item" :class="{ active: activeTab === 'pipeline' }" @click="activeTab = 'pipeline'; loadPipeline()">Pipeline Monitor</div>
    </div>

    <!-- KB List Tab -->
    <template v-if="activeTab === 'bases'">
      <!-- Stats Cards -->
      <div class="stats-row" v-if="stats">
        <div class="stat-card">
          <div class="stat-value">{{ stats.kb_count }}</div>
          <div class="stat-label">知识库</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ stats.document_count }}</div>
          <div class="stat-label">文档总数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #1890ff;">{{ stats.processing_count }}</div>
          <div class="stat-label">处理中</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #52c41a;">{{ stats.ready_count }}</div>
          <div class="stat-label">已就绪</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #e53e3e;">{{ stats.failed_count }}</div>
          <div class="stat-label">失败</div>
        </div>
      </div>
      <div class="action-toolbar">
        <select class="form-select" v-model="statusFilter" style="width: 140px;" @change="loadKbs">
          <option value="">全部状态</option>
          <option value="READY">READY</option>
          <option value="CREATING">CREATING</option>
          <option value="ERROR">ERROR</option>
        </select>
        <select class="form-select" v-model="typeFilter" style="width: 140px;" @change="loadKbs">
          <option value="">全部类型</option>
          <option value="DOCUMENT">DOCUMENT</option>
          <option value="TABLE">TABLE</option>
        </select>
        <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="tenantFilter" style="width: 240px;" @keyup.enter="loadKbs" />
        <button class="btn btn-default btn-small" @click="loadKbs">筛选</button>
      </div>

      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 30px;"></th>
              <th>名称</th>
              <th>租户</th>
              <th>类型</th>
              <th>状态</th>
              <th>文档数</th>
              <th>Embedding</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="kb in kbs" :key="kb.id">
              <tr>
                <td>
                  <button class="btn-icon-small" @click="toggleExpand(kb.id)">
                    {{ expandedKbs.has(kb.id) ? '▼' : '▶' }}
                  </button>
                </td>
                <td><strong>{{ kb.name }}</strong><br><span style="font-size: 11px; color: #999;">{{ kb.id }}</span></td>
                <td>
                  {{ tenantStore.name(kb.tenant_id) }}
                  <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ kb.tenant_id }}</span>
                </td>
                <td>{{ kb.type }}</td>
                <td>
                  <span class="status-dot" :class="kbStatusClass(kb.status)"></span>
                  {{ kb.status }}
                </td>
                <td>{{ kb.document_count ?? 0 }}</td>
                <td style="font-size: 12px;">{{ kb.embedding_model || 'BGE-M3' }}</td>
                <td>{{ formatDate(kb.created_at) }}</td>
                <td>
                  <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteKb(kb)">删除</button>
                </td>
              </tr>
              <!-- Expanded: document list -->
              <tr v-if="expandedKbs.has(kb.id)" class="expanded-row">
                <td colspan="9" style="padding: 0;">
                  <div style="background: #f9fafb; padding: 12px 16px 12px 40px;">
                    <div v-if="!kbDocs[kb.id]" style="color: #999; font-size: 13px;">加载中...</div>
                    <div v-else-if="kbDocs[kb.id]?.length === 0" style="color: #999; font-size: 13px;">暂无文档</div>
                    <table v-else class="data-table" style="margin: 0;">
                      <thead>
                        <tr>
                          <th>文件名</th>
                          <th>格式</th>
                          <th>Chunks</th>
                          <th>状态</th>
                          <th>错误</th>
                          <th>操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="doc in kbDocs[kb.id]" :key="doc.id">
                          <td>{{ doc.filename }}<br><span style="font-size: 11px; color: #999;">{{ doc.id }}</span></td>
                          <td>{{ doc.format }}</td>
                          <td>{{ doc.chunks_count ?? '-' }}</td>
                          <td>
                            <span class="status-dot" :class="docStatusClass(doc.status)"></span>
                            {{ doc.status }}
                          </td>
                          <td class="error-cell" style="max-width: 200px;">{{ doc.error || '-' }}</td>
                          <td>
                            <button v-if="doc.status === 'FAILED'" class="btn btn-text btn-small" style="color: #1890ff;" @click="reprocessDoc(doc)">重处理</button>
                            <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteDoc(doc, kb.id)">删除</button>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="kbs.length === 0">
              <td colspan="9" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Write Tasks Tab -->
    <template v-if="activeTab === 'tasks'">
      <div class="action-toolbar">
        <select class="form-select" v-model="taskStatusFilter" style="width: 140px;" @change="loadTasks">
          <option value="">全部状态</option>
          <option value="QUEUED">QUEUED</option>
          <option value="RUNNING">RUNNING</option>
          <option value="SUCCEEDED">SUCCEEDED</option>
          <option value="FAILED">FAILED</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadTasks">刷新</button>
      </div>

      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>任务ID</th>
              <th>类型</th>
              <th>状态</th>
              <th>租户ID</th>
              <th>知识库ID</th>
              <th>Job ID</th>
              <th>错误</th>
              <th>创建时间</th>
              <th>完成时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id">
              <td style="font-family: monospace; font-size: 12px;">{{ task.id }}</td>
              <td>{{ task.type }}</td>
              <td>
                <span class="status-dot" :class="taskStatusClass(task.status)"></span>
                {{ task.status }}
              </td>
              <td style="font-family: monospace; font-size: 12px;">{{ task.tenant_id }}</td>
              <td style="font-family: monospace; font-size: 12px;">{{ task.kb_id }}</td>
              <td style="font-family: monospace; font-size: 12px;">{{ task.job_id || '-' }}</td>
              <td class="error-cell" style="max-width: 200px;">{{ task.error || '-' }}</td>
              <td>{{ formatDate(task.created_at) }}</td>
              <td>{{ formatDate(task.completed_at) }}</td>
            </tr>
            <tr v-if="tasks.length === 0">
              <td colspan="9" class="empty-state">暂无任务</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Pipeline Monitor Tab -->
    <template v-if="activeTab === 'pipeline'">
      <!-- Pipeline Stats Cards -->
      <div class="stats-row" v-if="plStats">
        <div class="stat-card">
          <div class="stat-value">{{ plStats.total ?? 0 }}</div>
          <div class="stat-label">总任务数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #52c41a;">{{ plStats.success_rate != null ? (plStats.success_rate * 100).toFixed(1) + '%' : '-' }}</div>
          <div class="stat-label">成功率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #faad14;">{{ plStats.retry_rate != null ? (plStats.retry_rate * 100).toFixed(1) + '%' : '-' }}</div>
          <div class="stat-label">重试率</div>
        </div>
        <template v-if="plStats.avg_stage_durations_ms">
          <div class="stat-card" v-for="(ms, stage) in plStats.avg_stage_durations_ms" :key="stage">
            <div class="stat-value" style="font-size: 20px;">{{ formatDuration(ms) }}</div>
            <div class="stat-label">{{ stageLabels[stage as string] || stage }}</div>
          </div>
        </template>
      </div>

      <!-- Filters -->
      <div class="action-toolbar">
        <select class="form-select" v-model="plFilter.status" style="width: 130px;" @change="loadPipelineTasks">
          <option value="">全部状态</option>
          <option value="QUEUED">排队中</option>
          <option value="RUNNING">运行中</option>
          <option value="SUCCEEDED">成功</option>
          <option value="FAILED">失败</option>
        </select>
        <input type="date" class="form-select" v-model="plFilter.dateFrom" style="width: 150px;" @change="loadPipeline" />
        <input type="date" class="form-select" v-model="plFilter.dateTo" style="width: 150px;" @change="loadPipeline" />
        <button class="btn btn-default btn-small" @click="loadPipeline">刷新</button>
      </div>

      <!-- Pipeline Task Table -->
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 30px;"></th>
              <th>文档</th>
              <th>状态</th>
              <th>文件大小</th>
              <th>峰值内存</th>
              <th>总耗时</th>
              <th>重试</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="t in plTasks" :key="t.id">
              <tr style="cursor: pointer;" @click="togglePipelineExpand(t.id)">
                <td>
                  <button class="btn-icon-small">{{ expandedPl === t.id ? '▼' : '▶' }}</button>
                </td>
                <td>
                  <strong>{{ getDocName(t) }}</strong>
                  <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ t.id }}</span>
                </td>
                <td>
                  <span class="status-dot" :class="plStatusDotClass(t.status)"></span>
                  <span :class="'pl-status-' + t.status.toLowerCase()">
                    <template v-if="t.status === 'RUNNING' && t.retryCount > 0">重试中 ({{ t.retryCount }}/{{ t.maxRetries }})</template>
                    <template v-else>{{ plStatusLabels[t.status] || t.status }}</template>
                  </span>
                </td>
                <td>{{ formatBytes(getMetric(t, 'file_size_bytes')) }}</td>
                <td>{{ getMetric(t, 'peak_memory_mb') != null ? getMetric(t, 'peak_memory_mb') + ' MB' : '-' }}</td>
                <td>{{ formatDuration(getTotalDuration(t)) }}</td>
                <td>{{ t.retryCount || 0 }}</td>
                <td>{{ formatDate(t.createdAt || t.created_at) }}</td>
              </tr>
              <!-- Expanded detail -->
              <tr v-if="expandedPl === t.id" class="expanded-row">
                <td colspan="8" style="padding: 0;">
                  <div style="background: #f9fafb; padding: 16px 20px 16px 44px;">
                    <!-- Gantt Chart -->
                    <div v-if="getStages(t)" class="pipeline-gantt">
                      <div class="gantt-title">Stage Durations</div>
                      <div class="gantt-row" v-for="(stage, sKey) in getStages(t)" :key="sKey">
                        <div class="gantt-label">{{ stageLabels[sKey as string] || sKey }}</div>
                        <div class="gantt-bar-track">
                          <div class="gantt-bar" :style="{ width: ganttWidth(t, stage.duration_ms) + '%', background: stageColors[sKey as string] || '#94a3b8' }"></div>
                        </div>
                        <div class="gantt-duration">{{ formatDuration(stage.duration_ms) }}</div>
                      </div>
                    </div>

                    <!-- Memory per stage -->
                    <div v-if="getStageMemory(t)" class="pipeline-gantt" style="margin-top: 14px;">
                      <div class="gantt-title">Memory per Stage</div>
                      <div class="gantt-row" v-for="(memMb, sKey) in getStageMemory(t)" :key="sKey">
                        <div class="gantt-label">{{ stageLabels[sKey as string] || sKey }}</div>
                        <div class="gantt-bar-track">
                          <div class="gantt-bar" :style="{ width: memBarWidth(t, memMb as number) + '%', background: '#8b5cf6' }"></div>
                        </div>
                        <div class="gantt-duration">{{ memMb }} MB</div>
                      </div>
                    </div>

                    <!-- Error info for failed tasks -->
                    <div v-if="t.status === 'FAILED'" class="pipeline-error-box">
                      <span v-if="t.errorCategory" class="error-category-badge">{{ t.errorCategory || t.error_category }}</span>
                      <span v-if="getFailedStage(t)" style="margin-left: 8px; font-weight: 600;">Failed at: {{ stageLabels[getFailedStage(t)] || getFailedStage(t) }}</span>
                      <div style="margin-top: 6px; font-size: 13px;">{{ t.error || '-' }}</div>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="plTasks.length === 0">
              <td colspan="8" class="empty-state">暂无Pipeline任务</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      <div class="pipeline-pagination" v-if="plTotal > 20">
        <button class="btn btn-default btn-small" :disabled="plPage === 0" @click="plPage--; loadPipelineTasks()">上一页</button>
        <span style="margin: 0 12px; font-size: 13px; color: #666;">第 {{ plPage + 1 }} / {{ Math.ceil(plTotal / 20) }} 页</span>
        <button class="btn btn-default btn-small" :disabled="(plPage + 1) * 20 >= plTotal" @click="plPage++; loadPipelineTasks()">下一页</button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'
import { useTenantStore } from '../../stores/tenants'

const tenantStore = useTenantStore()

interface KnowledgeBase {
  id: string; tenant_id: string; name: string; description: string | null
  type: string; status: string; database_id: string | null
  embedding_model: string | null; document_count: number | null
  error: string | null; created_at: string
}

interface Doc {
  id: string; tenant_id: string; kb_id: string; filename: string
  format: string; status: string; size_bytes: number | null
  chunks_count: number | null; error: string | null; created_at: string
}

interface WriteTask {
  id: string; tenant_id: string; kb_id: string; database_id: string
  type: string; status: string; job_id: string | null
  error: string | null; created_at: string; started_at: string | null; completed_at: string | null
}

interface Stats {
  kb_count: number; document_count: number; processing_count: number
  failed_count: number; ready_count: number
}

const activeTab = ref('bases')
const stats = ref<Stats | null>(null)
const kbs = ref<KnowledgeBase[]>([])
const tasks = ref<WriteTask[]>([])
const expandedKbs = ref<Set<string>>(new Set())
const kbDocs = reactive<Record<string, Doc[]>>({})

const statusFilter = ref('')
const typeFilter = ref('')
const tenantFilter = ref('')
const taskStatusFilter = ref('')

// Pipeline Monitor state
const plStats = ref<any>(null)
const plTasks = ref<any[]>([])
const plTotal = ref(0)
const plPage = ref(0)
const expandedPl = ref<string | null>(null)
const plFilter = ref({ status: '', dateFrom: '', dateTo: '' })

const stageLabels: Record<string, string> = {
  JOB_POD: 'Job Pod启动',
  DOWNLOAD: '文件下载',
  PARSE: '文档解析',
  CHUNK: '切片',
  EMBED: '嵌入',
  COMPUTE_WAKE: 'Compute唤醒',
  WRITE: '写入DB',
}

const stageColors: Record<string, string> = {
  JOB_POD: '#94a3b8', DOWNLOAD: '#3b82f6', PARSE: '#10b981',
  CHUNK: '#f59e0b', EMBED: '#8b5cf6', COMPUTE_WAKE: '#ec4899', WRITE: '#ef4444',
}

const plStatusLabels: Record<string, string> = {
  QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败',
}

async function loadPipeline() {
  await Promise.all([loadPipelineStats(), loadPipelineTasks()])
}

async function loadPipelineStats() {
  try {
    const params: Record<string, string> = {}
    if (plFilter.value.dateFrom) params.from = new Date(plFilter.value.dateFrom).toISOString()
    if (plFilter.value.dateTo) params.to = new Date(plFilter.value.dateTo).toISOString()
    const resp = await adminApi.pipelineStats(params)
    plStats.value = resp.data
  } catch { /* ignore */ }
}

async function loadPipelineTasks() {
  try {
    const params: Record<string, string | number> = { page: plPage.value, size: 50 }
    if (plFilter.value.status) params.status = plFilter.value.status
    if (plFilter.value.dateFrom) params.from = new Date(plFilter.value.dateFrom).toISOString()
    if (plFilter.value.dateTo) params.to = new Date(plFilter.value.dateTo).toISOString()
    const resp = await adminApi.pipelineTasks(params)
    plTasks.value = resp.data.tasks || []
    plTotal.value = resp.data.total || 0
  } catch { /* ignore */ }
}

function togglePipelineExpand(id: string) {
  expandedPl.value = expandedPl.value === id ? null : id
}

function parseField(val: any) {
  if (val == null) return null
  if (typeof val === 'string') { try { return JSON.parse(val) } catch { return null } }
  return val
}

function getStages(task: any) {
  const result = parseField(task.result)
  return result?.stages || null
}

function getStageMemory(task: any) {
  const result = parseField(task.result)
  return result?.metrics?.stage_memory || null
}

function getMetric(task: any, key: string) {
  const result = parseField(task.result)
  return result?.metrics?.[key] ?? null
}

function getDocName(task: any) {
  const params = parseField(task.params)
  return params?.filename || params?.file_name || params?.document_name || task.id?.substring(0, 8) || '-'
}

function getFailedStage(task: any): string {
  const result = parseField(task.result)
  return result?.failed_stage || ''
}

function getTotalDuration(task: any): number {
  const stages = getStages(task)
  if (!stages) return 0
  return Object.values(stages).reduce((sum: number, s: any) => sum + (s.duration_ms || 0), 0)
}

function ganttWidth(task: any, durationMs: number): number {
  const stages = getStages(task)
  if (!stages) return 0
  const max = Math.max(...Object.values(stages).map((s: any) => s.duration_ms || 0))
  return max > 0 ? (durationMs / max) * 100 : 0
}

function memBarWidth(task: any, memMb: number): number {
  const peakMem = getMetric(task, 'peak_memory_mb')
  return peakMem > 0 ? (memMb / peakMem) * 100 : 0
}

function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms === 0) return '-'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60000).toFixed(1) + 'min'
}

function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

function plStatusDotClass(status: string) {
  return { 'status-green': status === 'SUCCEEDED', 'status-blue': status === 'RUNNING', 'status-red': status === 'FAILED', 'status-grey': status === 'QUEUED' }
}

async function loadStats() {
  try {
    const resp = await adminApi.knowledgeStats()
    stats.value = resp.data
  } catch { /* ignore */ }
}

async function loadKbs() {
  try {
    const params: Record<string, string> = {}
    if (statusFilter.value) params.status = statusFilter.value
    if (typeFilter.value) params.type = typeFilter.value
    if (tenantFilter.value) params.tenant_id = tenantFilter.value
    const resp = await adminApi.listKnowledgeBases(params)
    kbs.value = resp.data
  } catch { /* ignore */ }
}

async function loadTasks() {
  try {
    const params: Record<string, string | number> = { limit: 50 }
    if (taskStatusFilter.value) params.status = taskStatusFilter.value
    const resp = await adminApi.listWriteTasks(params)
    tasks.value = resp.data
  } catch { /* ignore */ }
}

async function toggleExpand(kbId: string) {
  if (expandedKbs.value.has(kbId)) {
    expandedKbs.value.delete(kbId)
    expandedKbs.value = new Set(expandedKbs.value)
    return
  }
  expandedKbs.value.add(kbId)
  expandedKbs.value = new Set(expandedKbs.value)
  // Load docs for this KB
  try {
    const resp = await adminApi.getKnowledgeBase(kbId)
    kbDocs[kbId] = resp.data.documents || []
  } catch {
    kbDocs[kbId] = []
  }
}

async function confirmDeleteKb(kb: KnowledgeBase) {
  if (!confirm(`确认删除知识库 "${kb.name}" (${kb.id})？\n此操作不可恢复。`)) return
  try {
    await adminApi.deleteKnowledgeBase(kb.id)
    await loadKbs()
    await loadStats()
  } catch (e: any) {
    alert(`删除失败: ${e.response?.data?.message || e.message}`)
  }
}

async function confirmDeleteDoc(doc: Doc, kbId: string) {
  if (!confirm(`确认删除文档 "${doc.filename}" (${doc.id})？`)) return
  try {
    await adminApi.deleteKnowledgeDocument(doc.id)
    // Refresh docs for this KB
    const resp = await adminApi.getKnowledgeBase(kbId)
    kbDocs[kbId] = resp.data.documents || []
    await loadStats()
  } catch (e: any) {
    alert(`删除失败: ${e.response?.data?.message || e.message}`)
  }
}

async function reprocessDoc(doc: Doc) {
  try {
    await adminApi.reprocessDocument(doc.id)
    doc.status = 'PROCESSING'
    doc.error = null
  } catch (e: any) {
    alert(`重处理失败: ${e.response?.data?.message || e.message}`)
  }
}

function kbStatusClass(status: string) {
  return { 'status-green': status === 'READY', 'status-yellow': status === 'CREATING', 'status-red': status === 'ERROR' }
}

function docStatusClass(status: string) {
  return { 'status-green': status === 'READY', 'status-blue': status === 'PROCESSING', 'status-red': status === 'FAILED', 'status-grey': status === 'PENDING' }
}

function taskStatusClass(status: string) {
  return { 'status-green': status === 'SUCCEEDED', 'status-blue': status === 'RUNNING', 'status-red': status === 'FAILED', 'status-grey': status === 'QUEUED' }
}

onMounted(() => {
  tenantStore.load()
  loadStats()
  loadKbs()
})
</script>

<style scoped>
.stats-row {
  display: flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap;
}
.stat-card {
  background: #fff; border: 1px solid #e5e5e5; border-radius: 6px;
  padding: 16px 24px; min-width: 120px; text-align: center;
}
.stat-value { font-size: 28px; font-weight: 600; color: #333; }
.stat-label { font-size: 13px; color: #999; margin-top: 4px; }

.tab-bar { display: flex; border-bottom: 1px solid #e5e5e5; margin-bottom: 16px; }
.tab-item {
  padding: 8px 16px; cursor: pointer; font-size: 14px; color: #666;
  border-bottom: 2px solid transparent;
}
.tab-item.active { color: #1890ff; border-bottom-color: #1890ff; }

.btn-icon-small {
  background: none; border: none; cursor: pointer; font-size: 11px;
  color: #999; padding: 2px 4px;
}
.expanded-row td { border-top: none !important; }

.status-dot {
  display: inline-block; width: 8px; height: 8px; border-radius: 50%;
  margin-right: 6px; background: #ccc;
}
.status-green { background: #52c41a; }
.status-blue { background: #1890ff; }
.status-yellow { background: #faad14; }
.status-red { background: #e53e3e; }
.status-grey { background: #ccc; }

.error-cell {
  font-size: 12px; color: #e53e3e; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}

/* Pipeline Monitor */
.pl-status-queued { color: #6b7280; }
.pl-status-running { color: #0073e6; }
.pl-status-succeeded { color: #52c41a; }
.pl-status-failed { color: #e6393d; }

.pipeline-gantt { margin-top: 4px; }
.gantt-title { font-size: 12px; font-weight: 600; color: #666; margin-bottom: 6px; }
.gantt-row { display: flex; align-items: center; margin-bottom: 4px; }
.gantt-label { width: 110px; font-size: 12px; color: #555; flex-shrink: 0; }
.gantt-bar-track {
  flex: 1; height: 18px; background: #eee; border-radius: 3px;
  overflow: hidden; margin: 0 10px;
}
.gantt-bar { height: 100%; border-radius: 3px; min-width: 2px; transition: width 0.3s; }
.gantt-duration { width: 70px; font-size: 12px; color: #666; text-align: right; flex-shrink: 0; }

.pipeline-error-box {
  margin-top: 12px; padding: 10px 14px; background: #fff1f0; border: 1px solid #ffa39e;
  border-radius: 4px; font-size: 13px; color: #cf1322;
}
.error-category-badge {
  display: inline-block; background: #e6393d; color: #fff; font-size: 11px;
  padding: 1px 8px; border-radius: 10px; font-weight: 500;
}

.pipeline-pagination {
  display: flex; align-items: center; justify-content: center;
  margin-top: 16px; padding: 8px 0;
}
</style>
