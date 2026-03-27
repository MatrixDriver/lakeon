<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">数据库实例</h1>
    </div>

    <!-- Tabs -->
    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'list' }" @click="activeTab = 'list'">数据库列表</div>
      <div class="tab-item" :class="{ active: activeTab === 'coldstart' }" @click="activeTab = 'coldstart'; loadColdStart()">冷启动分析</div>
    </div>

    <template v-if="activeTab === 'list'">
    <!-- Stats Cards -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-value">{{ databases.length }}</div>
        <div class="stat-label">总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #52c41a;">{{ databases.filter(d => d.status === 'RUNNING').length }}</div>
        <div class="stat-label">运行中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #8c8c8c;">{{ databases.filter(d => d.status === 'SUSPENDED').length }}</div>
        <div class="stat-label">已暂停</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #1890ff;">{{ databases.filter(d => d.status === 'CREATING').length }}</div>
        <div class="stat-label">创建中</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" style="color: #e53e3e;">{{ databases.filter(d => d.status === 'FAILED' || d.status === 'ERROR').length }}</div>
        <div class="stat-label">异常</div>
      </div>
    </div>

    <div class="action-toolbar">
      <select class="form-select" v-model="statusFilter" style="width: 160px;" @change="loadDatabases">
        <option value="">全部状态</option>
        <option value="RUNNING">RUNNING</option>
        <option value="SUSPENDED">SUSPENDED</option>
        <option value="CREATING">CREATING</option>
        <option value="ERROR">ERROR</option>
      </select>
      <input
        type="text"
        class="search-input"
        placeholder="按租户 ID 筛选..."
        v-model="tenantFilter"
        style="width: 260px;"
        @keyup.enter="loadDatabases"
      />
      <button class="btn btn-default btn-small" @click="loadDatabases">筛选</button>
      <button
        v-if="selectedIds.size > 0"
        class="btn btn-danger btn-small"
        @click="confirmBatchDelete"
        :disabled="deleting"
      >
        {{ deleting ? '删除中...' : `批量删除 (${selectedIds.size})` }}
      </button>
    </div>

    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th style="width: 40px;">
              <input type="checkbox" :checked="allSelected" @change="toggleAll" />
            </th>
            <th>名称</th>
            <th>租户</th>
            <th>状态</th>
            <th>状态信息</th>
            <th>规格</th>
            <th>存储上限</th>
            <th>Compute Pod</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="db in databases" :key="db.id" :class="{ 'row-selected': selectedIds.has(db.id) }">
            <td>
              <input type="checkbox" :checked="selectedIds.has(db.id)" @change="toggleSelect(db.id)" />
            </td>
            <td><a class="db-link" @click="router.push(`/databases/${db.id}`)">{{ db.name }}</a></td>
            <td>
              {{ tenantStore.name(db.tenant_id) }}
              <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ db.tenant_id }}</span>
            </td>
            <td>
              <span class="status-dot" :class="statusClass(db.status)"></span>
              {{ db.status }}
            </td>
            <td class="error-cell">{{ db.status_message || '-' }}</td>
            <td>{{ db.compute_size || '-' }}</td>
            <td>{{ db.storage_limit_gb ? db.storage_limit_gb + ' GB' : '-' }}</td>
            <td style="font-family: monospace; font-size: 13px;">{{ db.compute_pod_name || '-' }}</td>
            <td>{{ formatDate(db.created_at) }}</td>
            <td>
              <button v-if="db.status === 'FAILED'" class="btn btn-text btn-small" style="color: #0073e6;" @click="diagnose(db)">AI 诊断</button>
              <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="confirmDeleteOne(db)">删除</button>
            </td>
          </tr>
          <tr v-if="databases.length === 0">
            <td colspan="10" class="empty-state">暂无数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    </template><!-- end activeTab === 'list' -->

    <!-- Cold Start Analysis Tab -->
    <template v-if="activeTab === 'coldstart'">
      <!-- Summary Cards -->
      <div class="stats-row" v-if="csData">
        <div class="stat-card">
          <div class="stat-value" :style="{ color: csColor(csData.cold?.avg_ms) }">{{ csFormatMs(csData.cold?.avg_ms) }}</div>
          <div class="stat-label">冷启动均值</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" :style="{ color: csColor(csData.cold?.p50_ms) }">{{ csFormatMs(csData.cold?.p50_ms) }}</div>
          <div class="stat-label">P50</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" :style="{ color: csColor(csData.cold?.p90_ms) }">{{ csFormatMs(csData.cold?.p90_ms) }}</div>
          <div class="stat-label">P90</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" :style="{ color: csColor(csData.cold?.p99_ms) }">{{ csFormatMs(csData.cold?.p99_ms) }}</div>
          <div class="stat-label">P99</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ csData.cold?.count || 0 }}</div>
          <div class="stat-label">冷启动次数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #52c41a;">{{ csFormatMs(csData.warm?.avg_ms) }}</div>
          <div class="stat-label">热启动均值</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #52c41a;">{{ csData.warm?.count || 0 }}</div>
          <div class="stat-label">热启动次数</div>
        </div>
      </div>

      <!-- Daily Trend -->
      <div class="section-card" v-if="csData?.trend?.length">
        <div class="section-header"><h3>每日趋势</h3></div>
        <div style="padding: 16px; display: flex; gap: 8px; align-items: flex-end; min-height: 160px; overflow-x: auto;">
          <div v-for="d in csData.trend" :key="d.date" style="display: flex; flex-direction: column; align-items: center; min-width: 48px;">
            <div style="height: 120px; display: flex; align-items: flex-end;">
              <div :style="{ width: '32px', borderRadius: '4px 4px 0 0', minHeight: '4px', background: csColor(d.avg_ms), height: csBarHeight(d.avg_ms) + 'px' }"
                :title="`${d.date}: 均值 ${csFormatMs(d.avg_ms)}, ${d.count} 次`"></div>
            </div>
            <div style="font-size: 11px; color: #999; margin-top: 4px;">{{ d.date.substring(5) }}</div>
            <div style="font-size: 11px; font-weight: 600;">{{ csFormatMs(d.avg_ms) }}</div>
            <div style="font-size: 10px; color: #bbb;">{{ d.count }}次</div>
          </div>
        </div>
      </div>

      <!-- Per-Database Breakdown -->
      <div class="section-card" v-if="csData?.by_database?.length" style="margin-top: 16px;">
        <div class="section-header"><h3>按数据库分布</h3></div>
        <table class="data-table">
          <thead><tr><th>数据库</th><th>次数</th><th>平均耗时</th><th>P50</th><th>最大值</th></tr></thead>
          <tbody>
            <tr v-for="db in csData.by_database" :key="db.database">
              <td><strong>{{ db.database }}</strong></td>
              <td>{{ db.count }}</td>
              <td :style="{ color: csColor(db.avg_ms), fontWeight: 600 }">{{ csFormatMs(db.avg_ms) }}</td>
              <td>{{ csFormatMs(db.p50_ms) }}</td>
              <td :style="{ color: csColor(db.max_ms) }">{{ csFormatMs(db.max_ms) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Recent Cold Starts -->
      <div class="section-card" style="margin-top: 16px;">
        <div class="section-header">
          <h3>最近冷启动记录</h3>
          <select class="form-select" v-model="csDays" @change="loadColdStart" style="width: 120px;">
            <option :value="1">最近 1 天</option>
            <option :value="3">最近 3 天</option>
            <option :value="7">最近 7 天</option>
            <option :value="30">最近 30 天</option>
          </select>
        </div>
        <table class="data-table" v-if="csData?.recent?.length">
          <thead><tr><th>时间</th><th>数据库</th><th>耗时</th></tr></thead>
          <tbody>
            <tr v-for="op in csData.recent" :key="op.id">
              <td style="font-size: 12px; color: #666;">{{ new Date(op.started_at).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) }}</td>
              <td>{{ op.database_name || op.database_id }}</td>
              <td :style="{ color: csColor(op.duration_ms), fontWeight: 600 }">{{ csFormatMs(op.duration_ms) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else style="padding: 24px; text-align: center; color: #999;">暂无冷启动记录</div>
      </div>
    </template><!-- end activeTab === 'coldstart' -->

    <!-- Delete Confirm Dialog -->
    <div class="dialog-overlay" v-if="showDeleteDialog" @click.self="showDeleteDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3 style="color: #e53e3e;">确认删除</h3>
          <button class="dialog-close" @click="showDeleteDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p>确定要删除以下 <strong>{{ deleteTargetIds.length }}</strong> 个数据库吗？</p>
          <p style="color: #e53e3e; font-size: 13px;">此操作会删除计算节点和存储数据，不可恢复。</p>
          <ul style="font-size: 13px; max-height: 200px; overflow-y: auto; margin: 8px 0;">
            <li v-for="id in deleteTargetIds" :key="id">
              {{ dbNameById(id) }} <span style="color: #999;">({{ id }})</span>
            </li>
          </ul>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showDeleteDialog = false">取消</button>
          <button class="btn btn-danger" @click="executeBatchDelete" :disabled="deleting">
            {{ deleting ? '删除中...' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, inject } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'
import { useTenantStore } from '../../stores/tenants'

const router = useRouter()
const tenantStore = useTenantStore()

const openAiDiagnose = inject<(type: string, id: string, q: string) => void>('openAiDiagnose')

function diagnose(db: Database) {
  const q = `数据库 ${db.name} (${db.id}) 状态为 FAILED，错误信息: "${db.status_message || '无'}"。请诊断原因并给出修复建议。`
  openAiDiagnose?.('database', db.id, q)
}

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

const activeTab = ref('list')
const databases = ref<Database[]>([])
const statusFilter = ref('')
const tenantFilter = ref('')

// Cold start analysis state
const csData = ref<any>(null)
const csDays = ref(7)

async function loadColdStart() {
  try {
    const res = await adminApi.coldStartAnalysis(csDays.value)
    csData.value = res.data
  } catch (e) { console.error('Failed to load cold start data', e) }
}

function csFormatMs(ms: number | null | undefined): string {
  if (ms == null || ms === 0) return '-'
  if (ms < 1000) return ms + 'ms'
  return (ms / 1000).toFixed(1) + 's'
}

function csColor(ms: number | null | undefined): string {
  if (ms == null) return '#333'
  if (ms < 5000) return '#52c41a'
  if (ms < 15000) return '#faad14'
  return '#e53e3e'
}

function csBarHeight(ms: number): number {
  if (!csData.value?.cold?.max_ms) return 10
  return Math.max(8, (ms / csData.value.cold.max_ms) * 120)
}

// Selection state
const selectedIds = ref<Set<string>>(new Set())
const showDeleteDialog = ref(false)
const deleteTargetIds = ref<string[]>([])
const deleting = ref(false)

const allSelected = computed(() => {
  return databases.value.length > 0 && databases.value.every(db => selectedIds.value.has(db.id))
})

function toggleAll() {
  if (allSelected.value) {
    databases.value.forEach(db => selectedIds.value.delete(db.id))
  } else {
    databases.value.forEach(db => selectedIds.value.add(db.id))
  }
  selectedIds.value = new Set(selectedIds.value)
}

function toggleSelect(id: string) {
  if (selectedIds.value.has(id)) {
    selectedIds.value.delete(id)
  } else {
    selectedIds.value.add(id)
  }
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

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    case 'ERROR': return 'dot-red'
    default: return 'dot-gray'
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

onMounted(() => { tenantStore.load(); loadDatabases() })
</script>

<style scoped>
.stats-row {
  display: flex; gap: 16px; margin-bottom: 20px; flex-wrap: wrap;
}
.stat-card {
  background: #fff; border: 1px solid #e5e5e5; border-radius: 6px;
  padding: 16px 24px; min-width: 120px; text-align: center;
}
.stat-value { font-size: 28px; font-weight: 600; color: #333; }
.stat-label { font-size: 13px; color: #999; margin-top: 4px; }

.tab-bar { display: flex; border-bottom: 1px solid #e5e5e5; margin-bottom: 16px; }
.tab-item {
  padding: 8px 16px; cursor: pointer; font-size: 14px; color: #666;
  border-bottom: 2px solid transparent;
}
.tab-item.active { color: #1890ff; border-bottom-color: #1890ff; }

.section-card {
  background: #fff; border: 1px solid #e5e5e5; border-radius: 6px; overflow: hidden;
}
.section-header {
  padding: 12px 16px; border-bottom: 1px solid #f0f0f0;
  display: flex; align-items: center; justify-content: space-between;
}
.section-header h3 { margin: 0; font-size: 14px; font-weight: 600; }

.row-selected {
  background-color: #fff5f5;
}
.btn-danger {
  background-color: #e53e3e;
  color: white;
  border: none;
  padding: 6px 16px;
  border-radius: 4px;
  cursor: pointer;
}
.btn-danger:hover {
  background-color: #c53030;
}
.btn-danger:disabled {
  background-color: #feb2b2;
  cursor: not-allowed;
}
.db-link {
  color: #0052d9;
  cursor: pointer;
  text-decoration: none;
}
.db-link:hover {
  text-decoration: underline;
}

.error-cell {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #e53e3e;
  font-size: 13px;
}
</style>
