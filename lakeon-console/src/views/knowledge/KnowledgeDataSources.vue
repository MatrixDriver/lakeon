<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">数据源</h1>
    </div>

    <!-- KB selector -->
    <div style="margin-top: 16px; max-width: 360px;">
      <label class="form-label">选择知识库</label>
      <select v-model="selectedKbId" class="form-select" @change="loadDocuments">
        <option value="">请选择知识库</option>
        <option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>
    </div>

    <template v-if="selectedKbId">
      <!-- Status summary -->
      <div v-if="documents.length > 0" style="display: flex; gap: 16px; margin-top: 20px; font-size: 13px; color: #666;">
        <span>共 {{ documents.length }} 个文档</span>
        <span v-if="processingCount > 0" style="color: #1890ff;">{{ processingCount }} 个处理中</span>
        <span v-if="readyCount > 0" style="color: #52c41a;">{{ readyCount }} 个已就绪</span>
        <span v-if="failedCount > 0" style="color: #e6393d;">{{ failedCount }} 个失败</span>
      </div>

      <!-- Upload -->
      <div style="margin-top: 16px;">
        <label class="btn btn-primary" style="cursor: pointer;">
          上传文档
          <input type="file" accept=".pdf,.docx,.md,.markdown" multiple style="display: none;" @change="handleUpload" />
        </label>
        <span style="color: #999; font-size: 13px; margin-left: 12px;">支持 PDF、DOCX、Markdown</span>
      </div>

      <!-- Document table -->
      <TableToolbar v-model="docSearch" placeholder="搜索文件名" :loading="docLoading" @refresh="loadDocuments" style="margin-top: 16px;" />
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
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="doc in filteredDocs" :key="doc.id">
              <td style="font-weight: 500;">{{ doc.filename }}</td>
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
              <td>
                <button class="btn btn-text btn-small" style="color: #e6393d;" @click="handleDelete(doc)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="empty-state" style="margin-top: 48px; text-align: center;">
        <p style="color: #999;">当前知识库还没有文档</p>
      </div>
    </template>

    <div v-else style="margin-top: 48px; text-align: center; color: #999;">
      请先选择一个知识库
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listKnowledgeBases, listDocuments, getUploadUrl, processDocument, deleteDocument, type KnowledgeBase, type Document } from '../../api/knowledge'
import TableToolbar from '../../components/TableToolbar.vue'

const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKbId = ref('')
const documents = ref<Document[]>([])
const docLoading = ref(false)
const docSearch = ref('')

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

async function loadDocuments() {
  if (!selectedKbId.value) { documents.value = []; return }
  docLoading.value = true
  try {
    const resp = await listDocuments(selectedKbId.value)
    documents.value = resp.data
  } finally {
    docLoading.value = false
  }
}

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  try {
    for (const file of Array.from(input.files)) {
      const urlResp = await getUploadUrl(selectedKbId.value, file.name)
      const { document_id, upload_url } = urlResp.data
      const uploadResp = await fetch(upload_url, { method: 'PUT', body: file })
      if (!uploadResp.ok) {
        alert(`文件 "${file.name}" 上传失败 (HTTP ${uploadResp.status})`)
        continue
      }
      await processDocument(document_id)
    }
  } catch (err: any) {
    alert(`上传失败: ${err.message || err}`)
  } finally {
    await loadDocuments()
    input.value = ''
  }
}

async function handleDelete(doc: Document) {
  if (!confirm(`确认删除文档"${doc.filename}"？`)) return
  await deleteDocument(doc.id)
  await loadDocuments()
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
