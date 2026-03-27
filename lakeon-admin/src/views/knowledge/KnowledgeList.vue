<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">知识库管理</h1>
    </div>

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

    <!-- Tabs -->
    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'bases' }" @click="activeTab = 'bases'">知识库列表</div>
      <div class="tab-item" :class="{ active: activeTab === 'tasks' }" @click="activeTab = 'tasks'; loadTasks()">写入任务队列</div>
    </div>

    <!-- KB List Tab -->
    <template v-if="activeTab === 'bases'">
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
                <td>{{ tenantStore.name(kb.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ kb.tenant_id }}</span></td>
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
              <th>租户</th>
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
              <td>{{ tenantStore.name(task.tenant_id) }}<br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ task.tenant_id }}</span></td>
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
</style>
