<template>
  <div class="page-container">
    <!-- Back link -->
    <div style="margin-bottom: 16px;">
      <router-link to="/datalake/datasets" class="back-link">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
        返回数据集列表
      </router-link>
    </div>

    <!-- Page header -->
    <div class="page-header">
      <h1 class="page-title">新建数据集</h1>
    </div>

    <!-- Form -->
    <div class="form-card">
      <!-- Dataset name -->
      <div class="form-group">
        <label class="form-label">数据集名称 <span class="required">*</span></label>
        <input
          v-model="datasetName"
          class="form-input"
          style="max-width: 480px;"
          placeholder="请输入数据集名称"
          type="text"
        />
      </div>

      <!-- Select database -->
      <div class="form-group">
        <label class="form-label">选择数据库 <span class="required">*</span></label>
        <select
          v-model="selectedDbId"
          class="form-select"
          style="max-width: 480px;"
          @change="onDatabaseChange"
        >
          <option value="">-- 请选择数据库 --</option>
          <option v-for="db in databases" :key="db.id" :value="db.id">
            {{ db.name }}
            <template v-if="db.status !== 'RUNNING'">({{ db.status }})</template>
          </option>
        </select>
        <div v-if="dbLoading" class="hint-text">加载中...</div>
      </div>

      <!-- Mode toggle -->
      <div class="form-group">
        <label class="form-label">数据来源</label>
        <div class="mode-toggle">
          <button
            class="mode-btn"
            :class="{ active: mode === 'TABLE_SELECT' }"
            @click="mode = 'TABLE_SELECT'"
          >选择表</button>
          <button
            class="mode-btn"
            :class="{ active: mode === 'CUSTOM_SQL' }"
            @click="mode = 'CUSTOM_SQL'"
          >自定义 SQL</button>
        </div>
      </div>

      <!-- TABLE_SELECT mode -->
      <div v-if="mode === 'TABLE_SELECT'" class="form-group">
        <label class="form-label">选择表</label>
        <div v-if="!selectedDbId" class="hint-text">请先选择数据库</div>
        <div v-else-if="tableLoading" class="hint-text">加载表列表...</div>
        <div v-else-if="tables.length === 0" class="hint-text">该数据库没有可用的表</div>
        <div v-else class="table-list">
          <label
            v-for="t in tables"
            :key="t.name"
            class="table-item"
            :class="{ selected: selectedTable === t.name }"
          >
            <input
              type="radio"
              :value="t.name"
              v-model="selectedTable"
              style="margin-right: 8px;"
            />
            {{ t.name }}
          </label>
        </div>
      </div>

      <!-- CUSTOM_SQL mode -->
      <div v-if="mode === 'CUSTOM_SQL'" class="form-group">
        <label class="form-label">SQL 查询</label>
        <textarea
          v-model="customSql"
          class="form-textarea"
          placeholder="SELECT * FROM your_table LIMIT 1000"
          rows="6"
        ></textarea>
      </div>

      <!-- Error message -->
      <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

      <!-- Action buttons -->
      <div class="action-row">
        <button
          class="btn btn-default"
          :disabled="!canPreview || previewLoading"
          @click="handlePreview"
        >
          <span v-if="previewLoading">预览中...</span>
          <span v-else>预览数据</span>
        </button>
        <button
          class="btn btn-primary"
          :disabled="!canExport || exportLoading"
          @click="handleExport"
        >
          <span v-if="exportLoading">导出中...</span>
          <span v-else>创建并导出</span>
        </button>
      </div>
    </div>

    <!-- Preview result -->
    <div v-if="previewResult" class="preview-section">
      <div class="preview-header">
        <span class="section-title-text">预览结果</span>
        <span class="preview-meta">
          共 {{ previewResult.total_count.toLocaleString() }} 行，显示前 {{ previewResult.rows.length }} 行
        </span>
      </div>
      <div v-if="previewResult.preview_sql" class="preview-sql">
        <span class="sql-label">执行 SQL：</span>
        <code>{{ previewResult.preview_sql }}</code>
      </div>
      <div class="table-wrapper" style="margin-top: 12px;">
        <table class="data-table">
          <thead>
            <tr>
              <th v-for="col in previewResult.columns" :key="col">{{ col }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in previewResult.rows" :key="i">
              <td v-for="(cell, j) in row" :key="j">
                <span v-if="cell === null" style="color: #bbb;">NULL</span>
                <span v-else>{{ String(cell) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import client from '../../api/client'

const route = useRoute()
const router = useRouter()

interface Database {
  id: string
  name: string
  status: string
}

interface TableInfo {
  name: string
}

interface PreviewResult {
  columns: string[]
  rows: any[][]
  total_count: number
  preview_sql: string
}

// Form state
const datasetName = ref('')
const selectedDbId = ref('')
const mode = ref<'TABLE_SELECT' | 'CUSTOM_SQL'>('TABLE_SELECT')
const selectedTable = ref('')
const customSql = ref('')

// Data
const databases = ref<Database[]>([])
const tables = ref<TableInfo[]>([])

// Loading / error state
const dbLoading = ref(false)
const tableLoading = ref(false)
const previewLoading = ref(false)
const exportLoading = ref(false)
const errorMessage = ref('')
const previewResult = ref<PreviewResult | null>(null)

// Computed guards
const canPreview = computed(() => {
  if (!selectedDbId.value) return false
  if (mode.value === 'TABLE_SELECT') return !!selectedTable.value
  if (mode.value === 'CUSTOM_SQL') return customSql.value.trim().length > 0
  return false
})

const canExport = computed(() => {
  return canPreview.value && !!datasetName.value.trim()
})

async function loadDatabases() {
  dbLoading.value = true
  try {
    const resp = await client.get('/databases')
    databases.value = resp.data?.data ?? resp.data ?? []
  } catch (e: any) {
    errorMessage.value = '加载数据库列表失败: ' + (e.response?.data?.error?.message || e.message)
  } finally {
    dbLoading.value = false
  }
}

async function loadTables(dbId: string) {
  if (!dbId) return
  tableLoading.value = true
  tables.value = []
  selectedTable.value = ''
  try {
    const resp = await client.get(`/databases/${dbId}/schemas/public/tables`)
    tables.value = resp.data?.data ?? resp.data ?? []
  } catch (e: any) {
    errorMessage.value = '加载表列表失败: ' + (e.response?.data?.error?.message || e.message)
  } finally {
    tableLoading.value = false
  }
}

function onDatabaseChange() {
  errorMessage.value = ''
  previewResult.value = null
  selectedTable.value = ''
  if (selectedDbId.value && mode.value === 'TABLE_SELECT') {
    loadTables(selectedDbId.value)
  }
}

async function handlePreview() {
  errorMessage.value = ''
  previewResult.value = null
  previewLoading.value = true

  const body: Record<string, any> = {
    database_id: selectedDbId.value,
    query_mode: mode.value,
  }
  if (mode.value === 'TABLE_SELECT') {
    body.tables = [{ name: selectedTable.value }]
  } else {
    body.sql = customSql.value.trim()
  }

  try {
    const resp = await client.post('/datasets/preview', body)
    previewResult.value = resp.data?.data ?? resp.data
  } catch (e: any) {
    errorMessage.value = '预览失败: ' + (e.response?.data?.error?.message || e.message)
  } finally {
    previewLoading.value = false
  }
}

async function handleExport() {
  errorMessage.value = ''
  exportLoading.value = true

  const body: Record<string, any> = {
    name: datasetName.value.trim(),
    database_id: selectedDbId.value,
    query_mode: mode.value,
  }
  if (mode.value === 'TABLE_SELECT') {
    body.tables = [{ name: selectedTable.value }]
  } else {
    body.sql = customSql.value.trim()
  }

  try {
    const createResp = await client.post('/datasets', body)
    const dataset = createResp.data?.data ?? createResp.data
    const datasetId = dataset.id

    await client.post(`/datasets/${datasetId}/export`)
    router.push(`/datalake/datasets/${datasetId}`)
  } catch (e: any) {
    errorMessage.value = '创建失败: ' + (e.response?.data?.error?.message || e.message)
    exportLoading.value = false
  }
}

onMounted(async () => {
  await loadDatabases()

  // Pre-fill from query params
  const qDbId = route.query.database_id as string | undefined
  const qTable = route.query.table as string | undefined

  if (qDbId) {
    selectedDbId.value = qDbId
    await loadTables(qDbId)
    if (qTable) {
      selectedTable.value = qTable
    }
  }
})
</script>

<style scoped>
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #666;
  text-decoration: none;
  font-size: 14px;
}

.back-link:hover {
  color: #0073e6;
}

.form-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 24px;
  max-width: 800px;
}

.hint-text {
  font-size: 13px;
  color: #999;
  margin-top: 6px;
}

.mode-toggle {
  display: inline-flex;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  overflow: hidden;
}

.mode-btn {
  padding: 0 20px;
  height: 32px;
  background: #fff;
  border: none;
  border-right: 1px solid #c2c6cc;
  font-size: 14px;
  color: #575d6c;
  cursor: pointer;
  transition: all 0.15s;
}

.mode-btn:last-child {
  border-right: none;
}

.mode-btn:hover {
  background: #f5f7fa;
  color: #333;
}

.mode-btn.active {
  background: #e8f3ff;
  color: #0073e6;
  font-weight: 500;
}

.table-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid #e8e8e8;
  border-radius: 2px;
  padding: 8px;
  max-width: 480px;
  background: #fafafa;
}

.table-item {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 2px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
  transition: background 0.12s;
}

.table-item:hover {
  background: #f0f7ff;
}

.table-item.selected {
  background: #e8f3ff;
  color: #0073e6;
}

.form-textarea {
  width: 100%;
  max-width: 640px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  padding: 8px 12px;
  font-size: 14px;
  color: #191919;
  outline: none;
  resize: vertical;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  transition: border-color 0.2s;
  background: #fff;
  line-height: 1.6;
}

.form-textarea:focus {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.form-textarea::placeholder {
  color: #adb0b8;
  font-family: inherit;
}

.error-banner {
  padding: 10px 14px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 4px;
  color: #a8071a;
  font-size: 13px;
  margin-bottom: 16px;
  word-break: break-all;
}

.action-row {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

/* Preview section */
.preview-section {
  margin-top: 24px;
  max-width: 100%;
}

.preview-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 8px;
}

.section-title-text {
  font-size: 15px;
  font-weight: 600;
  color: #191919;
}

.preview-meta {
  font-size: 13px;
  color: #999;
}

.preview-sql {
  font-size: 12px;
  color: #666;
  background: #fafafa;
  border: 1px solid #e8e8e8;
  border-radius: 2px;
  padding: 8px 12px;
  margin-bottom: 4px;
  word-break: break-all;
}

.sql-label {
  color: #999;
  margin-right: 6px;
}

.preview-sql code {
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  color: #333;
}
</style>
