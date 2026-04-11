<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">应用指标</h1>
      <span class="auto-refresh-hint">每 30 秒自动刷新</span>
    </div>

    <!-- 用户体验指标 -->
    <div class="section-card">
      <div class="section-header"><h3>用户体验</h3></div>
      <div class="metric-cards">
        <div class="metric-card">
          <div class="metric-value" :class="wakeupColor(metrics.compute.cold_wake_avg_ms)">
            {{ metrics.compute.cold_wake_avg_ms || '-' }}<span class="metric-unit" v-if="metrics.compute.cold_wake_avg_ms">ms</span>
          </div>
          <div class="metric-label">Cold Wake 均值</div>
          <div class="metric-sub">{{ metrics.compute.cold_wake_count }} 次</div>
        </div>
        <div class="metric-card">
          <div class="metric-value text-warm">
            {{ metrics.compute.warm_wake_avg_ms || '-' }}<span class="metric-unit" v-if="metrics.compute.warm_wake_avg_ms">ms</span>
          </div>
          <div class="metric-label">Warm Wake 均值</div>
          <div class="metric-sub">{{ metrics.compute.warm_wake_count }} 次</div>
        </div>
        <div class="metric-card">
          <div class="metric-value" :class="{ 'text-danger': metrics.compute.wakeup_failures > 0 }">
            {{ metrics.compute.wakeup_failures }}
          </div>
          <div class="metric-label">唤醒失败</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ metrics.api.p50_ms }}<span class="metric-unit">ms</span></div>
          <div class="metric-label">API P50 延迟</div>
        </div>
        <div class="metric-card">
          <div class="metric-value" :class="{ 'text-warning': metrics.api.p95_ms > 500 }">
            {{ metrics.api.p95_ms }}<span class="metric-unit">ms</span>
          </div>
          <div class="metric-label">API P95 延迟</div>
        </div>
        <div class="metric-card">
          <div class="metric-value" :class="{ 'text-warning': metrics.api.p99_ms > 1000 }">
            {{ metrics.api.p99_ms }}<span class="metric-unit">ms</span>
          </div>
          <div class="metric-label">API P99 延迟</div>
        </div>
      </div>
    </div>

    <!-- 平台规模 -->
    <div class="section-card">
      <div class="section-header"><h3>平台规模</h3></div>
      <div class="metric-cards">
        <div class="metric-card">
          <div class="metric-value">{{ metrics.databases.total }}</div>
          <div class="metric-label">数据库总数</div>
        </div>
        <div class="metric-card">
          <div class="metric-value dot-green-text">{{ metrics.databases.running }}</div>
          <div class="metric-label">运行中</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ metrics.databases.suspended }}</div>
          <div class="metric-label">已挂起</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ metrics.compute.active_pods }}</div>
          <div class="metric-label">活跃计算节点</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ metrics.api.request_rate_1m }}</div>
          <div class="metric-label">请求速率 (req/s)</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ metrics.storage.used_gb }}<span class="metric-unit">GB</span></div>
          <div class="metric-label">存储使用</div>
        </div>
      </div>
    </div>

    <!-- Pageserver 压力 -->
    <div class="section-card">
      <div class="section-header">
        <h3>Pageserver 存储引擎</h3>
        <span v-if="ps.pressure" class="pressure-badge" :class="'pressure-' + ps.pressure">{{ pressureLabel }}</span>
      </div>
      <div class="metric-cards">
        <div class="metric-card">
          <div class="metric-value" :class="{ 'text-danger': ps.pressure === 'high', 'text-warning': ps.pressure === 'medium' }">
            {{ ps.pressure || '-' }}
          </div>
          <div class="metric-label">缓存压力</div>
          <div class="metric-sub" v-if="ps.shard_recommended">建议分片</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ ps.cache?.evicted_layers ?? 0 }}</div>
          <div class="metric-label">淘汰 Layers</div>
          <div class="metric-sub">缓存 {{ formatBytes(ps.cache?.current_bytes) }} / {{ formatBytes(ps.cache?.max_bytes) }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-value" :class="{ 'text-warning': (ps.remote_reads?.ondemand_download_count ?? 0) > 50 }">
            {{ ps.remote_reads?.ondemand_download_count ?? 0 }}
          </div>
          <div class="metric-label">OBS 回源次数</div>
          <div class="metric-sub">{{ (ps.remote_reads?.ondemand_download_seconds ?? 0).toFixed(2) }}s 总耗时</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ ps.tenants?.active ?? 0 }}</div>
          <div class="metric-label">活跃租户</div>
          <div v-if="ps.tenants?.broken > 0" class="metric-sub text-danger">{{ ps.tenants.broken }} 异常</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ ps.memory?.rss_mb ?? 0 }}<span class="metric-unit">MB</span></div>
          <div class="metric-label">RSS 内存</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ ps.wal_redo?.redo_count ?? 0 }}</div>
          <div class="metric-label">WAL Redo</div>
          <div class="metric-sub">{{ (ps.wal_redo?.redo_seconds ?? 0).toFixed(2) }}s</div>
        </div>
      </div>
      <div v-if="ps.error" class="ps-error">{{ ps.error }}</div>
    </div>

    <!-- 系统资源 -->
    <div class="section-card">
      <div class="section-header">
        <h3>系统资源</h3>
        <button class="toggle-btn" @click="showSystem = !showSystem">{{ showSystem ? '收起' : '展开' }}</button>
      </div>
      <div v-if="showSystem">
        <div class="metric-cards">
          <div class="metric-card">
            <div class="metric-value">{{ metrics.jvm.heap_used_mb }}<span class="metric-unit">MB</span></div>
            <div class="metric-label">堆内存使用</div>
            <div class="metric-sub">/ {{ metrics.jvm.heap_max_mb }} MB</div>
          </div>
          <div class="metric-card">
            <div class="metric-value">{{ metrics.jvm.threads }}</div>
            <div class="metric-label">线程数</div>
          </div>
          <div class="metric-card">
            <div class="metric-value">{{ metrics.jvm.gc_pause_ms }}<span class="metric-unit">ms</span></div>
            <div class="metric-label">GC 暂停</div>
          </div>
        </div>

        <!-- Trend chart -->
        <div style="margin-top: 16px; padding: 0 16px 16px;">
          <canvas ref="chartRef" width="800" height="200"></canvas>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { adminApi } from '../../api/admin'

interface MetricsSummary {
  jvm: { heap_used_mb: number; heap_max_mb: number; threads: number; gc_pause_ms: number }
  api: { request_rate_1m: number; p50_ms: number; p95_ms: number; p99_ms: number }
  compute: { active_pods: number; wakeup_p50_ms: number; warm_wake_count: number; warm_wake_avg_ms: number; cold_wake_count: number; cold_wake_avg_ms: number; wakeup_failures: number }
  databases: { total: number; running: number; suspended: number }
  storage: { used_gb: number }
}

const defaultMetrics: MetricsSummary = {
  jvm: { heap_used_mb: 0, heap_max_mb: 0, threads: 0, gc_pause_ms: 0 },
  api: { request_rate_1m: 0, p50_ms: 0, p95_ms: 0, p99_ms: 0 },
  compute: { active_pods: 0, wakeup_p50_ms: 0, warm_wake_count: 0, warm_wake_avg_ms: 0, cold_wake_count: 0, cold_wake_avg_ms: 0, wakeup_failures: 0 },
  databases: { total: 0, running: 0, suspended: 0 },
  storage: { used_gb: 0 },
}

const metrics = reactive<MetricsSummary>({ ...defaultMetrics })
const ps = reactive<Record<string, any>>({})
const chartRef = ref<HTMLCanvasElement | null>(null)
const showSystem = ref(false)
let refreshTimer: number | null = null

const pressureLabel = computed(() => {
  const p = ps.pressure
  if (p === 'high') return '高压 — 建议分片'
  if (p === 'medium') return '中等'
  return '正常'
})

function formatBytes(bytes: number | undefined): string {
  if (!bytes) return '0'
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + 'KB'
  return (bytes / 1024 / 1024).toFixed(1) + 'MB'
}

const heapHistory: number[] = []
const timeLabels: string[] = []

function wakeupColor(ms: number): string {
  if (!ms) return ''
  if (ms < 5000) return 'text-warm'
  if (ms < 15000) return 'text-warning'
  return 'text-danger'
}

async function fetchMetrics() {
  try {
    const { data } = await adminApi.metricsSummary()
    Object.assign(metrics, data)

    const now = new Date()
    timeLabels.push(now.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }))
    heapHistory.push(data.jvm?.heap_used_mb ?? 0)

    if (timeLabels.length > 60) {
      timeLabels.shift()
      heapHistory.shift()
    }

    await nextTick()
    if (showSystem.value) renderChart()
  } catch (e) {
    console.error('Failed to load metrics', e)
  }
}

function renderChart() {
  const canvas = chartRef.value
  if (!canvas || heapHistory.length < 2) return

  const dpr = window.devicePixelRatio || 1
  const w = canvas.clientWidth
  const h = canvas.clientHeight
  canvas.width = w * dpr
  canvas.height = h * dpr
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.scale(dpr, dpr)

  const pad = { top: 20, right: 60, bottom: 30, left: 50 }
  const cw = w - pad.left - pad.right
  const ch = h - pad.top - pad.bottom

  ctx.clearRect(0, 0, w, h)

  ctx.strokeStyle = '#eee'
  ctx.lineWidth = 1
  for (let i = 0; i <= 4; i++) {
    const y = pad.top + (ch / 4) * i
    ctx.beginPath()
    ctx.moveTo(pad.left, y)
    ctx.lineTo(w - pad.right, y)
    ctx.stroke()
  }

  const maxHeap = Math.max(...heapHistory, 1)
  const n = heapHistory.length

  ctx.strokeStyle = '#c67d3a'
  ctx.lineWidth = 2
  ctx.beginPath()
  for (let i = 0; i < n; i++) {
    const x = pad.left + (cw / (n - 1)) * i
    const y = pad.top + ch - ((heapHistory[i] ?? 0) / maxHeap) * ch
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
  }
  ctx.stroke()

  ctx.fillStyle = '#999'
  ctx.font = '11px sans-serif'
  ctx.textAlign = 'right'
  for (let i = 0; i <= 4; i++) {
    const y = pad.top + (ch / 4) * i
    const val = Math.round(maxHeap - (maxHeap / 4) * i)
    ctx.fillText(val + ' MB', pad.left - 6, y + 4)
  }

  ctx.textAlign = 'center'
  for (let i = 0; i < n; i += Math.max(1, Math.floor(n / 6))) {
    const x = pad.left + (cw / (n - 1)) * i
    ctx.fillText(timeLabels[i] ?? '', x, h - 6)
  }

  ctx.fillStyle = '#c67d3a'
  ctx.fillRect(w - pad.right + 8, pad.top, 12, 3)
  ctx.fillStyle = '#333'
  ctx.textAlign = 'left'
  ctx.fillText('Heap', w - pad.right + 24, pad.top + 5)
}

async function fetchPageserverMetrics() {
  try {
    const { data } = await adminApi.pageserverMetrics()
    Object.assign(ps, data)
  } catch (e) {
    ps.error = 'Failed to load'
  }
}

onMounted(() => {
  fetchMetrics()
  fetchPageserverMetrics()
  refreshTimer = window.setInterval(() => { fetchMetrics(); fetchPageserverMetrics() }, 30000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
</script>

<style scoped>
.metric-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 16px;
  padding: 16px;
}
.metric-card {
  padding: 20px;
  text-align: center;
  background: #fafbfc;
  border-radius: 6px;
  border: 1px solid #ebebeb;
}
.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #2c3e50;
}
.metric-unit {
  font-size: 14px;
  font-weight: 400;
  color: #999;
  margin-left: 2px;
}
.metric-label {
  font-size: 13px;
  color: #64748b;
  margin-top: 4px;
}
.metric-sub {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}
.text-warning { color: var(--cs-warn); }
.text-danger { color: var(--cs-severe); }
.text-warm { color: #386b47; }
.dot-green-text { color: #386b47; }
.auto-refresh-hint {
  font-size: 12px;
  color: #999;
  font-weight: 400;
}
.toggle-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 2px 12px;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
}
.toggle-btn:hover {
  border-color: #c67d3a;
  color: #9a5b25;
}
canvas {
  width: 100%;
  height: 200px;
}
.pressure-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 10px;
}
.pressure-low { background: color-mix(in oklch, var(--c-success) 10%, #fff); color: #386b47; }
.pressure-medium { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: var(--cs-warn); }
.pressure-high { background: color-mix(in oklch, var(--cs-severe) 10%, #fff); color: var(--cs-severe); }
.ps-error {
  padding: 8px 16px;
  font-size: 12px;
  color: var(--cs-severe);
}
</style>
