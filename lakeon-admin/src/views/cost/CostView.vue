<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">成本监控</h1>
    </div>

    <!-- Cost Summary -->
    <div class="section-card">
      <div class="section-header">
        <h3>DBay 月度成本 <span v-if="costSource" class="source-badge" :class="costSource">{{ costSource === 'cbc' ? '实时账单' : '预估' }}</span></h3>
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

    <!-- Resource Details (CBC real data) -->
    <div class="section-card" style="margin-top: 24px;" v-if="resourceDetails.length > 0">
      <div class="section-header">
        <h3>资源费用明细</h3>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>资源名称</th>
              <th>服务类型</th>
              <th>费用(元)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in resourceDetails" :key="d.resource_id">
              <td>{{ d.resource_name }}</td>
              <td>{{ d.service_type }}</td>
              <td>{{ formatCurrency(d.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Cost Trend Chart -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>日成本趋势（最近 30 天）</h3>
      </div>
      <div style="padding: 16px;" v-if="trendData.length > 0">
        <canvas ref="trendCanvas" height="100"></canvas>
      </div>
      <div v-else style="padding: 16px; color: #999;">加载中...</div>
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
import { ref, computed, onMounted, nextTick } from 'vue'
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

const summary = ref<CostSummary>({})
const breakdown = ref<Array<{ resource: string; cost: number }>>([])
const tenantCosts = ref<TenantCost[]>([])
const trendData = ref<Array<{ date: string; fixed_cost: number; compute_cost: number; total_cost: number }>>([])
const trendCanvas = ref<HTMLCanvasElement | null>(null)
const costSource = ref<string>('')
const resourceDetails = ref<Array<{ resource_id: string; resource_name: string; service_type: string; amount: number }>>([])

const hourlyCost = computed(() => {
  const m = summary.value.total_monthly_cost
  return m != null ? Math.round(m / 720 * 100) / 100 : undefined
})
const dailyCost = computed(() => {
  const m = summary.value.total_monthly_cost
  return m != null ? Math.round(m / 30 * 100) / 100 : undefined
})

onMounted(async () => {
  try {
    const [summaryRes, tenantRes] = await Promise.all([
      adminApi.costSummary(),
      adminApi.costByTenant(),
    ])

    // Cost data (real CBC or estimated)
    const sd = summaryRes.data
    summary.value = { total_monthly_cost: sd.total }
    costSource.value = sd.source || 'estimate'

    const COST_LABELS: Record<string, string> = {
      cce_cluster: 'CCE 集群（含 EIP）', cce_nodes: 'CCE 节点', elb: '弹性负载均衡',
      rds: 'RDS 数据库', eip: '弹性公网 IP', obs: 'OBS 存储', compute: '计算资源',
    }
    const bd = sd.breakdown || {}
    breakdown.value = Object.entries(bd).map(([key, cost]: [string, any]) => ({
      resource: COST_LABELS[key] || key,
      cost: cost as number,
    }))
    resourceDetails.value = sd.details || []
    tenantCosts.value = tenantRes.data.tenants || tenantRes.data || []

    // Cost trend
    try {
      const trendRes = await adminApi.costTrend(30)
      trendData.value = trendRes.data
      await nextTick()
      renderTrendChart()
    } catch (e) {
      console.error('Failed to load cost trend', e)
    }
  } catch (e) {
    console.error('Failed to load cost data', e)
  }
})

function renderTrendChart() {
  const canvas = trendCanvas.value
  if (!canvas || trendData.value.length === 0) return
  const ctx = canvas.getContext('2d')!
  if (!ctx) return

  const data = trendData.value
  const labels = data.map(d => d.date.substring(5)) // MM-DD
  const fixedCosts = data.map(d => d.fixed_cost)
  const computeCosts = data.map(d => d.compute_cost)

  const width = canvas.clientWidth
  const height = canvas.clientHeight || 300
  const dpr = window.devicePixelRatio || 1
  canvas.width = width * dpr
  canvas.height = height * dpr
  ctx.scale(dpr, dpr)

  const padding = { top: 20, right: 20, bottom: 40, left: 60 }
  const chartW = width - padding.left - padding.right
  const chartH = height - padding.top - padding.bottom

  const allValues = [...fixedCosts, ...computeCosts, ...data.map(d => d.total_cost)]
  const maxVal = Math.max(...allValues, 1) * 1.1

  ctx.clearRect(0, 0, width, height)
  ctx.font = '11px sans-serif'
  ctx.fillStyle = '#999'

  // Y axis
  for (let i = 0; i <= 4; i++) {
    const y = padding.top + chartH - (i / 4) * chartH
    const val = (maxVal * i / 4).toFixed(1)
    ctx.fillText(val, 5, y + 3)
    ctx.strokeStyle = '#f0f0f0'
    ctx.beginPath()
    ctx.moveTo(padding.left, y)
    ctx.lineTo(padding.left + chartW, y)
    ctx.stroke()
  }

  // X axis labels (every ~5 days)
  const step = Math.max(1, Math.floor(labels.length / 6))
  for (let i = 0; i < labels.length; i += step) {
    const x = padding.left + (i / (labels.length - 1)) * chartW
    ctx.fillText(labels[i] ?? '', x - 12, height - 8)
  }

  function drawLine(values: number[], color: string) {
    ctx.strokeStyle = color
    ctx.lineWidth = 2
    ctx.beginPath()
    values.forEach((v, i) => {
      const x = padding.left + (i / (values.length - 1)) * chartW
      const y = padding.top + chartH - (v / maxVal) * chartH
      if (i === 0) ctx.moveTo(x, y)
      else ctx.lineTo(x, y)
    })
    ctx.stroke()
  }

  drawLine(fixedCosts, '#9a5b25')
  drawLine(computeCosts, '#e37318')

  // Legend
  const legendY = 12
  ctx.fillStyle = '#9a5b25'
  ctx.fillRect(padding.left, legendY - 8, 12, 3)
  ctx.fillStyle = '#333'
  ctx.fillText('固定成本', padding.left + 16, legendY)
  ctx.fillStyle = '#e37318'
  ctx.fillRect(padding.left + 80, legendY - 8, 12, 3)
  ctx.fillStyle = '#333'
  ctx.fillText('计算成本', padding.left + 96, legendY)
}
</script>

<style scoped>
.source-badge {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 4px;
  margin-left: 8px;
  vertical-align: middle;
}

.source-badge.cbc {
  background: #e8f5e9;
  color: #2e7d32;
}

.source-badge.estimate {
  background: #fff3e0;
  color: #e65100;
}

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

.total-cost-label {
  font-size: 14px;
  color: #64748b;
  margin-bottom: 8px;
}

.total-cost-value {
  font-size: 28px;
  font-weight: 700;
  color: #2c3e50;
}

.total-cost-unit {
  font-size: 14px;
  font-weight: 400;
  color: #64748b;
}

@media (max-width: 768px) {
  .cost-cards {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .total-cost-card {
    padding: 16px;
  }

  .total-cost-value {
    font-size: 22px;
  }
}
</style>
