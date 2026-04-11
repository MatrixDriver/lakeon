<template>
  <div class="db-page">
    <header class="db-page-header">
      <h1 class="db-title">数据库</h1>
      <p class="db-subtitle">Lakeon 集群所有 Serverless PostgreSQL 实例</p>
    </header>

    <nav class="db-tab-bar" role="tablist">
      <button
        class="db-tab"
        :class="{ 'is-active': activeTab === 'coldstart' }"
        role="tab"
        :aria-selected="activeTab === 'coldstart'"
        @click="setTab('coldstart')"
      >冷启动分析</button>
      <button
        class="db-tab"
        :class="{ 'is-active': activeTab === 'list' }"
        role="tab"
        :aria-selected="activeTab === 'list'"
        @click="setTab('list')"
      >数据库列表</button>
    </nav>

    <!-- ══════════  COLD START TAB  ══════════ -->
    <section v-if="activeTab === 'coldstart'" class="db-pane">
      <!-- Hero: P99 as protagonist -->
      <div class="cs-hero" v-if="csData?.cold">
        <div class="cs-hero-main">
          <div
            class="cs-hero-number"
            :style="{ color: csTextColor(csData.cold.p99_ms) }"
          >{{ csFormatMs(csData.cold.p99_ms) }}</div>
          <div class="cs-hero-label">
            <span class="cs-hero-label-key">P99</span>
            <span class="cs-hero-label-sep">·</span>
            <span>最近 {{ csDays }} 天</span>
            <span class="cs-hero-label-sep">·</span>
            <span>{{ csData.cold.count || 0 }} 次冷启动</span>
          </div>
          <div class="cs-hero-secondary" v-if="csData.cold.p90_ms != null">
            <span class="cs-hero-secondary-label">P90 参照</span>
            <span
              class="cs-hero-secondary-value"
              :style="{ color: csTextColor(csData.cold.p90_ms) }"
            >{{ csFormatMs(csData.cold.p90_ms) }}</span>
          </div>
        </div>

        <div class="cs-hero-window">
          <label class="cs-window-label">时间窗口</label>
          <select v-model.number="csDays" @change="loadColdStart" class="cs-window-select">
            <option :value="1">最近 1 天</option>
            <option :value="3">最近 3 天</option>
            <option :value="7">最近 7 天</option>
            <option :value="30">最近 30 天</option>
          </select>
        </div>

        <div class="cs-hero-narrative" v-if="csTrendAnalysis">
          <div class="cs-narrative-primary">
            <span class="cs-narrative-lede">最近均值</span>
            <span
              class="cs-narrative-value"
              :style="{ color: csTextColor(csTrendAnalysis.recentAvg) }"
            >{{ csFormatMs(csTrendAnalysis.recentAvg) }}</span>
            <span
              v-if="csTrendAnalysis.changePercent != null"
              class="cs-narrative-delta"
              :class="csTrendAnalysis.changePercent <= 0 ? 'is-improved' : 'is-degraded'"
            >
              <span class="cs-narrative-arrow" aria-hidden="true">
                {{ csTrendAnalysis.changePercent <= 0 ? '↓' : '↑' }}
              </span>
              <span>
                比前半段 {{ csFormatMs(csTrendAnalysis.earlyAvg) }}
                {{ csTrendAnalysis.changePercent <= 0 ? '改善' : '恶化' }}
                {{ Math.abs(csTrendAnalysis.changePercent) }}%
              </span>
            </span>
          </div>
          <div class="cs-narrative-secondary">
            <span v-if="csTrendAnalysis.bestDay" class="cs-narrative-day">
              <span class="cs-narrative-day-lbl">最佳日</span>
              <span>{{ csTrendAnalysis.bestDay.date.substring(5) }}</span>
              <span class="cs-narrative-day-val is-improved">
                {{ csFormatMs(csTrendAnalysis.bestDay.avg_ms) }}
              </span>
            </span>
            <span v-if="csTrendAnalysis.worstDay" class="cs-narrative-day">
              <span class="cs-narrative-day-lbl">最差日</span>
              <span>{{ csTrendAnalysis.worstDay.date.substring(5) }}</span>
              <span
                class="cs-narrative-day-val"
                :style="{ color: csTextColor(csTrendAnalysis.worstDay.avg_ms) }"
              >{{ csFormatMs(csTrendAnalysis.worstDay.avg_ms) }}</span>
            </span>
          </div>
        </div>
      </div>

      <!-- Empty hero: no cold start data -->
      <div class="cs-hero-empty" v-else-if="!loadingCs">
        <div class="cs-empty-dash">—</div>
        <div class="cs-empty-text">最近 {{ csDays }} 天没有冷启动发生</div>
        <div class="cs-empty-sub">冷启动会在数据库被挂起后第一次访问时记录</div>
        <div class="cs-hero-window cs-hero-window--empty">
          <select v-model.number="csDays" @change="loadColdStart" class="cs-window-select">
            <option :value="1">最近 1 天</option>
            <option :value="3">最近 3 天</option>
            <option :value="7">最近 7 天</option>
            <option :value="30">最近 30 天</option>
          </select>
        </div>
      </div>

      <!-- Loading skeleton for hero -->
      <div class="cs-hero-skeleton" v-else>
        <div class="sk sk-number"></div>
        <div class="sk sk-line sk-line-lg"></div>
        <div class="sk sk-line"></div>
      </div>

      <!-- Secondary percentiles row -->
      <div class="cs-percentiles" v-if="csData?.cold">
        <div class="cs-perc">
          <div class="cs-perc-value">{{ csFormatMs(csData.cold.avg_ms) }}</div>
          <div class="cs-perc-label">均值</div>
        </div>
        <div class="cs-perc">
          <div class="cs-perc-value">{{ csFormatMs(csData.cold.p50_ms) }}</div>
          <div class="cs-perc-label">P50</div>
        </div>
        <div class="cs-perc">
          <div class="cs-perc-value">{{ csFormatMs(csData.cold.p90_ms) }}</div>
          <div class="cs-perc-label">P90</div>
        </div>
        <div class="cs-perc">
          <div class="cs-perc-value">{{ csFormatMs(csData.cold.p99_ms) }}</div>
          <div class="cs-perc-label">P99</div>
        </div>
        <div class="cs-perc-divider" aria-hidden="true"></div>
        <div class="cs-perc">
          <div class="cs-perc-value cs-perc-warm">{{ csFormatMs(csData.warm?.avg_ms) }}</div>
          <div class="cs-perc-label">热启均值 · {{ csData.warm?.count || 0 }} 次</div>
        </div>
      </div>

      <!-- Daily trend -->
      <div class="cs-section" v-if="csData?.trend?.length">
        <h2 class="cs-section-title">每日趋势</h2>
        <div class="cs-trend-chart">
          <div
            v-for="(d, idx) in csData.trend"
            :key="d.date"
            class="cs-trend-col"
            :style="{ '--col-idx': idx }"
          >
            <div class="cs-trend-bar-wrap">
              <div
                class="cs-trend-bar"
                :style="{
                  height: csBarHeight(d.avg_ms) + 'px',
                  background: csBarColor(d.avg_ms)
                }"
                :title="`${d.date}: 均值 ${csFormatMs(d.avg_ms)}, ${d.count} 次`"
              ></div>
              <div
                v-if="csTrendDelta(idx) != null"
                class="cs-trend-delta"
                :class="(csTrendDelta(idx) ?? 0) <= 0 ? 'is-improved' : 'is-degraded'"
              >{{ (csTrendDelta(idx) ?? 0) > 0 ? '+' : '' }}{{ csTrendDelta(idx) }}%</div>
            </div>
            <div class="cs-trend-value">{{ csFormatMs(d.avg_ms) }}</div>
            <div class="cs-trend-date">{{ d.date.substring(5) }}</div>
            <div class="cs-trend-count">{{ d.count }} 次</div>
          </div>
        </div>
      </div>

      <!-- Bottom two-column: per-db + recent records -->
      <div
        class="cs-bottom-grid"
        v-if="(csData?.by_database?.length || 0) > 0 || (csData?.recent?.length || 0) > 0"
      >
        <div class="cs-bottom-col cs-bottom-main">
          <h2 class="cs-section-title">按数据库分布</h2>
          <table class="cs-subtable">
            <thead>
              <tr>
                <th>数据库</th>
                <th class="num">次数</th>
                <th class="num">均值</th>
                <th class="num">P50</th>
                <th class="num">最大</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="db in csData?.by_database || []" :key="db.database">
                <td class="cs-td-name">{{ db.database }}</td>
                <td class="num">{{ db.count }}</td>
                <td
                  class="num"
                  :style="{ color: csTextColor(db.avg_ms), fontWeight: 500 }"
                >{{ csFormatMs(db.avg_ms) }}</td>
                <td class="num">{{ csFormatMs(db.p50_ms) }}</td>
                <td
                  class="num"
                  :style="{ color: csTextColor(db.max_ms) }"
                >{{ csFormatMs(db.max_ms) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="cs-bottom-col cs-bottom-aside">
          <h2 class="cs-section-title">最近冷启动</h2>
          <table class="cs-subtable" v-if="csData?.recent?.length">
            <thead>
              <tr>
                <th>时间</th>
                <th>数据库</th>
                <th class="num">耗时</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="op in csData.recent" :key="op.id">
                <td class="cs-td-time">{{ fmtShortTime(op.started_at) }}</td>
                <td>{{ op.database_name || op.database_id }}</td>
                <td
                  class="num"
                  :style="{ color: csTextColor(op.duration_ms), fontWeight: 500 }"
                >{{ csFormatMs(op.duration_ms) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="cs-empty-small">暂无冷启动记录</div>
        </div>
      </div>
    </section>

    <!-- ══════════  LIST TAB  ══════════ -->
    <section v-if="activeTab === 'list'" class="db-pane">
      <!-- Status line (replaces 6-card grid) -->
      <div class="db-status-line">
        <span class="sl-item sl-total">
          <span class="sl-num">{{ databases.length }}</span>
          <span class="sl-lbl">个数据库</span>
        </span>
        <span class="sl-sep" aria-hidden="true"></span>
        <span class="sl-item">
          <span class="sl-chip sl-chip-running"></span>
          <span class="sl-lbl">运行中</span>
          <span class="sl-num">{{ statCount('RUNNING') }}</span>
        </span>
        <span class="sl-item">
          <span class="sl-chip sl-chip-suspended"></span>
          <span class="sl-lbl">暂停</span>
          <span class="sl-num">{{ statCount('SUSPENDED') }}</span>
        </span>
        <span class="sl-item" :class="{ 'sl-alert': failedCount > 0 }">
          <span class="sl-chip sl-chip-failed"></span>
          <span class="sl-lbl">异常</span>
          <span class="sl-num">{{ failedCount }}</span>
        </span>
        <span class="sl-item">
          <span class="sl-chip sl-chip-creating"></span>
          <span class="sl-lbl">创建中</span>
          <span class="sl-num">{{ statCount('CREATING') }}</span>
        </span>
        <span class="sl-item" v-if="statCount('DELETED') > 0">
          <span class="sl-chip sl-chip-deleted"></span>
          <span class="sl-lbl">回收站</span>
          <span class="sl-num">{{ statCount('DELETED') }}</span>
        </span>
      </div>

      <!-- Filters -->
      <div class="db-filters">
        <select class="db-select" v-model="statusFilter" @change="loadDatabases">
          <option value="">全部状态</option>
          <option value="RUNNING">运行中</option>
          <option value="SUSPENDED">已暂停</option>
          <option value="CREATING">创建中</option>
          <option value="ERROR">异常</option>
          <option value="DELETED">回收站</option>
        </select>
        <input
          class="db-input"
          type="text"
          placeholder="按租户 ID 筛选"
          v-model="tenantFilter"
          @keyup.enter="loadDatabases"
        />
        <button class="db-btn db-btn-primary" @click="loadDatabases">筛选</button>

        <div v-if="selectedIds.size > 0" class="db-batch-inline">
          <span class="db-batch-count">已选择 {{ selectedIds.size }} 个</span>
          <button class="db-btn db-btn-danger" @click="confirmBatchDelete" :disabled="deleting">
            {{ deleting ? '删除中…' : '批量删除' }}
          </button>
        </div>
      </div>

      <!-- Table -->
      <div class="db-table-wrap">
        <table class="db-table">
          <thead>
            <tr>
              <th class="th-check">
                <input type="checkbox" :checked="allSelected" @change="toggleAll" />
              </th>
              <th>名称</th>
              <th>租户</th>
              <th>状态</th>
              <th>状态信息</th>
              <th>规格</th>
              <th>存储</th>
              <th>Compute Pod</th>
              <th>创建于</th>
              <th class="th-actions"></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="db in sortedDatabases"
              :key="db.id"
              :class="{
                'row-selected': selectedIds.has(db.id),
                'row-alert': isAlert(db)
              }"
            >
              <td>
                <input
                  type="checkbox"
                  :checked="selectedIds.has(db.id)"
                  @change="toggleSelect(db.id)"
                />
              </td>
              <td class="td-name">
                <a class="db-link" @click="router.push(`/databases/${db.id}`)">{{ db.name }}</a>
                <div class="td-sub-mono">{{ db.id }}</div>
              </td>
              <td class="td-tenant">
                <div>{{ tenantStore.name(db.tenant_id) }}</div>
                <div class="td-sub-mono">{{ db.tenant_id }}</div>
              </td>
              <td>
                <span class="status-chip" :class="'chip-' + db.status.toLowerCase()">
                  <span class="chip-dot"></span>{{ db.status }}
                </span>
              </td>
              <td
                class="td-status-msg"
                :class="{ 'is-empty': !db.status_message }"
                :title="db.status_message || ''"
              >{{ db.status_message || '—' }}</td>
              <td class="td-compact">{{ db.compute_size || '—' }}</td>
              <td class="td-compact">{{ db.storage_limit_gb ? db.storage_limit_gb + ' GB' : '—' }}</td>
              <td class="td-mono" :title="db.compute_pod_name || ''">{{ db.compute_pod_name || '—' }}</td>
              <td class="td-date">{{ fmtRelDate(db.created_at) }}</td>
              <td class="td-actions">
                <div class="row-actions">
                  <button
                    v-if="db.status === 'FAILED'"
                    class="row-btn row-btn-accent"
                    @click="diagnose(db)"
                  >AI 诊断</button>
                  <button
                    class="row-btn row-btn-danger"
                    @click="confirmDeleteOne(db)"
                  >删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="databases.length === 0">
              <td colspan="10" class="empty-row">
                <div class="empty-title">集群里还没有数据库</div>
                <div class="empty-sub">等待第一个租户创建</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- Delete dialog -->
    <div class="dialog-overlay" v-if="showDeleteDialog" @click.self="showDeleteDialog = false">
      <div class="dialog-card">
        <div class="dialog-head">
          <h2 class="dialog-title">删除数据库</h2>
          <button class="dialog-x" @click="showDeleteDialog = false" aria-label="关闭">×</button>
        </div>
        <div class="dialog-body">
          <p class="dialog-lede">
            将永久删除以下 <strong>{{ deleteTargetIds.length }}</strong> 个数据库及其存储数据，此操作不可恢复。
          </p>
          <ul class="dialog-list">
            <li v-for="id in deleteTargetIds" :key="id">
              <span class="dialog-list-name">{{ dbNameById(id) }}</span>
              <span class="dialog-list-id">{{ id }}</span>
            </li>
          </ul>
        </div>
        <div class="dialog-foot">
          <button class="db-btn db-btn-ghost" @click="showDeleteDialog = false">取消</button>
          <button
            class="db-btn db-btn-danger"
            @click="executeBatchDelete"
            :disabled="deleting"
          >{{ deleting ? '删除中…' : '删除' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, inject } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../../api/admin'
import { useTenantStore } from '../../stores/tenants'

const router = useRouter()
const tenantStore = useTenantStore()

const openAiDiagnose = inject<(type: string, id: string, q: string) => void>('openAiDiagnose')

interface Database {
  id: string
  name: string
  tenant_id: string
  status: string
  status_message?: string
  compute_size?: string
  storage_limit_gb?: number
  compute_pod_name?: string
  last_active_at?: string
  created_at: string
}

interface ColdStartBucket {
  count: number
  avg_ms: number
  p50_ms: number
  p90_ms: number
  p99_ms: number
  max_ms: number
}

interface ColdStartTrendPoint {
  date: string
  avg_ms: number
  count: number
}

interface ColdStartByDb {
  database: string
  count: number
  avg_ms: number
  p50_ms: number
  max_ms: number
}

interface ColdStartRecent {
  id: string
  database_id: string
  database_name?: string
  started_at: string
  duration_ms: number
}

interface ColdStartData {
  cold?: ColdStartBucket
  warm?: { count: number; avg_ms: number; p50_ms: number }
  trend?: ColdStartTrendPoint[]
  by_database?: ColdStartByDb[]
  recent?: ColdStartRecent[]
}

const activeTab = ref<'coldstart' | 'list'>('coldstart')
const databases = ref<Database[]>([])
const statusFilter = ref('')
const tenantFilter = ref('')

const csData = ref<ColdStartData | null>(null)
const csDays = ref(7)
const loadingCs = ref(false)

function setTab(tab: 'coldstart' | 'list') {
  activeTab.value = tab
  if (tab === 'coldstart' && !csData.value) loadColdStart()
  if (tab === 'list' && databases.value.length === 0) loadDatabases()
}

function diagnose(db: Database) {
  const q = `数据库 ${db.name} (${db.id}) 状态为 FAILED，错误信息："${db.status_message || '无'}"。请诊断原因并给出修复建议。`
  openAiDiagnose?.('database', db.id, q)
}

async function loadColdStart() {
  loadingCs.value = true
  try {
    const res = await adminApi.coldStartAnalysis(csDays.value)
    csData.value = res.data
  } catch (e) {
    console.error('Failed to load cold start data', e)
    csData.value = null
  } finally {
    loadingCs.value = false
  }
}

function csFormatMs(ms: number | null | undefined): string {
  if (ms == null || ms === 0) return '—'
  if (ms < 1000) return Math.round(ms) + ' ms'
  return (ms / 1000).toFixed(1) + ' s'
}

/** Text color based on stricter thresholds: <3s normal, 3–10s warn, >10s severe */
function csTextColor(ms: number | null | undefined): string {
  if (ms == null) return 'var(--c-text)'
  if (ms < 3000) return 'var(--cs-normal)'
  if (ms < 10000) return 'var(--cs-warn)'
  return 'var(--cs-severe)'
}

/** Bar color is harbor blue by default; severe days only turn red. Never green/yellow/red traffic light. */
function csBarColor(ms: number | null | undefined): string {
  if (ms == null) return 'var(--c-border)'
  if (ms > 10000) return 'var(--cs-severe)'
  return 'var(--c-primary)'
}

const csTrendAnalysis = computed(() => {
  const trend = csData.value?.trend
  if (!trend || trend.length < 2) return null
  const mid = Math.ceil(trend.length / 2)
  const earlyDays = trend.slice(0, mid)
  const recentDays = trend.slice(mid)
  const avgOf = (days: ColdStartTrendPoint[]) => {
    let total = 0, count = 0
    for (const d of days) { total += d.avg_ms * d.count; count += d.count }
    return count > 0 ? total / count : 0
  }
  const earlyAvg = avgOf(earlyDays)
  const recentAvg = avgOf(recentDays)
  const changePercent = earlyAvg > 0 ? Math.round((recentAvg - earlyAvg) / earlyAvg * 100) : null
  const bestDay = [...trend].sort((a, b) => a.avg_ms - b.avg_ms)[0]
  const worstDay = [...trend].sort((a, b) => b.avg_ms - a.avg_ms)[0]
  return { earlyAvg, recentAvg, changePercent, bestDay, worstDay }
})

function csTrendDelta(idx: number): number | null {
  const trend = csData.value?.trend
  if (!trend || idx <= 0) return null
  const curr = Number(trend[idx]?.avg_ms)
  const prev = Number(trend[idx - 1]?.avg_ms)
  if (!prev) return null
  return Math.round((curr - prev) / prev * 100)
}

function csBarHeight(ms: number | null | undefined): number {
  if (ms == null) return 8
  const trend = csData.value?.trend || []
  const maxMs = trend.reduce((m, d) => Math.max(m, d.avg_ms || 0), 1)
  return Math.max(8, Math.round((ms / maxMs) * 140))
}

function fmtShortTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function fmtRelDate(iso: string): string {
  if (!iso) return '—'
  const now = Date.now()
  const then = new Date(iso).getTime()
  const diffMs = now - then
  const day = 86400_000
  if (diffMs < 60_000) return '刚刚'
  if (diffMs < 3600_000) return Math.floor(diffMs / 60_000) + ' 分钟前'
  if (diffMs < day) return Math.floor(diffMs / 3600_000) + ' 小时前'
  if (diffMs < 7 * day) return Math.floor(diffMs / day) + ' 天前'
  const d = new Date(iso)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

// Selection state
const selectedIds = ref<Set<string>>(new Set())
const showDeleteDialog = ref(false)
const deleteTargetIds = ref<string[]>([])
const deleting = ref(false)

const allSelected = computed(() =>
  databases.value.length > 0 && databases.value.every(db => selectedIds.value.has(db.id))
)

const sortedDatabases = computed(() => {
  const severity = (s: string) => {
    if (s === 'FAILED') return 0
    if (s === 'ERROR') return 1
    return 2
  }
  return [...databases.value].sort((a, b) => {
    const sa = severity(a.status), sb = severity(b.status)
    if (sa !== sb) return sa - sb
    return (b.created_at || '').localeCompare(a.created_at || '')
  })
})

const failedCount = computed(() =>
  databases.value.filter(d => d.status === 'FAILED' || d.status === 'ERROR').length
)

function statCount(status: string): number {
  return databases.value.filter(d => d.status === status).length
}

function isAlert(db: Database): boolean {
  return db.status === 'FAILED' || db.status === 'ERROR'
}

function toggleAll() {
  if (allSelected.value) {
    databases.value.forEach(db => selectedIds.value.delete(db.id))
  } else {
    databases.value.forEach(db => selectedIds.value.add(db.id))
  }
  selectedIds.value = new Set(selectedIds.value)
}

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) selectedIds.value.delete(id)
  else selectedIds.value.add(id)
  selectedIds.value = new Set(selectedIds.value)
}

function dbNameById(id: string): string {
  return databases.value.find(db => db.id === id)?.name ?? id
}

function confirmDeleteOne(db: Database) {
  deleteTargetIds.value = [db.id]
  showDeleteDialog.value = true
}

function confirmBatchDelete() {
  deleteTargetIds.value = Array.from(selectedIds.value)
  showDeleteDialog.value = true
}

async function executeBatchDelete() {
  deleting.value = true
  try {
    const res = await adminApi.batchDeleteDatabases(deleteTargetIds.value)
    const result = res.data
    if (result.errors?.length > 0) {
      alert(`删除完成：成功 ${result.deleted} 个，失败 ${result.errors.length} 个\n${result.errors.map((e: { id: string; error: string }) => e.error).join('\n')}`)
    }
    showDeleteDialog.value = false
    selectedIds.value = new Set()
    await loadDatabases()
  } catch (e) {
    console.error('Failed to batch delete', e)
    alert('批量删除失败')
  } finally {
    deleting.value = false
  }
}

async function loadDatabases() {
  try {
    const params: Record<string, string> = {}
    if (statusFilter.value) params.status = statusFilter.value
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    const res = await adminApi.listDatabases(params)
    databases.value = res.data
    selectedIds.value = new Set()
  } catch (e) {
    console.error('Failed to load databases', e)
  }
}

onMounted(() => {
  tenantStore.load()
  loadColdStart()
  loadDatabases()
})
</script>

<style scoped>
/* ══════════════════════════════════════════
   Page shell
   ══════════════════════════════════════════ */
.db-page {
  padding: var(--space-3xl) var(--space-2xl) var(--space-4xl);
  max-width: 1560px;
  margin: 0 auto;
  background: var(--c-bg-alt);
  min-height: 100%;
  color: var(--c-text);
}

.db-page-header {
  margin-bottom: var(--space-xl);
}

.db-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 34px;
  line-height: 1.1;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-xs);
}

.db-subtitle {
  font-size: 14px;
  color: var(--c-text-2);
  margin: 0;
  max-width: 56ch;
}

/* ══════════════════════════════════════════
   Tab bar — warm underline, not Ant blue
   ══════════════════════════════════════════ */
.db-tab-bar {
  display: flex;
  gap: var(--space-2xl);
  border-bottom: 1px solid var(--c-border);
  margin-top: var(--space-xl);
  margin-bottom: var(--space-2xl);
}

.db-tab {
  background: none;
  border: none;
  padding: var(--space-md) 0;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-2);
  cursor: pointer;
  position: relative;
  transition: color 160ms ease-out;
}

.db-tab:hover {
  color: var(--c-text);
}

.db-tab.is-active {
  color: var(--c-primary);
}

.db-tab.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--c-accent);
  border-radius: 1px;
}

.db-tab:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 4px;
  border-radius: 2px;
}

.db-pane {
  animation: fade-in 220ms cubic-bezier(0.22, 0.61, 0.36, 1);
}

@keyframes fade-in {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
}

/* ══════════════════════════════════════════
   Cold-start Hero (the protagonist)
   ══════════════════════════════════════════ */
.cs-hero {
  display: grid;
  grid-template-columns: auto 1fr auto;
  column-gap: var(--space-3xl);
  row-gap: var(--space-xl);
  align-items: start;
  padding: var(--space-2xl) 0 var(--space-3xl);
  border-bottom: 1px solid var(--c-border-light);
}

.cs-hero-main {
  grid-column: 1;
  grid-row: 1 / 3;
  align-self: end;
}

.cs-hero-number {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(64px, 9vw, 104px);
  line-height: 0.95;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  color: var(--c-primary);
  transition: color 320ms ease-out;
}

.cs-hero-label {
  margin-top: var(--space-md);
  font-size: 13px;
  color: var(--c-text-3);
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
}

.cs-hero-label-key {
  font-weight: 500;
  color: var(--c-text-2);
  letter-spacing: 0.04em;
}

.cs-hero-label-sep {
  color: var(--c-border);
}

.cs-hero-secondary {
  margin-top: var(--space-lg);
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  font-size: 13px;
}

.cs-hero-secondary-label {
  color: var(--c-text-3);
  letter-spacing: 0.02em;
}

.cs-hero-secondary-value {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  color: var(--c-primary);
}

.cs-hero-window {
  grid-column: 3;
  grid-row: 1;
  justify-self: end;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-xs);
}

.cs-hero-window--empty {
  margin-top: var(--space-xl);
}

.cs-window-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

.cs-window-select {
  font: inherit;
  font-size: 13px;
  color: var(--c-text);
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 6px 28px 6px 12px;
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'><path d='M1 1l4 4 4-4' stroke='%239a5b25' stroke-width='1.4' fill='none' stroke-linecap='round' stroke-linejoin='round'/></svg>");
  background-repeat: no-repeat;
  background-position: right 10px center;
  transition: border-color 160ms ease-out;
}

.cs-window-select:hover {
  border-color: var(--c-accent);
}

.cs-window-select:focus-visible {
  outline: none;
  border-color: var(--c-accent);
  box-shadow: 0 0 0 3px rgb(from var(--c-accent) r g b / 0.15);
}

.cs-hero-narrative {
  grid-column: 2 / 4;
  grid-row: 2;
  justify-self: end;
  align-self: end;
  text-align: right;
  max-width: 52ch;
}

.cs-narrative-primary {
  font-size: 14px;
  color: var(--c-text-2);
  line-height: 1.6;
}

.cs-narrative-lede {
  color: var(--c-text-3);
}

.cs-narrative-value {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 20px;
  font-variant-numeric: tabular-nums;
  margin: 0 var(--space-xs);
}

.cs-narrative-delta {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  font-weight: 500;
  font-size: 13px;
  margin-left: var(--space-sm);
}

.cs-narrative-delta.is-improved { color: var(--cs-normal); }
.cs-narrative-delta.is-degraded { color: var(--cs-severe); }

.cs-narrative-arrow {
  font-size: 15px;
  line-height: 1;
  transform: translateY(1px);
}

.cs-narrative-secondary {
  margin-top: var(--space-md);
  display: flex;
  justify-content: flex-end;
  gap: var(--space-xl);
  font-size: 12px;
  color: var(--c-text-3);
}

.cs-narrative-day {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-sm);
}

.cs-narrative-day-lbl {
  color: var(--c-text-3);
  letter-spacing: 0.02em;
}

.cs-narrative-day-val {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 14px;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.cs-narrative-day-val.is-improved { color: var(--cs-normal); }

/* Empty & loading hero */
.cs-hero-empty {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  padding: var(--space-3xl) 0 var(--space-3xl);
  border-bottom: 1px solid var(--c-border-light);
  column-gap: var(--space-3xl);
}

.cs-empty-dash {
  font-family: var(--font-display);
  font-weight: 300;
  font-size: 96px;
  line-height: 1;
  color: var(--c-border);
  grid-column: 1;
}

.cs-empty-text {
  grid-column: 2;
  font-family: var(--font-display);
  font-size: 18px;
  color: var(--c-text-2);
  line-height: 1.4;
}

.cs-empty-sub {
  grid-column: 2;
  font-size: 13px;
  color: var(--c-text-3);
  margin-top: var(--space-xs);
}

.cs-hero-skeleton {
  padding: var(--space-3xl) 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.sk {
  background: var(--c-border-light);
  border-radius: 3px;
  animation: sk-pulse 1.6s ease-in-out infinite;
}

.sk-number { width: 320px; height: 96px; }
.sk-line { width: 360px; height: 12px; }
.sk-line-lg { width: 440px; height: 14px; }

@keyframes sk-pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .sk { animation: none; }
  .db-pane { animation: none; }
}

/* ══════════════════════════════════════════
   Secondary percentiles row
   ══════════════════════════════════════════ */
.cs-percentiles {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3xl);
  padding: var(--space-2xl) 0;
  border-bottom: 1px solid var(--c-border-light);
}

.cs-perc {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.cs-perc-value {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 26px;
  line-height: 1;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.cs-perc-warm {
  color: var(--cs-normal);
}

.cs-perc-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

.cs-perc-divider {
  width: 1px;
  height: 36px;
  background: var(--c-border);
  margin-bottom: var(--space-md);
}

/* ══════════════════════════════════════════
   Daily trend chart
   ══════════════════════════════════════════ */
.cs-section {
  padding: var(--space-2xl) 0;
  border-bottom: 1px solid var(--c-border-light);
}

.cs-section-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 18px;
  color: var(--c-text);
  margin: 0 0 var(--space-xl);
  letter-spacing: -0.005em;
}

.cs-trend-chart {
  display: flex;
  align-items: flex-end;
  gap: 20px;
  min-height: 200px;
  overflow-x: auto;
  padding-bottom: var(--space-xs);
}

.cs-trend-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 52px;
  animation: col-in 380ms cubic-bezier(0.22, 0.61, 0.36, 1) backwards;
  animation-delay: calc(var(--col-idx, 0) * 40ms);
}

@keyframes col-in {
  from { opacity: 0; transform: translateY(6px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (prefers-reduced-motion: reduce) {
  .cs-trend-col { animation: none; }
}

.cs-trend-bar-wrap {
  height: 148px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  position: relative;
}

.cs-trend-bar {
  width: 26px;
  min-height: 6px;
  border-radius: 2px 2px 0 0;
  transition: opacity 180ms ease-out;
}

.cs-trend-bar-wrap:hover .cs-trend-bar {
  opacity: 0.85;
}

.cs-trend-delta {
  position: absolute;
  top: -18px;
  font-size: 10px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  opacity: 0;
  transition: opacity 180ms ease-out;
}

.cs-trend-bar-wrap:hover .cs-trend-delta {
  opacity: 1;
}

.cs-trend-delta.is-improved { color: var(--cs-normal); }
.cs-trend-delta.is-degraded { color: var(--cs-severe); }

.cs-trend-value {
  font-family: var(--font-display);
  font-size: 13px;
  font-weight: 500;
  margin-top: var(--space-sm);
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.cs-trend-date {
  font-size: 11px;
  color: var(--c-text-3);
  margin-top: 2px;
}

.cs-trend-count {
  font-size: 10px;
  color: var(--c-text-3);
  margin-top: 1px;
  letter-spacing: 0.02em;
}

/* ══════════════════════════════════════════
   Bottom two-column
   ══════════════════════════════════════════ */
.cs-bottom-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: var(--space-3xl);
  padding-top: var(--space-2xl);
}

.cs-bottom-col {
  min-width: 0;
}

.cs-subtable {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.cs-subtable thead th {
  text-align: left;
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
  padding: var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--c-border);
  background: transparent;
}

.cs-subtable thead th.num {
  text-align: right;
}

.cs-subtable tbody td {
  padding: var(--space-md);
  border-bottom: 1px solid var(--c-border-light);
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.cs-subtable tbody td.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.cs-subtable tbody tr:last-child td {
  border-bottom: none;
}

.cs-subtable tbody tr:hover {
  background: var(--c-hover);
}

.cs-td-name {
  font-weight: 500;
}

.cs-td-time {
  font-size: 12px;
  color: var(--c-text-2);
  font-variant-numeric: tabular-nums;
}

.cs-empty-small {
  padding: var(--space-2xl) 0;
  text-align: center;
  color: var(--c-text-3);
  font-size: 13px;
}

/* ══════════════════════════════════════════
   List tab — status line
   ══════════════════════════════════════════ */
.db-status-line {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-xl);
  padding: var(--space-lg) 0;
  margin-bottom: var(--space-lg);
  border-bottom: 1px solid var(--c-border-light);
}

.sl-item {
  display: inline-flex;
  align-items: baseline;
  gap: var(--space-sm);
  font-size: 13px;
  color: var(--c-text-2);
}

.sl-item.sl-alert .sl-num { color: var(--cs-severe); }

.sl-total .sl-num {
  font-family: var(--font-display);
  font-size: 22px;
}

.sl-num {
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.sl-lbl {
  color: var(--c-text-2);
}

.sl-sep {
  width: 1px;
  height: 18px;
  background: var(--c-border);
  align-self: center;
}

.sl-chip {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  transform: translateY(-1px);
}

.sl-chip-running { background: #52a36b; }
.sl-chip-suspended { background: var(--c-text-3); }
.sl-chip-failed { background: var(--cs-severe); }
.sl-chip-creating { background: var(--c-primary); }
.sl-chip-deleted { background: var(--c-accent); }

/* ══════════════════════════════════════════
   Filters row
   ══════════════════════════════════════════ */
.db-filters {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
}

.db-select,
.db-input {
  font: inherit;
  font-size: 13px;
  color: var(--c-text);
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 4px;
  padding: 8px 12px;
  height: 34px;
  outline: none;
  transition: border-color 160ms ease-out, box-shadow 160ms ease-out;
}

.db-select { min-width: 140px; padding-right: 28px; cursor: pointer; }
.db-input { width: 260px; }

.db-select:hover,
.db-input:hover {
  border-color: var(--c-accent);
}

.db-select:focus-visible,
.db-input:focus-visible {
  border-color: var(--c-accent);
  box-shadow: 0 0 0 3px rgb(from var(--c-accent) r g b / 0.15);
}

.db-input::placeholder { color: var(--c-text-3); }

.db-btn {
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  height: 34px;
  padding: 0 16px;
  border-radius: 4px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 160ms ease-out, border-color 160ms ease-out, color 160ms ease-out;
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}

.db-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.db-btn-primary {
  background: var(--c-accent);
  color: #fff;
  border-color: var(--c-accent);
}

.db-btn-primary:hover:not(:disabled) {
  background: var(--c-accent-hover);
  border-color: var(--c-accent-hover);
}

.db-btn-ghost {
  background: transparent;
  color: var(--c-text-2);
  border-color: var(--c-border);
}

.db-btn-ghost:hover:not(:disabled) {
  color: var(--c-text);
  border-color: var(--c-text-3);
}

.db-btn-danger {
  background: var(--c-danger);
  color: #fff;
  border-color: var(--c-danger);
}

.db-btn-danger:hover:not(:disabled) {
  background: var(--c-danger-hover);
  border-color: var(--c-danger-hover);
}

.db-batch-inline {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-md);
  font-size: 13px;
  color: var(--c-text-2);
  animation: fade-in 180ms ease-out;
}

.db-batch-count {
  font-weight: 500;
  color: var(--c-text);
}

/* ══════════════════════════════════════════
   Data table
   ══════════════════════════════════════════ */
.db-table-wrap {
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  overflow: hidden;
}

.db-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.db-table thead th {
  text-align: left;
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
  padding: var(--space-md) var(--space-lg);
  background: var(--c-bg-alt);
  border-bottom: 1px solid var(--c-border);
  white-space: nowrap;
}

.db-table th.th-check,
.db-table th.th-actions {
  width: 1%;
}

.db-table tbody td {
  padding: var(--space-lg) var(--space-lg);
  color: var(--c-text);
  border-bottom: 1px solid var(--c-border-light);
  vertical-align: middle;
  line-height: 1.5;
}

.db-table tbody tr:last-child td {
  border-bottom: none;
}

.db-table tbody tr {
  transition: background 120ms ease-out;
}

.db-table tbody tr:hover {
  background: var(--c-hover);
}

.db-table tbody tr.row-selected {
  background: color-mix(in oklch, var(--c-accent) 6%, #fff);
}

.db-table tbody tr.row-alert {
  background: color-mix(in oklch, var(--c-danger) 4%, #fff);
}

.db-table tbody tr.row-alert:hover {
  background: color-mix(in oklch, var(--c-danger) 7%, #fff);
}

.td-name {
  min-width: 180px;
  max-width: 220px;
}

.td-name .db-link {
  color: var(--c-primary);
  font-weight: 500;
  font-size: 14px;
  text-decoration: none;
  cursor: pointer;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.td-name .db-link:hover {
  color: var(--c-accent-text);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.td-sub-mono {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-text-3);
  margin-top: 2px;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 200px;
}

.td-tenant {
  min-width: 150px;
  max-width: 200px;
}

.td-tenant > div:first-child {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.td-mono {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 180px;
}

.td-compact {
  font-variant-numeric: tabular-nums;
  color: var(--c-text-2);
  white-space: nowrap;
}

.td-status-msg {
  min-width: 240px;
  max-width: 280px;
  font-size: 12px;
  color: var(--cs-severe);
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.td-status-msg.is-empty {
  color: var(--c-text-3);
  min-width: 0;
}

.td-date {
  color: var(--c-text-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  font-size: 12px;
}

/* Status chips */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
  padding: 3px 10px;
  border-radius: 10px;
  background: var(--c-bg-alt);
  color: var(--c-text-2);
  white-space: nowrap;
}

.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-text-3);
}

.chip-running {
  background: color-mix(in oklch, #52a36b 12%, #fff);
  color: #386b47;
}
.chip-running .chip-dot { background: #52a36b; }

.chip-suspended .chip-dot { background: var(--c-text-3); }

.chip-creating {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}
.chip-creating .chip-dot { background: var(--c-primary); }

.chip-failed,
.chip-error {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}
.chip-failed .chip-dot,
.chip-error .chip-dot { background: var(--cs-severe); }

.chip-deleted {
  background: color-mix(in oklch, var(--c-accent) 10%, #fff);
  color: var(--c-accent-text);
}
.chip-deleted .chip-dot { background: var(--c-accent); }

/* Row actions — hidden until hover */
.td-actions {
  text-align: right;
  width: 1%;
  white-space: nowrap;
}

.row-actions {
  display: inline-flex;
  gap: var(--space-sm);
  opacity: 0;
  transition: opacity 180ms ease-out;
}

.db-table tbody tr:hover .row-actions,
.db-table tbody tr:focus-within .row-actions {
  opacity: 1;
}

.row-btn {
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  background: none;
  border: none;
  padding: 4px 8px;
  border-radius: 3px;
  cursor: pointer;
  transition: background 120ms ease-out, color 120ms ease-out;
}

.row-btn-accent {
  color: var(--c-accent-text);
}
.row-btn-accent:hover {
  background: var(--c-accent-light);
}

.row-btn-danger {
  color: var(--c-text-3);
}
.row-btn-danger:hover {
  color: var(--cs-severe);
  background: color-mix(in oklch, var(--cs-severe) 8%, #fff);
}

/* Empty state */
.empty-row {
  padding: var(--space-4xl) var(--space-xl);
  text-align: center;
  background: #fff;
}

.empty-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 18px;
  color: var(--c-text);
}

.empty-sub {
  margin-top: var(--space-xs);
  font-size: 13px;
  color: var(--c-text-3);
}

/* ══════════════════════════════════════════
   Delete dialog
   ══════════════════════════════════════════ */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: color-mix(in oklch, var(--c-primary) 30%, transparent);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fade-in 160ms ease-out;
}

.dialog-card {
  background: #fff;
  border-radius: 8px;
  width: 440px;
  max-width: 92vw;
  box-shadow: 0 24px 64px -12px rgb(42 77 106 / 0.22),
              0 8px 16px -8px rgb(42 77 106 / 0.08);
  animation: dialog-in 240ms cubic-bezier(0.22, 0.61, 0.36, 1);
}

@keyframes dialog-in {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .dialog-overlay,
  .dialog-card { animation: none; }
}

.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-xl) var(--space-xl) var(--space-md);
}

.dialog-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 22px;
  color: var(--c-text);
  margin: 0;
}

.dialog-x {
  background: none;
  border: none;
  font-size: 22px;
  line-height: 1;
  color: var(--c-text-3);
  cursor: pointer;
  padding: 4px 8px;
  transition: color 120ms ease-out;
}
.dialog-x:hover { color: var(--c-text); }

.dialog-body {
  padding: 0 var(--space-xl) var(--space-lg);
}

.dialog-lede {
  font-size: 14px;
  color: var(--c-text-2);
  line-height: 1.55;
  margin: 0 0 var(--space-md);
}

.dialog-list {
  list-style: none;
  padding: var(--space-sm) 0;
  margin: 0;
  max-height: 200px;
  overflow-y: auto;
  font-size: 13px;
  border-top: 1px solid var(--c-border-light);
  border-bottom: 1px solid var(--c-border-light);
}

.dialog-list li {
  padding: 6px 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.dialog-list-name {
  color: var(--c-text);
  font-weight: 500;
}

.dialog-list-id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-text-3);
}

.dialog-foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-xl) var(--space-xl);
}

/* ══════════════════════════════════════════
   Responsive
   ══════════════════════════════════════════ */
@media (max-width: 960px) {
  .db-page { padding: var(--space-xl) var(--space-lg); }
  .cs-hero {
    grid-template-columns: 1fr;
    row-gap: var(--space-lg);
  }
  .cs-hero-main { grid-column: 1; grid-row: 1; }
  .cs-hero-window { grid-column: 1; grid-row: 2; justify-self: start; }
  .cs-hero-narrative {
    grid-column: 1;
    grid-row: 3;
    justify-self: start;
    text-align: left;
  }
  .cs-narrative-secondary { justify-content: flex-start; }
  .cs-bottom-grid { grid-template-columns: 1fr; }
  .cs-percentiles { gap: var(--space-xl); flex-wrap: wrap; }
  .db-input { width: 180px; }
}
</style>
