<template>
  <div class="page-container cdf-page">
    <div class="page-header cdf-header">
      <div>
        <h1 class="page-title">CDF Streams</h1>
        <p class="page-subtitle">
          将 Lakebase 表的变更流写入 DBay 管理的 Iceberg 表，供 Lakeon REST Catalog 和后续导出流程读取。
        </p>
      </div>
      <button class="btn btn-primary" :disabled="loadingDatabases" @click="refreshAll">
        {{ loadingDatabases || loadingStreams ? '刷新中...' : '刷新' }}
      </button>
    </div>

    <div v-if="error" class="offline-banner">{{ error }}</div>

    <div class="cdf-control-band">
      <div class="selector-panel">
        <label class="form-label" for="cdf-database">数据库</label>
        <select id="cdf-database" v-model="selectedDatabaseId" class="form-select" :disabled="loadingDatabases">
          <option value="">选择数据库</option>
          <option v-for="db in databases" :key="db.id" :value="db.id">
            {{ db.name }} · {{ db.status }}
          </option>
        </select>
      </div>

      <div class="metric-strip">
        <div class="metric-item">
          <span>Streams</span>
          <strong>{{ streams.length }}</strong>
        </div>
        <div class="metric-item">
          <span>Running</span>
          <strong>{{ runningCount }}</strong>
        </div>
        <div class="metric-item">
          <span>Readable</span>
          <strong>{{ readableCount }}</strong>
        </div>
        <div class="metric-item">
          <span>Exported</span>
          <strong>{{ exportedCount }}</strong>
        </div>
      </div>
    </div>

    <form class="section-card create-stream-panel" @submit.prevent="createStream">
      <div class="section-header">
        <h3>创建 Stream</h3>
        <span class="section-meta">{{ selectedBranchId || '等待选择数据库' }}</span>
      </div>
      <div class="create-grid">
        <div class="form-group">
          <label class="form-label" for="cdf-source-schema">源 Schema</label>
          <input id="cdf-source-schema" v-model="createForm.source_schema" class="form-input" placeholder="public" />
        </div>
        <div class="form-group">
          <label class="form-label" for="cdf-source-table">源 Table</label>
          <input id="cdf-source-table" v-model="createForm.source_table" class="form-input" placeholder="orders" />
        </div>
        <div class="form-group">
          <label class="form-label" for="cdf-target-namespace">目标 Namespace</label>
          <input id="cdf-target-namespace" v-model="createForm.target_namespace" class="form-input" placeholder="public" />
        </div>
        <div class="form-group">
          <label class="form-label" for="cdf-target-table">目标 Table</label>
          <input id="cdf-target-table" v-model="createForm.target_table" class="form-input" placeholder="orders_cdf" />
        </div>
        <div class="form-group">
          <label class="form-label" for="cdf-mode">Mode</label>
          <select id="cdf-mode" v-model="createForm.mode" class="form-select">
            <option value="APPEND_CHANGELOG">APPEND_CHANGELOG</option>
          </select>
        </div>
        <label class="backfill-toggle">
          <input v-model="createForm.initial_backfill" type="checkbox" />
          <span>Initial backfill</span>
        </label>
        <button class="btn btn-primary create-submit" type="submit" :disabled="!canCreate || creating">
          {{ creating ? '创建中...' : '创建' }}
        </button>
      </div>
    </form>

    <div class="section-card stream-table-panel">
      <div v-if="loadingStreams" class="loading-bar" aria-hidden="true"></div>
      <div class="section-header">
        <h3>Streams</h3>
        <span class="section-meta">{{ selectedDatabase ? selectedDatabase.name : '未选择数据库' }}</span>
      </div>
      <div class="table-wrapper cdf-table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>Source</th>
              <th>Target</th>
              <th>Mode</th>
              <th>Status</th>
              <th>Backfill</th>
              <th>LSN</th>
              <th>Snapshot</th>
              <th>Export</th>
              <th>Lag</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loadingStreams && streams.length === 0">
              <td colspan="10" class="empty-cell">加载中...</td>
            </tr>
            <tr v-else-if="!selectedDatabaseId">
              <td colspan="10" class="empty-cell">选择一个数据库后查看 CDF streams。</td>
            </tr>
            <tr v-else-if="!loadingStreams && streams.length === 0">
              <td colspan="10" class="empty-cell">当前数据库还没有 CDF stream。</td>
            </tr>
            <tr v-for="stream in streams" :key="stream.id">
              <td>
                <div class="table-name-cell">
                  <strong>{{ stream.source_schema }}.{{ stream.source_table }}</strong>
                  <small>{{ stream.id }}</small>
                </div>
              </td>
              <td>{{ stream.target_namespace }}.{{ stream.target_table }}</td>
              <td><span class="code-chip">{{ stream.mode }}</span></td>
              <td><span class="status-tag" :class="statusClass(stream.status)">{{ stream.status }}</span></td>
              <td>
                <span class="status-tag" :class="backfillClass(stream.backfill_status)">{{ stream.backfill_status }}</span>
                <small v-if="stream.last_error" class="stream-error">{{ stream.last_error }}</small>
              </td>
              <td class="lsn-cell">
                <span>{{ stream.last_commit_lsn || '-' }}</span>
                <small>{{ stream.backfill_lsn || '-' }}</small>
              </td>
              <td>{{ stream.last_snapshot_id ?? '-' }}</td>
              <td><span class="status-tag" :class="exportClass(stream.export_status)">{{ stream.export_status }}</span></td>
              <td>{{ formatLag(stream.observed_lag_ms) }}</td>
              <td>
                <div class="action-btns cdf-actions">
                  <button
                    v-if="stream.status === 'RUNNING'"
                    class="btn btn-small btn-text"
                    :disabled="actionLoading[stream.id]"
                    @click="pause(stream)"
                  >Pause</button>
                  <button
                    v-else
                    class="btn btn-small btn-text btn-accent-text"
                    :disabled="actionLoading[stream.id]"
                    @click="resume(stream)"
                  >Resume</button>
                  <button
                    class="btn btn-small btn-text"
                    :disabled="actionLoading[stream.id]"
                    @click="materialize(stream)"
                  >Export</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { cdfApi, type CdfMode, type CdfStream } from '@/api/cdf'
import { databaseApi, type Database } from '@/api/database'

const databases = ref<Database[]>([])
const streams = ref<CdfStream[]>([])
const selectedDatabaseId = ref('')
const loadingDatabases = ref(false)
const loadingStreams = ref(false)
const creating = ref(false)
const error = ref('')
const actionLoading = reactive<Record<string, boolean>>({})

const createForm = reactive({
  source_schema: 'public',
  source_table: '',
  target_namespace: 'public',
  target_table: '',
  mode: 'APPEND_CHANGELOG' as CdfMode,
  initial_backfill: true,
})

const selectedDatabase = computed(() => databases.value.find((db) => db.id === selectedDatabaseId.value) || null)
const selectedBranchId = computed(() => {
  const branches = selectedDatabase.value?.branches || []
  return branches.find((branch) => branch.is_default)?.id || branches[0]?.id || ''
})
const runningCount = computed(() => streams.value.filter((stream) => stream.status === 'RUNNING').length)
const readableCount = computed(() => streams.value.filter((stream) => stream.readable).length)
const exportedCount = computed(() => streams.value.filter((stream) => stream.export_status === 'MATERIALIZED').length)
const canCreate = computed(() =>
  Boolean(selectedDatabaseId.value && selectedBranchId.value
    && createForm.source_schema.trim()
    && createForm.source_table.trim()
    && createForm.target_namespace.trim()
    && createForm.target_table.trim())
)

watch(selectedDatabaseId, () => {
  streams.value = []
  if (selectedDatabaseId.value) {
    loadStreams()
  }
})

watch(() => createForm.source_table, (value) => {
  if (!createForm.target_table || createForm.target_table.endsWith('_cdf')) {
    createForm.target_table = value.trim() ? `${value.trim()}_cdf` : ''
  }
})

onMounted(() => {
  loadDatabases()
})

async function refreshAll() {
  await loadDatabases()
  if (selectedDatabaseId.value) {
    await loadStreams()
  }
}

async function loadDatabases() {
  loadingDatabases.value = true
  error.value = ''
  try {
    const response = await databaseApi.list()
    databases.value = response.data
    const firstDatabase = databases.value[0]
    if (!selectedDatabaseId.value && firstDatabase) {
      selectedDatabaseId.value = firstDatabase.id
    }
  } catch (err) {
    error.value = errorMessage(err, '数据库列表加载失败')
  } finally {
    loadingDatabases.value = false
  }
}

async function loadStreams() {
  if (!selectedDatabaseId.value) return
  loadingStreams.value = true
  error.value = ''
  try {
    const response = await cdfApi.listStreams(selectedDatabaseId.value)
    streams.value = response.data
  } catch (err) {
    error.value = errorMessage(err, 'CDF streams 加载失败')
  } finally {
    loadingStreams.value = false
  }
}

async function createStream() {
  if (!canCreate.value) return
  creating.value = true
  error.value = ''
  try {
    await cdfApi.createStream(selectedDatabaseId.value, {
      database_id: selectedDatabaseId.value,
      branch_id: selectedBranchId.value,
      source_schema: createForm.source_schema.trim(),
      source_table: createForm.source_table.trim(),
      target_namespace: createForm.target_namespace.trim(),
      target_table: createForm.target_table.trim(),
      mode: createForm.mode,
      initial_backfill: createForm.initial_backfill,
    })
    createForm.source_table = ''
    createForm.target_table = ''
    await loadStreams()
  } catch (err) {
    error.value = errorMessage(err, 'CDF stream 创建失败')
  } finally {
    creating.value = false
  }
}

async function resume(stream: CdfStream) {
  await runStreamAction(stream, () => cdfApi.resumeStream(selectedDatabaseId.value, stream.id), 'Stream resume 失败')
}

async function pause(stream: CdfStream) {
  await runStreamAction(stream, () => cdfApi.pauseStream(selectedDatabaseId.value, stream.id), 'Stream pause 失败')
}

async function materialize(stream: CdfStream) {
  await runStreamAction(stream, () => cdfApi.materializeExport(selectedDatabaseId.value, stream.id), 'Iceberg export 失败')
}

async function runStreamAction(stream: CdfStream, action: () => Promise<unknown>, fallback: string) {
  actionLoading[stream.id] = true
  error.value = ''
  try {
    await action()
    await loadStreams()
  } catch (err) {
    error.value = errorMessage(err, fallback)
  } finally {
    actionLoading[stream.id] = false
  }
}

function statusClass(status: string) {
  if (status === 'RUNNING') return 'tag-green'
  if (status === 'FAILED') return 'tag-red'
  return 'tag-blue'
}

function backfillClass(status: string) {
  if (status === 'SUCCEEDED') return 'tag-green'
  if (status === 'FAILED') return 'tag-red'
  if (status === 'RUNNING') return 'tag-blue'
  return 'tag-muted'
}

function exportClass(status: string) {
  if (status === 'MATERIALIZED') return 'tag-green'
  if (status === 'FAILED') return 'tag-red'
  if (status === 'MATERIALIZING') return 'tag-blue'
  return 'tag-muted'
}

function formatLag(value?: number | null) {
  if (value === null || value === undefined) return '-'
  if (value < 1000) return `${Math.round(value)} ms`
  return `${(value / 1000).toFixed(1)} s`
}

function errorMessage(err: unknown, fallback: string) {
  const maybe = err as { response?: { data?: { error?: { message?: string } } }; message?: string }
  return maybe.response?.data?.error?.message || maybe.message || fallback
}
</script>

<style scoped>
.cdf-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.cdf-header {
  align-items: flex-start;
}

.cdf-control-band {
  display: grid;
  grid-template-columns: minmax(260px, 360px) 1fr;
  gap: 16px;
  align-items: stretch;
}

.selector-panel,
.metric-strip {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  padding: 16px;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metric-item span {
  color: var(--c-text-muted);
  font-size: 12px;
}

.metric-item strong {
  color: var(--c-text);
  font-size: 24px;
  font-weight: 600;
}

.create-stream-panel,
.stream-table-panel {
  position: relative;
}

.create-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr)) minmax(150px, 180px) minmax(140px, 160px) auto;
  gap: 12px;
  align-items: end;
}

.backfill-toggle {
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-text);
  font-size: 14px;
}

.backfill-toggle input {
  width: 16px;
  height: 16px;
}

.create-submit {
  min-width: 82px;
}

.cdf-table-wrapper {
  overflow-x: auto;
}

.cdf-table-wrapper table {
  min-width: 1160px;
}

.table-name-cell,
.lsn-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.table-name-cell small,
.lsn-cell small {
  color: var(--c-text-muted);
  font-family: var(--font-mono);
  font-size: 11px;
}

.stream-error {
  display: block;
  max-width: 260px;
  margin-top: 4px;
  color: var(--c-danger);
  font-size: 11px;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

.code-chip {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--c-primary);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  padding: 3px 6px;
}

.cdf-actions {
  min-width: 160px;
}

.tag-muted {
  background: var(--c-bg-alt);
  color: var(--c-text-muted);
}

@media (max-width: 1180px) {
  .cdf-control-band {
    grid-template-columns: 1fr;
  }

  .create-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .metric-strip,
  .create-grid {
    grid-template-columns: 1fr;
  }
}
</style>
