<template>
  <div class="structure-view">
    <div v-if="loading" class="struct-loading">加载中...</div>
    <div v-else-if="!schema || !table" class="struct-empty">选择一个表以查看结构</div>
    <template v-else>
      <div class="struct-header">
        <span class="struct-schema">{{ schema }}</span>.<span class="struct-table-name">{{ table }}</span>
      </div>
      <!-- Columns -->
      <div class="struct-section">
        <h4 class="struct-title">列 ({{ columns.length }})</h4>
        <table class="data-table" v-if="columns.length > 0">
          <thead>
            <tr>
              <th>#</th>
              <th>列名</th>
              <th>类型</th>
              <th>可空</th>
              <th>默认值</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="col in columns" :key="col.name">
              <td class="col-pos">{{ col.ordinal_position }}</td>
              <td class="col-name">{{ col.name }}</td>
              <td class="col-type">{{ col.data_type }}</td>
              <td>
                <span v-if="col.nullable" class="tag-yes">YES</span>
                <span v-else class="tag-no">NO</span>
              </td>
              <td class="col-default">{{ col.default_value || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <!-- Data Preview -->
      <div class="struct-section">
        <h4 class="struct-title">
          数据预览
          <button class="refresh-btn" @click="loadDataPreview" :disabled="dataLoading" title="刷新数据">↻</button>
          <span v-if="dataPreview && dataPreview.total_rows > 100" class="preview-hint">
            (显示前 100 行，共 {{ dataPreview.total_rows }} 行)
          </span>
        </h4>

        <div v-if="dataLoading" class="preview-loading">加载中...</div>
        <div v-else-if="dataError" class="preview-error">{{ dataError }}</div>
        <div v-else-if="!dataPreview || dataPreview.rows.length === 0" class="preview-empty">无数据</div>

        <div v-else class="preview-table-wrapper">
          <table class="data-table preview-table">
            <thead>
              <tr>
                <th v-for="col in dataPreview.columns" :key="col">{{ col }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, idx) in pagedPreviewRows" :key="idx">
                <td v-for="(cell, cellIdx) in row" :key="cellIdx">
                  {{ cell === null ? 'NULL' : cell }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <TableFooter
          v-if="dataPreview && dataPreview.rows.length > 0"
          :total="dataPreview.rows.length"
          v-model:pageSize="previewPageSize"
          v-model:currentPage="previewCurrentPage"
          :pageSizeOptions="[20, 50, 100]"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { databaseApi, type ColumnInfo } from '../api/database'
import TableFooter from './TableFooter.vue'

const props = defineProps<{
  dbId: string
  schema: string
  table: string
}>()

const columns = ref<ColumnInfo[]>([])
const loading = ref(false)

const dataPreview = ref<{ columns: string[]; rows: unknown[][]; total_rows: number } | null>(null)
const dataLoading = ref(false)
const dataError = ref('')
const previewPageSize = ref(20)
const previewCurrentPage = ref(1)

const pagedPreviewRows = computed(() => {
  if (!dataPreview.value) return []
  const start = (previewCurrentPage.value - 1) * previewPageSize.value
  return dataPreview.value.rows.slice(start, start + previewPageSize.value)
})

async function loadDataPreview() {
  if (!props.schema || !props.table) return
  dataLoading.value = true
  dataError.value = ''
  try {
    const res = await databaseApi.tableData(props.dbId, props.schema, props.table, {
      page: 0,
      size: 100
    })
    dataPreview.value = {
      columns: res.data.columns,
      rows: res.data.rows,
      total_rows: res.data.total_rows
    }
  } catch (e: unknown) {
    const err = e as { response?: { data?: { error?: { message?: string }; message?: string } }; message?: string }
    dataError.value = err.response?.data?.error?.message || err.response?.data?.message || err.message || '数据加载失败'
    console.error('Failed to load data preview', e)
  } finally {
    dataLoading.value = false
  }
}

async function loadStructure() {
  if (!props.schema || !props.table) return
  loading.value = true
  try {
    // Load columns first (may trigger compute wakeup), then data preview
    const colRes = await databaseApi.listColumns(props.dbId, props.schema, props.table)
    columns.value = colRes.data
    await loadDataPreview()
  } catch (e) {
    console.error('Failed to load structure', e)
  } finally {
    loading.value = false
  }
}

watch(() => [props.schema, props.table], () => {
  if (props.schema && props.table) loadStructure()
}, { immediate: true })
</script>

<style scoped>
.structure-view {
  padding: 12px;
  overflow-y: auto;
  height: 100%;
}

.struct-loading, .struct-empty {
  padding: 40px 20px;
  text-align: center;
  color: #8a8e99;
  font-size: 14px;
}

.struct-header {
  font-size: 15px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 16px;
  padding-bottom: 10px;
  border-bottom: 1px solid #ebebeb;
}

.struct-schema {
  color: #8a8e99;
  font-weight: 400;
}

.struct-table-name {
  color: #191919;
}

.struct-section {
  margin-bottom: 24px;
}

.struct-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin: 0 0 10px;
}

.refresh-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  color: #575d6c;
  padding: 1px 6px;
  line-height: 1;
  transition: all 0.15s;
}

.refresh-btn:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.refresh-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.struct-empty-inline {
  color: #8a8e99;
  font-size: 13px;
  padding: 8px 0;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th,
.data-table td {
  padding: 6px 10px;
  border-bottom: 1px solid #ebebeb;
  text-align: left;
}

.data-table th {
  background: #f7f8fa;
  font-weight: 600;
  color: #575d6c;
}

.data-table tbody tr:hover {
  background: #f7f8fa;
}

.col-pos {
  color: #8a8e99;
  width: 30px;
}

.col-name {
  font-weight: 500;
  color: #191919;
}

.col-type {
  color: #0073e6;
  font-family: monospace;
  font-size: 12px;
}

.col-default {
  color: #575d6c;
  font-family: monospace;
  font-size: 12px;
}

.tag-yes {
  color: #52c41a;
  font-size: 12px;
  font-weight: 600;
}

.tag-no {
  color: #d4380d;
  font-size: 12px;
  font-weight: 600;
}

.preview-hint {
  color: #8a8e99;
  font-weight: 400;
  font-size: 12px;
  margin-left: 8px;
}

.preview-loading,
.preview-error,
.preview-empty {
  padding: 20px;
  text-align: center;
  color: #8a8e99;
  font-size: 13px;
}

.preview-error {
  color: #d4380d;
}

.preview-table-wrapper {
  overflow-x: auto;
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid #ebebeb;
  border-radius: 2px;
}

.preview-table {
  min-width: 100%;
}

.preview-table thead {
  position: sticky;
  top: 0;
  z-index: 1;
}

.preview-table td {
  font-family: monospace;
  font-size: 12px;
  color: #191919;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
