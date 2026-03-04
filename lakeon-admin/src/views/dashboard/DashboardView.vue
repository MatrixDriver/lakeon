<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">仪表盘</h1>
    </div>

    <!-- Metric Cards -->
    <div class="metric-cards">
      <div class="metric-card">
        <div class="metric-value">{{ data.total_tenants ?? '-' }}</div>
        <div class="metric-label">租户总数</div>
      </div>
      <div class="metric-card">
        <div class="metric-value">{{ data.total_databases ?? '-' }}</div>
        <div class="metric-label">数据库总数</div>
      </div>
      <div class="metric-card">
        <div class="metric-value">{{ data.active_computes ?? '-' }}</div>
        <div class="metric-label">活跃计算节点</div>
      </div>
      <div class="metric-card">
        <div class="metric-value">{{ formatCurrency(data.estimated_monthly_cost) }}</div>
        <div class="metric-label">当月预估成本(元)</div>
      </div>
    </div>

    <!-- Component Health -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>组件健康状态</h3>
      </div>
      <div class="health-items">
        <div class="health-item" v-for="comp in components" :key="comp.name">
          <span class="status-dot" :class="comp.healthy ? 'dot-green' : 'dot-red'"></span>
          <span class="health-name">{{ comp.name }}</span>
          <span class="health-status">{{ comp.healthy ? '正常' : '异常' }}</span>
        </div>
      </div>
    </div>

    <!-- 24h Operation Stats -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>24h 操作统计</h3>
      </div>
      <div class="op-stats">
        <div class="op-stat-item" v-for="op in operationStats" :key="op.type">
          <span class="op-type">{{ op.type }}</span>
          <span class="op-count">{{ op.count }}</span>
        </div>
      </div>
    </div>

    <!-- Cost Breakdown -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>成本概览</h3>
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
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'
import { formatCurrency } from '../../utils/format'

interface DashboardData {
  total_tenants?: number
  total_databases?: number
  active_computes?: number
  estimated_monthly_cost?: number
  components?: Array<{ name: string; healthy: boolean }>
  operation_stats_24h?: Array<{ type: string; count: number }>
  cost_breakdown?: Array<{ resource: string; cost: number }>
}

const data = ref<DashboardData>({})
const components = ref<Array<{ name: string; healthy: boolean }>>([])
const operationStats = ref<Array<{ type: string; count: number }>>([])
const costBreakdown = ref<Array<{ resource: string; cost: number }>>([])

onMounted(async () => {
  try {
    const res = await adminApi.dashboard()
    data.value = res.data
    components.value = res.data.components || []
    operationStats.value = res.data.operation_stats_24h || []
    costBreakdown.value = res.data.cost_breakdown || []
  } catch (e) {
    console.error('Failed to load dashboard', e)
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

.health-items {
  display: flex;
  gap: 32px;
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

.health-status {
  color: #575d6c;
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
</style>
