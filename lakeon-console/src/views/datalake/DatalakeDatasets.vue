<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">数据集</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="$router.push('/datalake/datasets/new')">新建数据集</button>
      </div>
    </div>

    <!-- Status filter tabs -->
    <div class="status-tabs">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        class="status-tab"
        :class="{ active: statusFilter === tab.value }"
        @click="statusFilter = tab.value"
      >
        {{ tab.label }}
        <span v-if="tab.value && statusCounts[tab.value]" class="tab-count">{{ statusCounts[tab.value] }}</span>
      </button>
    </div>

    <!-- Dataset list table -->
    <TableToolbar v-model="dsSearch" placeholder="搜索数据集名称" :loading="loading" @refresh="fetchDatasets" style="margin-top: 16px;" />
    <div v-if="searchedDatasets.length > 0" class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>来源</th>
            <th>行数</th>
            <th>大小</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ds in searchedDatasets" :key="ds.id">
            <td>
              <router-link :to="`/datalake/datasets/${ds.id}`" class="dataset-name-link">
                {{ ds.name }}
              </router-link>
              <div class="dataset-id-hint">{{ ds.id }}</div>
            </td>
            <td style="color: #575d6c;">{{ sourceLabel(ds.sourceType) }}</td>
            <td style="color: #575d6c;">{{ ds.rowCount != null ? ds.rowCount.toLocaleString() : '-' }}</td>
            <td style="color: #575d6c;">{{ ds.sizeBytes != null ? formatSize(ds.sizeBytes) : '-' }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + statusColor(ds.status)"></span>
              {{ statusText(ds.status) }}
            </td>
            <td style="color: #999;">{{ formatTime(ds.createdAt) }}</td>
            <td>
              <button
                class="btn btn-text btn-small"
                style="color: #e6393d;"
                @click="handleDelete(ds.id, ds.name)"
              >删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-else-if="!loading" class="empty-state" style="margin-top: 64px;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M3 3h18v4H3zM3 10h18v4H3zM3 17h18v4H3z"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">
        {{ statusFilter ? '没有符合条件的数据集' : '还没有数据集' }}
      </p>
      <p style="color: #999; font-size: 13px;">从 DBay 数据库导出 Parquet 格式数据，或由作业产出</p>
    </div>

    <!-- Loading -->
    <div v-if="loading" style="text-align: center; padding: 40px; color: #999;">加载中...</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import TableToolbar from '../../components/TableToolbar.vue'
import client from '../../api/client'
interface Dataset {
  id: string
  name: string
  sourceType: string
  rowCount: number | null
  sizeBytes: number | null
  status: string
  createdAt: string
}

const datasets = ref<Dataset[]>([])
const loading = ref(false)
const dsSearch = ref('')
const statusFilter = ref<string>('')

const statusTabs: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'EXPORTING', label: '导出中' },
  { value: 'READY', label: '就绪' },
  { value: 'FAILED', label: '失败' },
]

const filteredDatasets = computed(() => {
  if (!statusFilter.value) return datasets.value
  return datasets.value.filter(d => d.status === statusFilter.value)
})

const searchedDatasets = computed(() => {
  if (!dsSearch.value) return filteredDatasets.value
  const q = dsSearch.value.toLowerCase()
  return filteredDatasets.value.filter(d => d.name.toLowerCase().includes(q))
})

const statusCounts = computed(() => {
  const counts: Record<string, number> = {}
  for (const d of datasets.value) {
    counts[d.status] = (counts[d.status] || 0) + 1
  }
  return counts
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

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function fetchDatasets() {
  loading.value = true
  try {
    const resp = await client.get('/datasets')
    datasets.value = resp.data?.data ?? resp.data ?? []
  } catch (e: any) {
    console.error('Failed to load datasets:', e)
  } finally {
    loading.value = false
  }
}

async function handleDelete(id: string, name: string) {
  if (!confirm(`确认删除数据集"${name}"？此操作不可撤销。`)) return
  try {
    await client.delete(`/datasets/${id}`)
    await fetchDatasets()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  fetchDatasets()
  pollTimer = setInterval(() => {
    const hasExporting = datasets.value.some(d => d.status === 'EXPORTING')
    if (hasExporting) fetchDatasets()
  }, 10000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.status-tabs {
  display: flex;
  gap: 4px;
  margin-top: 16px;
  border-bottom: 1px solid #e5e5e5;
}

.status-tab {
  padding: 8px 16px;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  transition: all 0.15s;
}

.status-tab:hover {
  color: #333;
}

.status-tab.active {
  color: #0073e6;
  border-bottom-color: #0073e6;
  font-weight: 500;
}

.tab-count {
  display: inline-block;
  min-width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 9px;
  background: #f0f0f0;
  font-size: 11px;
  margin-left: 4px;
  padding: 0 5px;
}

.status-tab.active .tab-count {
  background: #e8f3ff;
  color: #0073e6;
}

.dataset-name-link {
  color: #0073e6;
  text-decoration: none;
  font-weight: 500;
}

.dataset-name-link:hover {
  text-decoration: underline;
}

.dataset-id-hint {
  font-size: 11px;
  color: #bbb;
  margin-top: 2px;
  font-family: monospace;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.dot-green { background-color: #52c41a; }
.dot-blue { background-color: #1890ff; }
.dot-red { background-color: #e6393d; }
.dot-gray { background-color: #d9d9d9; }
</style>
