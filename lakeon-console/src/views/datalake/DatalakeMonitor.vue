<template>
  <div class="page-container">
    <h2 class="page-title">镜像监控</h2>
    <p class="page-desc">查看数据湖预置镜像的大小、节点池和启动速度统计</p>

    <div v-if="loading" class="loading-text">加载中...</div>
    <div v-else-if="error" class="error-text">{{ error }}</div>

    <template v-else>
      <!-- Summary cards -->
      <div class="summary-cards">
        <div class="summary-card">
          <div class="summary-value">{{ images.length }}</div>
          <div class="summary-label">预置镜像</div>
        </div>
        <div class="summary-card">
          <div class="summary-value">{{ totalSessions }}</div>
          <div class="summary-label">历史会话数</div>
        </div>
        <div class="summary-card">
          <div class="summary-value">{{ nodeSelector }}</div>
          <div class="summary-label">节点池</div>
        </div>
      </div>

      <!-- Image table -->
      <table class="image-table">
        <thead>
          <tr>
            <th>镜像名称</th>
            <th>完整地址</th>
            <th style="text-align:right">镜像大小</th>
            <th style="text-align:right">会话数</th>
            <th style="text-align:right">平均启动</th>
            <th style="text-align:right">最快</th>
            <th style="text-align:right">最慢</th>
            <th>节点池</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="img in images" :key="img.key">
            <td>
              <span class="image-key">{{ img.key }}</span>
            </td>
            <td class="image-addr">{{ img.image }}</td>
            <td style="text-align:right">
              <span v-if="img.size_bytes" class="mono">{{ formatSize(img.size_bytes) }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td style="text-align:right" class="mono">{{ img.session_count }}</td>
            <td style="text-align:right">
              <span v-if="img.avg_startup_ms" class="mono" :class="startupClass(img.avg_startup_ms)">
                {{ formatMs(img.avg_startup_ms) }}
              </span>
              <span v-else class="text-muted">—</span>
            </td>
            <td style="text-align:right">
              <span v-if="img.min_startup_ms" class="mono">{{ formatMs(img.min_startup_ms) }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td style="text-align:right">
              <span v-if="img.max_startup_ms" class="mono">{{ formatMs(img.max_startup_ms) }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td>
              <span class="pool-badge">{{ img.node_pool }}</span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Startup speed bar chart -->
      <div v-if="imagesWithStats.length > 0" class="chart-section">
        <h3 class="section-title">启动速度对比</h3>
        <div class="bar-chart">
          <div v-for="img in imagesWithStats" :key="img.key" class="bar-row">
            <span class="bar-label">{{ img.key }}</span>
            <div class="bar-track">
              <div class="bar-fill" :class="startupClass(img.avg_startup_ms)"
                   :style="{ width: barWidth(img.avg_startup_ms) + '%' }">
              </div>
              <span class="bar-value">{{ formatMs(img.avg_startup_ms) }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import client from '../../api/client'

interface ImageInfo {
  key: string
  image: string
  node_pool: string
  size_bytes: number | null
  session_count: number
  avg_startup_ms: number | null
  min_startup_ms: number | null
  max_startup_ms: number | null
}

const images = ref<ImageInfo[]>([])
const nodeSelector = ref('')
const loading = ref(true)
const error = ref('')

const totalSessions = computed(() => images.value.reduce((sum, img) => sum + img.session_count, 0))
const imagesWithStats = computed(() => images.value.filter(img => img.avg_startup_ms != null))
const maxStartup = computed(() => Math.max(...imagesWithStats.value.map(img => img.avg_startup_ms!), 1))

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return (bytes / (1024 * 1024 * 1024)).toFixed(1) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(0) + ' MB'
  return (bytes / 1024).toFixed(0) + ' KB'
}

function formatMs(ms: number | null): string {
  if (!ms) return '-'
  if (ms >= 60000) return (ms / 60000).toFixed(1) + ' min'
  return (ms / 1000).toFixed(1) + 's'
}

function startupClass(ms: number | null): string {
  if (!ms) return 'speed-slow'
  if (ms < 10000) return 'speed-fast'
  if (ms < 30000) return 'speed-medium'
  return 'speed-slow'
}

function barWidth(ms: number | null): number {
  if (!ms) return 5
  return Math.max(5, (ms / maxStartup.value) * 100)
}

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await client.get('/admin/datalake/images')
    images.value = data.images || []
    if (data.node_selector) {
      nodeSelector.value = data.node_selector.key + '=' + data.node_selector.value
    }
  } catch (e: any) {
    error.value = '加载失败: ' + (e.response?.data?.message || e.message)
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.page-container { max-width: 1100px; }
.page-title { font-size: 20px; font-weight: 700; color: #1e293b; margin: 0 0 4px; }
.page-desc { font-size: 13px; color: #6b7280; margin: 0 0 24px; }

.summary-cards { display: flex; gap: 16px; margin-bottom: 24px; }
.summary-card { flex: 1; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px 20px; }
.summary-value { font-size: 24px; font-weight: 700; color: #1e293b; }
.summary-label { font-size: 12px; color: #6b7280; margin-top: 4px; }

.image-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 32px; }
.image-table th { text-align: left; padding: 10px 12px; color: #6b7280; font-weight: 600; font-size: 12px; border-bottom: 2px solid #e5e7eb; white-space: nowrap; }
.image-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
.image-table tbody tr:hover { background: #f8fafc; }

.image-key { font-weight: 600; color: #1e293b; font-family: monospace; font-size: 13px; }
.image-addr { font-family: monospace; font-size: 11px; color: #6b7280; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mono { font-family: monospace; }
.text-muted { color: #cbd5e1; }

.pool-badge { display: inline-block; background: #eff6ff; color: #2563eb; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-family: monospace; white-space: nowrap; }

.speed-fast { color: #16a34a; }
.speed-medium { color: #d97706; }
.speed-slow { color: #dc2626; }

.chart-section { margin-top: 8px; }
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin: 0 0 16px; }

.bar-chart { display: flex; flex-direction: column; gap: 10px; }
.bar-row { display: flex; align-items: center; gap: 12px; }
.bar-label { width: 110px; font-size: 13px; font-weight: 500; color: #374151; font-family: monospace; text-align: right; flex-shrink: 0; }
.bar-track { flex: 1; height: 24px; background: #f1f5f9; border-radius: 4px; position: relative; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; }
.bar-fill.speed-fast { background: #bbf7d0; }
.bar-fill.speed-medium { background: #fde68a; }
.bar-fill.speed-slow { background: #fecaca; }
.bar-value { position: absolute; right: 8px; top: 50%; transform: translateY(-50%); font-size: 12px; font-weight: 600; color: #374151; font-family: monospace; }

.loading-text { color: #6b7280; font-size: 14px; padding: 40px 0; text-align: center; }
.error-text { color: #dc2626; font-size: 14px; padding: 40px 0; text-align: center; }
</style>
