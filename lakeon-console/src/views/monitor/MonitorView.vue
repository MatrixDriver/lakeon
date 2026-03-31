<template>
  <div class="page-monitor">
    <div class="page-header">
      <h1 class="page-title">监控面板</h1>
      <button class="refresh-btn" @click="fetchAll" :disabled="loading">{{ loading ? '刷新中...' : '刷新' }}</button>
    </div>

    <!-- Tab Header -->
    <div class="monitor-tabs">
      <button class="monitor-tab" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">服务总览</button>
      <button class="monitor-tab" :class="{ active: activeTab === 'wakeup' }" @click="activeTab = 'wakeup'">唤醒监控</button>
      <button class="monitor-tab" :class="{ active: activeTab === 'performance' }" @click="activeTab = 'performance'">性能诊断</button>
      <button class="monitor-tab" :class="{ active: activeTab === 'usage' }" @click="activeTab = 'usage'">用量统计</button>
    </div>

    <!-- ====== Tab 1: Service Overview ====== -->
    <div v-if="activeTab === 'overview'">
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
          <div class="metric-value">{{ totalBranches }}</div>
          <div class="metric-label">分支总数</div>
          <div class="metric-sub">{{ databases.length }} 个数据库</div>
        </div>
      </div>

      <!-- Database List -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>数据库实例</h3>
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
                  <span v-if="db.active_connections > 0">{{ db.active_connections }}</span>
                  <span v-else-if="db.status === 'RUNNING'">0</span>
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
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">
            <p v-if="loading">加载中...</p>
            <p v-else>暂无数据库</p>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== Tab 2: Wakeup Monitoring ====== -->
    <div v-if="activeTab === 'wakeup'">
      <!-- Wakeup summary cards -->
      <div class="metric-cards">
        <div class="metric-card">
          <div class="metric-value">{{ wakeupCount24h }}</div>
          <div class="metric-label">24h 唤醒次数</div>
          <div class="metric-sub">
            冷启动 {{ coldWakeCount }} · 热启动 {{ warmWakeCount }}
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-value" :class="avgLatencyClass">{{ avgWakeLatency }}</div>
          <div class="metric-label">平均唤醒延迟</div>
          <div class="metric-sub">
            最快 {{ minWakeLatency }} · 最慢 {{ maxWakeLatency }}
          </div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ wakeSuccessRate }}</div>
          <div class="metric-label">唤醒成功率</div>
          <div class="metric-sub">{{ wakeFailCount }} 次失败</div>
        </div>
        <div class="metric-card">
          <div class="metric-value">{{ coldRatio }}</div>
          <div class="metric-label">冷启动比例</div>
          <div class="metric-sub">冷启动 &ge; 3s</div>
        </div>
      </div>

      <!-- Latency distribution -->
      <div class="section-card" style="margin-top: 24px;" v-if="wakeupOps.length > 0">
        <div class="section-header">
          <h3>延迟分布</h3>
        </div>
        <div class="latency-dist">
          <div class="latency-bar-group" v-for="bucket in latencyBuckets" :key="bucket.label">
            <div class="latency-bar-label">{{ bucket.label }}</div>
            <div class="latency-bar-track">
              <div class="latency-bar-fill" :class="bucket.color" :style="{ width: bucket.pct + '%' }"></div>
            </div>
            <div class="latency-bar-count">{{ bucket.count }}</div>
          </div>
        </div>
      </div>

      <!-- Recent wakeup records -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>最近唤醒记录</h3>
        </div>
        <div class="table-wrapper">
          <table class="data-table" v-if="wakeupOps.length > 0">
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
              <tr v-for="op in wakeupOps.slice(0, 20)" :key="op.id">
                <td>{{ op.databaseName }}</td>
                <td>
                  <span class="wake-type-tag" :class="op.durationMs != null && op.durationMs < 3000 ? 'tag-warm' : 'tag-cold'">
                    {{ op.durationMs != null && op.durationMs < 3000 ? '热启动' : '冷启动' }}
                  </span>
                </td>
                <td>
                  <span :class="latencyColorClass(op.durationMs)">
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
          <div v-else class="empty-state">
            <p>24h 内暂无唤醒记录</p>
          </div>
        </div>
      </div>
    </div>

    <!-- ====== Tab 3: Performance Diagnosis ====== -->
    <div v-if="activeTab === 'performance'">
      <div class="perf-selector" v-if="databases.length > 0">
        <label class="perf-selector-label">选择数据库</label>
        <select v-model="selectedDbId" class="perf-select">
          <option v-for="db in databases" :key="db.id" :value="db.id">
            {{ db.name }} ({{ statusText(db.status) }})
          </option>
        </select>
      </div>
      <PerformanceMonitor
        v-if="selectedDb"
        :key="selectedDb.id"
        :dbId="selectedDb.id"
        :dbName="selectedDb.name"
        :status="selectedDb.status"
      />
      <div v-else class="empty-state" style="padding: 48px;">
        <p>暂无数据库</p>
      </div>
    </div>

    <!-- ====== Tab 4: Usage Statistics ====== -->
    <div v-if="activeTab === 'usage'">
      <!-- Storage ranking -->
      <div class="section-card">
        <div class="section-header">
          <h3>存储用量排行</h3>
          <span class="section-sub">总计 {{ totalStorageUsed.toFixed(2) }} / {{ totalStorageLimit }} GB</span>
        </div>
        <div class="table-wrapper">
          <table class="data-table" v-if="storageRanking.length > 0">
            <thead>
              <tr>
                <th>排名</th>
                <th>数据库</th>
                <th>存储用量</th>
                <th>存储上限</th>
                <th>使用率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, idx) in storageRanking" :key="item.id">
                <td>
                  <span class="rank-badge" :class="idx < 3 ? 'rank-top' : ''">{{ idx + 1 }}</span>
                </td>
                <td>
                  <router-link :to="`/databases/${item.id}`" class="db-name-link">{{ item.name }}</router-link>
                </td>
                <td>{{ item.storage_used_gb.toFixed(2) }} GB</td>
                <td>{{ item.storage_limit_gb }} GB</td>
                <td>
                  <div class="storage-bar-wrap">
                    <div class="storage-bar" style="width: 120px;">
                      <div class="storage-fill" :class="storageBarColor(item)" :style="{ width: storagePercent(item) + '%' }"></div>
                    </div>
                    <span class="storage-pct">{{ storagePercent(item).toFixed(1) }}%</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">
            <p>暂无数据库</p>
          </div>
        </div>
      </div>

      <!-- Branch storage details -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>分支存储明细</h3>
          <span class="section-sub">{{ totalBranches }} 个分支</span>
        </div>
        <div v-if="branchDataLoading" class="empty-state"><p>加载中...</p></div>
        <template v-else>
          <div v-for="dbBranch in branchStorageData" :key="dbBranch.dbId" class="branch-db-group">
            <div class="branch-db-header" @click="dbBranch.expanded = !dbBranch.expanded">
              <span class="branch-expand-icon">{{ dbBranch.expanded ? '▾' : '▸' }}</span>
              <span class="branch-db-name">{{ dbBranch.dbName }}</span>
              <span class="branch-db-count">{{ dbBranch.branches.length }} 个分支</span>
              <span class="branch-db-size">{{ formatSize(dbBranch.totalSize) }}</span>
            </div>
            <div v-if="dbBranch.expanded" class="branch-list">
              <div v-for="branch in dbBranch.branches" :key="branch.id" class="branch-row">
                <span class="branch-name">
                  {{ branch.name }}
                  <span v-if="branch.is_default" class="default-tag">默认</span>
                  <span v-if="branch.idle" class="idle-tag">闲置</span>
                </span>
                <span class="branch-size">{{ formatSize(branch.current_logical_size_bytes) }}</span>
              </div>
            </div>
          </div>
          <div v-if="branchStorageData.length === 0" class="empty-state"><p>暂无分支数据</p></div>
        </template>
      </div>

      <!-- Wakeup frequency -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>唤醒频次分析</h3>
          <span class="section-sub">24h 内各数据库唤醒次数</span>
        </div>
        <div v-if="wakeupByDb.length > 0" class="wakeup-freq-list">
          <div class="wakeup-freq-row" v-for="item in wakeupByDb" :key="item.dbId">
            <span class="wakeup-freq-name">{{ item.dbName }}</span>
            <div class="wakeup-freq-bar-wrap">
              <div class="wakeup-freq-bar">
                <div class="wakeup-freq-fill" :style="{ width: wakeupBarPct(item.count) + '%' }"></div>
              </div>
              <span class="wakeup-freq-count">{{ item.count }} 次</span>
            </div>
            <span v-if="item.count >= 10" class="wakeup-freq-tip">频繁唤醒，建议增大挂起超时</span>
          </div>
        </div>
        <div v-else class="empty-state"><p>24h 内无唤醒记录</p></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { databaseApi, type Database } from '../../api/database'
import { branchApi } from '../../api/branch'
import { operationApi, type OperationLog } from '../../api/operation'
import { formatDuration, formatDate, formatSize } from '../../utils/format'
import PerformanceMonitor from '../../components/PerformanceMonitor.vue'

const activeTab = ref('overview')
const databases = ref<Database[]>([])
const recentOps = ref<OperationLog[]>([])
const loading = ref(true)
const selectedDbId = ref('')

const selectedDb = computed(() => databases.value.find(d => d.id === selectedDbId.value) || null)

// ── Overview computeds ──
const runningCount = computed(() => databases.value.filter(d => d.status === 'RUNNING').length)
const suspendedCount = computed(() => databases.value.filter(d => d.status === 'SUSPENDED').length)
const totalConnections = computed(() => databases.value.reduce((s, d) => s + (d.active_connections || 0), 0))
const totalStorageUsed = computed(() => databases.value.reduce((s, d) => s + (d.storage_used_gb || 0), 0))
const totalStorageLimit = computed(() => databases.value.reduce((s, d) => s + (d.storage_limit_gb || 0), 0))
const totalBranches = computed(() => databases.value.reduce((s, d) => s + (d.branches?.length || 0), 0))

// ── Wakeup computeds ──
const wakeupOps = computed(() => recentOps.value.filter(op => op.operationType === 'RESUME'))
const wakeupCount24h = computed(() => wakeupOps.value.length)
const successWakeups = computed(() => wakeupOps.value.filter(op => op.status === 'SUCCESS' && op.durationMs != null))
const coldWakeCount = computed(() => successWakeups.value.filter(op => op.durationMs! >= 3000).length)
const warmWakeCount = computed(() => successWakeups.value.filter(op => op.durationMs! < 3000).length)
const wakeFailCount = computed(() => wakeupOps.value.filter(op => op.status !== 'SUCCESS').length)

const wakeSuccessRate = computed(() => {
  if (wakeupCount24h.value === 0) return '-'
  return ((wakeupCount24h.value - wakeFailCount.value) / wakeupCount24h.value * 100).toFixed(1) + '%'
})

const coldRatio = computed(() => {
  if (successWakeups.value.length === 0) return '-'
  return (coldWakeCount.value / successWakeups.value.length * 100).toFixed(0) + '%'
})

const avgWakeLatency = computed(() => {
  if (successWakeups.value.length === 0) return '-'
  const avg = successWakeups.value.reduce((s, op) => s + op.durationMs!, 0) / successWakeups.value.length
  return formatDuration(Math.round(avg))
})

const avgLatencyClass = computed(() => {
  if (successWakeups.value.length === 0) return ''
  const avg = successWakeups.value.reduce((s, op) => s + op.durationMs!, 0) / successWakeups.value.length
  return latencyColorClass(avg)
})

const minWakeLatency = computed(() => {
  if (successWakeups.value.length === 0) return '-'
  return formatDuration(Math.min(...successWakeups.value.map(op => op.durationMs!)))
})

const maxWakeLatency = computed(() => {
  if (successWakeups.value.length === 0) return '-'
  return formatDuration(Math.max(...successWakeups.value.map(op => op.durationMs!)))
})

// Latency distribution buckets
const latencyBuckets = computed(() => {
  const ops = successWakeups.value
  const buckets = [
    { label: '< 1s', min: 0, max: 1000, count: 0, color: 'fill-green', pct: 0 },
    { label: '1-3s', min: 1000, max: 3000, count: 0, color: 'fill-green', pct: 0 },
    { label: '3-5s', min: 3000, max: 5000, count: 0, color: 'fill-orange', pct: 0 },
    { label: '5-10s', min: 5000, max: 10000, count: 0, color: 'fill-orange', pct: 0 },
    { label: '> 10s', min: 10000, max: Infinity, count: 0, color: 'fill-red', pct: 0 },
  ]
  for (const op of ops) {
    const ms = op.durationMs!
    for (const b of buckets) {
      if (ms >= b.min && ms < b.max) { b.count++; break }
    }
  }
  const maxCount = Math.max(...buckets.map(b => b.count), 1)
  for (const b of buckets) b.pct = (b.count / maxCount) * 100
  return buckets
})

// ── Usage computeds ──
const storageRanking = computed(() =>
  [...databases.value].sort((a, b) => b.storage_used_gb - a.storage_used_gb)
)

// Wakeup frequency by database
const wakeupByDb = computed(() => {
  const map = new Map<string, { dbId: string; dbName: string; count: number }>()
  for (const op of wakeupOps.value) {
    const existing = map.get(op.databaseId)
    if (existing) {
      existing.count++
    } else {
      map.set(op.databaseId, { dbId: op.databaseId, dbName: op.databaseName, count: 1 })
    }
  }
  return [...map.values()].sort((a, b) => b.count - a.count)
})

const maxWakeupFreq = computed(() => Math.max(...wakeupByDb.value.map(i => i.count), 1))

function wakeupBarPct(count: number): number {
  return (count / maxWakeupFreq.value) * 100
}

// Branch storage data
interface BranchStorageItem {
  dbId: string
  dbName: string
  totalSize: number
  expanded: boolean
  branches: { id: string; name: string; is_default: boolean; current_logical_size_bytes: number; idle: boolean }[]
}

const branchStorageData = reactive<BranchStorageItem[]>([])
const branchDataLoading = ref(false)

async function fetchBranchData() {
  branchDataLoading.value = true
  branchStorageData.length = 0
  try {
    const results = await Promise.allSettled(
      databases.value.map(async (db) => {
        const res = await branchApi.list(db.id)
        return { db, branches: res.data }
      })
    )
    for (const result of results) {
      if (result.status === 'fulfilled') {
        const { db, branches } = result.value
        const now = Date.now()
        branchStorageData.push({
          dbId: db.id,
          dbName: db.name,
          totalSize: branches.reduce((s, b) => s + (b.current_logical_size_bytes || 0), 0),
          expanded: false,
          branches: branches.map(b => ({
            id: b.id,
            name: b.name,
            is_default: b.is_default,
            current_logical_size_bytes: b.current_logical_size_bytes || 0,
            idle: !b.is_default && !!b.created_at && (now - new Date(b.created_at).getTime()) > 7 * 24 * 60 * 60 * 1000,
          })).sort((a, b) => b.current_logical_size_bytes - a.current_logical_size_bytes),
        })
      }
    }
    branchStorageData.sort((a, b) => b.totalSize - a.totalSize)
  } catch (e) {
    console.error('Failed to load branch data', e)
  } finally {
    branchDataLoading.value = false
  }
}

// ── Shared helpers ──
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

function latencyColorClass(ms: number | null): string {
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
    if (!selectedDbId.value && databases.value.length > 0) {
      selectedDbId.value = databases.value[0]!.id
    }
  } catch (e) {
    console.error('Failed to load monitor data', e)
  } finally {
    loading.value = false
  }
}

// Lazy load branch data when switching to usage tab
watch(activeTab, (tab) => {
  if (tab === 'usage' && branchStorageData.length === 0 && databases.value.length > 0) {
    fetchBranchData()
  }
})

onMounted(() => fetchAll())
</script>

<style scoped>
/* Tab header */
.monitor-tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid #e5e5e5;
  margin-bottom: 20px;
}

.monitor-tab {
  padding: 10px 24px;
  font-size: 14px;
  color: #64748b;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.15s;
}

.monitor-tab:hover { color: #9a5b25; }

.monitor-tab.active {
  color: #9a5b25;
  font-weight: 600;
  border-bottom-color: #c67d3a;
}

/* Summary cards */
.metric-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.metric-card {
  background: #fff;
  border: 1px solid #e8e4df;
  border-radius: 8px;
  padding: 20px 24px;
  text-align: center;
  border-top: 3px solid #e8e4df;
}

.metric-card:nth-child(1) { border-top-color: #1e2d3d; }
.metric-card:nth-child(2) { border-top-color: #c67d3a; }
.metric-card:nth-child(3) { border-top-color: #2d6a4f; }
.metric-card:nth-child(4) { border-top-color: #64748b; }

.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #2c3e50;
  margin-bottom: 4px;
}


.metric-unit {
  font-size: 14px;
  font-weight: 400;
  color: #8a8e99;
}

.metric-label {
  font-size: 14px;
  color: #64748b;
}

.metric-sub {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.sub-green { color: #52c41a; }
.sub-gray { color: #999; }

/* Database table */
.db-name-link {
  color: #9a5b25;
  text-decoration: none;
}

.db-name-link:hover { text-decoration: underline; }

.text-muted { color: #ccc; }

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
  background-color: #9a5b25;
  border-radius: 3px;
  transition: width 0.3s;
}

.storage-fill.fill-orange { background-color: #e37318; }
.storage-fill.fill-red { background-color: #e6393d; }
.storage-fill.fill-green { background-color: #52c41a; }

.storage-pct {
  font-size: 12px;
  color: #8a8e99;
  min-width: 32px;
}

/* Wakeup tab */
.wake-type-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
}

.tag-warm { background: #f6ffed; color: #389e0d; }
.tag-cold { background: #fff7e6; color: #d48806; }

.latency-good { color: #52c41a; font-weight: 600; }
.latency-warn { color: #e37318; font-weight: 600; }
.latency-bad { color: #e6393d; font-weight: 600; }

/* Latency distribution */
.latency-dist {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.latency-bar-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.latency-bar-label {
  width: 60px;
  font-size: 13px;
  color: #64748b;
  text-align: right;
  flex-shrink: 0;
}

.latency-bar-track {
  flex: 1;
  height: 20px;
  background: #f5f5f5;
  border-radius: 4px;
  overflow: hidden;
}

.latency-bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s;
  background-color: #9a5b25;
}

.latency-bar-fill.fill-green { background-color: #52c41a; }
.latency-bar-fill.fill-orange { background-color: #e37318; }
.latency-bar-fill.fill-red { background-color: #e6393d; }

.latency-bar-count {
  width: 32px;
  font-size: 13px;
  color: #2c3e50;
  font-weight: 600;
  flex-shrink: 0;
}

/* Performance selector */
.perf-selector {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.perf-selector-label {
  font-size: 14px;
  color: #64748b;
  white-space: nowrap;
}

.perf-select {
  padding: 6px 12px;
  font-size: 14px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  color: #2c3e50;
  background: #fff;
  min-width: 200px;
  cursor: pointer;
}

.perf-select:focus {
  outline: none;
  border-color: #c67d3a;
}

/* Usage tab */
.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 600;
  color: #8a8e99;
  background: #f5f5f5;
}

.rank-badge.rank-top {
  background: #9a5b25;
  color: #fff;
}

.section-sub {
  font-size: 13px;
  color: #8a8e99;
}

/* Branch storage */
.branch-db-group {
  border-bottom: 1px solid #f0f0f0;
}

.branch-db-group:last-child { border-bottom: none; }

.branch-db-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  cursor: pointer;
  transition: background 0.15s;
}

.branch-db-header:hover { background: #fafafa; }

.branch-expand-icon {
  width: 16px;
  font-size: 12px;
  color: #8a8e99;
}

.branch-db-name {
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
  flex: 1;
}

.branch-db-count {
  font-size: 12px;
  color: #8a8e99;
}

.branch-db-size {
  font-size: 13px;
  color: #64748b;
  font-weight: 600;
  min-width: 80px;
  text-align: right;
}

.branch-list {
  padding: 0 20px 12px 44px;
}

.branch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 13px;
  color: #64748b;
  border-bottom: 1px solid #fafafa;
}

.branch-row:last-child { border-bottom: none; }

.branch-name {
  display: flex;
  align-items: center;
  gap: 6px;
}

.branch-size {
  color: #8a8e99;
}

.default-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  background: #fdf5ed;
  color: #9a5b25;
}

.idle-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  background: #fff7e6;
  color: #d48806;
}

/* Wakeup frequency */
.wakeup-freq-list {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.wakeup-freq-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.wakeup-freq-name {
  width: 120px;
  font-size: 13px;
  color: #2c3e50;
  font-weight: 500;
  flex-shrink: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wakeup-freq-bar-wrap {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.wakeup-freq-bar {
  flex: 1;
  height: 16px;
  background: #f5f5f5;
  border-radius: 4px;
  overflow: hidden;
}

.wakeup-freq-fill {
  height: 100%;
  background: #9a5b25;
  border-radius: 4px;
  transition: width 0.3s;
}

.wakeup-freq-count {
  font-size: 13px;
  color: #64748b;
  min-width: 48px;
  white-space: nowrap;
}

.wakeup-freq-tip {
  font-size: 12px;
  color: #e37318;
  white-space: nowrap;
}

/* Refresh button */
.refresh-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 14px;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
}

.refresh-btn:hover:not(:disabled) {
  border-color: #c67d3a;
  color: #9a5b25;
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 768px) {
  .metric-cards {
    grid-template-columns: repeat(2, 1fr);
  }

  .wakeup-freq-name {
    width: 80px;
  }

  .wakeup-freq-tip {
    display: none;
  }
}
</style>
