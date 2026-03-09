<template>
  <div class="page-usage">
    <div class="page-header">
      <h1 class="page-title">用量与计费</h1>
      <div class="header-actions">
        <select v-model="billCycle" class="cycle-select" @change="fetchUsage">
          <option v-for="m in months" :key="m" :value="m">{{ m }}</option>
        </select>
        <button class="btn btn-default btn-small" @click="fetchUsage" :disabled="loading">{{ loading ? '加载中...' : '刷新' }}</button>
      </div>
    </div>

    <p class="page-desc">
      查看当前账期的计算用量、存储使用和预估费用。目前为免费公测阶段，所有用量均不产生实际费用。
    </p>

    <!-- Summary Cards -->
    <div class="summary-cards">
      <div class="summary-card">
        <div class="summary-value">{{ formatHours(usage?.total_compute_cu_hours) }}</div>
        <div class="summary-label">计算用量 (CU·小时)</div>
        <div class="summary-sub">累计计算资源消耗</div>
      </div>
      <div class="summary-card">
        <div class="summary-value">{{ formatStorage(usage?.total_storage_used_gb) }}</div>
        <div class="summary-label">存储用量 (GB)</div>
        <div class="summary-sub">当前数据存储占用</div>
      </div>
      <div class="summary-card">
        <div class="summary-value">{{ formatDuration(usage?.total_compute_seconds) }}</div>
        <div class="summary-label">运行时长</div>
        <div class="summary-sub">本月数据库累计运行</div>
      </div>
      <div class="summary-card highlight">
        <div class="summary-value">&yen;{{ formatCost(usage?.total_estimated_cost) }}</div>
        <div class="summary-label">预估费用</div>
        <div class="summary-sub free-tag">免费公测中</div>
      </div>
    </div>

    <!-- Cost breakdown -->
    <div class="cost-breakdown" v-if="usage">
      <div class="cost-item">
        <span class="cost-label">计算费用</span>
        <span class="cost-value">&yen;{{ formatCost(usage.total_compute_cost) }}</span>
      </div>
      <span class="cost-plus">+</span>
      <div class="cost-item">
        <span class="cost-label">存储费用</span>
        <span class="cost-value">&yen;{{ formatCost(usage.total_storage_cost) }}</span>
      </div>
      <span class="cost-eq">=</span>
      <div class="cost-item cost-total">
        <span class="cost-label">合计</span>
        <span class="cost-value">&yen;{{ formatCost(usage.total_estimated_cost) }}</span>
      </div>
    </div>

    <!-- Per-database breakdown -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>数据库明细</h3>
      </div>
      <div class="table-wrapper">
        <table class="data-table" v-if="usage?.databases?.length">
          <thead>
            <tr>
              <th>数据库</th>
              <th>规格</th>
              <th>运行时长</th>
              <th>计算用量 (CU·h)</th>
              <th>计算费用</th>
              <th>存储 (GB)</th>
              <th>存储费用</th>
              <th>合计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="db in usage.databases" :key="db.database_id">
              <td class="td-name">{{ db.database_name }}</td>
              <td>{{ db.compute_size }}</td>
              <td>{{ formatDuration(db.compute_seconds) }}</td>
              <td>{{ db.compute_cu_hours.toFixed(2) }}</td>
              <td>&yen;{{ db.compute_cost.toFixed(2) }}</td>
              <td>{{ formatStorage(db.storage_used_gb) }}</td>
              <td>&yen;{{ db.storage_cost.toFixed(2) }}</td>
              <td class="td-total">&yen;{{ db.estimated_cost.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!loading" class="empty-state">
          <p>本账期暂无用量数据</p>
        </div>
        <div v-else class="empty-state"><p>加载中...</p></div>
      </div>
    </div>

    <!-- Pricing info -->
    <div class="pricing-note">
      <div class="note-title">计费说明</div>
      <ul>
        <li>计算用量按数据库实际运行时间计费，挂起状态不计费</li>
        <li>1 CU = 1 vCPU + 4 GB 内存，按 CU·小时计量</li>
        <li>存储用量按实际使用空间计费，包含数据和 WAL 日志，按 GB·月计量</li>
        <li>存储费用 = 当前存储量 × 单价（即使数据库挂起，存储仍持续计费）</li>
        <li>当前为免费公测阶段，所有用量均不收费</li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import client from '../../api/client'

interface DatabaseUsage {
  database_id: string
  database_name: string
  compute_size: string
  compute_seconds: number
  compute_cu_hours: number
  compute_cost: number
  storage_used_gb: number
  storage_cost: number
  estimated_cost: number
}

interface UsageSummary {
  tenant_id: string
  tenant_name: string
  databases: DatabaseUsage[]
  total_compute_seconds: number
  total_compute_cu_hours: number
  total_compute_cost: number
  total_storage_used_gb: number
  total_storage_cost: number
  total_estimated_cost: number
}

const loading = ref(false)
const usage = ref<UsageSummary | null>(null)
const billCycle = ref(getCurrentMonth())
const months = ref(generateMonths())

function getCurrentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function generateMonths(): string[] {
  const result: string[] = []
  const now = new Date()
  for (let i = 0; i < 6; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    result.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }
  return result
}

function formatHours(v?: number): string {
  return v != null ? v.toFixed(2) : '0.00'
}

function formatCost(v?: number): string {
  return v != null ? v.toFixed(2) : '0.00'
}

function formatStorage(v?: number): string {
  if (v == null || v <= 0) return '0.00'
  return v.toFixed(2)
}

function formatDuration(seconds?: number): string {
  if (!seconds || seconds <= 0) return '0 分钟'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (h > 0) return `${h} 小时 ${m} 分钟`
  return `${m} 分钟`
}

async function fetchUsage() {
  loading.value = true
  try {
    const res = await client.get<UsageSummary>('/usage/me', { params: { bill_cycle: billCycle.value } })
    usage.value = res.data
  } catch (e) {
    console.error('Failed to load usage', e)
  } finally {
    loading.value = false
  }
}

onMounted(fetchUsage)
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cycle-select {
  padding: 6px 12px;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  font-size: 14px;
  color: #333;
  background: #fff;
  outline: none;
  cursor: pointer;
}

.cycle-select:focus {
  border-color: #0073e6;
}

.page-desc {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 24px;
  line-height: 1.6;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.summary-card {
  background: #fff;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  padding: 20px;
}

.summary-card.highlight {
  border-color: #0073e6;
  background: #f0f7ff;
}

.summary-value {
  font-size: 28px;
  font-weight: 700;
  color: #191919;
  margin-bottom: 4px;
}

.summary-card.highlight .summary-value {
  color: #0073e6;
}

.summary-label {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 4px;
}

.summary-sub {
  font-size: 12px;
  color: #8a8e99;
}

.free-tag {
  color: #52c41a;
  font-weight: 500;
}

.cost-breakdown {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
  padding: 12px 20px;
  background: #fafafa;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
}

.cost-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cost-label {
  font-size: 13px;
  color: #8a8e99;
}

.cost-value {
  font-size: 15px;
  font-weight: 600;
  color: #333;
}

.cost-plus, .cost-eq {
  font-size: 14px;
  color: #8a8e99;
}

.cost-total .cost-value {
  color: #0073e6;
  font-size: 16px;
}

.section-header {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.section-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #191919;
}

.td-name {
  font-weight: 500;
}

.td-total {
  font-weight: 600;
  color: #0073e6;
}

.pricing-note {
  margin-top: 24px;
  background: #fafafa;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  padding: 16px 20px;
}

.note-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 8px;
}

.pricing-note ul {
  margin: 0;
  padding-left: 20px;
}

.pricing-note li {
  font-size: 13px;
  color: #575d6c;
  line-height: 1.8;
}

@media (max-width: 768px) {
  .summary-cards {
    grid-template-columns: repeat(2, 1fr);
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .cost-breakdown {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (max-width: 480px) {
  .summary-cards {
    grid-template-columns: 1fr;
  }
}
</style>
