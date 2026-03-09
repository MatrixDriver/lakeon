<template>
  <div class="page-dashboard">
    <div class="page-header">
      <h1 class="page-title">总览</h1>
    </div>

    <div v-if="apiOffline" class="offline-banner">
      DBay API 当前不可用，服务可能正在维护中。
    </div>

    <!-- ===== New User: Welcome & Onboarding ===== -->
    <template v-if="!loading && stats.total === 0">
      <!-- Welcome Hero -->
      <div class="welcome-card">
        <div class="welcome-icon">
          <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
            <rect x="4" y="8" width="40" height="12" rx="3" fill="#0073e6" opacity="0.15"/>
            <rect x="4" y="24" width="40" height="12" rx="3" fill="#0073e6" opacity="0.10"/>
            <circle cx="12" cy="14" r="2" fill="#0073e6"/>
            <circle cx="12" cy="30" r="2" fill="#0073e6"/>
            <rect x="18" y="13" width="18" height="2" rx="1" fill="#0073e6" opacity="0.5"/>
            <rect x="18" y="29" width="14" height="2" rx="1" fill="#0073e6" opacity="0.5"/>
          </svg>
        </div>
        <h2 class="welcome-title">欢迎来到 DBay 数据港湾</h2>
        <p class="welcome-desc">Serverless PostgreSQL 云数据库，几秒即可创建，不使用时自动休眠，零闲置费用。</p>
        <div class="welcome-actions">
          <button class="btn btn-primary btn-lg" @click="showCreateDialog = true">创建第一个数据库</button>
          <router-link to="/databases" class="btn btn-outline btn-lg" @click.prevent="goImport">导入已有数据库</router-link>
        </div>
      </div>

      <!-- Quick Start Steps -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header"><h3>快速入门</h3></div>
        <div class="quickstart-steps">
          <div class="qs-step">
            <span class="qs-num">1</span>
            <div class="qs-content">
              <div class="qs-title">创建数据库</div>
              <div class="qs-desc">选择规格，几秒内即可获得一个 PostgreSQL 17 实例</div>
            </div>
          </div>
          <div class="qs-step">
            <span class="qs-num">2</span>
            <div class="qs-content">
              <div class="qs-title">获取连接字符串</div>
              <div class="qs-desc">在数据库详情页复制连接地址，支持 SSL 安全连接</div>
            </div>
          </div>
          <div class="qs-step">
            <span class="qs-num">3</span>
            <div class="qs-content">
              <div class="qs-title">连接并使用</div>
              <div class="qs-desc">用 psql、DBeaver 或任何 PostgreSQL 客户端直接连接</div>
            </div>
          </div>
          <div class="qs-step">
            <span class="qs-num">4</span>
            <div class="qs-content">
              <div class="qs-title">导入已有数据 <span class="qs-optional">可选</span></div>
              <div class="qs-desc">从外部 PostgreSQL 一键导入，轻松迁移到云端</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Feature Highlights -->
      <div class="feature-grid" style="margin-top: 24px;">
        <div class="feature-card">
          <div class="feature-icon feature-icon-serverless">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>
            </svg>
          </div>
          <div class="feature-title">Serverless 弹性</div>
          <div class="feature-desc">自动休眠与唤醒，不使用时零费用，首次连接秒级启动</div>
        </div>
        <div class="feature-card">
          <div class="feature-icon feature-icon-import">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3"/>
            </svg>
          </div>
          <div class="feature-title">数据导入</div>
          <div class="feature-desc">从任意 PostgreSQL 一键迁移，自动处理 schema 和数据</div>
        </div>
        <div class="feature-card">
          <div class="feature-icon feature-icon-branch">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <line x1="6" y1="3" x2="6" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/>
              <path d="M18 9a9 9 0 01-9 9"/>
            </svg>
          </div>
          <div class="feature-title">分支管理</div>
          <div class="feature-desc">像 Git 一样创建数据库分支，隔离开发与测试环境</div>
        </div>
        <div class="feature-card">
          <div class="feature-icon feature-icon-secure">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/>
            </svg>
          </div>
          <div class="feature-title">安全连接</div>
          <div class="feature-desc">SSL/TLS 全链路加密，API Key 认证，细粒度访问控制</div>
        </div>
        <div class="feature-card">
          <div class="feature-icon feature-icon-compat">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>
            </svg>
          </div>
          <div class="feature-title">完全兼容</div>
          <div class="feature-desc">100% 兼容 PostgreSQL 17，现有工具和 ORM 无缝对接</div>
        </div>
        <div class="feature-card">
          <div class="feature-icon feature-icon-backup">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 102.13-9.36L1 10"/>
            </svg>
          </div>
          <div class="feature-title">自动备份</div>
          <div class="feature-desc">基于 WAL 连续备份，支持按时间点恢复，数据永不丢失</div>
        </div>
      </div>
    </template>

    <!-- ===== Returning User: Dashboard ===== -->
    <template v-else-if="!loading && stats.total > 0">
      <!-- Status Bar -->
      <div class="status-bar">
        <div class="status-bar-item">
          <span class="status-bar-label">数据库总数</span>
          <span class="status-bar-count">{{ stats.total }}</span>
        </div>
        <div class="status-bar-item">
          <span class="status-dot dot-green"></span>
          <span class="status-bar-label">运行中</span>
          <span class="status-bar-count">{{ stats.running }}</span>
        </div>
        <div class="status-bar-item">
          <span class="status-dot dot-gray"></span>
          <span class="status-bar-label">已挂起</span>
          <span class="status-bar-count">{{ stats.suspended }}</span>
        </div>
        <div class="status-bar-item">
          <span class="status-dot dot-red"></span>
          <span class="status-bar-label">异常</span>
          <span class="status-bar-count" :class="{ 'has-error': stats.error > 0 }">{{ stats.error }}</span>
        </div>
        <div class="status-bar-item" v-if="stats.storageUsed > 0">
          <span class="status-bar-label">存储用量</span>
          <span class="status-bar-count">{{ stats.storageUsed.toFixed(1) }} / {{ stats.storageTotal }} GB</span>
        </div>
      </div>

      <!-- Quick Actions -->
      <div class="quick-actions">
        <button class="qa-btn" @click="showCreateDialog = true">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          创建数据库
        </button>
        <button class="qa-btn" @click="goImport">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
          导入数据
        </button>
        <router-link to="/apikey" class="qa-btn">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 11-7.78 7.78 5.5 5.5 0 017.78-7.78zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>
          API Keys
        </router-link>
        <router-link to="/docs" class="qa-btn">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 016.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/></svg>
          使用指南
        </router-link>
      </div>

      <!-- Database Instances -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>数据库实例</h3>
          <router-link to="/databases" class="section-link">查看全部</router-link>
        </div>
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>状态</th>
                <th>规格</th>
                <th>存储用量</th>
                <th>连接字符串</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="db in databases.slice(0, 5)" :key="db.id">
                <td>
                  <router-link :to="`/databases/${db.id}`" class="db-name-link">{{ db.name }}</router-link>
                </td>
                <td>
                  <span class="status-dot" :class="statusClass(db.status)"></span>
                  {{ statusText(db.status) }}
                </td>
                <td>{{ db.compute_size }}</td>
                <td>
                  <div class="storage-info">
                    <div class="storage-bar"><div class="storage-fill" :style="{ width: storagePercent(db) + '%' }"></div></div>
                    <span class="storage-text">{{ db.storage_used_gb.toFixed(2) }} / {{ db.storage_limit_gb }} GB</span>
                  </div>
                </td>
                <td>
                  <button class="copy-btn" @click="copyConnectionUri(db)" :title="db.connection_uri">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>
                    {{ copiedId === db.id ? '已复制' : '复制' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Recent Operations -->
      <div class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>最近操作</h3>
        </div>
        <TableToolbar v-model="opsSearch" placeholder="搜索数据库名称或操作类型" :loading="false" @refresh="fetchData" />
        <div class="table-wrapper">
          <table class="data-table" v-if="filteredOps.length > 0">
            <thead>
              <tr>
                <th>数据库</th>
                <th>操作类型</th>
                <th>状态</th>
                <th>耗时</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="op in pagedOps" :key="op.id">
                <td>{{ op.databaseName }}</td>
                <td>{{ OP_LABELS[op.operationType] || op.operationType }}</td>
                <td>
                  <span class="status-tag" :class="op.status === 'SUCCESS' ? 'tag-green' : 'tag-red'">
                    {{ op.status === 'SUCCESS' ? '成功' : '失败' }}
                  </span>
                </td>
                <td>{{ formatDuration(op.durationMs) }}</td>
                <td>{{ formatDate(op.startedAt) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty-state">
            <p>暂无操作记录</p>
          </div>
        </div>
        <TableFooter
          v-if="filteredOps.length > 0"
          :total="filteredOps.length"
          v-model:pageSize="opsPageSize"
          v-model:currentPage="opsCurrentPage"
        />
      </div>
    </template>

    <!-- Loading State -->
    <div v-if="loading" class="loading-state">
      <p>加载中...</p>
    </div>

    <!-- Create Database Dialog (reused from DatabaseList) -->
    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="showCreateDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>创建数据库</h3>
          <button class="dialog-close" @click="showCreateDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">名称 <span class="required">*</span></label>
            <input v-model="createForm.name" class="form-input" placeholder="请输入数据库名称" @keyup.enter="handleCreate" />
          </div>
          <div class="form-group">
            <label class="form-label">规格</label>
            <select v-model="createForm.compute_size" class="form-select">
              <option value="1cu">1 CU</option>
              <option value="2cu">2 CU</option>
              <option value="4cu">4 CU</option>
              <option value="8cu">8 CU</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">挂起超时</label>
            <select v-model="createForm.suspend_timeout" class="form-select">
              <option value="5m">5 分钟</option>
              <option value="10m">10 分钟</option>
              <option value="30m">30 分钟</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">存储上限</label>
            <select v-model="createForm.storage_limit_gb" class="form-select">
              <option :value="5">5 GB</option>
              <option :value="10">10 GB</option>
              <option :value="50">50 GB</option>
              <option :value="100">100 GB</option>
            </select>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreateDialog = false">取消</button>
          <button class="btn btn-primary" :disabled="!createForm.name.trim() || createLoading" @click="handleCreate">
            {{ createLoading ? '创建中...' : '确定' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import { operationApi, type OperationLog } from '../../api/operation'
import { formatDuration, formatDate } from '../../utils/format'
import TableToolbar from '../../components/TableToolbar.vue'
import TableFooter from '../../components/TableFooter.vue'

const router = useRouter()

const OP_LABELS: Record<string, string> = {
  CREATE: '创建',
  RESUME: '唤醒',
  SUSPEND: '挂起',
  DELETE: '删除',
  IMPORT: '导入',
  UPDATE: '更新',
  RESET_PASSWORD: '重置密码',
}

const stats = reactive({ total: 0, running: 0, suspended: 0, error: 0, storageUsed: 0, storageTotal: 0 })
const databases = ref<Database[]>([])
const recentOps = ref<OperationLog[]>([])
const loading = ref(true)
const apiOffline = ref(false)
const opsSearch = ref('')
const opsPageSize = ref(10)
const opsCurrentPage = ref(1)
const copiedId = ref<string | null>(null)

// Create dialog
const showCreateDialog = ref(false)
const createLoading = ref(false)
const createForm = reactive({
  name: '',
  compute_size: '1cu',
  suspend_timeout: '5m',
  storage_limit_gb: 10,
})

const filteredOps = computed(() => {
  const q = opsSearch.value.toLowerCase()
  if (!q) return recentOps.value
  return recentOps.value.filter(op =>
    op.databaseName.toLowerCase().includes(q) ||
    op.operationType.toLowerCase().includes(q)
  )
})

const pagedOps = computed(() => {
  const start = (opsCurrentPage.value - 1) * opsPageSize.value
  return filteredOps.value.slice(start, start + opsPageSize.value)
})

watch([opsSearch, opsPageSize], () => { opsCurrentPage.value = 1 })

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

function copyConnectionUri(db: Database) {
  navigator.clipboard.writeText(db.connection_uri)
  copiedId.value = db.id
  setTimeout(() => { copiedId.value = null }, 2000)
}

function goImport() {
  // Navigate to databases page — user can use import from there
  router.push('/databases')
}

async function handleCreate() {
  if (!createForm.name.trim()) return
  createLoading.value = true
  try {
    await databaseApi.create({
      name: createForm.name.trim(),
      compute_size: createForm.compute_size,
      suspend_timeout: createForm.suspend_timeout,
      storage_limit_gb: createForm.storage_limit_gb,
    })
    showCreateDialog.value = false
    createForm.name = ''
    createForm.compute_size = '1cu'
    createForm.suspend_timeout = '5m'
    createForm.storage_limit_gb = 10
    await fetchData()
  } catch (e) {
    console.error('Failed to create database', e)
  } finally {
    createLoading.value = false
  }
}

async function fetchData() {
  loading.value = true
  try {
    const [dbRes, opsRes] = await Promise.all([
      databaseApi.list(),
      operationApi.getRecent(),
    ])

    databases.value = dbRes.data
    stats.total = databases.value.length
    stats.running = databases.value.filter(d => d.status === 'RUNNING').length
    stats.suspended = databases.value.filter(d => d.status === 'SUSPENDED').length
    stats.error = databases.value.filter(d => !['RUNNING', 'SUSPENDED', 'CREATING'].includes(d.status)).length
    stats.storageUsed = databases.value.reduce((sum, d) => sum + (d.storage_used_gb || 0), 0)
    stats.storageTotal = databases.value.reduce((sum, d) => sum + (d.storage_limit_gb || 0), 0)

    recentOps.value = opsRes.data
  } catch (e: any) {
    if (e.isApiOffline) {
      apiOffline.value = true
    }
    console.error('Failed to load dashboard data', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>

<style scoped>
/* ── Welcome Card ── */
.welcome-card {
  background: linear-gradient(135deg, #f0f7ff 0%, #ffffff 100%);
  border: 1px solid #d4e5f7;
  border-radius: 8px;
  padding: 48px 40px;
  text-align: center;
}

.welcome-icon {
  margin-bottom: 16px;
}

.welcome-title {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
  margin: 0 0 8px;
}

.welcome-desc {
  font-size: 15px;
  color: #575d6c;
  margin: 0 0 28px;
}

.welcome-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}

.btn-lg {
  padding: 10px 28px;
  font-size: 15px;
}

.btn-outline {
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  color: #333;
  cursor: pointer;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
}

.btn-outline:hover {
  border-color: #0073e6;
  color: #0073e6;
}

/* ── Quick Start Steps ── */
.quickstart-steps {
  padding: 8px 16px 16px;
}

.qs-step {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid #f5f5f5;
}

.qs-step:last-child {
  border-bottom: none;
}

.qs-num {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #0073e6;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.qs-content {
  flex: 1;
}

.qs-title {
  font-size: 15px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 2px;
}

.qs-desc {
  font-size: 13px;
  color: #8a8e99;
}

.qs-optional {
  font-size: 11px;
  color: #999;
  background: #f5f5f5;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 400;
  margin-left: 6px;
}

/* ── Feature Cards ── */
.feature-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.feature-card {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 8px;
  padding: 24px 20px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.feature-card:hover {
  border-color: #d4e5f7;
  box-shadow: 0 2px 8px rgba(0, 115, 230, 0.06);
}

.feature-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
}

.feature-icon-serverless { background: #fff7e6; color: #e37318; }
.feature-icon-import { background: #e6f7ff; color: #0073e6; }
.feature-icon-branch { background: #f0f5ff; color: #2f54eb; }
.feature-icon-secure { background: #f6ffed; color: #389e0d; }
.feature-icon-compat { background: #f9f0ff; color: #722ed1; }
.feature-icon-backup { background: #fff0f6; color: #c41d7f; }

.feature-title {
  font-size: 15px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 6px;
}

.feature-desc {
  font-size: 13px;
  color: #8a8e99;
  line-height: 1.5;
}

/* ── Quick Actions ── */
.quick-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.qa-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  text-decoration: none;
  transition: all 0.15s;
}

.qa-btn:hover {
  border-color: #0073e6;
  color: #0073e6;
}

/* ── Database Table in Dashboard ── */
.db-name-link {
  color: #0073e6;
  text-decoration: none;
  font-size: 14px;
}

.db-name-link:hover {
  text-decoration: underline;
}

.storage-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.storage-bar {
  width: 60px;
  height: 4px;
  background-color: #e8e8e8;
  border-radius: 2px;
  overflow: hidden;
  flex-shrink: 0;
}

.storage-fill {
  height: 100%;
  background-color: #0073e6;
  border-radius: 2px;
}

.storage-text {
  font-size: 12px;
  color: #8a8e99;
  white-space: nowrap;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 12px;
  color: #575d6c;
  cursor: pointer;
  transition: all 0.15s;
}

.copy-btn:hover {
  border-color: #0073e6;
  color: #0073e6;
}

.section-link {
  font-size: 13px;
  color: #0073e6;
  text-decoration: none;
}

.section-link:hover {
  text-decoration: underline;
}

/* ── Misc ── */
.offline-banner {
  background: #fff3e0;
  border: 1px solid #ffb74d;
  color: #e65100;
  padding: 12px 16px;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 14px;
}

.loading-state {
  text-align: center;
  padding: 60px 0;
  color: #8a8e99;
  font-size: 14px;
}

@media (max-width: 768px) {
  .feature-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .welcome-actions {
    flex-direction: column;
    align-items: center;
  }

  .quick-actions {
    flex-wrap: wrap;
  }
}
</style>
