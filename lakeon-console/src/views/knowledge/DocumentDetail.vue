<template>
  <div class="page-container">
    <!-- Breadcrumb -->
    <div class="breadcrumb">
      <router-link to="/knowledge" class="breadcrumb-link">知识库</router-link>
      <span class="breadcrumb-sep">/</span>
      <router-link :to="`/knowledge/${kbId}`" class="breadcrumb-link">{{ kb?.name || '...' }}</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-item active">{{ doc?.filename || '...' }}</span>
    </div>

    <!-- Top bar -->
    <div class="page-header">
      <div>
        <h1 class="page-title">{{ doc?.filename || '加载中...' }}</h1>
        <div v-if="doc" class="doc-info">
          <span>{{ doc.format }}</span>
          <span>{{ formatSize(doc.size_bytes) }}</span>
          <span>{{ doc.chunks_count ?? 0 }} 个切片</span>
          <span>
            <span class="status-dot" :style="{ background: docStatusColor(doc.status) }"></span>
            {{ docStatusText(doc.status) }}
          </span>
        </div>
      </div>
      <div class="page-header-actions">
        <button class="btn btn-default btn-small" @click="showAddChunk = true">新增切片</button>
        <button class="btn btn-default btn-small" @click="handleRechunk">重新切片</button>
      </div>
    </div>

    <!-- Threshold legend -->
    <div class="threshold-bar">
      <span style="color: var(--cs-warn);">&#9888; 过短 &lt; 80 字</span>
      <span style="color: var(--cs-warn);">&#9888; 过长 &gt; 800 字</span>
      <span style="color: var(--cs-severe);">&#9888; 疑似重复 &gt; 92% 相似度</span>
    </div>

    <!-- Document summary -->
    <div v-if="!loading" style="margin-bottom: 16px;">
      <div class="section-card" style="max-width: 100%;">
        <div class="section-header" style="cursor: pointer; user-select: none; display: flex; align-items: center; justify-content: space-between;" @click="summaryExpanded = !summaryExpanded">
          <div style="display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0;">
            <span>摘要</span>
            <span v-if="!summaryExpanded && summary" style="font-size: 13px; color: #999; font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ summary.split('\n')[0] }}</span>
            <span v-if="!summaryExpanded && !summary && !summaryLoading" style="font-size: 13px; color: #ccc; font-weight: 400;">摘要尚未生成</span>
          </div>
          <span style="font-size: 12px; color: #999; flex-shrink: 0;">{{ summaryExpanded ? '收起' : '展开' }}</span>
        </div>
        <div v-if="summaryExpanded" style="padding: 16px; font-size: 14px; line-height: 1.8; color: #333; white-space: pre-wrap;">
          <span v-if="summaryLoading" style="color: #999;">加载中...</span>
          <span v-else-if="summary">{{ summary }}</span>
          <span v-else style="color: #999;">摘要尚未生成</span>
        </div>
      </div>
    </div>

    <!-- Main layout -->
    <div v-if="loading" class="empty-state">加载中...</div>
    <div v-else-if="chunks.length === 0" class="empty-state">暂无切片数据</div>
    <div v-else class="split-layout">
      <!-- Left panel: chunk list -->
      <div class="left-panel">
        <ChunkList
          :chunks="chunks"
          :selected-index="selectedChunkIndex"
          :stats="chunkStats"
          @select="selectChunk"
        />
      </div>

      <!-- Right panel: chunk content -->
      <div class="right-panel">
        <ChunkContent
          v-if="selectedChunk"
          :chunk="selectedChunk"
          :context="chunkContext"
          :kb-id="kbId"
          :doc-id="docId"
          @updated="onChunkUpdated"
          @deleted="onChunkDeleted"
        />
        <div v-else class="empty-state">选择左侧切片查看详情</div>
      </div>
    </div>

    <!-- Rechunk dialog -->
    <RechunkDialog
      :kb-id="kbId"
      :doc-id="docId"
      :old-stats="chunkStats"
      :visible="showRechunk"
      @close="showRechunk = false"
      @completed="onRechunkCompleted"
    />

    <!-- Add chunk dialog -->
    <div v-if="showAddChunk" class="dialog-overlay" @click.self="showAddChunk = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>新增切片</h3>
          <button class="dialog-close" @click="showAddChunk = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">插入位置（在第 N 个切片后）</label>
            <input v-model.number="newChunkAfterIndex" type="number" class="form-input" min="-1" :max="chunks.length - 1"
                   placeholder="输入切片序号，-1 表示插入到最前面" />
          </div>
          <div class="form-group">
            <label class="form-label">切片内容 <span class="required">*</span></label>
            <textarea v-model="newChunkContent" class="form-input" style="height: 160px; resize: vertical; padding: 8px 12px;"
                      placeholder="输入切片内容..."></textarea>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showAddChunk = false">取消</button>
          <button class="btn btn-primary" @click="handleAddChunk" :disabled="!newChunkContent.trim() || addingChunk">
            {{ addingChunk ? '添加中...' : '添加' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  getKnowledgeBase,
  getDocument,
  listChunks,
  getChunk,
  getChunkContext,
  getChunkStats,
  createChunk,
  getDocumentSummary,
  type KnowledgeBase as KBType,
  type Document,
  type Chunk,
  type ChunkContext as ChunkContextType,
  type ChunkStats,
} from '../../api/knowledge'
import ChunkList from '../../components/knowledge/ChunkList.vue'
import ChunkContent from '../../components/knowledge/ChunkContent.vue'
import RechunkDialog from '../../components/knowledge/RechunkDialog.vue'
import { formatSize } from '../../utils/format'

const route = useRoute()
const kbId = route.params.kbId as string
const docId = route.params.docId as string

const kb = ref<KBType | null>(null)
const doc = ref<Document | null>(null)
const chunks = ref<Chunk[]>([])
const chunkStats = ref<ChunkStats | null>(null)
const selectedChunkIndex = ref(-1)
const selectedChunk = ref<Chunk | null>(null)
const chunkContext = ref<ChunkContextType | null>(null)
const loading = ref(true)

// Rechunk dialog
const showRechunk = ref(false)

// Add chunk dialog
const showAddChunk = ref(false)
const newChunkContent = ref('')
const newChunkAfterIndex = ref(0)
const addingChunk = ref(false)

// Summary
const summary = ref<string | null>(null)
const summaryLoading = ref(false)
const summaryExpanded = ref(false)

async function loadSummary() {
  summaryLoading.value = true
  try {
    const resp = await getDocumentSummary(kbId, docId)
    summary.value = resp.data.content || null
  } catch {
    summary.value = null
  } finally {
    summaryLoading.value = false
  }
}

function docStatusColor(s: string) {
  if (s === 'READY') return '#386b47'
  if (s === 'PROCESSING') return '#2a4d6a'
  if (s === 'FAILED') return '#c6333a'
  return '#c2c6cc'
}

function docStatusText(s: string) {
  const map: Record<string, string> = { PENDING: '等待中', PROCESSING: '处理中', READY: '就绪', FAILED: '失败' }
  return map[s] || s
}


async function loadChunks() {
  // Load all chunks via pagination (API limits to 200 per page)
  const PAGE = 200
  let offset = 0
  const all: Chunk[] = []
  while (true) {
    const resp = await listChunks(kbId, docId, 0, offset, PAGE)
    all.push(...resp.data.chunks)
    if (resp.data.chunks.length < PAGE) break
    offset += PAGE
  }
  chunks.value = all
}

async function loadStats() {
  try {
    const resp = await getChunkStats(kbId, docId)
    chunkStats.value = resp.data
  } catch {
    // Stats may fail if compute is cold, ignore
  }
}

async function selectChunk(chunkIndex: number) {
  selectedChunkIndex.value = chunkIndex
  chunkContext.value = null

  try {
    const [chunkResp, ctxResp] = await Promise.all([
      getChunk(kbId, docId, chunkIndex),
      getChunkContext(kbId, docId, chunkIndex),
    ])
    selectedChunk.value = chunkResp.data
    chunkContext.value = ctxResp.data
  } catch (e: any) {
    console.error('Failed to load chunk:', e)
  }
}

async function onChunkUpdated() {
  await loadChunks()
  if (selectedChunkIndex.value >= 0) {
    await selectChunk(selectedChunkIndex.value)
  }
}

async function onChunkDeleted(_chunkIndex: number) {
  selectedChunk.value = null
  selectedChunkIndex.value = -1
  chunkContext.value = null
  await loadChunks()
  await loadStats()
  // Select the first chunk if available
  if (chunks.value.length > 0) {
    await selectChunk(chunks.value[0]!.chunk_index)
  }
}

async function handleAddChunk() {
  if (!newChunkContent.value.trim() || addingChunk.value) return
  addingChunk.value = true
  try {
    await createChunk(kbId, docId, newChunkContent.value, newChunkAfterIndex.value)
    showAddChunk.value = false
    newChunkContent.value = ''
    newChunkAfterIndex.value = 0
    await loadChunks()
    await loadStats()
  } catch (e: any) {
    alert('添加失败: ' + (e.response?.data?.error || e.message))
  } finally {
    addingChunk.value = false
  }
}

function handleRechunk() {
  showRechunk.value = true
}

async function onRechunkCompleted() {
  showRechunk.value = false
  await Promise.all([loadChunks(), loadStats()])
  // Reload document metadata (chunk count may have changed)
  try {
    const docResp = await getDocument(docId)
    doc.value = docResp.data
  } catch {
    // ignore
  }
  // Reselect first chunk
  selectedChunk.value = null
  selectedChunkIndex.value = -1
  chunkContext.value = null
  if (chunks.value.length > 0) {
    await selectChunk(chunks.value[0]!.chunk_index)
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const [kbResp, docResp] = await Promise.all([
      getKnowledgeBase(kbId),
      getDocument(docId),
    ])
    kb.value = kbResp.data
    doc.value = docResp.data

    // Load chunks, stats, and summary in parallel
    await Promise.all([loadChunks(), loadStats(), loadSummary()])

    // Auto-select first chunk
    if (chunks.value.length > 0) {
      await selectChunk(chunks.value[0]!.chunk_index)
    }
  } catch (e: any) {
    console.error('Failed to load document detail:', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.doc-info {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #64748b;
  margin-top: 6px;
}

.threshold-bar {
  display: flex;
  gap: 20px;
  font-size: 12px;
  padding: 8px 0 16px;
  border-bottom: 1px solid #ebebeb;
  margin-bottom: 16px;
}

.split-layout {
  display: flex;
  gap: 0;
  height: calc(100vh - 260px);
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}

.left-panel {
  width: 340px;
  min-width: 340px;
  border-right: 1px solid #e5e5e5;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.right-panel {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
