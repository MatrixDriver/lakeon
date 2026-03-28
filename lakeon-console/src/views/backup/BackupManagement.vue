<template>
  <div>
    <div class="page-header">
      <div class="breadcrumb">
        <span class="bc-text">备份管理</span>
      </div>
    </div>

    <div class="page-actions">
      <button class="btn btn-primary" @click="showCreateDialog = true">创建备份</button>
    </div>

    <p class="page-tip">
      查看和管理所有数据库的备份。从备份恢复时将创建一个新的数据库实例，不会覆盖当前数据。
      <router-link to="/docs#backups" class="tip-link">了解更多</router-link>
    </p>

    <!-- Summary -->
    <div class="summary-row" v-if="!loading">
      <div class="summary-item">
        <span class="summary-value">{{ backups.length }}</span>
        <span class="summary-label">总备份数</span>
      </div>
      <div class="summary-item">
        <span class="summary-value">{{ completedCount }}</span>
        <span class="summary-label">已完成</span>
      </div>
      <div class="summary-item">
        <span class="summary-value">{{ formatTotalSize }}</span>
        <span class="summary-label">总大小</span>
      </div>
      <div class="summary-item">
        <span class="summary-value">{{ dbCount }}</span>
        <span class="summary-label">涉及数据库</span>
      </div>
    </div>

    <!-- Filter -->
    <div class="section-card">
      <div class="filter-bar">
        <div class="filter-left">
          <select v-model="filterDb" class="filter-select">
            <option value="">全部数据库</option>
            <option v-for="db in dbOptions" :key="db.id" :value="db.id">{{ db.name }}</option>
          </select>
          <select v-model="filterType" class="filter-select">
            <option value="">全部类型</option>
            <option value="MANUAL">手动</option>
            <option value="SCHEDULED">定时</option>
          </select>
          <select v-model="filterStatus" class="filter-select">
            <option value="">全部状态</option>
            <option value="COMPLETED">已完成</option>
            <option value="RUNNING">进行中</option>
            <option value="PENDING">等待中</option>
            <option value="FAILED">失败</option>
          </select>
        </div>
        <div class="filter-right">
          <input v-model="searchText" class="filter-input" placeholder="搜索备份名称..." />
          <button class="btn btn-icon" @click="loadData" :disabled="loading">
            <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
              <path d="M8 3a5 5 0 0 1 4.546 2.914.5.5 0 0 0 .908-.418A6 6 0 0 0 2 8a6 6 0 0 0 11.454 2.504.5.5 0 0 0-.908-.418A5 5 0 1 1 8 3z"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- Table -->
      <div class="table-wrapper">
        <table class="data-table" v-if="filtered.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>数据库</th>
              <th>类型</th>
              <th>状态</th>
              <th>LSN</th>
              <th>大小</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in paged" :key="b.id">
              <td class="backup-name">{{ b.name }}</td>
              <td>
                <router-link v-if="dbMap[b.database_id]" :to="`/databases/${b.database_id}`" class="db-link">
                  {{ dbMap[b.database_id] }}
                </router-link>
                <span v-else class="db-id-text">{{ b.database_id }}</span>
              </td>
              <td>{{ b.type === 'MANUAL' ? '手动' : '定时' }}</td>
              <td>
                <span class="status-tag" :class="statusClass(b.status)">
                  {{ statusText(b.status) }}
                </span>
              </td>
              <td><code class="lsn-text">{{ b.lsn || '-' }}</code></td>
              <td>{{ formatSize(b.size_bytes) }}</td>
              <td class="time-text">{{ formatDate(b.created_at) }}</td>
              <td>
                <button
                  class="btn btn-small btn-text"
                  :disabled="b.status !== 'COMPLETED'"
                  @click="openRestore(b)"
                >恢复</button>
                <button
                  class="btn btn-small btn-text btn-danger-text"
                  @click="handleDelete(b)"
                >删除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">
          <p v-if="loading">加载中...</p>
          <p v-else>暂无备份</p>
        </div>
      </div>

      <!-- Pagination -->
      <div class="table-footer" v-if="filtered.length > 0">
        <span class="footer-total">共 {{ filtered.length }} 条</span>
        <div class="footer-pages">
          <button class="btn btn-small" :disabled="currentPage <= 1" @click="currentPage--">上一页</button>
          <span class="page-info">{{ currentPage }} / {{ totalPages }}</span>
          <button class="btn btn-small" :disabled="currentPage >= totalPages" @click="currentPage++">下一页</button>
        </div>
      </div>
    </div>

    <!-- Restore Dialog -->
    <div v-if="showRestoreDialog" class="dialog-overlay" @click.self="showRestoreDialog = false">
      <div class="dialog-box dialog-confirm">
        <div class="dialog-header">
          <h3>从备份恢复</h3>
          <button class="dialog-close" @click="showRestoreDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p class="restore-hint">
            将从备份 <strong>{{ restoreBackup?.name }}</strong>
            <span v-if="dbMap[restoreBackup?.database_id ?? '']">（数据库: {{ dbMap[restoreBackup?.database_id ?? ''] }}）</span>
            创建一个新的数据库实例。
          </p>
          <div class="form-group">
            <label class="form-label">新数据库名称 <span class="required">*</span></label>
            <input v-model="restoreDbName" class="form-input" placeholder="请输入新数据库名称" />
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showRestoreDialog = false">取消</button>
          <button
            class="btn btn-primary"
            :disabled="!restoreDbName.trim() || restoring"
            @click="handleRestore"
          >{{ restoring ? '恢复中...' : '确定' }}</button>
        </div>
      </div>
    </div>

    <!-- Create Backup Dialog -->
    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="showCreateDialog = false">
      <div class="dialog-box dialog-confirm">
        <div class="dialog-header">
          <h3>创建备份</h3>
          <button class="dialog-close" @click="showCreateDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">选择数据库 <span class="required">*</span></label>
            <select v-model="createDbId" class="form-input">
              <option value="" disabled>请选择数据库</option>
              <option v-for="db in allDbs" :key="db.id" :value="db.id">{{ db.name }}</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">备份名称</label>
            <input v-model="createBackupName" class="form-input" placeholder="可选，留空自动生成" />
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreateDialog = false">取消</button>
          <button
            class="btn btn-primary"
            :disabled="!createDbId || creating"
            @click="handleCreate"
          >{{ creating ? '创建中...' : '确定' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { backupApi, type Backup } from '../../api/backup'
import { databaseApi } from '../../api/database'
import { formatSize } from '../../utils/format'

const router = useRouter()

interface DbOption { id: string; name: string }

const backups = ref<Backup[]>([])
const dbMap = ref<Record<string, string>>({})
const allDbs = ref<DbOption[]>([])
const loading = ref(true)
const searchText = ref('')
const filterDb = ref('')
const filterType = ref('')
const filterStatus = ref('')
const currentPage = ref(1)
const pageSize = 15

// Create
const showCreateDialog = ref(false)
const createDbId = ref('')
const createBackupName = ref('')
const creating = ref(false)

// Restore
const showRestoreDialog = ref(false)
const restoreBackup = ref<Backup | null>(null)
const restoreDbName = ref('')
const restoring = ref(false)

const dbOptions = computed(() => {
  const ids = new Set(backups.value.map(b => b.database_id))
  return Array.from(ids).map(id => ({ id, name: dbMap.value[id] || id }))
})

const completedCount = computed(() => backups.value.filter(b => b.status === 'COMPLETED').length)
const dbCount = computed(() => new Set(backups.value.map(b => b.database_id)).size)
const formatTotalSize = computed(() => {
  const total = backups.value.reduce((sum, b) => sum + (b.size_bytes || 0), 0)
  return formatSize(total)
})

const filtered = computed(() => {
  let list = backups.value
  if (filterDb.value) list = list.filter(b => b.database_id === filterDb.value)
  if (filterType.value) list = list.filter(b => b.type === filterType.value)
  if (filterStatus.value) list = list.filter(b => b.status === filterStatus.value)
  if (searchText.value) {
    const q = searchText.value.toLowerCase()
    list = list.filter(b => b.name.toLowerCase().includes(q))
  }
  return list
})

const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const paged = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  return filtered.value.slice(start, start + pageSize)
})

function statusText(s: string): string {
  const map: Record<string, string> = { COMPLETED: '已完成', RUNNING: '进行中', PENDING: '等待中', FAILED: '失败' }
  return map[s] || s
}

function statusClass(s: string): string {
  if (s === 'COMPLETED') return 'tag-green'
  if (s === 'FAILED') return 'tag-red'
  return 'tag-blue'
}


function formatDate(iso: string): string {
  if (!iso) return ''
  try {
    const d = new Date(iso)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch { return iso }
}

function openRestore(b: Backup) {
  restoreBackup.value = b
  restoreDbName.value = ''
  showRestoreDialog.value = true
}

async function handleRestore() {
  if (!restoreBackup.value || !restoreDbName.value.trim()) return
  restoring.value = true
  try {
    const { data } = await backupApi.restore(restoreBackup.value.database_id, restoreBackup.value.id, { name: restoreDbName.value.trim() })
    alert(`恢复成功！新数据库: ${data.name}`)
    showRestoreDialog.value = false
    router.push(`/databases/${data.id}`)
  } catch (e: any) {
    alert('恢复失败: ' + (e.response?.data?.message || e.message))
  } finally {
    restoring.value = false
  }
}

async function handleCreate() {
  if (!createDbId.value) return
  creating.value = true
  try {
    await backupApi.create(createDbId.value, { name: createBackupName.value.trim() || undefined })
    showCreateDialog.value = false
    createDbId.value = ''
    createBackupName.value = ''
    await loadData()
  } catch (e: any) {
    alert('创建失败: ' + (e.response?.data?.message || e.message))
  } finally {
    creating.value = false
  }
}

async function handleDelete(b: Backup) {
  if (!confirm(`确认删除备份「${b.name}」？此操作不可恢复。`)) return
  try {
    await backupApi.delete(b.database_id, b.id)
    backups.value = backups.value.filter(x => x.id !== b.id)
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

async function loadData() {
  loading.value = true
  try {
    const [backupsRes, dbsRes] = await Promise.allSettled([
      backupApi.listAll(),
      databaseApi.list(),
    ])
    if (backupsRes.status === 'fulfilled') {
      backups.value = backupsRes.value.data
    }
    if (dbsRes.status === 'fulfilled') {
      const map: Record<string, string> = {}
      const dbs: DbOption[] = []
      for (const db of dbsRes.value.data) {
        map[db.id] = db.name
        dbs.push({ id: db.id, name: db.name })
      }
      dbMap.value = map
      allDbs.value = dbs
    }
  } catch (e) {
    console.error('Failed to load backups', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.page-actions {
  margin-bottom: 16px;
}
.page-tip {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 20px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  padding: 10px 16px;
}
.tip-link { color: #0073e6; text-decoration: none; }
.tip-link:hover { text-decoration: underline; }

.summary-row {
  display: flex;
  gap: 24px;
  margin-bottom: 20px;
}
.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 28px;
  background: #fafbfc;
  border: 1px solid #ebebeb;
  border-radius: 6px;
  min-width: 120px;
}
.summary-value {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
}
.summary-label {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #ebebeb;
  flex-wrap: wrap;
  gap: 8px;
}
.filter-left, .filter-right {
  display: flex;
  gap: 8px;
  align-items: center;
}
.filter-select {
  height: 32px;
  padding: 0 8px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
  color: #333;
}
.filter-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  width: 180px;
}
.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  color: #575d6c;
}
.btn-icon:hover { border-color: #0073e6; color: #0073e6; }

.backup-name { font-weight: 500; }
.db-link { color: #0073e6; text-decoration: none; font-size: 13px; }
.db-link:hover { text-decoration: underline; }
.db-id-text { font-size: 12px; color: #999; font-family: monospace; }
.lsn-text { font-size: 12px; color: #575d6c; }
.time-text { font-size: 13px; color: #575d6c; white-space: nowrap; }

.status-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}
.tag-green { background: #f6ffed; color: #389e0d; }
.tag-red { background: #fff1f0; color: #e6393d; }
.tag-blue { background: #e6f7ff; color: #096dd9; }

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-top: 1px solid #ebebeb;
  font-size: 13px;
  color: #575d6c;
}
.footer-pages {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-info { font-size: 13px; }

.empty-state { padding: 40px; text-align: center; color: #999; }

/* Dialog */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.dialog-box {
  background: #fff;
  border-radius: 8px;
  width: 480px;
  max-width: 90vw;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.15);
}
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 0;
}
.dialog-header h3 { margin: 0; font-size: 16px; }
.dialog-close {
  background: none;
  border: none;
  font-size: 20px;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
}
.dialog-body { padding: 20px 24px; }
.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 0 24px 20px;
}
.restore-hint { font-size: 14px; color: #575d6c; margin-bottom: 16px; }
.form-group { margin-bottom: 16px; }
.form-label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; }
.required { color: #e6393d; }
.form-input {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 14px;
  box-sizing: border-box;
}

/* Shared btn styles (should exist from global, but fallback) */
.btn { cursor: pointer; border-radius: 4px; font-size: 13px; padding: 6px 16px; border: 1px solid #d9d9d9; background: #fff; }
.btn-primary { background: #0073e6; color: #fff; border-color: #0073e6; }
.btn-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-default { background: #fff; color: #333; }
.btn-small { padding: 4px 10px; font-size: 12px; }
.btn-text { border: none; background: none; color: #0073e6; padding: 4px 8px; }
.btn-text:hover { text-decoration: underline; }
.btn-text:disabled { color: #ccc; text-decoration: none; }
.btn-danger-text { color: #e6393d; }

@media (max-width: 768px) {
  .summary-row { flex-wrap: wrap; gap: 12px; }
  .summary-item { min-width: 80px; padding: 12px 16px; }
  .filter-bar { flex-direction: column; align-items: stretch; }
  .filter-input { width: 100%; }
}
</style>
