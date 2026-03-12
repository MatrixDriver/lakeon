<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">仪表盘</h1>
    </div>

    <div v-if="apiOffline" class="offline-banner">
      DBay API 当前不可用，服务可能正在维护中。
    </div>

    <!-- 核心指标 -->
    <div class="metric-cards">
      <div class="metric-card clickable" @click="router.push('/system')">
        <div class="metric-value" :class="allHealthy ? 'dot-green-text' : 'text-danger'">
          {{ healthyCount }}/{{ totalComponents }}
        </div>
        <div class="metric-label">组件健康</div>
      </div>
      <div class="metric-card clickable" @click="router.push('/databases')">
        <div class="metric-value">{{ data.total_databases ?? '-' }}</div>
        <div class="metric-label">数据库</div>
        <div class="metric-sub">{{ data.running ?? 0 }} 运行 · {{ data.suspended ?? 0 }} 挂起</div>
      </div>
      <div class="metric-card clickable" @click="router.push('/infra')">
        <div class="metric-value">{{ data.active_computes ?? '-' }}</div>
        <div class="metric-label">活跃计算节点</div>
      </div>
      <div class="metric-card clickable" @click="router.push('/tenants')">
        <div class="metric-value">{{ data.total_tenants ?? '-' }}</div>
        <div class="metric-label">租户</div>
      </div>
    </div>

    <!-- 用户体验关键指标 -->
    <div class="section-card clickable-section" style="margin-top: 24px;" @click="router.push('/metrics')">
      <div class="section-header"><h3>用户体验</h3><span class="section-arrow">&rsaquo;</span></div>
      <div class="ux-metrics">
        <div class="ux-item">
          <span class="ux-label">Cold Wake</span>
          <span class="ux-value" :class="wakeupColor(metrics.cold_wake_avg_ms)">
            {{ metrics.cold_wake_avg_ms || '-' }}<span class="ux-unit" v-if="metrics.cold_wake_avg_ms">ms</span>
          </span>
          <span class="ux-sub">{{ metrics.cold_wake_count }} 次</span>
        </div>
        <div class="ux-item">
          <span class="ux-label">Warm Wake</span>
          <span class="ux-value text-warm">
            {{ metrics.warm_wake_avg_ms || '-' }}<span class="ux-unit" v-if="metrics.warm_wake_avg_ms">ms</span>
          </span>
          <span class="ux-sub">{{ metrics.warm_wake_count }} 次</span>
        </div>
        <div class="ux-item">
          <span class="ux-label">唤醒失败</span>
          <span class="ux-value" :class="{ 'text-danger': metrics.wakeup_failures > 0 }">{{ metrics.wakeup_failures }}</span>
        </div>
        <div class="ux-item">
          <span class="ux-label">API P95</span>
          <span class="ux-value" :class="{ 'text-warning': metrics.api_p95_ms > 500 }">{{ metrics.api_p95_ms }}<span class="ux-unit">ms</span></span>
        </div>
      </div>
    </div>

    <!-- 组件健康状态 -->
    <div class="section-card clickable-section" style="margin-top: 24px;" @click="router.push('/system')">
      <div class="section-header">
        <h3>组件状态</h3><span class="section-arrow">&rsaquo;</span>
      </div>
      <div class="health-items">
        <div class="health-item" v-for="comp in components" :key="comp.name">
          <span class="status-dot" :class="comp.healthy ? 'dot-green' : 'dot-red'"></span>
          <span class="health-name">{{ comp.label }}</span>
        </div>
      </div>
    </div>

    <!-- 24h 操作统计 -->
    <div class="section-card clickable-section" style="margin-top: 24px;" @click="router.push('/operations')">
      <div class="section-header">
        <h3>24h 操作统计</h3><span class="section-arrow">&rsaquo;</span>
      </div>
      <div class="op-stats">
        <div class="op-stat-item" v-for="op in operationStats" :key="op.type">
          <span class="op-type">{{ OP_LABELS[op.type] || op.type }}</span>
          <span class="op-count">{{ op.count }}</span>
        </div>
      </div>
    </div>

    <!-- 成本概览 -->
    <div class="section-card clickable-section" style="margin-top: 24px;" @click="router.push('/cost')">
      <div class="section-header">
        <h3>成本概览</h3><span class="section-arrow">&rsaquo;</span>
        <span class="cost-total">¥{{ formatCurrency(data.estimated_monthly_cost) }}/月</span>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>资源类型</th>
              <th>月成本(元)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in costBreakdown" :key="item.resource">
              <td>{{ item.resource }}</td>
              <td>{{ formatCurrency(item.cost) }}</td>
            </tr>
            <tr v-if="costBreakdown.length === 0">
              <td colspan="2" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../../api/admin'

const router = useRouter()
import { formatCurrency } from '../../utils/format'

interface DashboardData {
  total_tenants?: number
  total_databases?: number
  running?: number
  suspended?: number
  active_computes?: number
  estimated_monthly_cost?: number
}

const COMP_LABELS: Record<string, string> = {
  api: 'API',
  pageserver: 'Pageserver',
  safekeeper: 'Safekeeper',
  storage_broker: 'Storage Broker',
  proxy: 'Proxy',
  rds: 'RDS',
  obs: 'OBS',
  elb: 'ELB',
}

const OP_LABELS: Record<string, string> = {
  create: '创建',
  resume: '唤醒',
  suspend: '挂起',
  delete: '删除',
  import: '导入',
}

const COST_LABELS: Record<string, string> = {
  cce_cluster: 'CCE 集群',
  cce_nodes: 'CCE 节点',
  elb: '弹性负载均衡',
  rds: 'RDS 数据库',
  eip: '弹性公网 IP',
  obs: 'OBS 存储',
  compute: '计算资源',
}

const data = ref<DashboardData>({})
const apiOffline = ref(false)
const components = ref<Array<{ name: string; label: string; healthy: boolean }>>([])
const operationStats = ref<Array<{ type: string; count: number }>>([])
const costBreakdown = ref<Array<{ resource: string; cost: number }>>([])
const metrics = ref({
  cold_wake_avg_ms: 0,
  cold_wake_count: 0,
  warm_wake_avg_ms: 0,
  warm_wake_count: 0,
  wakeup_failures: 0,
  api_p95_ms: 0,
})

const healthyCount = computed(() => components.value.filter(c => c.healthy).length)
const totalComponents = computed(() => components.value.length)
const allHealthy = computed(() => healthyCount.value === totalComponents.value && totalComponents.value > 0)

function wakeupColor(ms: number): string {
  if (!ms) return ''
  if (ms < 5000) return 'text-warm'
  if (ms < 15000) return 'text-warning'
  return 'text-danger'
}

onMounted(async () => {
  // Fetch dashboard + metrics in parallel
  const [dashRes, metricsRes] = await Promise.allSettled([
    adminApi.dashboard(),
    adminApi.metricsSummary(),
  ])

  if (dashRes.status === 'fulfilled') {
    const d = dashRes.value.data
    data.value = {
      total_tenants: d.tenant_count,
      total_databases: d.database_count,
      running: d.databases_by_status?.running || 0,
      suspended: d.databases_by_status?.suspended || 0,
      active_computes: d.active_compute_pods,
      estimated_monthly_cost: d.estimated_monthly_cost?.total,
    }
    const ch = d.component_health || {}
    components.value = Object.entries(ch).map(([name, info]: [string, any]) => ({
      name,
      label: COMP_LABELS[name] || name,
      healthy: info.status === 'healthy',
    }))
    const ops = d.operation_stats_24h || {}
    operationStats.value = Object.entries(ops).map(([type, count]: [string, any]) => ({
      type,
      count: count as number,
    }))
    const bd = d.estimated_monthly_cost?.breakdown || {}
    costBreakdown.value = Object.entries(bd).map(([key, cost]: [string, any]) => ({
      resource: COST_LABELS[key] || key,
      cost: cost as number,
    }))
  } else {
    const err = dashRes.reason
    if (err?.isApiOffline) apiOffline.value = true
    console.error('Failed to load dashboard', err)
  }

  if (metricsRes.status === 'fulfilled') {
    const m = metricsRes.value.data
    metrics.value = {
      cold_wake_avg_ms: m.compute?.cold_wake_avg_ms || 0,
      cold_wake_count: m.compute?.cold_wake_count || 0,
      warm_wake_avg_ms: m.compute?.warm_wake_avg_ms || 0,
      warm_wake_count: m.compute?.warm_wake_count || 0,
      wakeup_failures: m.compute?.wakeup_failures || 0,
      api_p95_ms: m.api?.p95_ms || 0,
    }
  }
})
</script>

<style scoped>
.metric-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.metric-card {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 20px;
  text-align: center;
  transition: all 0.15s;
}

.metric-card.clickable {
  cursor: pointer;
}

.metric-card.clickable:hover {
  border-color: #0073e6;
  box-shadow: 0 2px 8px rgba(0, 115, 230, 0.1);
}

.clickable-section {
  cursor: pointer;
  transition: border-color 0.15s;
}

.clickable-section:hover {
  border-color: #0073e6;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-arrow {
  font-size: 20px;
  color: #999;
  transition: color 0.15s;
}

.clickable-section:hover .section-arrow {
  color: #0073e6;
}

.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #191919;
  margin-bottom: 4px;
}

.metric-label {
  font-size: 14px;
  color: #575d6c;
}

.metric-sub {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}

.ux-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding: 16px;
}

.ux-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.ux-label {
  font-size: 13px;
  color: #575d6c;
}

.ux-value {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
}

.ux-unit {
  font-size: 13px;
  font-weight: 400;
  color: #999;
}

.ux-sub {
  font-size: 12px;
  color: #999;
}

.health-items {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  padding: 16px;
}

.health-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #191919;
}

.health-name {
  font-weight: 500;
}

.op-stats {
  display: flex;
  gap: 32px;
  padding: 16px;
}

.op-stat-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.op-type {
  color: #575d6c;
}

.op-count {
  font-weight: 700;
  color: #191919;
}

.cost-total {
  font-size: 16px;
  font-weight: 700;
  color: #191919;
}

.offline-banner {
  background: #fff3e0;
  border: 1px solid #ffb74d;
  color: #e65100;
  padding: 12px 16px;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 14px;
}

.dot-green-text { color: #52c41a; }
.text-warning { color: #e37318; }
.text-danger { color: #e6393d; }
.text-warm { color: #52c41a; }

@media (max-width: 768px) {
  .metric-cards {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }

  .metric-value {
    font-size: 22px;
  }

  .ux-metrics {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
    padding: 12px;
  }

  .ux-value {
    font-size: 20px;
  }

  .op-stats {
    flex-wrap: wrap;
    gap: 16px;
    padding: 12px;
  }

  .health-items {
    gap: 16px;
    padding: 12px;
  }

  .cost-total {
    font-size: 14px;
  }
}

@media (max-width: 480px) {
  .metric-cards {
    grid-template-columns: 1fr;
  }

  .metric-card {
    padding: 14px;
  }

  .ux-metrics {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
