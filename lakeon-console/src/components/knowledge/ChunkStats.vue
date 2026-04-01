<template>
  <div>
    <!-- Loading state -->
    <div v-if="loading" class="empty-state" style="margin-top: 48px;">加载中...</div>

    <template v-else>
      <!-- Stats cards -->
      <div class="stats-row">
        <div class="stat-card">
          <div class="stat-label">总切片数</div>
          <div class="stat-value blue">{{ aggregated.total_chunks }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">平均字数</div>
          <div class="stat-value green">{{ aggregated.avg_char_count }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">异常切片</div>
          <div class="stat-value orange">{{ aggregated.anomaly_count }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">疑似重复</div>
          <div class="stat-value red">{{ aggregated.duplicate_count }}</div>
        </div>
      </div>

      <!-- Threshold display -->
      <div class="threshold-bar">
        <span style="color: #fa8c16;">&#9888; 过短 &lt; 80 字</span>
        <span style="color: #fa8c16;">&#9888; 过长 &gt; 800 字</span>
        <span style="color: #e6393d;">&#9888; 疑似重复 &gt; 92% 相似度</span>
      </div>

      <!-- Histogram -->
      <div v-if="aggregated.length_distribution.length > 0" class="section-card" style="margin-top: 20px;">
        <div class="section-header">长度分布</div>
        <div style="padding: 16px;">
          <div class="histogram">
            <div
              v-for="bucket in aggregated.length_distribution"
              :key="bucket.bucket"
              class="histogram-col"
            >
              <div class="histogram-bar-wrap">
                <div
                  class="histogram-bar"
                  :style="{ height: barHeight(bucket.count) + 'px' }"
                  :title="bucket.count + ' 个'"
                ></div>
              </div>
              <div class="histogram-label">{{ bucket.bucket }}</div>
            </div>
          </div>
          <div style="font-size: 11px; color: #999; margin-top: 4px; text-align: center;">字数区间</div>
        </div>
      </div>

      <!-- Filter bar -->
      <div class="filter-bar">
        <select v-model="filterDocId" class="form-select" style="width: 220px;">
          <option value="">全部文档</option>
          <option v-for="doc in documents" :key="doc.id" :value="doc.id">{{ doc.filename }}</option>
        </select>
        <select v-model="filterStatus" class="form-select" style="width: 140px;">
          <option value="">全部状态</option>
          <option value="short">过短</option>
          <option value="long">过长</option>
          <option value="duplicate">疑似重复</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadChunks(true)">筛选</button>
      </div>

      <!-- Chunk table -->
      <div v-if="chunks.length > 0" class="table-wrapper" style="margin-top: 12px;">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 60px;">#</th>
              <th>文档</th>
              <th>内容预览</th>
              <th style="width: 80px;">字数</th>
              <th style="width: 100px;">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="chunk in chunks"
              :key="chunk.id"
              class="clickable-row"
              @click="goToChunk(chunk)"
            >
              <td style="color: #999;">{{ chunk.chunk_index }}</td>
              <td style="max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                {{ docNameMap[chunk.document_id] || chunk.document_id }}
              </td>
              <td style="max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #444;">
                {{ chunk.content }}
              </td>
              <td>{{ chunk.char_count }}</td>
              <td>
                <span v-if="chunk.char_count < 80" class="status-tag" style="background: #fff3e0; color: #fa8c16; font-size: 11px;">过短</span>
                <span v-else-if="chunk.char_count > 800" class="status-tag" style="background: #fff3e0; color: #fa8c16; font-size: 11px;">过长</span>
                <span v-else class="status-tag" style="background: #f6ffed; color: #52c41a; font-size: 11px;">正常</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="empty-state" style="margin-top: 32px; text-align: center; color: #999;">
        暂无切片数据
      </div>

      <!-- Pagination -->
      <div v-if="total > limit" class="pagination-bar">
        <button class="btn btn-default btn-small" :disabled="offset === 0" @click="prevPage">上一页</button>
        <span style="font-size: 13px; color: #666;">第 {{ Math.floor(offset / limit) + 1 }} / {{ Math.ceil(total / limit) }} 页，共 {{ total }} 条</span>
        <button class="btn btn-default btn-small" :disabled="offset + limit >= total" @click="nextPage">下一页</button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  listDocuments,
  getChunkStats,
  listKbChunks,
  type Document,
  type Chunk,
  type ChunkStats,
} from '../../api/knowledge'

const props = defineProps<{ kbId: string }>()
const router = useRouter()

const loading = ref(true)
const documents = ref<Document[]>([])
const chunks = ref<Chunk[]>([])
const total = ref(0)
const offset = ref(0)
const limit = 50

const filterDocId = ref('')
const filterStatus = ref('')

// Aggregated stats across all documents
const aggregated = ref<{
  total_chunks: number
  avg_char_count: number
  anomaly_count: number
  duplicate_count: number
  length_distribution: { bucket: number; count: number }[]
}>({
  total_chunks: 0,
  avg_char_count: 0,
  anomaly_count: 0,
  duplicate_count: 0,
  length_distribution: [],
})

// Map document_id → filename
const docNameMap = computed<Record<string, string>>(() => {
  const m: Record<string, string> = {}
  for (const d of documents.value) m[d.id] = d.filename
  return m
})

function barHeight(count: number): number {
  const maxCount = Math.max(...aggregated.value.length_distribution.map(b => b.count), 1)
  return Math.max(4, Math.round((count / maxCount) * 80))
}

async function loadStats() {
  const docs = documents.value.filter(d => d.status === 'READY' && (d.chunks_count ?? 0) > 0)
  if (docs.length === 0) return

  const results = await Promise.allSettled(
    docs.map(d => getChunkStats(props.kbId, d.id))
  )

  let totalChunks = 0
  let totalCharSum = 0
  let totalAnomalies = 0
  let totalDuplicates = 0
  const bucketMap: Record<number, number> = {}

  for (const r of results) {
    if (r.status !== 'fulfilled') continue
    const s: ChunkStats = r.value.data
    totalChunks += s.total_chunks
    totalCharSum += s.avg_char_count * s.total_chunks
    totalAnomalies += s.anomaly_count
    totalDuplicates += s.duplicate_count
    for (const b of s.length_distribution) {
      bucketMap[b.bucket] = (bucketMap[b.bucket] ?? 0) + b.count
    }
  }

  const buckets = Object.entries(bucketMap)
    .map(([bucket, count]) => ({ bucket: Number(bucket), count }))
    .sort((a, b) => a.bucket - b.bucket)

  aggregated.value = {
    total_chunks: totalChunks,
    avg_char_count: totalChunks > 0 ? Math.round(totalCharSum / totalChunks) : 0,
    anomaly_count: totalAnomalies,
    duplicate_count: totalDuplicates,
    length_distribution: buckets,
  }
}

async function loadChunks(resetPage = false) {
  if (resetPage) offset.value = 0
  const params: Record<string, any> = { offset: offset.value, limit }
  if (filterDocId.value) params.doc_id = filterDocId.value
  if (filterStatus.value) params.status = filterStatus.value
  const resp = await listKbChunks(props.kbId, params)
  chunks.value = resp.data.chunks
  total.value = resp.data.total
}

function prevPage() {
  offset.value = Math.max(0, offset.value - limit)
  loadChunks()
}

function nextPage() {
  offset.value = offset.value + limit
  loadChunks()
}

function goToChunk(chunk: Chunk) {
  router.push({
    name: 'DocumentDetail',
    params: { kbId: props.kbId, docId: chunk.document_id },
    query: { chunkIndex: String(chunk.chunk_index) },
  })
}

onMounted(async () => {
  loading.value = true
  try {
    const resp = await listDocuments(props.kbId, { page_size: 200 })
    documents.value = resp.data.documents
    await Promise.all([loadStats(), loadChunks()])
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.stats-row {
  display: flex;
  gap: 16px;
  margin-top: 24px;
}

.stat-card {
  flex: 1;
  background: #fff;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  padding: 20px 24px;
}

.stat-label {
  font-size: 13px;
  color: #999;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
}

.stat-value.blue   { color: #9a5b25; }
.stat-value.green  { color: #52c41a; }
.stat-value.orange { color: #fa8c16; }
.stat-value.red    { color: #e6393d; }

.threshold-bar {
  display: flex;
  gap: 20px;
  margin-top: 16px;
  font-size: 12px;
  color: #666;
}

.histogram {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 100px;
}

.histogram-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
}

.histogram-bar-wrap {
  display: flex;
  align-items: flex-end;
  height: 80px;
}

.histogram-bar {
  width: 100%;
  min-width: 12px;
  background: #9a5b25;
  border-radius: 3px 3px 0 0;
  opacity: 0.75;
  transition: opacity 0.15s;
  cursor: default;
}

.histogram-bar:hover {
  opacity: 1;
}

.histogram-label {
  font-size: 10px;
  color: #999;
  margin-top: 4px;
  white-space: nowrap;
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
}

.form-select {
  height: 32px;
  padding: 0 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #333;
  background: #fff;
  outline: none;
  cursor: pointer;
}

.form-select:focus {
  border-color: #c67d3a;
}

.pagination-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  justify-content: center;
}

.clickable-row {
  cursor: pointer;
}

.status-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 11px;
}
</style>
