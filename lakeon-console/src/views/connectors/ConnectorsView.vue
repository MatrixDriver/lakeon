<template>
  <div class="connectors-page">
    <div class="connectors-shell">
      <header class="connectors-header">
        <div>
          <div class="breadcrumb">数据源 / 连接器</div>
          <h1 class="page-title">连接器</h1>
          <p class="page-desc">统一管理 PostgreSQL 与 OBS 数据源，供数据迁移和数据湖任务复用。</p>
        </div>
        <button class="btn btn-primary" data-test="open-create-postgres" @click="openCreateDialog">
          新建 PostgreSQL
        </button>
      </header>

      <section class="toolbar">
        <div class="toolbar-summary">
          <span class="summary-item"><strong>{{ connectors.length }}</strong> 个连接器</span>
          <span class="summary-item"><strong>{{ postgresCount }}</strong> 个 PostgreSQL</span>
          <span class="summary-item"><strong>{{ obsCount }}</strong> 个 OBS</span>
        </div>
        <button class="btn btn-default btn-small" :disabled="loading" @click="() => loadConnectors()">
          {{ loading ? '刷新中...' : '刷新' }}
        </button>
      </section>

      <div v-if="error" class="error-banner">{{ error }}</div>

      <section class="connector-list" aria-label="连接器列表">
        <div class="connector-row connector-head">
          <span>连接器</span>
          <span>类型</span>
          <span>状态</span>
          <span>目标</span>
          <span>使用</span>
          <span>最近测试</span>
          <span>操作</span>
        </div>

        <div v-if="loading && connectors.length === 0" class="empty-state">
          <p>正在加载连接器...</p>
        </div>

        <div v-else-if="connectors.length === 0" class="empty-state">
          <p>暂无连接器。创建 PostgreSQL 连接器后，可在数据迁移中直接使用。</p>
        </div>

        <div v-for="connector in connectors" :key="connector.id" class="connector-row">
          <div class="connector-name-cell">
            <strong>{{ connector.name }}</strong>
            <small>{{ connector.id }}</small>
          </div>
          <div class="connector-cell" data-label="类型">
            <span class="type-pill" :class="connector.type.toLowerCase()">
              {{ typeLabel(connector.type) }}
            </span>
          </div>
          <div class="connector-cell" data-label="状态">
            <span class="status-pill" :class="connector.status.toLowerCase()">
              {{ statusLabel(connector.status) }}
            </span>
          </div>
          <div class="target-cell connector-cell" data-label="目标">
            <span>{{ targetSummary(connector) }}</span>
            <small v-if="connector.last_error" class="last-error">{{ connector.last_error }}</small>
          </div>
          <div class="usage-cell connector-cell" data-label="使用">
            <span>{{ connector.usage_count }} 次</span>
            <small>{{ connector.usage_hint || '未关联任务' }}</small>
          </div>
          <div class="time-cell connector-cell" data-label="最近测试">{{ formatDate(connector.last_tested_at) }}</div>
          <div class="actions-cell connector-cell" data-label="操作">
            <button
              class="action-link"
              :data-test="`test-${connector.id}`"
              :disabled="connector.type !== 'POSTGRESQL' || Boolean(testingId)"
              :title="connector.type === 'POSTGRESQL' ? '测试连接' : 'OBS 暂不支持此测试接口'"
              @click="handleTest(connector)"
            >
              {{ testingId === connector.id ? '测试中...' : '测试' }}
            </button>
            <router-link
              v-if="connector.type === 'POSTGRESQL'"
              class="action-link"
              to="/import"
            >
              用于迁移
            </router-link>
          </div>
        </div>
      </section>
    </div>

    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="closeCreateDialog">
      <div class="dialog-box connectors-dialog">
        <div class="dialog-header">
          <h3>新建 PostgreSQL 连接器</h3>
          <button class="dialog-close" @click="closeCreateDialog">&times;</button>
        </div>
        <form data-test="save-postgres" @submit.prevent="handleCreatePostgres">
          <div class="dialog-body">
            <div class="form-grid">
              <div class="form-group wide">
                <label class="form-label">名称 <span class="required">*</span></label>
                <input v-model="postgresForm.name" data-test="pg-name" class="form-input" placeholder="例如：生产只读库" />
              </div>
              <div class="form-group wide">
                <label class="form-label">主机 <span class="required">*</span></label>
                <input v-model="postgresForm.host" data-test="pg-host" class="form-input" placeholder="postgres.internal" />
              </div>
              <div class="form-group">
                <label class="form-label">端口 <span class="required">*</span></label>
                <input v-model.number="postgresForm.port" data-test="pg-port" class="form-input" type="number" min="1" max="65535" />
              </div>
              <div class="form-group">
                <label class="form-label">数据库 <span class="required">*</span></label>
                <input v-model="postgresForm.dbname" data-test="pg-dbname" class="form-input" placeholder="app" />
              </div>
              <div class="form-group">
                <label class="form-label">用户名 <span class="required">*</span></label>
                <input v-model="postgresForm.user" data-test="pg-user" class="form-input" autocomplete="username" />
              </div>
              <div class="form-group">
                <label class="form-label">密码 <span class="required">*</span></label>
                <input v-model="postgresForm.password" data-test="pg-password" class="form-input" type="password" autocomplete="current-password" />
              </div>
            </div>
            <p v-if="createError" class="form-error">{{ createError }}</p>
          </div>
          <div class="dialog-footer">
            <button type="button" class="btn btn-default" @click="closeCreateDialog">取消</button>
            <button type="submit" class="btn btn-primary" :disabled="!canCreate || creating">
              {{ creating ? '保存中...' : '保存' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { connectorsApi, type Connector, type ConnectorStatus, type ConnectorType } from '../../api/connectors'

const connectors = ref<Connector[]>([])
const loading = ref(false)
const error = ref('')
const showCreateDialog = ref(false)
const creating = ref(false)
const createError = ref('')
const testingId = ref('')

const postgresForm = reactive({
  name: '',
  host: '',
  port: 5432,
  dbname: '',
  user: '',
  password: '',
})

const postgresCount = computed(() => connectors.value.filter((connector) => connector.type === 'POSTGRESQL').length)
const obsCount = computed(() => connectors.value.filter((connector) => connector.type === 'OBS').length)
const portNumber = computed(() => Number(postgresForm.port))
const isValidPort = computed(() =>
  Number.isInteger(portNumber.value)
  && portNumber.value >= 1
  && portNumber.value <= 65535
)
const canCreate = computed(() =>
  postgresForm.name.trim()
  && postgresForm.host.trim()
  && isValidPort.value
  && postgresForm.dbname.trim()
  && postgresForm.user.trim()
  && postgresForm.password
)

onMounted(loadConnectors)

async function loadConnectors(options: { preserveError?: boolean } = {}) {
  loading.value = true
  const preservedError = error.value
  if (!options.preserveError) {
    error.value = ''
  }
  try {
    const res = await connectorsApi.list()
    connectors.value = res.data
    if (options.preserveError) {
      error.value = preservedError
    }
  } catch (e: any) {
    error.value = options.preserveError && preservedError
      ? preservedError
      : e.response?.data?.error?.message || '连接器加载失败'
  } finally {
    loading.value = false
  }
}

function openCreateDialog() {
  resetForm()
  showCreateDialog.value = true
}

function closeCreateDialog() {
  showCreateDialog.value = false
  createError.value = ''
}

function resetForm() {
  postgresForm.name = ''
  postgresForm.host = ''
  postgresForm.port = 5432
  postgresForm.dbname = ''
  postgresForm.user = ''
  postgresForm.password = ''
  createError.value = ''
}

async function handleCreatePostgres() {
  if (!canCreate.value || creating.value) return
  creating.value = true
  createError.value = ''
  try {
    await connectorsApi.createPostgres({
      name: postgresForm.name.trim(),
      host: postgresForm.host.trim(),
      port: Number(postgresForm.port),
      dbname: postgresForm.dbname.trim(),
      user: postgresForm.user.trim(),
      password: postgresForm.password,
    })
    showCreateDialog.value = false
    await loadConnectors()
  } catch (e: any) {
    createError.value = e.response?.data?.error?.message || '连接器创建失败'
  } finally {
    creating.value = false
  }
}

async function handleTest(connector: Connector) {
  if (connector.type !== 'POSTGRESQL' || testingId.value) return
  testingId.value = connector.id
  error.value = ''
  try {
    const res = await connectorsApi.test(connector.id)
    if (!res.data.ok) {
      error.value = res.data.error || '连接测试失败'
      await loadConnectors({ preserveError: true })
      return
    }
    await loadConnectors()
  } catch (e: any) {
    error.value = e.response?.data?.error?.message || '连接测试失败'
    await loadConnectors({ preserveError: true })
  } finally {
    testingId.value = ''
  }
}

function typeLabel(type: ConnectorType) {
  return type === 'POSTGRESQL' ? 'PostgreSQL' : 'OBS'
}

function statusLabel(status: ConnectorStatus) {
  const labels: Record<ConnectorStatus, string> = {
    UNTESTED: '未测试',
    CONNECTED: '已连接',
    FAILED: '失败',
  }
  return labels[status]
}

function targetSummary(connector: Connector) {
  if (connector.target_summary) return connector.target_summary
  if (connector.type === 'POSTGRESQL') {
    const host = connector.config.host || '-'
    const port = connector.config.port || 5432
    const dbname = connector.config.dbname || '-'
    return `${host}:${port}/${dbname}`
  }
  return '未配置目标'
}

function formatDate(iso: string | null) {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '-'
  return `${date.getFullYear()}/${String(date.getMonth() + 1).padStart(2, '0')}/${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
</script>

<style scoped>
.connectors-page {
  min-height: 100%;
  background: #f8fafc;
}

.connectors-shell {
  width: min(1180px, 100%);
  margin: 0 auto;
  padding: 28px 28px 48px;
}

.connectors-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.breadcrumb {
  color: #64748b;
  font-size: 13px;
  margin-bottom: 8px;
}

.page-title {
  margin: 0;
  color: #0f172a;
  font-size: 28px;
  font-weight: 650;
  letter-spacing: 0;
}

.page-desc {
  margin: 10px 0 0;
  max-width: 620px;
  color: #64748b;
  font-size: 14px;
  line-height: 1.7;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.toolbar-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #475569;
  font-size: 13px;
}

.summary-item strong {
  color: #0f172a;
  font-weight: 650;
}

.error-banner,
.form-error {
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}

.error-banner {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
}

.connector-list {
  overflow: hidden;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
}

.connector-row {
  display: grid;
  grid-template-columns: minmax(180px, 1.25fr) 110px 110px minmax(220px, 1.35fr) 130px 140px 150px;
  gap: 16px;
  align-items: center;
  min-height: 72px;
  padding: 14px 16px;
  border-top: 1px solid #eef2f7;
  color: #334155;
  font-size: 13px;
}

.connector-head {
  min-height: auto;
  padding-block: 10px;
  border-top: 0;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}

.connector-name-cell,
.target-cell,
.usage-cell {
  min-width: 0;
}

.connector-name-cell strong,
.target-cell span,
.usage-cell span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connector-name-cell strong {
  color: #0f172a;
  font-size: 14px;
}

.connector-name-cell small,
.target-cell small,
.usage-cell small {
  display: block;
  margin-top: 5px;
  overflow: hidden;
  color: #94a3b8;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.last-error {
  color: #b91c1c !important;
}

.type-pill,
.status-pill {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.type-pill.postgresql {
  color: #1d4ed8;
  background: #eff6ff;
}

.type-pill.obs {
  color: #047857;
  background: #ecfdf5;
}

.status-pill.untested {
  color: #475569;
  background: #f1f5f9;
}

.status-pill.connected {
  color: #047857;
  background: #ecfdf5;
}

.status-pill.failed {
  color: #b91c1c;
  background: #fef2f2;
}

.time-cell {
  color: #64748b;
  white-space: nowrap;
}

.actions-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.action-link {
  padding: 0;
  border: 0;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  text-decoration: none;
}

.action-link:disabled {
  color: #cbd5e1;
  cursor: not-allowed;
}

.empty-state {
  padding: 54px 16px;
  text-align: center;
  color: #64748b;
}

.connectors-dialog {
  width: min(620px, calc(100vw - 32px));
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 16px;
}

.form-group.wide {
  grid-column: 1 / -1;
}

.form-error {
  margin: 14px 0 0;
  padding: 9px 10px;
  border-radius: 6px;
  font-size: 13px;
}

@media (max-width: 980px) {
  .connector-row {
    grid-template-columns: 1fr 1fr;
    align-items: start;
  }

  .connector-head {
    display: none;
  }

  .connector-cell {
    display: grid;
    grid-template-columns: 82px minmax(0, 1fr);
    gap: 12px;
    align-items: start;
  }

  .connector-cell::before {
    content: attr(data-label);
    color: #64748b;
    font-size: 12px;
    font-weight: 600;
    line-height: 24px;
  }

  .actions-cell {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .connectors-shell {
    padding: 20px 16px 36px;
  }

  .connectors-header,
  .toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .connector-row,
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
