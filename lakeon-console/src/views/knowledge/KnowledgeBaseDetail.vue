<template>
  <div class="page-container">
    <!-- Breadcrumb -->
    <div class="breadcrumb" style="margin-bottom: 16px;">
      <router-link to="/knowledge" style="color: #0073e6; text-decoration: none;">知识库</router-link>
      <span style="margin: 0 8px; color: #ccc;">/</span>
      <span style="color: #333;">{{ kb?.name || '...' }}</span>
    </div>

    <div class="page-header">
      <h1 class="page-title">{{ kb?.name || '加载中...' }}</h1>
    </div>

    <!-- TABLE type KB: delegate to TableKbDetail -->
    <TableKbDetail v-if="kb && kb.type === 'TABLE'" :kb="kb" />

    <!-- DOCUMENT type KB (or legacy without type) -->
    <template v-if="!kb || kb.type !== 'TABLE'">

    <!-- Tabs -->
    <div class="tab-bar" style="margin-top: 20px; border-bottom: 1px solid #e5e5e5; display: flex; gap: 0;">
      <div v-for="tab in tabs" :key="tab.key"
           class="tab-item"
           :class="{ active: activeTab === tab.key }"
           @click="activeTab = tab.key">
        {{ tab.label }}
      </div>
    </div>

    <!-- Overview Tab -->
    <div v-if="activeTab === 'overview'" style="margin-top: 24px;">
      <div class="section-card" style="max-width: 600px;">
        <div class="section-header">概览</div>
        <div style="padding: 16px; display: grid; grid-template-columns: 120px 1fr; gap: 12px; font-size: 14px;">
          <span style="color: #999;">名称</span><span>{{ kb?.name }}</span>
          <span style="color: #999;">描述</span><span>{{ kb?.description || '-' }}</span>
          <span style="color: #999;">文档数</span><span>{{ kb?.document_count ?? 0 }}</span>
          <span style="color: #999;">Embedding 模型</span><span>BGE-M3 (1024维)</span>
          <span style="color: #999;">切片策略</span><span>结构化切片 (400 tokens)</span>
          <span style="color: #999;">状态</span>
          <span><span class="status-tag" :class="'tag-' + (kb?.status === 'READY' ? 'green' : 'blue')">{{ kb?.status === 'READY' ? '就绪' : kb?.status }}</span></span>
          <span style="color: #999;">创建时间</span><span>{{ kb?.created_at ? new Date(kb.created_at).toLocaleString('zh-CN') : '-' }}</span>
          <span style="color: #999;">底层数据库</span>
          <span v-if="kb?.database_id">
            <router-link :to="'/databases/' + kb.database_id" style="color: #2563eb; text-decoration: none;">{{ kb.database_id }}</router-link>
          </span>
          <span v-else>-</span>
        </div>
      </div>
    </div>

    <!-- Documents Tab -->
    <div v-if="activeTab === 'documents'" style="margin-top: 24px;">
      <!-- Status summary bar -->
      <div v-if="documents.length > 0" style="display: flex; gap: 16px; margin-bottom: 16px; font-size: 13px; color: #666;">
        <span v-if="statusCounts.processing > 0" style="color: #1890ff;">{{ statusCounts.processing }} 个处理中</span>
        <span v-if="statusCounts.ready > 0" style="color: #52c41a;">{{ statusCounts.ready }} 个已就绪</span>
        <span v-if="statusCounts.failed > 0" style="color: #e6393d;">{{ statusCounts.failed }} 个失败</span>
      </div>

      <div style="margin-bottom: 16px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
        <label class="btn btn-primary" style="cursor: pointer;" :class="{ disabled: uploading }">
          上传文件
          <input type="file" accept=".pdf,.docx,.doc,.xlsx,.xls,.xlsm,.pptx,.epub,.html,.htm,.md,.markdown,.txt" multiple style="display: none;" :disabled="uploading" @change="handleUpload" />
        </label>
        <label class="btn btn-secondary" style="cursor: pointer;" :class="{ disabled: uploading }">
          上传目录
          <input type="file" style="display: none;" :disabled="uploading" webkitdirectory @change="handleDirectoryUpload" />
        </label>
        <span style="color: #999; font-size: 13px;">支持 PDF、DOCX、DOC、XLSX、XLS、PPTX、EPUB、HTML、Markdown、TXT，最多 20 个/批</span>
      </div>

      <!-- Upload progress list -->
      <div v-if="uploadProgress.length > 0" style="margin-bottom: 16px; border: 1px solid #e5e5e5; border-radius: 6px; overflow: hidden;">
        <div style="padding: 8px 12px; background: #f8f8f8; font-size: 12px; color: #666; border-bottom: 1px solid #e5e5e5;">
          上传进度 ({{ uploadProgress.filter(f => f.status === 'done').length }}/{{ uploadProgress.length }})
          <button v-if="!uploading" style="float: right; background: none; border: none; cursor: pointer; color: #999; font-size: 12px;" @click="uploadProgress = []">清除</button>
        </div>
        <div style="max-height: 180px; overflow-y: auto;">
          <div v-for="f in uploadProgress" :key="f.filename"
               style="display: flex; align-items: center; gap: 8px; padding: 6px 12px; font-size: 13px; border-bottom: 1px solid #f0f0f0;">
            <span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ f.filename }}</span>
            <span v-if="f.status === 'pending'" style="color: #999; font-size: 12px; flex-shrink: 0;">等待中</span>
            <span v-else-if="f.status === 'uploading'" style="color: #1890ff; font-size: 12px; flex-shrink: 0;">上传中...</span>
            <span v-else-if="f.status === 'processing'" style="color: #1890ff; font-size: 12px; flex-shrink: 0;">处理中...</span>
            <span v-else-if="f.status === 'done'" style="color: #52c41a; font-size: 12px; flex-shrink: 0;">✓ 完成</span>
            <span v-else-if="f.status === 'error'" style="color: #e6393d; font-size: 12px; flex-shrink: 0;" :title="f.error">✗ 失败</span>
          </div>
        </div>
      </div>

      <TableToolbar v-model="docSearch" placeholder="搜索文件名" :loading="docLoading" @refresh="loadDocuments">
        <template #extra>
          <button v-if="selectedDocIds.size > 0" style="background: #e6393d; color: #fff; border: none; border-radius: 4px; padding: 4px 12px; cursor: pointer; font-size: 12px; white-space: nowrap;" @click="handleBatchDelete">
            删除选中 ({{ selectedDocIds.size }})
          </button>
        </template>
      </TableToolbar>
      <div v-if="filteredDocs.length > 0" class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 36px; text-align: center;">
                <input type="checkbox" ref="selectAllCheckbox" :checked="isAllSelected" @change="toggleSelectAll" style="cursor: pointer;">
              </th>
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
            <tr v-for="doc in filteredDocs" :key="doc.id" class="clickable-row" @click="router.push({ name: 'DocumentDetail', params: { kbId: route.params.kbId, docId: doc.id } })">
              <td style="text-align: center;" @click.stop>
                <input type="checkbox" :checked="selectedDocIds.has(doc.id)" @change="toggleSelect(doc.id)" style="cursor: pointer;">
              </td>
              <td>
                <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
                  <span style="font-weight: 500;">{{ doc.filename }}</span>
                  <span v-for="tag in (doc.tags || [])" :key="tag" class="tag-badge">{{ tag }}</span>
                  <button class="btn-icon" title="编辑标签" @click.stop="openTagDialog(doc)">
                    <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                    </svg>
                  </button>
                </div>
              </td>
              <td><span class="tag-blue" style="font-size: 11px; padding: 1px 6px; border-radius: 3px;">{{ doc.format }}</span></td>
              <td style="color: #666;">{{ formatSize(doc.size_bytes) }}</td>
              <td>{{ doc.chunks_count ?? '-' }}</td>
              <td>
                <div style="display: flex; flex-direction: column; gap: 4px;">
                  <div style="display: flex; align-items: center; gap: 6px;">
                    <span class="status-dot" :style="{ background: docStatusColor(doc.status) }"></span>
                    <span>{{ docStatusText(doc.status) }}</span>
                  </div>
                  <!-- Progress bar for PROCESSING -->
                  <div v-if="doc.status === 'PROCESSING' && doc.progress != null" style="display: flex; align-items: center; gap: 8px;">
                    <div style="flex: 1; height: 4px; background: #e5e5e5; border-radius: 2px; max-width: 120px;">
                      <div :style="{ width: Math.round(doc.progress * 100) + '%', height: '100%', background: '#1890ff', borderRadius: '2px', transition: 'width 0.3s' }"></div>
                    </div>
                    <span style="color: #1890ff; font-size: 12px; white-space: nowrap;">{{ Math.round(doc.progress * 100) }}%</span>
                  </div>
                  <div v-if="doc.status === 'PROCESSING' && doc.progress_message" style="color: #999; font-size: 11px;">
                    {{ doc.progress_message }}
                  </div>
                  <!-- Error with click to expand -->
                  <div v-if="doc.status === 'FAILED' && doc.error"
                       style="color: #e6393d; font-size: 12px; cursor: pointer; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                       :title="doc.error"
                       @click.stop="showErrorDetail(doc)">
                    {{ doc.error }}
                  </div>
                </div>
              </td>
              <td style="color: #999;">{{ doc.created_at ? new Date(doc.created_at).toLocaleString('zh-CN') : '-' }}</td>
              <td>
                <button class="btn btn-text btn-small" style="color: #e6393d;" @click.stop="handleDeleteDoc(doc)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="empty-state" style="margin-top: 48px; text-align: center;">
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="#ccc" stroke-width="1.5">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
          <polyline points="14 2 14 8 20 8"/>
        </svg>
        <p style="color: #999; margin-top: 12px;">还没有文档，点击"上传文档"开始</p>
      </div>
    </div>

    <!-- Chunks Tab -->
    <div v-if="activeTab === 'chunks'" style="margin-top: 8px;">
      <ChunkStats :kb-id="(route.params.kbId as string)" />
    </div>

    <!-- Search Tab -->
    <div v-if="activeTab === 'search'" style="margin-top: 24px; max-width: 720px; display: flex; flex-direction: column; height: calc(100vh - 220px); min-height: 400px;">
      <!-- Top controls row: tag filter + clear button -->
      <div style="display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; flex-shrink: 0;">
        <div style="flex: 1;">
          <div v-if="allTags.length > 0">
            <div style="font-size: 13px; color: #666; margin-bottom: 6px;">按标签过滤</div>
            <div style="display: flex; flex-wrap: wrap; gap: 6px;">
              <span v-for="tag in allTags" :key="tag"
                    class="tag-badge tag-filter"
                    :class="{ 'tag-filter-active': searchFilterTags.includes(tag) }"
                    @click="toggleFilterTag(tag)">
                {{ tag }}
              </span>
            </div>
          </div>
        </div>
        <button v-if="chatMessages.length > 0" class="btn btn-text" style="font-size: 13px; color: #999; flex-shrink: 0; margin-top: 2px;" @click="clearChat">清除对话</button>
      </div>

      <!-- Chat message list -->
      <div ref="chatContainer" class="chat-container">
        <div v-if="chatMessages.length === 0" class="chat-empty">
          <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="#ccc" stroke-width="1.5">
            <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
          </svg>
          <p style="color: #bbb; margin-top: 10px; font-size: 14px;">输入问题开始对话式搜索</p>
          <p style="color: #ccc; font-size: 12px; margin-top: 4px;">语义搜索 + 关键词搜索（pgvector + BM25 + RRF 融合）</p>
        </div>

        <div v-for="(msg, idx) in chatMessages" :key="idx" class="chat-message-row" :class="msg.role">
          <!-- User bubble -->
          <div v-if="msg.role === 'user'" class="chat-bubble user-bubble">
            {{ msg.content }}
          </div>

          <!-- Assistant results -->
          <div v-if="msg.role === 'assistant'" class="chat-bubble assistant-bubble">
            <div v-if="msg.rewritten_query && msg.rewritten_query !== msg.original_query"
                 style="font-size: 12px; color: #888; margin-bottom: 10px; font-style: italic;">
              搜索改写为: <span style="color: #0073e6;">{{ msg.rewritten_query }}</span>
            </div>
            <div v-if="msg.results && msg.results.length > 0">
              <div v-for="(r, ri) in msg.results" :key="ri" class="result-card">
                <div style="font-size: 13px; line-height: 1.6; color: #333; white-space: pre-wrap;">{{ r.content }}</div>
                <div style="margin-top: 8px; font-size: 12px; color: #999; display: flex; gap: 12px; flex-wrap: wrap;">
                  <span>来源: {{ r.metadata?.filename }}</span>
                  <span v-if="r.metadata?.section">章节: {{ r.metadata.section }}</span>
                  <span>得分: {{ r.score?.toFixed(3) }}</span>
                </div>
              </div>
            </div>
            <div v-else style="font-size: 13px; color: #999;">未找到相关内容</div>
          </div>

          <!-- Loading indicator -->
          <div v-if="msg.role === 'loading'" class="chat-bubble assistant-bubble loading-bubble">
            <span class="loading-dot"></span>
            <span class="loading-dot"></span>
            <span class="loading-dot"></span>
          </div>
        </div>
      </div>

      <!-- Chat input -->
      <div class="chat-input-row" style="flex-shrink: 0;">
        <input
          ref="chatInput"
          v-model="searchQuery"
          class="form-input chat-input"
          placeholder="在当前知识库中搜索..."
          :disabled="isSearching"
          @keyup.enter="handleSearch"
        />
        <button class="btn btn-primary" style="flex-shrink: 0;" :disabled="!searchQuery.trim() || isSearching" @click="handleSearch">
          {{ isSearching ? '搜索中...' : '发送' }}
        </button>
      </div>
    </div>

    </template><!-- end DOCUMENT type -->

    <!-- Error Detail Dialog -->
    <div v-if="errorDetail.open" class="modal-overlay" @click.self="errorDetail.open = false">
      <div class="modal-box" style="max-width: 600px;">
        <div class="modal-header">
          <span>处理失败详情</span>
          <button class="btn-icon" @click="errorDetail.open = false">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p style="font-size: 13px; color: #666; margin-bottom: 8px;">
            文档: <strong>{{ errorDetail.filename }}</strong>
          </p>
          <pre style="font-size: 13px; color: #e6393d; background: #fff5f5; border: 1px solid #fee; border-radius: 4px; padding: 12px; white-space: pre-wrap; word-break: break-word; max-height: 300px; overflow-y: auto;">{{ errorDetail.error }}</pre>
        </div>
        <div class="modal-footer">
          <button class="btn btn-text" @click="errorDetail.open = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- Tag Edit Dialog -->
    <div v-if="tagDialog.open" class="modal-overlay" @click.self="tagDialog.open = false">
      <div class="modal-box">
        <div class="modal-header">
          <span>编辑标签</span>
          <button class="btn-icon" @click="tagDialog.open = false">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p style="font-size: 13px; color: #666; margin-bottom: 10px;">
            文档: <strong>{{ tagDialog.doc?.filename }}</strong>
          </p>
          <label style="font-size: 13px; color: #555; display: block; margin-bottom: 6px;">
            标签（逗号分隔）
          </label>
          <input
            v-model="tagDialog.input"
            class="form-input"
            placeholder="例如: 技术文档, 2024, 重要"
            @keyup.enter="saveDocTags"
          />
          <p style="font-size: 12px; color: #aaa; margin-top: 6px;">多个标签用英文逗号分隔</p>
        </div>
        <div class="modal-footer">
          <button class="btn btn-text" @click="tagDialog.open = false">取消</button>
          <button class="btn btn-primary" :disabled="tagDialog.saving" @click="saveDocTags">
            {{ tagDialog.saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getKnowledgeBase, listDocuments, deleteDocument, searchKnowledge, setDocumentTags, batchGetUploadUrls, batchProcessDocuments, type KnowledgeBase as KBType, type Document, type SearchResult } from '../../api/knowledge'
import ChunkStats from '../../components/knowledge/ChunkStats.vue'
import TableKbDetail from '../../components/knowledge/TableKbDetail.vue'
import TableToolbar from '../../components/TableToolbar.vue'

const route = useRoute()
const router = useRouter()

const kb = ref<KBType | null>(null)
const documents = ref<Document[]>([])
const activeTab = ref('documents')
const searchQuery = ref('')
const searchFilterTags = ref<string[]>([])

// Chat-style search state
interface ChatMessage {
  role: 'user' | 'assistant' | 'loading'
  content: string
  results?: SearchResult[]
  rewritten_query?: string
  original_query?: string
}
const chatMessages = ref<ChatMessage[]>([])
const isSearching = ref(false)
const uploading = ref(false)
const docLoading = ref(false)

interface UploadFileState {
  filename: string
  status: 'pending' | 'uploading' | 'processing' | 'done' | 'error'
  error?: string
}
const uploadProgress = ref<UploadFileState[]>([])
const docSearch = ref('')
const chatContainer = ref<HTMLElement | null>(null)
const chatInput = ref<HTMLInputElement | null>(null)

const errorDetail = ref<{ open: boolean; filename: string; error: string }>({
  open: false, filename: '', error: ''
})

function showErrorDetail(doc: Document) {
  errorDetail.value = { open: true, filename: doc.filename, error: doc.error || '未知错误' }
}

const tagDialog = ref<{
  open: boolean
  doc: Document | null
  input: string
  saving: boolean
}>({ open: false, doc: null, input: '', saving: false })

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'documents', label: '文档' },
  { key: 'search', label: '搜索' },
  { key: 'chunks', label: '切片' },
]

const statusCounts = computed(() => {
  const counts = { processing: 0, ready: 0, failed: 0 }
  for (const d of documents.value) {
    if (d.status === 'PROCESSING') counts.processing++
    else if (d.status === 'READY') counts.ready++
    else if (d.status === 'FAILED') counts.failed++
  }
  return counts
})

const allTags = computed(() => {
  const tagSet = new Set<string>()
  for (const d of documents.value) {
    for (const t of (d.tags || [])) {
      tagSet.add(t)
    }
  }
  return Array.from(tagSet).sort()
})

function docStatusColor(s: string) {
  if (s === 'READY') return '#52c41a'
  if (s === 'PROCESSING') return '#1890ff'
  if (s === 'FAILED') return '#e6393d'
  return '#d9d9d9'
}

function docStatusText(s: string) {
  const map: Record<string, string> = { PENDING: '等待中', PROCESSING: '处理中', READY: '就绪', FAILED: '失败' }
  return map[s] || s
}

function formatSize(bytes: number) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function openTagDialog(doc: Document) {
  tagDialog.value = {
    open: true,
    doc,
    input: (doc.tags || []).join(', '),
    saving: false,
  }
}

async function saveDocTags() {
  if (!tagDialog.value.doc) return
  tagDialog.value.saving = true
  const tags = tagDialog.value.input
    .split(',')
    .map(t => t.trim())
    .filter(t => t.length > 0)
  try {
    await setDocumentTags(tagDialog.value.doc.id, tags)
    const doc = documents.value.find(d => d.id === tagDialog.value.doc!.id)
    if (doc) doc.tags = tags
    tagDialog.value.open = false
  } finally {
    tagDialog.value.saving = false
  }
}

function toggleFilterTag(tag: string) {
  const idx = searchFilterTags.value.indexOf(tag)
  if (idx === -1) {
    searchFilterTags.value.push(tag)
  } else {
    searchFilterTags.value.splice(idx, 1)
  }
}

const filteredDocs = computed(() => {
  if (!docSearch.value) return documents.value
  const q = docSearch.value.toLowerCase()
  return documents.value.filter(d => d.filename.toLowerCase().includes(q))
})

async function loadDocuments() {
  const kbId = route.params.kbId as string
  docLoading.value = true
  try {
    const resp = await listDocuments(kbId)
    documents.value = resp.data
  } finally {
    docLoading.value = false
  }
}

const SUPPORTED_EXTENSIONS = ['.pdf', '.docx', '.md', '.markdown', '.txt', '.epub']

function filterSupportedFiles(files: File[]): File[] {
  return files.filter(f => SUPPORTED_EXTENSIONS.some(ext => f.name.toLowerCase().endsWith(ext)))
}

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const files = filterSupportedFiles(Array.from(input.files))
  if (!files.length) {
    alert('没有支持的文件格式（支持 PDF、DOCX、EPUB、Markdown、TXT）')
    input.value = ''
    return
  }
  await runBatchUpload(files)
  input.value = ''
}

async function handleDirectoryUpload(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const files = filterSupportedFiles(Array.from(input.files))
  if (!files.length) {
    alert('目录中没有支持的文件格式（支持 PDF、DOCX、EPUB、Markdown、TXT）')
    input.value = ''
    return
  }
  await runBatchUpload(files)
  input.value = ''
}

async function runBatchUpload(files: File[]) {
  const kbId = route.params.kbId as string
  uploading.value = true
  uploadProgress.value = files.map(f => ({ filename: f.name, status: 'pending' as const }))

  try {
    // Split into chunks of 20
    const BATCH_SIZE = 20
    for (let batchStart = 0; batchStart < files.length; batchStart += BATCH_SIZE) {
      const batchFiles = files.slice(batchStart, batchStart + BATCH_SIZE)
      const batchIndices = batchFiles.map((_, i) => batchStart + i)

      // Get presigned URLs for this batch
      const fileSpecs = batchFiles.map(f => ({ filename: f.name }))
      const urlResp = await batchGetUploadUrls(kbId, fileSpecs)
      const docItems = urlResp.data.documents

      // Concurrent PUT uploads (3 at a time)
      const CONCURRENCY = 3
      const documentIds: string[] = new Array(docItems.length)
      const uploadTasks = docItems.map((item, i) => async () => {
        const idx = batchIndices[i]!
        uploadProgress.value[idx] = { filename: item.filename, status: 'uploading' }
        const uploadResp = await fetch(item.upload_url, { method: 'PUT', body: batchFiles[i] })
        if (!uploadResp.ok) {
          uploadProgress.value[idx] = { filename: item.filename, status: 'error', error: `HTTP ${uploadResp.status}` }
          return null
        }
        documentIds[i] = item.document_id
        return item.document_id
      })

      // Run with concurrency limit
      const results: (string | null)[] = []
      for (let i = 0; i < uploadTasks.length; i += CONCURRENCY) {
        const chunk = uploadTasks.slice(i, i + CONCURRENCY)
        const chunkResults = await Promise.all(chunk.map(t => t()))
        results.push(...chunkResults)
      }

      // Collect successfully uploaded document IDs
      const successIds = results.filter((id): id is string => id !== null)
      if (successIds.length === 0) continue

      // Mark as processing
      batchIndices.forEach((idx, i) => {
        if (results[i] !== null) {
          uploadProgress.value[idx] = { filename: files[idx]!.name, status: 'processing' }
        }
      })

      // Submit batch process
      await batchProcessDocuments(successIds)

      // Mark done
      batchIndices.forEach((idx, i) => {
        if (results[i] !== null) {
          uploadProgress.value[idx] = { filename: files[idx]!.name, status: 'done' }
        }
      })

      await loadDocuments()
    }
  } catch (err: any) {
    alert(`上传失败: ${err.message || err}`)
  } finally {
    uploading.value = false
    await loadDocuments()
  }
}

async function handleDeleteDoc(doc: Document) {
  if (!confirm(`确认删除文档"${doc.filename}"？`)) return
  await deleteDocument(doc.id)
  selectedDocIds.value.delete(doc.id)
  await loadDocuments()
}

// ── Batch selection ──
const selectedDocIds = ref<Set<string>>(new Set())
const selectAllCheckbox = ref<HTMLInputElement | null>(null)

const isAllSelected = computed(() =>
  filteredDocs.value.length > 0 && filteredDocs.value.every(d => selectedDocIds.value.has(d.id))
)
const isIndeterminate = computed(() =>
  !isAllSelected.value && filteredDocs.value.some(d => selectedDocIds.value.has(d.id))
)

watch(isIndeterminate, (val) => {
  if (selectAllCheckbox.value) selectAllCheckbox.value.indeterminate = val
})

function toggleSelect(docId: string) {
  const s = new Set(selectedDocIds.value)
  if (s.has(docId)) s.delete(docId); else s.add(docId)
  selectedDocIds.value = s
}

function toggleSelectAll() {
  if (isAllSelected.value) {
    selectedDocIds.value = new Set()
  } else {
    selectedDocIds.value = new Set(filteredDocs.value.map(d => d.id))
  }
}

async function handleBatchDelete() {
  const count = selectedDocIds.value.size
  if (!confirm(`确认删除选中的 ${count} 个文档？`)) return
  const ids = [...selectedDocIds.value]
  for (const id of ids) {
    try { await deleteDocument(id) } catch (e) { console.error('Failed to delete', id, e) }
  }
  selectedDocIds.value = new Set()
  await loadDocuments()
}

function buildConversationHistory(): { role: string; content: string }[] {
  // Take up to the last 5 user+assistant turn pairs (10 messages max)
  const turns = chatMessages.value.filter(m => m.role === 'user' || m.role === 'assistant')
  const recent = turns.slice(-10)
  return recent.map(m => {
    if (m.role === 'user') return { role: 'user', content: m.content }
    // Summarize assistant results as a brief content line
    const topResults = (m.results || []).slice(0, 2).map(r => r.content.slice(0, 120)).join(' | ')
    return { role: 'assistant', content: topResults || '未找到相关内容' }
  })
}

async function handleSearch() {
  const query = searchQuery.value.trim()
  if (!query || isSearching.value) return
  const kbId = route.params.kbId as string

  isSearching.value = true
  searchQuery.value = ''

  // Add user message
  chatMessages.value.push({ role: 'user', content: query })
  // Add loading placeholder
  chatMessages.value.push({ role: 'loading', content: '' })
  await nextTick()
  scrollChatToBottom()

  try {
    const history = buildConversationHistory().slice(0, -0) // all built turns (excludes loading)
    const options: { tags?: string[]; conversation_history?: { role: string; content: string }[] } = {}
    if (searchFilterTags.value.length > 0) options.tags = searchFilterTags.value
    if (history.length > 0) options.conversation_history = history

    const resp = await searchKnowledge(kbId, query, 5, options)
    const { results, rewritten_query } = resp.data

    // Remove loading placeholder and add real assistant message
    chatMessages.value.pop()
    chatMessages.value.push({
      role: 'assistant',
      content: rewritten_query || query,
      results,
      rewritten_query,
      original_query: query,
    })
  } catch {
    chatMessages.value.pop()
    chatMessages.value.push({ role: 'assistant', content: '搜索出错，请重试', results: [] })
  } finally {
    isSearching.value = false
    await nextTick()
    scrollChatToBottom()
    chatInput.value?.focus()
  }
}

function clearChat() {
  chatMessages.value = []
}

function scrollChatToBottom() {
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  }
}

// ── Auto-poll PROCESSING documents for progress ────────────────
let pollTimer: ReturnType<typeof setInterval> | null = null

function startPollingIfNeeded() {
  const hasProcessing = documents.value.some(d => d.status === 'PROCESSING')
  if (hasProcessing && !pollTimer) {
    pollTimer = setInterval(async () => {
      const kbId = route.params.kbId as string
      try {
        const resp = await listDocuments(kbId)
        documents.value = resp.data
        // Stop polling when no more PROCESSING docs
        if (!resp.data.some(d => d.status === 'PROCESSING')) {
          stopPolling()
        }
      } catch { /* ignore */ }
    }, 3000)
  } else if (!hasProcessing && pollTimer) {
    stopPolling()
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

watch(documents, startPollingIfNeeded, { deep: true })
onUnmounted(stopPolling)

onMounted(async () => {
  const kbId = route.params.kbId as string
  const [kbResp, docsResp] = await Promise.all([
    getKnowledgeBase(kbId),
    listDocuments(kbId),
  ])
  kb.value = kbResp.data
  documents.value = docsResp.data
})
</script>

<style scoped>
.tab-bar {
  display: flex;
  gap: 0;
}
.tab-item {
  padding: 10px 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.tab-item:hover {
  color: #333;
}
.tab-item.active {
  color: #0073e6;
  font-weight: 600;
  border-bottom-color: #0073e6;
}
.clickable-row {
  cursor: pointer;
}
.tag-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: #e8f3ff;
  color: #0073e6;
  border: 1px solid #b3d4f7;
  white-space: nowrap;
}
.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  background: transparent;
  color: #aaa;
  cursor: pointer;
  border-radius: 4px;
  padding: 0;
  transition: background 0.15s, color 0.15s;
}
.btn-icon:hover {
  background: #f0f0f0;
  color: #555;
}
.tag-filter {
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
.tag-filter:hover {
  background: #cfe4fc;
}
.tag-filter-active {
  background: #0073e6;
  color: #fff;
  border-color: #0073e6;
}

/* Chat */
.chat-container {
  flex: 1;
  overflow-y: auto;
  border: 1px solid #e5e5e5;
  border-radius: 10px 10px 0 0;
  padding: 16px;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 0;
}
.chat-message-row {
  display: flex;
}
.chat-message-row.user {
  justify-content: flex-end;
}
.chat-message-row.assistant,
.chat-message-row.loading {
  justify-content: flex-start;
}
.chat-bubble {
  max-width: 88%;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.5;
}
.user-bubble {
  background: #0073e6;
  color: #fff;
  border-bottom-right-radius: 3px;
}
.assistant-bubble {
  background: #fff;
  border: 1px solid #e5e5e5;
  border-bottom-left-radius: 3px;
  width: 100%;
  max-width: 100%;
}
.loading-bubble {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 12px 16px;
}
.loading-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #bbb;
  animation: dot-bounce 1.2s infinite ease-in-out;
}
.loading-dot:nth-child(2) { animation-delay: 0.2s; }
.loading-dot:nth-child(3) { animation-delay: 0.4s; }
@keyframes dot-bounce {
  0%, 80%, 100% { transform: translateY(0); opacity: 0.4; }
  40% { transform: translateY(-5px); opacity: 1; }
}
.result-card {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fafafa;
}
.result-card:last-child {
  margin-bottom: 0;
}
.chat-input-row {
  display: flex;
  gap: 8px;
  border: 1px solid #e5e5e5;
  border-top: none;
  border-radius: 0 0 10px 10px;
  padding: 10px 12px;
  background: #fff;
}
.chat-input {
  flex: 1;
  border-radius: 6px;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: #fff;
  border-radius: 10px;
  width: 420px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px 12px;
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid #f0f0f0;
}
.modal-body {
  padding: 16px 20px;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px 16px;
  border-top: 1px solid #f0f0f0;
}
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
