<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">成本监控</h1>
    </div>

    <!-- CBC Actual Cost -->
    <div class="section-card" v-if="cbcBills.length > 0">
      <div class="section-header">
        <h3>华为云实际账单（{{ cbcCycle }}）</h3>
      </div>
      <div class="cost-cards" style="margin-bottom: 16px; padding: 0 16px;">
        <div class="total-cost-card highlight">
          <div class="total-cost-label">当月实际消费</div>
          <div class="total-cost-value">{{ formatCurrency(cbcTotal) }} <span class="total-cost-unit">元</span></div>
        </div>
        <div class="total-cost-card">
          <div class="total-cost-label">每天实际（均摊）</div>
          <div class="total-cost-value">{{ formatCurrency(cbcDaily) }} <span class="total-cost-unit">元</span></div>
        </div>
        <div class="total-cost-card">
          <div class="total-cost-label">每小时实际（均摊）</div>
          <div class="total-cost-value">{{ formatCurrency(cbcHourly) }} <span class="total-cost-unit">元</span></div>
        </div>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>云服务</th>
              <th>资源类型</th>
              <th>实际消费(元)</th>
              <th>官方价(元)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in cbcBills" :key="b.resource_type_code">
              <td>{{ b.service }}</td>
              <td>{{ b.resource }}</td>
              <td>{{ formatCurrency(b.consume) }}</td>
              <td>{{ formatCurrency(b.official) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Estimated Cost -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>Lakeon 预估成本</h3>
      </div>
      <div class="cost-cards" style="margin-bottom: 16px; padding: 0 16px;">
        <div class="total-cost-card">
          <div class="total-cost-label">每小时预估</div>
          <div class="total-cost-value">{{ formatCurrency(hourlyCost) }} <span class="total-cost-unit">元</span></div>
        </div>
        <div class="total-cost-card">
          <div class="total-cost-label">每天预估</div>
          <div class="total-cost-value">{{ formatCurrency(dailyCost) }} <span class="total-cost-unit">元</span></div>
        </div>
        <div class="total-cost-card">
          <div class="total-cost-label">当月预估</div>
          <div class="total-cost-value">{{ formatCurrency(summary.total_monthly_cost) }} <span class="total-cost-unit">元</span></div>
        </div>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>资源类型</th>
              <th>每小时(元)</th>
              <th>每天(元)</th>
              <th>每月(元)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in breakdown" :key="item.resource">
              <td>{{ item.resource }}</td>
              <td>{{ formatCurrency(item.cost / 720) }}</td>
              <td>{{ formatCurrency(item.cost / 30) }}</td>
              <td>{{ formatCurrency(item.cost) }}</td>
            </tr>
            <tr v-if="breakdown.length === 0">
              <td colspan="4" class="empty-state">暂无数据</td>
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
import { ref, computed, onMounted } from 'vue'
import { adminApi } from '../../api/admin'
import { formatCurrency } from '../../utils/format'

interface CostSummary {
  total_monthly_cost?: number
}

interface TenantCost {
  tenant_id: string
  database_count?: number
  compute_cost?: number
  storage_cost?: number
  total_cost?: number
}

interface CbcBill {
  service: string
  resource: string
  consume: number
  official: number
  resource_type_code: string
}

const summary = ref<CostSummary>({})
const breakdown = ref<Array<{ resource: string; cost: number }>>([])
const tenantCosts = ref<TenantCost[]>([])
const cbcBills = ref<CbcBill[]>([])
const cbcTotal = ref(0)
const cbcCycle = ref('')

const hourlyCost = computed(() => {
  const m = summary.value.total_monthly_cost
  return m != null ? Math.round(m / 720 * 100) / 100 : undefined
})
const dailyCost = computed(() => {
  const m = summary.value.total_monthly_cost
  return m != null ? Math.round(m / 30 * 100) / 100 : undefined
})

// CBC: elapsed days in current month
const cbcDays = computed(() => {
  const now = new Date()
  return now.getDate()
})
const cbcDaily = computed(() => {
  return cbcDays.value > 0 ? Math.round(cbcTotal.value / cbcDays.value * 100) / 100 : 0
})
const cbcHourly = computed(() => {
  const hours = cbcDays.value * 24
  return hours > 0 ? Math.round(cbcTotal.value / hours * 100) / 100 : 0
})

onMounted(async () => {
  try {
    const [summaryRes, tenantRes, cbcRes] = await Promise.all([
      adminApi.costSummary(),
      adminApi.costByTenant(),
      adminApi.costCbc().catch(() => ({ data: null })),
    ])

    // Estimated cost
    const sd = summaryRes.data
    summary.value = { total_monthly_cost: sd.total }
    const COST_LABELS: Record<string, string> = {
      cce_nodes: 'CCE 节点', elb: '弹性负载均衡', rds: 'RDS 数据库',
      eip: '弹性公网 IP', obs: 'OBS 存储', compute: '计算资源',
    }
    const bd = sd.breakdown || {}
    breakdown.value = Object.entries(bd).map(([key, cost]: [string, any]) => ({
      resource: COST_LABELS[key] || key,
      cost: cost as number,
    }))
    tenantCosts.value = tenantRes.data.tenants || tenantRes.data || []

    // CBC actual billing
    const cbc = typeof cbcRes.data === 'string' ? JSON.parse(cbcRes.data) : cbcRes.data
    if (cbc && cbc.bill_sums) {
      cbcTotal.value = cbc.consume_amount || 0
      cbcCycle.value = cbc.bill_sums[0]?.bill_cycle || ''
      cbcBills.value = cbc.bill_sums
        .filter((b: any) => b.consume_amount > 0 || b.official_amount > 0.01)
        .map((b: any) => ({
          service: b.service_type_name,
          resource: b.resource_type_name,
          consume: b.consume_amount,
          official: b.official_amount,
          resource_type_code: b.resource_type_code,
        }))
        .sort((a: CbcBill, b: CbcBill) => b.consume - a.consume)
    }
  } catch (e) {
    console.error('Failed to load cost data', e)
  }
})
</script>

<style scoped>
.cost-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.total-cost-card {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 24px;
  text-align: center;
}

.total-cost-card.highlight {
  border-color: #0052d9;
  background: #f2f7ff;
}

.total-cost-label {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 8px;
}

.total-cost-value {
  font-size: 28px;
  font-weight: 700;
  color: #191919;
}

.total-cost-unit {
  font-size: 14px;
  font-weight: 400;
  color: #575d6c;
}
</style>
