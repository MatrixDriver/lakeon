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
        <div class="health-card" v-for="comp in components" :key="comp.name"
             :class="{ 'health-card-error': !comp.healthy, 'health-card-expanded': expandedCard === comp.name }"
             @click="expandedCard = expandedCard === comp.name ? null : comp.name">
          <div class="health-card-header">
            <span class="status-dot" :class="comp.healthy ? 'dot-green' : 'dot-red'"></span>
            <span class="health-card-name">{{ comp.label }}</span>
            <span class="health-card-toggle">{{ expandedCard === comp.name ? '&minus;' : '&plus;' }}</span>
          </div>
          <div class="health-card-status">
            {{ comp.healthy ? '正常运行' : '异常' }}
          </div>
          <div class="health-card-detail" v-if="expandedCard === comp.name && comp.detailList.length > 0">
            <div class="detail-row" v-for="(d, i) in comp.detailList" :key="i">
              <span class="detail-key">{{ d.key }}</span>
              <span class="detail-val">{{ d.value }}</span>
            </div>
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

interface DetailItem { key: string; value: string }
interface ComponentHealth {
  name: string
  label: string
  healthy: boolean
  detailList: DetailItem[]
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
const expandedCard = ref<string | null>(null)

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
        detailList: buildDetailList(c),
      }))
    } else {
      components.value = Object.entries(raw).map(([key, val]: [string, any]) => ({
        name: key,
        label: COMP_LABELS[key] || key,
        healthy: val.status === 'healthy',
        detailList: buildDetailList(val),
      }))
    }
  } catch (e) {
    console.error('Failed to load system health', e)
  } finally {
    loading.value = false
  }
}

function buildDetailList(val: any): DetailItem[] {
  const items: DetailItem[] = []
  if (val.pod) items.push({ key: 'Pod', value: val.pod })
  if (val.node) items.push({ key: '节点', value: val.node })
  if (val.ip) items.push({ key: 'IP', value: val.ip })
  if (val.url) items.push({ key: '地址', value: val.url })
  if (val.urls) items.push({ key: '地址', value: Array.isArray(val.urls) ? val.urls.join(', ') : val.urls })
  if (val.type) items.push({ key: '类型', value: val.type })
  if (val.endpoint) items.push({ key: '端点', value: val.endpoint })
  if (val.bucket) items.push({ key: '桶', value: val.bucket })
  if (val.latency_ms != null) items.push({ key: '延迟', value: `${val.latency_ms}ms` })
  if (val.total_objects_estimate != null) items.push({ key: '对象数', value: `${val.total_objects_estimate}` })
  if (val.total_size_gb_estimate != null) items.push({ key: '总大小', value: `${val.total_size_gb_estimate} GB` })
  if (val.error) items.push({ key: '错误', value: val.error })
  return items
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
  transition: all 0.15s;
  cursor: pointer;
}

.health-card:hover {
  border-color: #0073e6;
  box-shadow: 0 2px 8px rgba(0, 115, 230, 0.08);
}

.health-card-expanded {
  border-color: #0073e6;
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

.health-card-toggle {
  margin-left: auto;
  font-size: 16px;
  color: #999;
  font-weight: 500;
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
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}

.detail-row {
  display: flex;
  font-size: 13px;
  line-height: 2;
}

.detail-key {
  color: #8a8e99;
  min-width: 60px;
  flex-shrink: 0;
}

.detail-val {
  color: #333;
  font-family: monospace;
  word-break: break-all;
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

@media (max-width: 768px) {
  .health-grid {
    grid-template-columns: 1fr;
    gap: 12px;
    padding: 12px;
  }

  .health-card {
    padding: 16px;
  }
}
</style>
