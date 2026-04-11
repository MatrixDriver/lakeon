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

    <!-- Create mode toggle -->
    <div class="create-mode-toggle">
      <button
        class="create-mode-btn"
        :class="{ active: createMode === 'db' }"
        @click="createMode = 'db'"
      >从数据库创建</button>
      <button
        class="create-mode-btn"
        :class="{ active: createMode === 'upload' }"
        @click="createMode = 'upload'"
      >上传文件</button>
    </div>

    <!-- ===== DB mode (existing) ===== -->
    <div v-if="createMode === 'db'" class="form-card">
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

    <!-- ===== Upload mode ===== -->
    <div v-if="createMode === 'upload'" class="form-card">
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

      <!-- Description -->
      <div class="form-group">
        <label class="form-label">描述</label>
        <input
          v-model="description"
          class="form-input"
          style="max-width: 480px;"
          placeholder="可选，简要描述数据集内容"
          type="text"
        />
      </div>

      <!-- File picker -->
      <div class="form-group">
        <label class="form-label">选择文件</label>
        <div
          class="drop-zone"
          :class="{ 'drop-zone-active': dragOver }"
          @dragover.prevent="dragOver = true"
          @dragleave.prevent="dragOver = false"
          @drop.prevent="handleDrop"
        >
          <div class="drop-zone-inner">
            <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="#bbb" stroke-width="1.5">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="17 8 12 3 7 8"/>
              <line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
            <div class="drop-zone-text">拖拽文件或目录到此处</div>
            <div class="drop-zone-actions">
              <button class="btn btn-default btn-sm" @click="fileInputRef?.click()">选择文件</button>
              <button class="btn btn-default btn-sm" @click="dirInputRef?.click()">选择目录</button>
            </div>
          </div>
        </div>
        <input ref="fileInputRef" type="file" multiple style="display: none;" @change="handleFileSelect" />
        <input ref="dirInputRef" type="file" multiple webkitdirectory style="display: none;" @change="handleDirSelect" />
      </div>

      <!-- File list -->
      <div v-if="uploadFiles.length > 0" class="form-group">
        <label class="form-label">已选文件 ({{ uploadFiles.length }})</label>
        <div class="file-list">
          <div v-for="(f, idx) in uploadFiles" :key="idx" class="file-item">
            <span class="file-path">{{ getFilePath(f) }}</span>
            <span class="file-size">{{ formatFileSize(f.size) }}</span>
            <button class="file-remove" @click="removeFile(idx)" :disabled="uploadProgress.uploading">&times;</button>
          </div>
        </div>
      </div>

      <!-- Upload progress -->
      <div v-if="uploadProgress.uploading" class="form-group">
        <div class="progress-bar-wrap">
          <div class="progress-bar" :style="{ width: progressPercent + '%' }"></div>
        </div>
        <div class="hint-text" style="margin-top: 6px;">
          已上传 {{ uploadProgress.done }} / {{ uploadProgress.total }} 个文件
        </div>
      </div>

      <!-- Error message -->
      <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>

      <!-- Action buttons -->
      <div class="action-row">
        <button
          class="btn btn-primary"
          :disabled="!canUpload || uploadProgress.uploading"
          @click="handleUpload"
        >
          <span v-if="uploadProgress.uploading">上传中...</span>
          <span v-else>上传并创建</span>
        </button>
      </div>
    </div>

    <!-- Preview result (DB mode only) -->
    <div v-if="createMode === 'db' && previewResult" class="preview-section">
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
import { getDatasetUploadUrls, finalizeDataset } from '../../api/datalake'

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

// Create mode
const createMode = ref<'db' | 'upload'>('db')

// Form state (DB mode)
const datasetName = ref('')
const selectedDbId = ref('')
const mode = ref<'TABLE_SELECT' | 'CUSTOM_SQL'>('TABLE_SELECT')
const selectedTable = ref('')
const customSql = ref('')

// Form state (Upload mode)
const description = ref('')
const uploadFiles = ref<File[]>([])
const uploadProgress = ref({ done: 0, total: 0, uploading: false })
const dragOver = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const dirInputRef = ref<HTMLInputElement | null>(null)

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

const canUpload = computed(() => {
  return !!datasetName.value.trim() && uploadFiles.value.length > 0
})

const progressPercent = computed(() => {
  if (uploadProgress.value.total === 0) return 0
  return Math.round((uploadProgress.value.done / uploadProgress.value.total) * 100)
})

function getFilePath(file: File): string {
  return (file as any).webkitRelativePath || file.name
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function handleFileSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files) {
    const newFiles = Array.from(input.files)
    uploadFiles.value = [...uploadFiles.value, ...newFiles]
  }
  input.value = ''
}

function handleDirSelect(e: Event) {
  const input = e.target as HTMLInputElement
  if (input.files) {
    const newFiles = Array.from(input.files)
    uploadFiles.value = [...uploadFiles.value, ...newFiles]
  }
  input.value = ''
}

function handleDrop(e: DragEvent) {
  dragOver.value = false
  if (e.dataTransfer?.files) {
    const newFiles = Array.from(e.dataTransfer.files)
    uploadFiles.value = [...uploadFiles.value, ...newFiles]
  }
}

function removeFile(idx: number) {
  uploadFiles.value.splice(idx, 1)
}

async function handleUpload() {
  errorMessage.value = ''

  const files = uploadFiles.value.map(f => ({
    path: getFilePath(f),
    size: f.size,
  }))

  uploadProgress.value = { done: 0, total: files.length, uploading: true }

  try {
    // 1. Get presigned URLs
    const resp = await getDatasetUploadUrls(
      datasetName.value.trim(),
      files,
      description.value.trim() || undefined,
    )
    const data = resp.data?.data ?? resp.data
    const datasetId: string = data.dataset_id
    const uploads: { path: string; obs_key: string; upload_url: string; expires_in: number }[] = data.uploads

    // 2. Upload files concurrently (max 4 parallel)
    const fileMap = new Map<string, File>()
    for (const f of uploadFiles.value) {
      fileMap.set(getFilePath(f), f)
    }

    const queue = [...uploads]
    const concurrency = 4

    async function uploadNext(): Promise<void> {
      while (queue.length > 0) {
        const item = queue.shift()!
        const file = fileMap.get(item.path)
        if (!file) continue

        await fetch(item.upload_url, {
          method: 'PUT',
          body: file,
        })
        uploadProgress.value.done++
      }
    }

    const workers = Array.from({ length: Math.min(concurrency, queue.length) }, () => uploadNext())
    await Promise.all(workers)

    // 3. Finalize
    await finalizeDataset(datasetId)

    // 4. Navigate to detail
    router.push(`/datalake/datasets/${datasetId}`)
  } catch (e: any) {
    errorMessage.value = '上传失败: ' + (e.response?.data?.error?.message || e.message)
    uploadProgress.value.uploading = false
  }
}

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
  color: #9a5b25;
}

/* Create mode toggle */
.create-mode-toggle {
  display: inline-flex;
  border: 1px solid #d9d3cb;
  border-radius: 6px;
  overflow: hidden;
  margin-bottom: 20px;
  background: #fff;
}

.create-mode-btn {
  padding: 0 24px;
  height: 36px;
  background: #fff;
  border: none;
  border-right: 1px solid #d9d3cb;
  font-size: 14px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}

.create-mode-btn:last-child {
  border-right: none;
}

.create-mode-btn:hover {
  background: #f8f5f1;
  color: #333;
}

.create-mode-btn.active {
  background: #f0ebe4;
  color: #9a5b25;
  font-weight: 500;
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
  border-radius: 4px;
  overflow: hidden;
}

.mode-btn {
  padding: 0 20px;
  height: 32px;
  background: #fff;
  border: none;
  border-right: 1px solid #c2c6cc;
  font-size: 14px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}

.mode-btn:last-child {
  border-right: none;
}

.mode-btn:hover {
  background: #f8f5f1;
  color: #333;
}

.mode-btn.active {
  background: #e8f3ff;
  color: #9a5b25;
  font-weight: 500;
}

.table-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 8px;
  max-width: 480px;
  background: #fafafa;
}

.table-item {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 4px;
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
  color: #9a5b25;
}

.form-textarea {
  width: 100%;
  max-width: 640px;
  border: 1px solid #c2c6cc;
  border-radius: 4px;
  padding: 8px 12px;
  font-size: 14px;
  color: #2c3e50;
  outline: none;
  resize: vertical;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  transition: border-color 0.2s;
  background: #fff;
  line-height: 1.6;
}

.form-textarea:focus {
  border-color: #c67d3a;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.form-textarea::placeholder {
  color: #adb0b8;
  font-family: inherit;
}

.error-banner {
  padding: 10px 14px;
  background: color-mix(in oklch, var(--cs-severe) 8%, #fff);
  border: 1px solid #ffccc7;
  border-radius: 4px;
  color: #c6333a;
  font-size: 13px;
  margin-bottom: 16px;
  word-break: break-all;
}

.action-row {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}

/* Drop zone */
.drop-zone {
  border: 2px dashed #d9d3cb;
  border-radius: 8px;
  padding: 32px 24px;
  text-align: center;
  transition: all 0.2s;
  background: #fdfcfb;
  max-width: 480px;
  cursor: default;
}

.drop-zone:hover,
.drop-zone-active {
  border-color: #c67d3a;
  background: #faf6f1;
}

.drop-zone-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.drop-zone-text {
  font-size: 14px;
  color: #999;
}

.drop-zone-actions {
  display: flex;
  gap: 10px;
}

.btn-sm {
  padding: 0 14px !important;
  height: 30px !important;
  font-size: 13px !important;
}

/* File list */
.file-list {
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  max-height: 280px;
  overflow-y: auto;
  max-width: 600px;
  background: #fafafa;
}

.file-item {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 13px;
}

.file-item:last-child {
  border-bottom: none;
}

.file-path {
  flex: 1;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: monospace;
  font-size: 12px;
}

.file-size {
  color: #999;
  font-size: 12px;
  margin-left: 12px;
  flex-shrink: 0;
}

.file-remove {
  background: none;
  border: none;
  color: #ccc;
  font-size: 18px;
  cursor: pointer;
  margin-left: 8px;
  padding: 0 4px;
  line-height: 1;
  transition: color 0.15s;
}

.file-remove:hover {
  color: #c6333a;
}

/* Progress bar */
.progress-bar-wrap {
  background: #f0ebe4;
  border-radius: 4px;
  height: 8px;
  overflow: hidden;
  max-width: 480px;
}

.progress-bar {
  height: 100%;
  background: #c67d3a;
  border-radius: 4px;
  transition: width 0.3s ease;
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
  color: #2c3e50;
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
  border-radius: 4px;
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
