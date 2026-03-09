<template>
  <div class="page-logs">
    <div class="page-header">
      <h1 class="page-title">日志管理</h1>
    </div>

    <!-- Tab Header -->
    <div class="log-tabs">
      <button class="log-tab" :class="{ active: activeTab === 'operations' }" @click="activeTab = 'operations'">操作日志</button>
      <button class="log-tab" :class="{ active: activeTab === 'audit' }" @click="activeTab = 'audit'">SQL审计日志</button>
      <button class="log-tab" :class="{ active: activeTab === 'errorlog' }" @click="activeTab = 'errorlog'">错误日志</button>
    </div>

    <!-- Tab 1: Operation Logs -->
    <div v-if="activeTab === 'operations'">
      <div class="section-card">
        <TableToolbar v-model="opSearch" placeholder="搜索操作类型或错误信息" :loading="opLoading" @refresh="fetchOps">
          <template #extra>
            <select v-model="opDbFilter" class="filter-select" @change="opPage = 1">
              <option value="">全部数据库</option>
              <option v-for="db in databases" :key="db.id" :value="db.id">{{ db.name }}</option>
            </select>
            <select v-model="opTypeFilter" class="filter-select" @change="opPage = 1">
              <option value="">全部类型</option>
              <option value="CREATE">创建</option>
              <option value="RESUME">唤醒</option>
              <option value="SUSPEND">挂起</option>
              <option value="DELETE">删除</option>
              <option value="IMPORT">导入</option>
              <option value="UPDATE">更新</option>
              <option value="RESET_PASSWORD">重置密码</option>
            </select>
            <select v-model="opStatusFilter" class="filter-select" @change="opPage = 1">
              <option value="">全部状态</option>
              <option value="SUCCESS">成功</option>
              <option value="FAILURE">失败</option>
            </select>
          </template>
        </TableToolbar>
        <div class="table-wrapper">
          <table class="data-table" v-if="filteredOps.length > 0">
            <thead>
              <tr>
                <th>数据库</th>
                <th>操作类型</th>
                <th>状态</th>
                <th>耗时</th>
                <th>开始时间</th>
                <th>错误信息</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="op in pagedOps" :key="op.id" :class="{ 'row-error': op.status === 'FAILURE' }">
                <td>
                  <router-link v-if="op.databaseId" :to="`/databases/${op.databaseId}`" class="db-link">{{ op.databaseName }}</router-link>
                  <span v-else>{{ op.databaseName }}</span>
                </td>
                <td>
                  <span class="op-type-badge" :class="'op-' + op.operationType.toLowerCase()">
                    {{ OP_LABELS[op.operationType] || op.operationType }}
                  </span>
                </td>
                <td>
                  <span class="status-tag" :class="op.status === 'SUCCESS' ? 'tag-green' : 'tag-red'">
                    {{ op.status === 'SUCCESS' ? '成功' : '失败' }}
                  </span>
                </td>
                <td>{{ formatDuration(op.durationMs) }}</td>
                <td>{{ formatDate(op.startedAt) }}</td>
                <td>
                  <span v-if="op.errorMessage" class="error-text" :title="op.errorMessage">
                    {{ op.errorMessage.length > 50 ? op.errorMessage.slice(0, 50) + '...' : op.errorMessage }}
                  </span>
                  <span v-else class="text-muted">-</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">
            <p v-if="opLoading">加载中...</p>
            <p v-else>暂无操作记录</p>
          </div>
        </div>
        <TableFooter v-if="filteredOps.length > 0" :total="filteredOps.length" v-model:pageSize="opPageSize" v-model:currentPage="opPage" />
      </div>
    </div>

    <!-- Tab 2: SQL Audit Logs -->
    <div v-if="activeTab === 'audit'">
      <div class="section-card">
        <div class="audit-toolbar">
          <div class="audit-toolbar-left">
            <label class="toolbar-label">数据库</label>
            <select v-model="auditDbId" class="filter-select" @change="fetchAuditLogs">
              <option value="">选择数据库</option>
              <option v-for="db in databases" :key="db.id" :value="db.id">{{ db.name }}</option>
            </select>
            <select v-model="auditTypeFilter" class="filter-select" @change="auditPage = 1; fetchAuditLogs()">
              <option value="">全部类型</option>
              <option value="DDL">DDL</option>
              <option value="DML">DML</option>
              <option value="SELECT">SELECT</option>
            </select>
          </div>
          <button class="refresh-btn" @click="fetchAuditLogs" :disabled="auditLoading">{{ auditLoading ? '加载中...' : '刷新' }}</button>
        </div>

        <div v-if="!auditDbId" class="empty-state" style="padding: 48px;">
          <p>请选择数据库查看 SQL 审计日志</p>
        </div>
        <template v-else>
          <div v-if="auditNotEnabled" class="audit-tip">
            该数据库未启用 SQL 审计。请在数据库详情 → 审计日志 中开启。
          </div>
          <div class="table-wrapper">
            <table class="data-table" v-if="auditLogs.length > 0">
              <thead>
                <tr>
                  <th style="width: 180px;">时间</th>
                  <th>用户</th>
                  <th style="width: 80px;">类型</th>
                  <th>对象</th>
                  <th>SQL语句</th>
                  <th style="width: 80px;">耗时</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="log in auditLogs" :key="log.id">
                  <td>{{ formatDate(log.timestamp) }}</td>
                  <td>{{ log.user_name || '-' }}</td>
                  <td>
                    <span class="stmt-type" :class="'stmt-' + (log.statement_type || '').toLowerCase()">
                      {{ log.statement_type }}
                    </span>
                  </td>
                  <td>{{ log.object_name || '-' }}</td>
                  <td>
                    <code class="sql-text" :title="log.statement || ''">{{ truncate(log.statement, 80) }}</code>
                  </td>
                  <td>{{ log.duration != null ? log.duration + 'ms' : '-' }}</td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty-state">
              <p v-if="auditLoading">加载中...</p>
              <p v-else>暂无审计日志</p>
            </div>
          </div>
          <TableFooter v-if="auditTotal > 0" :total="auditTotal" v-model:pageSize="auditPageSize" v-model:currentPage="auditPage" @update:currentPage="fetchAuditLogs" @update:pageSize="auditPage = 1; fetchAuditLogs()" />
        </template>
      </div>
    </div>

    <!-- Tab 3: Error Logs -->
    <div v-if="activeTab === 'errorlog'">
      <div class="section-card">
        <div class="audit-toolbar">
          <div class="audit-toolbar-left">
            <label class="toolbar-label">数据库</label>
            <select v-model="errorLogDbId" class="filter-select" @change="fetchErrorLogs">
              <option value="">选择数据库</option>
              <option v-for="db in databases" :key="db.id" :value="db.id">{{ db.name }}</option>
            </select>
            <select v-model="errorLogLevel" class="filter-select" @change="filterErrorLogs">
              <option value="">All log levels</option>
              <option value="ERROR">ERROR</option>
              <option value="WARNING">WARNING</option>
              <option value="LOG">LOG</option>
              <option value="STATEMENT">STATEMENT</option>
            </select>
          </div>
          <button class="refresh-btn" @click="fetchErrorLogs" :disabled="errorLogLoading">{{ errorLogLoading ? '加载中...' : '刷新' }}</button>
        </div>

        <div v-if="!errorLogDbId" class="empty-state" style="padding: 48px;">
          <p>请选择数据库查看错误日志</p>
        </div>
        <template v-else>
          <div class="table-wrapper">
            <table class="data-table" v-if="filteredErrorLogs.length > 0">
              <thead>
                <tr>
                  <th style="width: 200px;">时间</th>
                  <th style="width: 100px;">日志级别/语句</th>
                  <th>描述</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(entry, i) in pagedErrorLogs" :key="i" :class="{ 'row-error': entry.level === 'ERROR' }">
                  <td>{{ entry.timestamp || '-' }}</td>
                  <td>
                    <span class="log-level" :class="'level-' + (entry.level || '').toLowerCase()">{{ entry.level }}</span>
                  </td>
                  <td><code class="log-message" :title="entry.message">{{ entry.message }}</code></td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty-state">
              <p v-if="errorLogLoading">加载中...</p>
              <p v-else>暂无日志记录。数据库未运行或没有日志输出。</p>
            </div>
          </div>
          <TableFooter v-if="filteredErrorLogs.length > 0" :total="filteredErrorLogs.length" v-model:pageSize="errorLogPageSize" v-model:currentPage="errorLogPage" />
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import { operationApi, type OperationLog } from '../../api/operation'
import { auditApi, type AuditLog } from '../../api/audit'
import { formatDuration, formatDate } from '../../utils/format'
import TableToolbar from '../../components/TableToolbar.vue'
import TableFooter from '../../components/TableFooter.vue'

const OP_LABELS: Record<string, string> = {
  CREATE: '创建',
  RESUME: '唤醒',
  SUSPEND: '挂起',
  DELETE: '删除',
  IMPORT: '导入',
  UPDATE: '更新',
  RESET_PASSWORD: '重置密码',
}

const route = useRoute()
const activeTab = ref('operations')
const databases = ref<Database[]>([])

// ── Operations Tab ──
const allOps = ref<OperationLog[]>([])
const opLoading = ref(true)
const opSearch = ref('')
const opDbFilter = ref((route.query.db as string) || '')
const opTypeFilter = ref('')
const opStatusFilter = ref('')
const opPageSize = ref(20)
const opPage = ref(1)

const filteredOps = computed(() => {
  let ops = allOps.value
  if (opDbFilter.value) ops = ops.filter(op => op.databaseId === opDbFilter.value)
  if (opTypeFilter.value) ops = ops.filter(op => op.operationType === opTypeFilter.value)
  if (opStatusFilter.value) ops = ops.filter(op => op.status === opStatusFilter.value)
  const q = opSearch.value.toLowerCase()
  if (q) {
    ops = ops.filter(op =>
      op.operationType.toLowerCase().includes(q) ||
      (OP_LABELS[op.operationType] || '').includes(q) ||
      (op.errorMessage || '').toLowerCase().includes(q)
    )
  }
  return ops
})

const pagedOps = computed(() => {
  const start = (opPage.value - 1) * opPageSize.value
  return filteredOps.value.slice(start, start + opPageSize.value)
})

watch([opSearch, opDbFilter, opTypeFilter, opStatusFilter, opPageSize], () => { opPage.value = 1 })

async function fetchOps() {
  opLoading.value = true
  try {
    const res = await operationApi.getRecent()
    allOps.value = res.data
  } catch (e) { console.error('Failed to load operations', e) }
  finally { opLoading.value = false }
}

// ── Audit Tab ──
const auditDbId = ref('')
const auditTypeFilter = ref('')
const auditLoading = ref(false)
const auditLogs = ref<AuditLog[]>([])
const auditTotal = ref(0)
const auditPageSize = ref(20)
const auditPage = ref(1)
const auditNotEnabled = ref(false)

async function fetchAuditLogs() {
  if (!auditDbId.value) return
  auditLoading.value = true
  auditNotEnabled.value = false
  try {
    // Check if audit is enabled
    const configRes = await auditApi.getConfig(auditDbId.value)
    if (!configRes.data.enabled) {
      auditNotEnabled.value = true
      auditLogs.value = []
      auditTotal.value = 0
      auditLoading.value = false
      return
    }
    const params: { type?: string; page?: number; size?: number } = {
      page: auditPage.value - 1,
      size: auditPageSize.value,
    }
    if (auditTypeFilter.value) params.type = auditTypeFilter.value
    const res = await auditApi.getLogs(auditDbId.value, params)
    auditLogs.value = res.data.data
    auditTotal.value = res.data.total
  } catch (e) { console.error('Failed to load audit logs', e) }
  finally { auditLoading.value = false }
}

// ── Error Log Tab ──
const errorLogDbId = ref('')
const errorLogLevel = ref('')
const errorLogLoading = ref(false)
const allErrorLogs = ref<{ timestamp: string; level: string; message: string }[]>([])
const errorLogPageSize = ref(20)
const errorLogPage = ref(1)

const filteredErrorLogs = computed(() => {
  if (!errorLogLevel.value) return allErrorLogs.value
  return allErrorLogs.value.filter(e => e.level === errorLogLevel.value)
})

const pagedErrorLogs = computed(() => {
  const start = (errorLogPage.value - 1) * errorLogPageSize.value
  return filteredErrorLogs.value.slice(start, start + errorLogPageSize.value)
})

function filterErrorLogs() { errorLogPage.value = 1 }

async function fetchErrorLogs() {
  if (!errorLogDbId.value) return
  errorLogLoading.value = true
  try {
    const res = await databaseApi.getLogs(errorLogDbId.value, 500)
    allErrorLogs.value = res.data
    errorLogPage.value = 1
  } catch (e) { console.error('Failed to load error logs', e) }
  finally { errorLogLoading.value = false }
}

// ── Shared ──
function truncate(s: string | null, max: number): string {
  if (!s) return '-'
  return s.length > max ? s.slice(0, max) + '...' : s
}

async function fetchDatabases() {
  try {
    const res = await databaseApi.list()
    databases.value = res.data
  } catch (e) { /* ignore */ }
}

onMounted(() => {
  fetchOps()
  fetchDatabases()
})
</script>

<style scoped>
/* Tabs */
.log-tabs {
  display: flex;
  gap: 0;
  border-bottom: 2px solid #e5e5e5;
  margin-bottom: 20px;
}

.log-tab {
  padding: 10px 24px;
  font-size: 14px;
  color: #575d6c;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  cursor: pointer;
  transition: all 0.15s;
}

.log-tab:hover { color: #0073e6; }

.log-tab.active {
  color: #191919;
  font-weight: 600;
  border-bottom-color: #191919;
}

/* Toolbar */
.audit-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.audit-toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar-label {
  font-size: 13px;
  color: #8a8e99;
  white-space: nowrap;
}

.filter-select {
  padding: 6px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #333;
  background: #fff;
  cursor: pointer;
}

.filter-select:hover { border-color: #0073e6; }

.refresh-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 14px;
  font-size: 13px;
  color: #575d6c;
  cursor: pointer;
}

.refresh-btn:hover:not(:disabled) { border-color: #0073e6; color: #0073e6; }
.refresh-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* Links */
.db-link {
  color: #0073e6;
  text-decoration: none;
}
.db-link:hover { text-decoration: underline; }

.text-muted { color: #ccc; }

/* Operation badges */
.op-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  background: #f5f5f5;
  color: #575d6c;
}

.op-create { background: #e6f7ff; color: #0073e6; }
.op-resume { background: #f6ffed; color: #389e0d; }
.op-suspend { background: #f5f5f5; color: #8a8e99; }
.op-delete { background: #fff1f0; color: #cf1322; }
.op-import { background: #f9f0ff; color: #722ed1; }
.op-update { background: #fff7e6; color: #d48806; }

.row-error { background: #fff8f8; }
.error-text { color: #cf1322; font-size: 12px; }

/* SQL Audit */
.stmt-type {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}

.stmt-ddl { background: #f6ffed; color: #389e0d; }
.stmt-dml { background: #fff7e6; color: #d48806; }
.stmt-select { background: #e6f7ff; color: #0073e6; }

.sql-text {
  font-size: 12px;
  color: #575d6c;
  word-break: break-all;
  max-width: 400px;
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audit-tip {
  padding: 12px 16px;
  background: #fffbe6;
  border-bottom: 1px solid #ffe58f;
  font-size: 13px;
  color: #d48806;
}

/* Error logs */
.log-level {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 600;
}

.level-error { color: #cf1322; background: #fff1f0; }
.level-warning { color: #d48806; background: #fff7e6; }
.level-log { color: #575d6c; background: #f5f5f5; }
.level-statement { color: #0073e6; background: #e6f7ff; text-decoration: underline; }
.level-info { color: #575d6c; background: #f5f5f5; }

.log-message {
  font-size: 12px;
  color: #575d6c;
  word-break: break-all;
}

@media (max-width: 768px) {
  .audit-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .audit-toolbar-left {
    flex-wrap: wrap;
  }
}
</style>
