<template>
  <div class="perf-monitor">
    <div class="perf-header">
      <h3>性能监控</h3>
      <div class="perf-header-right">
        <span class="perf-label" :class="{ active: mode === 'realtime' }" @click="mode = 'realtime'">实时监控</span>
        <span class="perf-label" :class="{ active: mode === 'slowsql' }" @click="mode = 'slowsql'">慢SQL分析</span>
        <span class="perf-time-range">
          近{{ dataPoints.length > 0 ? Math.ceil(dataPoints.length * pollInterval / 60) : 0 }}分钟
        </span>
      </div>
    </div>

    <!-- Suspended / No Pod state -->
    <div v-if="!hasPod" class="perf-empty">
      <p>数据库未运行，暂无性能数据。唤醒后将自动开始采集。</p>
    </div>

    <!-- Realtime Monitoring -->
    <div v-else-if="mode === 'realtime'" class="perf-grid">
      <!-- CPU Card -->
      <div class="perf-card">
        <div class="perf-card-top">
          <div class="perf-card-info">
            <div class="perf-card-title">CPU</div>
            <div class="perf-card-meta">
              <span>当前使用率 <b>{{ cpuPercent }}%</b></span>
            </div>
            <div class="perf-card-value">
              {{ latestMetrics?.cpuUsage?.toFixed(2) || '0.00' }}<span class="perf-unit">/{{ latestMetrics?.cpuLimit || 1 }} Cores</span>
            </div>
            <div class="perf-card-sub">当前使用量/总量</div>
          </div>
          <div class="perf-card-chart">
            <SparkLine :data="cpuHistory" :width="180" :height="48" :max="100" color="#0073e6" />
          </div>
        </div>
      </div>

      <!-- Memory Card -->
      <div class="perf-card">
        <div class="perf-card-top">
          <div class="perf-card-info">
            <div class="perf-card-title">内存</div>
            <div class="perf-card-meta">
              <span>当前使用率 <b>{{ memPercent }}%</b></span>
            </div>
            <div class="perf-card-value">
              {{ memUsedDisplay }}<span class="perf-unit">/{{ memLimitDisplay }}</span>
            </div>
            <div class="perf-card-sub">当前使用量/总量</div>
          </div>
          <div class="perf-card-chart">
            <SparkLine :data="memHistory" :width="180" :height="48" :max="100" color="#0073e6" />
          </div>
        </div>
      </div>

      <!-- Slow SQL Card -->
      <div class="perf-card">
        <div class="perf-card-top">
          <div class="perf-card-info">
            <div class="perf-card-title">已执行3s的SQL数</div>
            <div class="perf-card-value" style="margin-top: 12px;">
              {{ latestMetrics?.slowQueries || 0 }}<span class="perf-unit"> 个</span>
            </div>
            <div class="perf-card-sub">当前值</div>
          </div>
          <div class="perf-card-chart">
            <SparkLine
              v-if="slowHistory.some(v => v > 0)"
              :data="slowHistory"
              :width="180"
              :height="48"
              color="#e37318"
            />
            <div v-else class="perf-no-data">
              <svg viewBox="0 0 64 48" width="64" height="48" fill="none" stroke="#d9d9d9" stroke-width="1.5">
                <rect x="16" y="16" width="32" height="24" rx="2" />
                <path d="M20 16V12a12 12 0 0 1 24 0v4" />
                <line x1="32" y1="26" x2="32" y2="32" />
              </svg>
              <span>暂无数据</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Connections Card -->
      <div class="perf-card">
        <div class="perf-card-top">
          <div class="perf-card-info">
            <div class="perf-card-title">连接数</div>
            <div class="perf-card-value" style="margin-top: 12px;">
              {{ latestMetrics?.activeConnections || 0 }}<span class="perf-unit"> 个</span>
            </div>
            <div class="perf-card-sub">当前值</div>
          </div>
          <div class="perf-card-chart">
            <SparkLine :data="connHistory" :width="180" :height="48" color="#0073e6" />
          </div>
        </div>
      </div>
    </div>

    <!-- Slow SQL Analysis -->
    <div v-else-if="mode === 'slowsql'" class="perf-slow-sql">
      <div class="perf-empty" v-if="!slowHistory.some(v => v > 0)">
        <p>监控期间暂无慢查询（> 3s）。</p>
      </div>
      <div v-else class="perf-slow-detail">
        <div class="perf-card" style="max-width: 480px;">
          <div class="perf-card-title">慢SQL趋势</div>
          <SparkLine :data="slowHistory" :width="400" :height="80" color="#e37318" />
        </div>
        <p class="perf-tip">检测到 {{ slowHistory.filter(v => v > 0).length }} 次采样中存在慢查询。建议使用 SQL 编辑器中的 EXPLAIN ANALYZE 分析具体语句。</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { databaseApi, type DatabaseMetrics } from '../api/database'
import SparkLine from './SparkLine.vue'

const props = defineProps<{
  dbId: string
  dbName: string
  status: string
}>()

const mode = ref<'realtime' | 'slowsql'>('realtime')
const pollInterval = 10 // seconds
const maxPoints = 60    // keep last 10 minutes

interface DataPoint {
  ts: number
  metrics: DatabaseMetrics
}

const dataPoints = ref<DataPoint[]>([])
const metricsError = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const hasPod = computed(() => {
  // If we got any data with non-zero CPU limit, the pod is up
  if (dataPoints.value.length > 0) return true
  return props.status === 'RUNNING'
})
const latestMetrics = computed(() => dataPoints.value.length > 0 ? dataPoints.value[dataPoints.value.length - 1]!.metrics : null)

const cpuPercent = computed(() => {
  const m = latestMetrics.value
  if (!m || m.cpuLimit === 0) return 0
  return Math.round(m.cpuUsage / m.cpuLimit * 100)
})

const memPercent = computed(() => {
  const m = latestMetrics.value
  if (!m || m.memoryLimitMb === 0) return 0
  return Math.round(m.memoryUsageMb / m.memoryLimitMb * 100)
})

const memUsedDisplay = computed(() => {
  const m = latestMetrics.value
  if (!m) return '0 MB'
  if (m.memoryUsageMb >= 1024) return (m.memoryUsageMb / 1024).toFixed(2) + ' GB'
  return m.memoryUsageMb.toFixed(0) + ' MB'
})

const memLimitDisplay = computed(() => {
  const m = latestMetrics.value
  if (!m) return '0 GB'
  if (m.memoryLimitMb >= 1024) return (m.memoryLimitMb / 1024).toFixed(0) + ' GB'
  return m.memoryLimitMb.toFixed(0) + ' MB'
})

const cpuHistory = computed(() => dataPoints.value.map(d => d.metrics.cpuLimit > 0 ? d.metrics.cpuUsage / d.metrics.cpuLimit * 100 : 0))
const memHistory = computed(() => dataPoints.value.map(d => d.metrics.memoryLimitMb > 0 ? d.metrics.memoryUsageMb / d.metrics.memoryLimitMb * 100 : 0))
const connHistory = computed(() => dataPoints.value.map(d => d.metrics.activeConnections))
const slowHistory = computed(() => dataPoints.value.map(d => d.metrics.slowQueries))

async function fetchMetrics() {
  try {
    const res = await databaseApi.getMetrics(props.dbId)
    metricsError.value = false
    dataPoints.value.push({ ts: Date.now(), metrics: res.data })
    if (dataPoints.value.length > maxPoints) {
      dataPoints.value = dataPoints.value.slice(-maxPoints)
    }
  } catch (e) {
    metricsError.value = true
  }
}

function startPolling() {
  stopPolling()
  dataPoints.value = []
  fetchMetrics()
  timer = setInterval(fetchMetrics, pollInterval * 1000)
}

function stopPolling() {
  if (timer) { clearInterval(timer); timer = null }
}

watch(() => props.dbId, () => {
  startPolling()
})

onMounted(() => startPolling())
onUnmounted(() => stopPolling())
</script>

<style scoped>
.perf-monitor {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 4px;
}

.perf-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.perf-header h3 {
  font-size: 16px;
  font-weight: 700;
  color: #191919;
  margin: 0;
}

.perf-header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.perf-label {
  font-size: 14px;
  color: #8a8e99;
  cursor: pointer;
  padding-bottom: 2px;
}

.perf-label.active {
  color: #0073e6;
  border-bottom: 2px solid #0073e6;
}

.perf-label:hover:not(.active) {
  color: #333;
}

.perf-time-range {
  font-size: 13px;
  color: #8a8e99;
  background: #f5f5f5;
  padding: 4px 12px;
  border-radius: 4px;
}

.perf-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0;
}

.perf-card {
  padding: 20px;
  border-right: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
}

.perf-card:nth-child(2n) {
  border-right: none;
}

.perf-card:nth-child(n+3) {
  border-bottom: none;
}

.perf-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.perf-card-info {
  flex: 1;
  min-width: 0;
}

.perf-card-chart {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  margin-left: 16px;
}

.perf-card-title {
  font-size: 15px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 4px;
}

.perf-card-meta {
  font-size: 13px;
  color: #8a8e99;
  margin-bottom: 8px;
}

.perf-card-meta b {
  color: #191919;
  font-weight: 600;
}

.perf-card-value {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
  line-height: 1.2;
}

.perf-unit {
  font-size: 13px;
  font-weight: 400;
  color: #8a8e99;
}

.perf-card-sub {
  font-size: 12px;
  color: #bbb;
  margin-top: 2px;
}

.perf-no-data {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  color: #ccc;
  font-size: 12px;
}

.perf-empty {
  padding: 48px 20px;
  text-align: center;
  color: #999;
  font-size: 14px;
}

.perf-slow-sql {
  padding: 20px;
}

.perf-slow-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.perf-tip {
  font-size: 13px;
  color: #8a8e99;
}

@media (max-width: 768px) {
  .perf-grid {
    grid-template-columns: 1fr;
  }

  .perf-card {
    border-right: none;
  }

  .perf-card:nth-child(n+3) {
    border-bottom: 1px solid #f0f0f0;
  }

  .perf-card:last-child {
    border-bottom: none;
  }

  .perf-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
}
</style>
