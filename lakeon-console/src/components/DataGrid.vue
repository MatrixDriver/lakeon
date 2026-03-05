<template>
  <div class="data-grid">
    <div v-if="loading" class="grid-loading">加载中...</div>
    <div v-else-if="!data" class="grid-empty">选择一个表以浏览数据</div>
    <template v-else>
      <div class="grid-toolbar">
        <span class="grid-info">共 {{ data.total_rows }} 行</span>
        <button class="btn btn-small btn-default" @click="reload">刷新</button>
      </div>
      <div class="grid-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th class="row-num-col">#</th>
              <th
                v-for="col in data.columns"
                :key="col"
                class="sortable-col"
                @click="handleSort(col)"
              >
                {{ col }}
                <span v-if="sortCol === col" class="sort-indicator">{{ sortDir === 'asc' ? '↑' : '↓' }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, ri) in data.rows" :key="ri">
              <td class="row-num-col">{{ data.page * data.page_size + ri + 1 }}</td>
              <td v-for="(cell, ci) in row" :key="ci" :title="cellTitle(cell)">
                <span v-if="cell === null" class="null-value">NULL</span>
                <span v-else>{{ formatCell(cell) }}</span>
              </td>
            </tr>
            <tr v-if="data.rows.length === 0">
              <td :colspan="data.columns.length + 1" class="grid-empty-row">无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="totalPages > 1" class="pagination">
        <button class="page-btn" :disabled="page === 0" @click="page--; loadData()">上一页</button>
        <span class="page-info">{{ page + 1 }} / {{ totalPages }}</span>
        <button class="page-btn" :disabled="page >= totalPages - 1" @click="page++; loadData()">下一页</button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { databaseApi, type DataPage } from '../api/database'

const props = defineProps<{
  dbId: string
  schema: string
  table: string
}>()

const data = ref<DataPage | null>(null)
const loading = ref(false)
const page = ref(0)
const pageSize = 50
const sortCol = ref('')
const sortDir = ref<'asc' | 'desc'>('asc')

const totalPages = computed(() => {
  if (!data.value) return 0
  return Math.ceil(data.value.total_rows / data.value.page_size)
})

function formatCell(value: unknown): string {
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function cellTitle(value: unknown): string {
  if (value === null) return 'NULL'
  return formatCell(value)
}

function handleSort(col: string) {
  if (sortCol.value === col) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortCol.value = col
    sortDir.value = 'asc'
  }
  page.value = 0
  loadData()
}

async function loadData() {
  loading.value = true
  try {
    const params: { page: number; size: number; sort?: string; dir?: string } = {
      page: page.value,
      size: pageSize,
    }
    if (sortCol.value) {
      params.sort = sortCol.value
      params.dir = sortDir.value
    }
    const res = await databaseApi.tableData(props.dbId, props.schema, props.table, params)
    data.value = res.data
  } catch (e) {
    console.error('Failed to load data', e)
  } finally {
    loading.value = false
  }
}

function reload() {
  loadData()
}

watch(() => [props.schema, props.table], () => {
  if (props.schema && props.table) {
    page.value = 0
    sortCol.value = ''
    sortDir.value = 'asc'
    loadData()
  }
}, { immediate: true })

defineExpose({ reload })
</script>

<style scoped>
.data-grid {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.grid-loading, .grid-empty {
  padding: 40px 20px;
  text-align: center;
  color: #8a8e99;
  font-size: 14px;
}

.grid-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid #ebebeb;
}

.grid-info {
  font-size: 13px;
  color: #575d6c;
}

.grid-table-wrap {
  flex: 1;
  overflow: auto;
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
  white-space: nowrap;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.data-table th {
  background: #f7f8fa;
  font-weight: 600;
  color: #575d6c;
  position: sticky;
  top: 0;
  z-index: 1;
}

.data-table tbody tr:hover {
  background: #f7f8fa;
}

.sortable-col {
  cursor: pointer;
  user-select: none;
}

.sortable-col:hover {
  color: #0073e6;
}

.sort-indicator {
  margin-left: 2px;
  font-size: 11px;
}

.row-num-col {
  width: 40px;
  color: #8a8e99;
  text-align: center;
  font-size: 12px;
}

.null-value {
  color: #c2c6cc;
  font-style: italic;
  font-size: 12px;
}

.grid-empty-row {
  text-align: center;
  color: #8a8e99;
  padding: 20px !important;
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border-top: 1px solid #ebebeb;
}

.page-btn {
  background: #fff;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  padding: 4px 12px;
  font-size: 13px;
  cursor: pointer;
  color: #575d6c;
}

.page-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.page-btn:not(:disabled):hover {
  border-color: #0073e6;
  color: #0073e6;
}

.page-info {
  font-size: 13px;
  color: #575d6c;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 16px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  font-size: 14px;
  cursor: pointer;
  background: #fff;
  color: #191919;
  transition: all 0.2s;
}

.btn-small {
  padding: 3px 10px;
  font-size: 13px;
}

.btn-default:hover {
  border-color: #0073e6;
  color: #0073e6;
}
</style>
