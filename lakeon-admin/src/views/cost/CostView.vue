<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">成本监控</h1>
    </div>

    <!-- Total Cost -->
    <div class="total-cost-card">
      <div class="total-cost-label">当月总成本</div>
      <div class="total-cost-value">{{ formatCurrency(summary.total_monthly_cost) }} <span class="total-cost-unit">元</span></div>
    </div>

    <!-- Cost Breakdown -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>资源成本明细</h3>
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
            <tr v-for="item in breakdown" :key="item.resource">
              <td>{{ item.resource }}</td>
              <td>{{ formatCurrency(item.cost) }}</td>
            </tr>
            <tr v-if="breakdown.length === 0">
              <td colspan="2" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Tenant Cost -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>租户成本分摊</h3>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>租户ID</th>
              <th>数据库数</th>
              <th>计算成本(元)</th>
              <th>存储成本(元)</th>
              <th>总成本(元)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in tenantCosts" :key="t.tenant_id">
              <td style="font-family: monospace; font-size: 13px;">{{ t.tenant_id }}</td>
              <td>{{ t.database_count ?? 0 }}</td>
              <td>{{ formatCurrency(t.compute_cost) }}</td>
              <td>{{ formatCurrency(t.storage_cost) }}</td>
              <td>{{ formatCurrency(t.total_cost) }}</td>
            </tr>
            <tr v-if="tenantCosts.length === 0">
              <td colspan="5" class="empty-state">暂无数据</td>
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

interface CostSummary {
  total_monthly_cost?: number
  breakdown?: Array<{ resource: string; cost: number }>
}

interface TenantCost {
  tenant_id: string
  database_count?: number
  compute_cost?: number
  storage_cost?: number
  total_cost?: number
}

const summary = ref<CostSummary>({})
const breakdown = ref<Array<{ resource: string; cost: number }>>([])
const tenantCosts = ref<TenantCost[]>([])

onMounted(async () => {
  try {
    const [summaryRes, tenantRes] = await Promise.all([
      adminApi.costSummary(),
      adminApi.costByTenant(),
    ])
    summary.value = summaryRes.data
    breakdown.value = summaryRes.data.breakdown || []
    tenantCosts.value = tenantRes.data || []
  } catch (e) {
    console.error('Failed to load cost data', e)
  }
})
</script>

<style scoped>
.total-cost-card {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 32px;
  text-align: center;
}

.total-cost-label {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 12px;
}

.total-cost-value {
  font-size: 36px;
  font-weight: 700;
  color: #191919;
}

.total-cost-unit {
  font-size: 16px;
  font-weight: 400;
  color: #575d6c;
}
</style>
