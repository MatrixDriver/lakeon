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

      <div style="margin-bottom: 16px;">
        <label class="btn btn-primary" style="cursor: pointer;">
          上传文档
          <input type="file" accept=".pdf,.docx,.md,.markdown" multiple style="display: none;" @change="handleUpload" />
        </label>
        <span style="color: #999; font-size: 13px; margin-left: 12px;">支持 PDF、DOCX、Markdown</span>
      </div>

      <div v-if="documents.length > 0" class="table-wrapper">
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
            <tr v-for="doc in documents" :key="doc.id" class="clickable-row" @click="router.push({ name: 'DocumentDetail', params: { kbId: route.params.kbId, docId: doc.id } })">
              <td style="font-weight: 500;">{{ doc.filename }}</td>
              <td><span class="tag-blue" style="font-size: 11px; padding: 1px 6px; border-radius: 3px;">{{ doc.format }}</span></td>
              <td style="color: #666;">{{ formatSize(doc.size_bytes) }}</td>
              <td>{{ doc.chunks_count ?? '-' }}</td>
              <td>
                <div style="display: flex; align-items: center; gap: 6px;">
                  <span class="status-dot" :style="{ background: docStatusColor(doc.status) }"></span>
                  <span>{{ docStatusText(doc.status) }}</span>
                  <span v-if="doc.status === 'PROCESSING' && doc.progress" style="color: #999; font-size: 12px;">
                    {{ Math.round(doc.progress * 100) }}%
                  </span>
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
    <div v-if="activeTab === 'search'" style="margin-top: 24px; max-width: 720px;">
      <div style="display: flex; gap: 8px;">
        <input v-model="searchQuery" class="form-input" placeholder="在当前知识库中搜索..." style="flex: 1;" @keyup.enter="handleSearch" />
        <button class="btn btn-primary" @click="handleSearch" :disabled="!searchQuery.trim()">搜索</button>
      </div>
      <p style="color: #999; font-size: 12px; margin-top: 6px;">语义搜索 + 关键词搜索（pgvector + BM25 + RRF 融合）</p>

      <div v-if="searchResults.length > 0" style="margin-top: 20px;">
        <div v-for="(r, i) in searchResults" :key="i"
             style="border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 12px;">
          <div style="font-size: 14px; line-height: 1.6; color: #333; white-space: pre-wrap;">{{ r.content }}</div>
          <div style="margin-top: 8px; font-size: 12px; color: #999; display: flex; gap: 12px;">
            <span>来源: {{ r.metadata?.filename }}</span>
            <span v-if="r.metadata?.section">章节: {{ r.metadata.section }}</span>
            <span>得分: {{ r.score?.toFixed(3) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getKnowledgeBase, listDocuments, getUploadUrl, processDocument, deleteDocument, searchKnowledge, type KnowledgeBase as KBType, type Document, type SearchResult } from '../../api/knowledge'
import ChunkStats from '../../components/knowledge/ChunkStats.vue'

const route = useRoute()
const router = useRouter()

const kb = ref<KBType | null>(null)
const documents = ref<Document[]>([])
const activeTab = ref('documents')
const searchQuery = ref('')
const searchResults = ref<SearchResult[]>([])

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

async function loadDocuments() {
  const kbId = route.params.kbId as string
  const resp = await listDocuments(kbId)
  documents.value = resp.data
}

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const kbId = route.params.kbId as string
  for (const file of Array.from(input.files)) {
    const urlResp = await getUploadUrl(kbId, file.name)
    const { document_id, upload_url } = urlResp.data
    await fetch(upload_url, { method: 'PUT', body: file })
    await processDocument(document_id)
  }
  await loadDocuments()
  input.value = ''
}

async function handleDeleteDoc(doc: Document) {
  if (!confirm(`确认删除文档"${doc.filename}"？`)) return
  await deleteDocument(doc.id)
  await loadDocuments()
}

async function handleSearch() {
  if (!searchQuery.value.trim()) return
  const kbId = route.params.kbId as string
  const resp = await searchKnowledge(kbId, searchQuery.value, 5)
  searchResults.value = resp.data.results
}

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
</style>
