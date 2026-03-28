<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">数据源</h1>
    </div>

    <!-- KB selector -->
    <div style="margin-top: 16px; max-width: 360px;">
      <label class="form-label">选择知识库</label>
      <select v-model="selectedKbId" class="form-select" @change="onKbChange">
        <option value="">请选择知识库</option>
        <option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>
    </div>

    <template v-if="selectedKbId">
      <!-- OBS Datasources section -->
      <div style="margin-top: 24px;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
          <h3 style="margin: 0; font-size: 16px;">OBS 数据源</h3>
          <button class="btn btn-primary" @click="dsCreateDialog = true">添加数据源</button>
        </div>

        <div v-if="datasources.length === 0 && !dsLoading" class="empty-state" style="padding: 40px; text-align: center; color: #999;">
          暂无数据源。点击"添加数据源"创建一个 OBS 目录，将文件批量上传后同步到知识库。
        </div>

        <div v-for="ds in datasources" :key="ds.id" class="section-card" style="margin-bottom: 16px; padding: 16px;">
          <div style="display: flex; justify-content: space-between; align-items: flex-start;">
            <div>
              <div style="font-weight: 600; font-size: 15px;">{{ ds.name }}</div>
              <div style="color: #999; font-size: 12px; margin-top: 4px;">
                <code style="background: #f5f5f5; padding: 2px 6px; border-radius: 3px;">obs://{{ ds.obs_prefix }}</code>
              </div>
              <div style="display: flex; gap: 16px; margin-top: 8px; font-size: 13px; color: #666;">
                <span>{{ ds.file_count }} 个文件</span>
                <span v-if="ds.last_synced_at">上次同步: {{ new Date(ds.last_synced_at).toLocaleString('zh-CN') }}</span>
                <span v-else>未同步</span>
                <span :style="{ color: ds.status === 'SYNCING' ? '#1890ff' : ds.status === 'ERROR' ? '#e6393d' : '#52c41a' }">
                  {{ ds.status === 'ACTIVE' ? '正常' : ds.status === 'SYNCING' ? '同步中...' : '错误' }}
                </span>
              </div>
              <div v-if="ds.last_sync_stats" style="margin-top: 6px; font-size: 12px; color: #999;">
                新增 {{ ds.last_sync_stats.added }} / 修改 {{ ds.last_sync_stats.modified }} / 删除 {{ ds.last_sync_stats.deleted }} / 跳过 {{ ds.last_sync_stats.skipped }}
              </div>
              <div v-if="ds.error" style="margin-top: 6px; font-size: 12px; color: #e6393d;">{{ ds.error }}</div>
            </div>
            <div style="display: flex; gap: 8px; flex-shrink: 0;">
              <button class="btn btn-text" @click="handleGetCredentials(ds.id)">上传凭据</button>
              <button class="btn btn-primary" :disabled="dsSyncing.has(ds.id)" @click="handleSyncDs(ds.id)">
                {{ dsSyncing.has(ds.id) ? '同步中...' : '同步' }}
              </button>
              <button class="btn btn-danger-text" @click="handleDeleteDs(ds.id)">删除</button>
            </div>
          </div>

          <!-- Credentials panel -->
          <div v-if="dsCredentials && dsCredentials.dsId === ds.id" style="margin-top: 16px; padding: 16px; background: #f8f9fa; border-radius: 6px; font-size: 13px;">
            <div style="font-weight: 600; margin-bottom: 12px;">上传指引</div>
            <p style="margin-bottom: 8px;">将文件上传到以下 OBS 目录，然后点击"同步"将文件导入知识库：</p>
            <code style="display: block; background: #fff; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; word-break: break-all;">
              obs://{{ dsCredentials.creds.bucket }}/{{ dsCredentials.creds.prefix }}
            </code>
            <div style="margin-bottom: 8px; font-weight: 500;">支持格式：PDF、DOCX、DOC、XLSX、XLS、PPTX、EPUB、HTML、Markdown、TXT（支持子目录）</div>
            <div style="margin-bottom: 8px; font-weight: 500;">方式一：hcloud CLI</div>
            <pre style="background: #fff; padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 12px; margin-bottom: 12px; white-space: pre-wrap; word-break: break-all;">{{ dsCredentials.creds.upload_commands.hcloud }}</pre>
            <div style="margin-bottom: 8px; font-weight: 500;">方式二：obsutil</div>
            <pre style="background: #fff; padding: 8px 12px; border-radius: 4px; overflow-x: auto; font-size: 12px; margin-bottom: 12px; white-space: pre-wrap; word-break: break-all;">{{ dsCredentials.creds.upload_commands.obsutil }}</pre>
            <div style="margin-bottom: 8px; font-weight: 500;">方式三：华为云 OBS Console 网页端</div>
            <p style="margin: 0; color: #666;">登录华为云 Console → 对象存储服务 → {{ dsCredentials.creds.bucket }} → 进入 {{ dsCredentials.creds.prefix }} 目录 → 上传</p>
            <div style="margin-top: 12px; color: #faad14; font-size: 12px;">
              凭据有效期至 {{ new Date(dsCredentials.creds.expires_at).toLocaleString('zh-CN') }}，过期后重新获取。
            </div>
            <button class="btn btn-text" style="margin-top: 8px;" @click="dsCredentials = null">收起</button>
          </div>
        </div>
      </div>

      <!-- Documents from datasources -->
      <div v-if="documents.length > 0" style="margin-top: 32px;">
        <h3 style="margin: 0 0 12px; font-size: 16px;">同步的文档</h3>
        <div style="display: flex; gap: 16px; margin-bottom: 12px; font-size: 13px; color: #666;">
          <span>共 {{ documents.length }} 个文档</span>
          <span v-if="processingCount > 0" style="color: #1890ff;">{{ processingCount }} 个处理中</span>
          <span v-if="readyCount > 0" style="color: #52c41a;">{{ readyCount }} 个已就绪</span>
          <span v-if="failedCount > 0" style="color: #e6393d;">{{ failedCount }} 个失败</span>
        </div>

        <TableToolbar v-model="docSearch" placeholder="搜索文件名" :loading="docLoading" @refresh="loadDocuments" />
        <div v-if="filteredDocs.length > 0" class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>文件名</th>
                <th>格式</th>
                <th>大小</th>
                <th>Chunks</th>
                <th>状态</th>
                <th>上传时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="doc in filteredDocs" :key="doc.id" @click="$router.push(`/knowledge/${selectedKbId}/documents/${doc.id}`)" style="cursor: pointer;">
                <td style="font-weight: 500; color: #1890ff;">{{ doc.filename }}</td>
                <td><span style="background: #e8f4ff; color: #0073e6; font-size: 11px; padding: 1px 6px; border-radius: 3px;">{{ doc.format }}</span></td>
                <td style="color: #666;">{{ formatSize(doc.size_bytes) }}</td>
                <td>{{ doc.chunks_count ?? '-' }}</td>
                <td>
                  <div style="display: flex; align-items: center; gap: 6px;">
                    <span class="status-dot" :style="{ background: statusColor(doc.status) }"></span>
                    {{ statusText(doc.status) }}
                    <span v-if="doc.status === 'PROCESSING' && doc.progress" style="color: #999; font-size: 12px;">
                      {{ Math.round(doc.progress * 100) }}%
                    </span>
                    <span v-if="doc.status === 'FAILED' && doc.error" class="error-msg" :title="doc.error">
                      {{ doc.error }}
                    </span>
                  </div>
                </td>
                <td style="color: #999;">{{ formatTime(doc.created_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Create dialog -->
      <div v-if="dsCreateDialog" class="modal-overlay" @click.self="dsCreateDialog = false">
        <div class="modal-box" style="max-width: 400px;">
          <div class="modal-header">
            <span>添加数据源</span>
            <button class="btn-icon" @click="dsCreateDialog = false">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
          <div class="modal-body">
            <label class="form-label">数据源名称</label>
            <input v-model="dsCreateName" class="form-input" placeholder="例如：产品文档" @keyup.enter="handleCreateDs" />
          </div>
          <div class="modal-footer">
            <button class="btn btn-text" @click="dsCreateDialog = false">取消</button>
            <button class="btn btn-primary" :disabled="!dsCreateName.trim()" @click="handleCreateDs">创建</button>
          </div>
        </div>
      </div>
    </template>

    <div v-else style="margin-top: 48px; text-align: center; color: #999;">
      请先选择一个知识库
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  listKnowledgeBases, listDocuments,
  listDataSources, createDataSource, deleteDataSource, syncDataSource, getDataSourceCredentials,
  type KnowledgeBase, type Document, type DataSource, type DataSourceCredentials
} from '../../api/knowledge'
import TableToolbar from '../../components/TableToolbar.vue'

const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKbId = ref('')
const documents = ref<Document[]>([])
const docLoading = ref(false)
const docSearch = ref('')

// Datasource state
const datasources = ref<DataSource[]>([])
const dsLoading = ref(false)
const dsCreateDialog = ref(false)
const dsCreateName = ref('')
const dsCredentials = ref<{ dsId: string; creds: DataSourceCredentials } | null>(null)
const dsSyncing = ref<Set<string>>(new Set())

const processingCount = computed(() => documents.value.filter(d => d.status === 'PROCESSING').length)
const readyCount = computed(() => documents.value.filter(d => d.status === 'READY').length)
const failedCount = computed(() => documents.value.filter(d => d.status === 'FAILED').length)

const filteredDocs = computed(() => {
  if (!docSearch.value) return documents.value
  const q = docSearch.value.toLowerCase()
  return documents.value.filter(d => d.filename.toLowerCase().includes(q))
})

function statusColor(s: string) {
  if (s === 'READY') return '#52c41a'
  if (s === 'PROCESSING') return '#1890ff'
  if (s === 'FAILED') return '#e6393d'
  return '#d9d9d9'
}

function statusText(s: string) {
  const map: Record<string, string> = { PENDING: '等待中', PROCESSING: '处理中', READY: '就绪', FAILED: '失败' }
  return map[s] || s
}

function formatSize(bytes: number) {
  if (!bytes) return '-'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function formatTime(t: string) {
  return t ? new Date(t).toLocaleString('zh-CN') : '-'
}

async function onKbChange() {
  documents.value = []
  datasources.value = []
  dsCredentials.value = null
  if (selectedKbId.value) {
    await Promise.all([loadDocuments(), loadDataSources()])
  }
}

async function loadDocuments() {
  if (!selectedKbId.value) return
  docLoading.value = true
  try {
    const resp = await listDocuments(selectedKbId.value)
    documents.value = resp.data
  } finally {
    docLoading.value = false
  }
}

async function loadDataSources() {
  if (!selectedKbId.value) return
  dsLoading.value = true
  try {
    const res = await listDataSources(selectedKbId.value)
    datasources.value = res.data
  } finally {
    dsLoading.value = false
  }
}

async function handleCreateDs() {
  if (!selectedKbId.value || !dsCreateName.value.trim()) return
  await createDataSource(selectedKbId.value, dsCreateName.value.trim())
  dsCreateName.value = ''
  dsCreateDialog.value = false
  await loadDataSources()
}

async function handleDeleteDs(dsId: string) {
  if (!confirm('删除数据源将同时删除其关联的所有文档和切片，确定？')) return
  await deleteDataSource(selectedKbId.value, dsId)
  await Promise.all([loadDataSources(), loadDocuments()])
}

async function handleSyncDs(dsId: string) {
  dsSyncing.value = new Set([...dsSyncing.value, dsId])
  try {
    await syncDataSource(selectedKbId.value, dsId)
    await Promise.all([loadDataSources(), loadDocuments()])
  } finally {
    const next = new Set(dsSyncing.value)
    next.delete(dsId)
    dsSyncing.value = next
  }
}

async function handleGetCredentials(dsId: string) {
  const res = await getDataSourceCredentials(selectedKbId.value, dsId)
  dsCredentials.value = { dsId, creds: res.data }
}

onMounted(async () => {
  const resp = await listKnowledgeBases()
  knowledgeBases.value = resp.data
})
</script>

<style scoped>
.error-msg {
  color: #e6393d;
  font-size: 12px;
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  text-decoration: underline dashed #e6393d;
  text-underline-offset: 2px;
}
.error-msg:hover {
  opacity: 0.8;
}
</style>
