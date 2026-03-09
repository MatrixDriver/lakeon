<template>
  <div class="page-monitor">
    <div class="page-header">
      <h1 class="page-title">监控面板</h1>
      <button class="refresh-btn" @click="fetchAll" :disabled="loading">{{ loading ? '刷新中...' : '刷新' }}</button>
    </div>

    <!-- Summary Cards -->
    <div class="metric-cards">
      <div class="metric-card">
        <div class="metric-value">{{ databases.length }}</div>
        <div class="metric-label">数据库总数</div>
        <div class="metric-sub">
          <span class="sub-green">{{ runningCount }}</span> 运行 ·
          <span class="sub-gray">{{ suspendedCount }}</span> 挂起
        </div>
      </div>
      <div class="metric-card">
        <div class="metric-value">{{ totalConnections }}</div>
        <div class="metric-label">当前连接数</div>
        <div class="metric-sub">活跃连接合计</div>
      </div>
      <div class="metric-card">
        <div class="metric-value">{{ totalStorageUsed.toFixed(2) }} <span class="metric-unit">GB</span></div>
        <div class="metric-label">存储用量</div>
        <div class="metric-sub">上限 {{ totalStorageLimit }} GB</div>
      </div>
      <div class="metric-card">
        <div class="metric-value">{{ wakeupCount24h }}</div>
        <div class="metric-label">24h 唤醒次数</div>
        <div class="metric-sub">
          冷启动 {{ coldWakeCount }} · 热启动 {{ warmWakeCount }}
        </div>
      </div>
    </div>

    <!-- Per-Database Metrics -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>数据库详情</h3>
      </div>
      <div class="table-wrapper">
        <table class="data-table" v-if="databases.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>状态</th>
              <th>连接数</th>
              <th>规格</th>
              <th>存储用量</th>
              <th>存储占比</th>
              <th>24h 操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="db in databases" :key="db.id">
              <td>
                <router-link :to="`/databases/${db.id}`" class="db-name-link">{{ db.name }}</router-link>
              </td>
              <td>
                <span class="status-dot" :class="statusClass(db.status)"></span>
                {{ statusText(db.status) }}
              </td>
              <td>
                <span v-if="db.status === 'RUNNING'">{{ db.active_connections || 0 }}</span>
                <span v-else class="text-muted">-</span>
              </td>
              <td>{{ db.compute_size }}</td>
              <td>{{ db.storage_used_gb.toFixed(2) }} / {{ db.storage_limit_gb }} GB</td>
              <td>
                <div class="storage-bar-wrap">
                  <div class="storage-bar">
                    <div class="storage-fill" :class="storageBarColor(db)" :style="{ width: storagePercent(db) + '%' }"></div>
                  </div>
                  <span class="storage-pct">{{ storagePercent(db).toFixed(0) }}%</span>
                </div>
              </td>
              <td>
                <span class="op-badge" v-for="op in dbOpSummary(db.id)" :key="op.type">
                  {{ op.label }} {{ op.count }}
                </span>
                <span v-if="dbOpSummary(db.id).length === 0" class="text-muted">-</span>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">
          <p v-if="loading">加载中...</p>
          <p v-else>暂无数据库</p>
        </div>
      </div>
    </div>

    <!-- 24h Operation Summary -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>24h 操作统计</h3>
      </div>
      <div class="op-summary-grid">
        <div class="op-summary-item" v-for="item in opTypeSummary" :key="item.type">
          <div class="op-summary-count">{{ item.count }}</div>
          <div class="op-summary-label">{{ item.label }}</div>
          <div class="op-summary-bar">
            <div class="op-summary-fill" :style="{ width: opBarWidth(item.count) + '%' }"></div>
          </div>
        </div>
      </div>
      <div v-if="opTypeSummary.length === 0 && !loading" class="empty-state" style="padding: 24px;">
        <p>24h 内暂无操作</p>
      </div>
    </div>

    <!-- Wake Latency -->
    <div class="section-card" style="margin-top: 24px;" v-if="wakeupOps.length > 0">
      <div class="section-header">
        <h3>最近唤醒延迟</h3>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>数据库</th>
              <th>类型</th>
              <th>延迟</th>
              <th>状态</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="op in wakeupOps.slice(0, 10)" :key="op.id">
              <td>{{ op.databaseName }}</td>
              <td>{{ op.durationMs != null && op.durationMs < 3000 ? '热启动' : '冷启动' }}</td>
              <td>
                <span :class="latencyClass(op.durationMs)">
                  {{ formatDuration(op.durationMs) }}
                </span>
              </td>
              <td>
                <span class="status-tag" :class="op.status === 'SUCCESS' ? 'tag-green' : 'tag-red'">
                  {{ op.status === 'SUCCESS' ? '成功' : '失败' }}
                </span>
              </td>
              <td>{{ formatDate(op.startedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { databaseApi, type Database } from '../../api/database'
import { operationApi, type OperationLog } from '../../api/operation'
import { formatDuration, formatDate } from '../../utils/format'

const OP_LABELS: Record<string, string> = {
  CREATE: '创建',
  RESUME: '唤醒',
  SUSPEND: '挂起',
  DELETE: '删除',
  IMPORT: '导入',
  UPDATE: '更新',
  RESET_PASSWORD: '重置密码',
}

const databases = ref<Database[]>([])
const recentOps = ref<OperationLog[]>([])
const loading = ref(true)

const runningCount = computed(() => databases.value.filter(d => d.status === 'RUNNING').length)
const suspendedCount = computed(() => databases.value.filter(d => d.status === 'SUSPENDED').length)
const totalConnections = computed(() => databases.value.reduce((s, d) => s + (d.active_connections || 0), 0))
const totalStorageUsed = computed(() => databases.value.reduce((s, d) => s + (d.storage_used_gb || 0), 0))
const totalStorageLimit = computed(() => databases.value.reduce((s, d) => s + (d.storage_limit_gb || 0), 0))

const wakeupOps = computed(() => recentOps.value.filter(op => op.operationType === 'RESUME'))
const wakeupCount24h = computed(() => wakeupOps.value.length)
const coldWakeCount = computed(() => wakeupOps.value.filter(op => op.durationMs != null && op.durationMs >= 3000).length)
const warmWakeCount = computed(() => wakeupOps.value.filter(op => op.durationMs != null && op.durationMs < 3000).length)

const opTypeSummary = computed(() => {
  const counts: Record<string, number> = {}
  for (const op of recentOps.value) {
    counts[op.operationType] = (counts[op.operationType] || 0) + 1
  }
  return Object.entries(counts)
    .map(([type, count]) => ({ type, count, label: OP_LABELS[type] || type }))
    .sort((a, b) => b.count - a.count)
})

const maxOpCount = computed(() => Math.max(...opTypeSummary.value.map(i => i.count), 1))

function opBarWidth(count: number): number {
  return (count / maxOpCount.value) * 100
}

function dbOpSummary(dbId: string) {
  const counts: Record<string, number> = {}
  for (const op of recentOps.value) {
    if (op.databaseId === dbId) {
      counts[op.operationType] = (counts[op.operationType] || 0) + 1
    }
  }
  return Object.entries(counts)
    .map(([type, count]) => ({ type, count, label: OP_LABELS[type] || type }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 3)
}

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    default: return 'dot-red'
  }
}

function statusText(status: string): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'SUSPENDED': return '已挂起'
    case 'CREATING': return '创建中'
    default: return '异常'
  }
}

function storagePercent(db: Database): number {
  if (db.storage_limit_gb === 0) return 0
  return Math.min(100, (db.storage_used_gb / db.storage_limit_gb) * 100)
}

function storageBarColor(db: Database): string {
  const pct = storagePercent(db)
  if (pct >= 90) return 'fill-red'
  if (pct >= 70) return 'fill-orange'
  return ''
}

function latencyClass(ms: number | null): string {
  if (ms == null) return ''
  if (ms < 3000) return 'latency-good'
  if (ms < 10000) return 'latency-warn'
  return 'latency-bad'
}

async function fetchAll() {
  loading.value = true
  try {
    const [dbRes, opsRes] = await Promise.all([
      databaseApi.list(),
      operationApi.getRecent(),
    ])
    databases.value = dbRes.data
    recentOps.value = opsRes.data
  } catch (e) {
    console.error('Failed to load monitor data', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchAll())
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
  margin-bottom: 4px;
}

.metric-unit {
  font-size: 14px;
  font-weight: 400;
  color: #8a8e99;
}

.metric-label {
  font-size: 14px;
  color: #575d6c;
}

.metric-sub {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.sub-green { color: #52c41a; }
.sub-gray { color: #999; }

.db-name-link {
  color: #0073e6;
  text-decoration: none;
}

.db-name-link:hover {
  text-decoration: underline;
}

.text-muted {
  color: #ccc;
}

/* Storage bar */
.storage-bar-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.storage-bar {
  width: 80px;
  height: 6px;
  background-color: #e8e8e8;
  border-radius: 3px;
  overflow: hidden;
  flex-shrink: 0;
}

.storage-fill {
  height: 100%;
  background-color: #0073e6;
  border-radius: 3px;
  transition: width 0.3s;
}

.storage-fill.fill-orange { background-color: #e37318; }
.storage-fill.fill-red { background-color: #e6393d; }

.storage-pct {
  font-size: 12px;
  color: #8a8e99;
  min-width: 32px;
}

/* Operation badges */
.op-badge {
  display: inline-block;
  padding: 2px 8px;
  margin-right: 4px;
  font-size: 12px;
  color: #575d6c;
  background: #f5f5f5;
  border-radius: 3px;
}

/* Operation summary */
.op-summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
  padding: 16px;
}

.op-summary-item {
  text-align: center;
}

.op-summary-count {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
}

.op-summary-label {
  font-size: 13px;
  color: #575d6c;
  margin-bottom: 8px;
}

.op-summary-bar {
  height: 4px;
  background: #f0f0f0;
  border-radius: 2px;
  overflow: hidden;
}

.op-summary-fill {
  height: 100%;
  background: #0073e6;
  border-radius: 2px;
  transition: width 0.3s;
}

/* Wake latency */
.latency-good { color: #52c41a; font-weight: 600; }
.latency-warn { color: #e37318; font-weight: 600; }
.latency-bad { color: #e6393d; font-weight: 600; }

/* Refresh button */
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
  .metric-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
