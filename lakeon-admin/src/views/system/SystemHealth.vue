<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">组件健康</h1>
    </div>

    <!-- Compute Latency Stats -->
    <div class="section-card">
      <div class="section-header">
        <h3>唤醒延迟统计</h3>
      </div>
      <div class="metric-cards">
        <div class="metric-card">
          <div class="metric-value">{{ formatDuration(computeStats.p50_ms) }}</div>
          <div class="metric-label">P50 唤醒延迟</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ formatDuration(computeStats.p90_ms) }}</div>
          <div class="metric-label">P90 唤醒延迟</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ formatDuration(computeStats.p99_ms) }}</div>
          <div class="metric-label">P99 唤醒延迟</div>
        </div>
      </div>
    </div>

    <!-- Component Health Cards -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>组件状态</h3>
      </div>
      <div class="health-grid">
        <div class="health-card" v-for="comp in components" :key="comp.name">
          <div class="health-card-header">
            <span class="status-dot" :class="comp.healthy ? 'dot-green' : 'dot-red'"></span>
            <span class="health-card-name">{{ comp.name }}</span>
          </div>
          <div class="health-card-status">
            {{ comp.healthy ? '正常运行' : '异常' }}
          </div>
          <div class="health-card-detail" v-if="comp.url">
            {{ comp.url }}
          </div>
          <div class="health-card-detail" v-if="comp.details">
            {{ comp.details }}
          </div>
        </div>
        <div v-if="components.length === 0" class="empty-state" style="grid-column: 1 / -1;">
          暂无数据
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

interface ComponentHealth {
  name: string
  healthy: boolean
  url?: string
  details?: string
}

interface ComputeStats {
  p50_ms?: number
  p90_ms?: number
  p99_ms?: number
}

function formatDuration(ms?: number): string {
  if (ms == null) return '-'
  if (ms >= 1000) return (ms / 1000).toFixed(1) + 's'
  return ms + 'ms'
}

const components = ref<ComponentHealth[]>([])
const computeStats = ref<ComputeStats>({})

onMounted(async () => {
  try {
    const [healthRes, statsRes] = await Promise.all([
      adminApi.systemHealth(),
      adminApi.computeStats(),
    ])
    const COMP_LABELS: Record<string, string> = {
      pageserver: 'Pageserver', safekeeper: 'Safekeeper', proxy: 'Proxy', rds: 'RDS 数据库',
    }
    const raw = healthRes.data || {}
    if (Array.isArray(raw)) {
      components.value = raw
    } else {
      components.value = Object.entries(raw).map(([key, val]: [string, any]) => ({
        name: COMP_LABELS[key] || key,
        healthy: val.status === 'healthy',
        url: val.url || (Array.isArray(val.urls) ? val.urls.join(', ') : undefined),
        details: val.error || undefined,
      }))
    }
    computeStats.value = statsRes.data || {}
  } catch (e) {
    console.error('Failed to load system health', e)
  }
})
</script>

<style scoped>
.metric-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  padding: 16px;
}

.metric-card {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 20px;
  text-align: center;
}

.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #191919;
  margin-bottom: 8px;
}

.metric-label {
  font-size: 14px;
  color: #575d6c;
}

.health-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  padding: 16px;
}

.health-card {
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 20px;
}

.health-card-header {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
}

.health-card-name {
  font-size: 16px;
  font-weight: 600;
  color: #191919;
}

.health-card-status {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 8px;
}

.health-card-detail {
  font-size: 12px;
  color: #8a8e99;
  word-break: break-all;
  font-family: monospace;
}
</style>
