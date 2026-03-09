<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">组件健康</h1>
      <button class="refresh-btn" @click="loadHealth" :disabled="loading">{{ loading ? '刷新中...' : '刷新' }}</button>
    </div>

    <!-- Component Health Cards -->
    <div class="section-card">
      <div class="section-header">
        <h3>组件状态</h3>
        <span class="health-summary" v-if="components.length > 0">
          <span class="dot-green-text">{{ healthyCount }}</span> / {{ components.length }} 正常
        </span>
      </div>
      <div class="health-grid">
        <div class="health-card" v-for="comp in components" :key="comp.name" :class="{ 'health-card-error': !comp.healthy }">
          <div class="health-card-header">
            <span class="status-dot" :class="comp.healthy ? 'dot-green' : 'dot-red'"></span>
            <span class="health-card-name">{{ comp.label }}</span>
          </div>
          <div class="health-card-status">
            {{ comp.healthy ? '正常运行' : '异常' }}
          </div>
          <div class="health-card-detail" v-if="comp.details">
            {{ comp.details }}
          </div>
        </div>
        <div v-if="components.length === 0 && !loading" class="empty-state" style="grid-column: 1 / -1;">
          暂无数据
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

interface ComponentHealth {
  name: string
  label: string
  healthy: boolean
  details?: string
}

const COMP_LABELS: Record<string, string> = {
  api: 'API Pod',
  pageserver: 'Pageserver',
  safekeeper: 'Safekeeper',
  storage_broker: 'Storage Broker',
  proxy: 'Proxy',
  rds: 'RDS 数据库',
  obs: 'OBS 存储',
  elb: 'ELB 负载均衡',
}

const components = ref<ComponentHealth[]>([])
const loading = ref(false)

const healthyCount = computed(() => components.value.filter(c => c.healthy).length)

async function loadHealth() {
  loading.value = true
  try {
    const res = await adminApi.systemHealth()
    const raw = res.data || {}
    if (Array.isArray(raw)) {
      components.value = raw.map((c: any) => ({
        name: c.name,
        label: COMP_LABELS[c.name] || c.name,
        healthy: c.status === 'healthy',
        details: buildDetails(c),
      }))
    } else {
      components.value = Object.entries(raw).map(([key, val]: [string, any]) => ({
        name: key,
        label: COMP_LABELS[key] || key,
        healthy: val.status === 'healthy',
        details: buildDetails(val),
      }))
    }
  } catch (e) {
    console.error('Failed to load system health', e)
  } finally {
    loading.value = false
  }
}

function buildDetails(val: any): string | undefined {
  const parts: string[] = []
  if (val.pod) parts.push(`Pod: ${val.pod}`)
  if (val.node) parts.push(`Node: ${val.node}`)
  if (val.ip) parts.push(`IP: ${val.ip}`)
  if (val.url) parts.push(val.url)
  if (val.urls) parts.push(Array.isArray(val.urls) ? val.urls.join(', ') : val.urls)
  if (val.type) parts.push(`Type: ${val.type}`)
  if (val.endpoint) parts.push(val.endpoint)
  if (val.bucket) parts.push(`Bucket: ${val.bucket}`)
  if (val.latency_ms != null) parts.push(`Latency: ${val.latency_ms}ms`)
  if (val.error) parts.push(`Error: ${val.error}`)
  return parts.length > 0 ? parts.join(' · ') : undefined
}

onMounted(loadHealth)
</script>

<style scoped>
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
  transition: border-color 0.2s;
}

.health-card-error {
  border-color: #ffccc7;
  background: #fff2f0;
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
  line-height: 1.6;
}

.health-summary {
  font-size: 13px;
  color: #575d6c;
  font-weight: 400;
}

.dot-green-text { color: #52c41a; }

.refresh-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 14px;
  font-size: 13px;
  color: #575d6c;
  cursor: pointer;
}
.refresh-btn:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}
.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
