<template>
  <div class="page-container">
    <!-- Back link -->
    <div style="margin-bottom: 16px;">
      <router-link to="/datalake/datasets" class="back-link">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
        返回数据集列表
      </router-link>
    </div>

    <div v-if="loading && !dataset" style="text-align: center; padding: 40px; color: #999;">加载中...</div>

    <template v-if="dataset">
      <!-- Header -->
      <div class="detail-header">
        <div>
          <div style="display: flex; align-items: center; gap: 10px; margin-bottom: 4px;">
            <h1 class="page-title" style="margin: 0;">{{ dataset.name }}</h1>
            <span class="status-badge" :class="'badge-' + statusColor(dataset.status)">
              {{ statusText(dataset.status) }}
            </span>
          </div>
          <span class="job-id-mono">{{ dataset.id }}</span>
        </div>
        <div class="detail-header-actions">
          <a
            v-if="dataset.status === 'READY' && dataset.download_url"
            :href="dataset.download_url"
            target="_blank"
            class="btn btn-primary"
          >下载数据集</a>
          <button
            class="btn btn-default"
            style="color: #e6393d; border-color: #e6393d;"
            @click="handleDelete"
          >删除</button>
        </div>
      </div>

      <!-- Info grid -->
      <div class="info-grid">
        <div class="info-card">
          <div class="info-label">来源类型</div>
          <div class="info-value">{{ sourceLabel(dataset.source_type) }}</div>
        </div>
        <div class="info-card" v-if="dataset.database_name">
          <div class="info-label">数据库</div>
          <div class="info-value">{{ dataset.database_name }}</div>
        </div>
        <div class="info-card">
          <div class="info-label">行数</div>
          <div class="info-value">{{ dataset.row_count != null ? dataset.row_count.toLocaleString() : '-' }}</div>
        </div>
        <div class="info-card">
          <div class="info-label">文件大小</div>
          <div class="info-value">{{ dataset.file_size != null ? formatSize(dataset.file_size) : '-' }}</div>
        </div>
        <div class="info-card">
          <div class="info-label">创建时间</div>
          <div class="info-value">{{ formatTime(dataset.created_at) }}</div>
        </div>
        <div class="info-card">
          <div class="info-label">更新时间</div>
          <div class="info-value">{{ formatTime(dataset.updated_at) }}</div>
        </div>
      </div>

      <!-- Exporting progress -->
      <div v-if="dataset.status === 'EXPORTING'" class="section">
        <div class="exporting-banner">
          <span class="pulse-dot"></span>
          导出中，请稍候...
        </div>
      </div>

      <!-- Error section -->
      <div v-if="dataset.status === 'FAILED' && dataset.error" class="error-banner">
        <strong>错误信息：</strong>{{ dataset.error }}
      </div>

      <!-- Schema -->
      <div v-if="dataset.schema && dataset.schema.length > 0" class="section">
        <h3 class="section-title" style="margin-bottom: 12px;">Schema</h3>
        <table class="data-table" style="width: 100%;">
          <thead>
            <tr>
              <th style="width: 40px;">#</th>
              <th>列名</th>
              <th>类型</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(col, i) in dataset.schema" :key="col.name">
              <td style="color: #999;">{{ i + 1 }}</td>
              <td style="font-family: monospace;">{{ col.name }}</td>
              <td style="font-family: monospace; color: #666;">{{ col.type }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Code snippets -->
      <div v-if="dataset.status === 'READY' && dataset.code_snippets" class="section">
        <h3 class="section-title" style="margin-bottom: 12px;">使用示例</h3>
        <div class="snippet-tabs">
          <button
            v-for="tab in snippetTabs"
            :key="tab.key"
            class="snippet-tab"
            :class="{ active: activeTab === tab.key }"
            @click="activeTab = tab.key"
          >{{ tab.label }}</button>
        </div>
        <div class="snippet-body">
          <div class="snippet-toolbar">
            <span class="snippet-lang">{{ activeTabLabel }}</span>
            <button class="copy-btn" @click="copySnippet">
              <svg v-if="!copied" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2"/>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
              </svg>
              <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="20 6 9 17 4 12"/>
              </svg>
              {{ copied ? '已复制' : '复制' }}
            </button>
          </div>
          <pre class="snippet-code">{{ activeSnippet }}</pre>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import client from '../../api/client'

interface CodeSnippets {
  pandas: string
  ray: string
  duckdb: string
}

interface Dataset {
  id: string
  name: string
  description: string
  source_type: 'DB_EXPORT' | 'JOB_OUTPUT'
  database_id: string
  database_name: string
  status: 'DRAFT' | 'EXPORTING' | 'READY' | 'FAILED'
  row_count: number | null
  file_size: number | null
  obs_path: string
  download_url: string
  error: string
  created_at: string
  updated_at: string
  code_snippets?: CodeSnippets
  schema?: { name: string; type: string }[]
}

const route = useRoute()
const router = useRouter()
const id = route.params.id as string

const dataset = ref<Dataset | null>(null)
const loading = ref(false)
const activeTab = ref<'pandas' | 'ray' | 'duckdb'>('pandas')
const copied = ref(false)

let pollTimer: ReturnType<typeof setInterval> | null = null
let copyTimer: ReturnType<typeof setTimeout> | null = null

const snippetTabs = [
  { key: 'pandas' as const, label: 'Pandas' },
  { key: 'ray' as const, label: 'Ray' },
  { key: 'duckdb' as const, label: 'DuckDB' },
]

const activeTabLabel = computed(() => snippetTabs.find(t => t.key === activeTab.value)?.label ?? '')

const activeSnippet = computed(() => {
  if (!dataset.value?.code_snippets) return ''
  return dataset.value.code_snippets[activeTab.value] ?? ''
})

function statusColor(status: string) {
  const map: Record<string, string> = {
    DRAFT: 'gray',
    EXPORTING: 'blue',
    READY: 'green',
    FAILED: 'red',
  }
  return map[status] || 'gray'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    EXPORTING: '导出中',
    READY: '就绪',
    FAILED: '失败',
  }
  return map[status] || status
}

function sourceLabel(sourceType: string) {
  if (sourceType === 'DB_EXPORT') return '数据库导出'
  if (sourceType === 'JOB_OUTPUT') return '作业产出'
  return sourceType || '-'
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function formatTime(t: string | null) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function loadDataset() {
  loading.value = true
  try {
    const res = await client.get(`/datasets/${id}`)
    dataset.value = res.data
  } catch (e: any) {
    if (e.response?.status === 404) {
      alert('数据集不存在')
      router.push('/datalake/datasets')
    }
  } finally {
    loading.value = false
  }
}

async function handleDelete() {
  if (!confirm(`确认删除数据集"${dataset.value?.name}"？此操作不可撤销。`)) return
  try {
    await client.delete(`/datasets/${id}`)
    router.push('/datalake/datasets')
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function copySnippet() {
  if (!activeSnippet.value) return
  try {
    await navigator.clipboard.writeText(activeSnippet.value)
    copied.value = true
    if (copyTimer) clearTimeout(copyTimer)
    copyTimer = setTimeout(() => { copied.value = false }, 2000)
  } catch {
    // fallback: do nothing
  }
}

onMounted(() => {
  loadDataset()
  pollTimer = setInterval(() => {
    if (dataset.value?.status === 'EXPORTING') {
      loadDataset()
    }
  }, 5000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  if (copyTimer) clearTimeout(copyTimer)
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
  color: #0073e6;
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
  align-items: center;
}

.job-id-mono {
  font-family: monospace;
  font-size: 12px;
  color: #bbb;
}

.status-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.badge-gray { background: #f0f0f0; color: #888; }
.badge-blue { background: #e8f3ff; color: #0073e6; }
.badge-green { background: #f6ffed; color: #389e0d; }
.badge-red { background: #fff2f0; color: #cf1322; }

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 24px;
}

.info-card {
  padding: 12px 16px;
  background: #fafbfc;
  border: 1px solid #eee;
  border-radius: 6px;
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

.section {
  margin-bottom: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #191919;
  margin: 0;
}

.exporting-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #e8f3ff;
  border: 1px solid #b3d4f7;
  border-radius: 6px;
  color: #0073e6;
  font-size: 14px;
}

.pulse-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #1890ff;
  animation: pulse 1.5s ease-in-out infinite;
  flex-shrink: 0;
}

@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

.error-banner {
  padding: 12px 16px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 6px;
  color: #a8071a;
  font-size: 13px;
  margin-bottom: 24px;
  word-break: break-all;
}

.snippet-tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid #e5e5e5;
  margin-bottom: 0;
}

.snippet-tab {
  padding: 7px 16px;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  font-size: 13px;
  color: #666;
  transition: all 0.15s;
}

.snippet-tab:hover {
  color: #333;
}

.snippet-tab.active {
  color: #0073e6;
  border-bottom-color: #0073e6;
  font-weight: 500;
}

.snippet-body {
  background: #1e1e1e;
  border-radius: 0 0 6px 6px;
}

.snippet-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  border-bottom: 1px solid #2d2d2d;
}

.snippet-lang {
  font-size: 12px;
  color: #858585;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: 1px solid #444;
  border-radius: 4px;
  color: #aaa;
  font-size: 12px;
  padding: 3px 10px;
  cursor: pointer;
  transition: all 0.15s;
}

.copy-btn:hover {
  border-color: #888;
  color: #ddd;
}

.snippet-code {
  color: #d4d4d4;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
  padding: 16px;
  white-space: pre-wrap;
  word-break: break-all;
  overflow-x: auto;
}
</style>
