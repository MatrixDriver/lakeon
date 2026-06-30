<template>
  <div class="page-db-manager">
    <div class="breadcrumb">
      <router-link to="/databases" class="breadcrumb-link">数据库实例</router-link>
      <span class="breadcrumb-sep">/</span>
      <router-link :to="`/databases/${dbId}`" class="breadcrumb-link">{{ dbName || dbId }}</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-item active">管理</span>
    </div>

    <div class="manager-layout">
      <!-- Left: Object Tree -->
      <div class="manager-sidebar">
        <ObjectTree ref="objectTreeRef" :db-id="dbId" @select="handleSelectTable" @schema-loaded="handleSchemaLoaded" />
      </div>

      <!-- Right: SQL Editor + Table Structure -->
      <div class="manager-content">
        <!-- Top: SQL Editor -->
        <div class="content-top" :style="{ height: editorHeight + 'px' }">
          <SqlEditor :db-id="dbId" :schema="schemaMap" @query-result="handleQueryResult" />
        </div>
        <!-- Resize Handle -->
        <div class="resize-handle" @mousedown="startResize">
          <div class="resize-grip"></div>
        </div>
        <!-- Bottom: query result, preview, and schema workspace -->
        <div class="content-bottom">
          <div class="workspace-tabs">
            <button
              class="tab-button"
              :class="{ active: activeWorkspaceTab === 'result' }"
              @click="activeWorkspaceTab = 'result'"
            >查询结果</button>
            <button
              class="tab-button"
              :class="{ active: activeWorkspaceTab === 'preview' }"
              @click="activeWorkspaceTab = 'preview'"
            >表数据预览</button>
            <button
              class="tab-button"
              :class="{ active: activeWorkspaceTab === 'schema' }"
              @click="activeWorkspaceTab = 'schema'"
            >表结构</button>
            <span class="workspace-context">{{ workspaceContext }}</span>
          </div>

          <div class="workspace-body">
            <div v-if="activeWorkspaceTab === 'result'" class="workspace-pane">
              <div v-if="queryResult || queryError" class="query-result-panel">
                <div class="query-status-strip">
                  <span v-if="queryResult" class="result-info">
                    {{ queryResult.is_select ? `${queryResult.row_count} 行` : `影响 ${queryResult.row_count} 行` }}
                    · {{ queryResult.execution_time_ms }}ms
                  </span>
                  <span v-if="queryError" class="result-error-text">{{ queryError }}</span>
                  <button class="toolbar-btn" @click="clearQueryResult" title="关闭">✕</button>
                </div>

                <div v-if="queryResult && queryResult.is_select && queryResult.columns.length > 0" class="result-table-wrapper">
                  <table class="result-table">
                    <thead>
                      <tr>
                        <th v-for="col in queryResult.columns" :key="col">{{ col }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="(row, i) in pagedQueryRows" :key="i">
                        <td v-for="(cell, j) in row" :key="j">{{ formatCell(cell) }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>

                <TableFooter
                  v-if="queryResult && queryResult.is_select && queryResult.rows.length > 0"
                  :total="queryResult.rows.length"
                  v-model:pageSize="queryPageSize"
                  v-model:currentPage="queryCurrentPage"
                  :pageSizeOptions="[20, 50, 100]"
                  style="flex-shrink: 0;"
                />

                <div v-else-if="queryResult && !queryResult.is_select" class="result-message">
                  语句执行成功，影响 {{ queryResult.row_count }} 行
                </div>
              </div>
              <div v-else class="empty-hint">
                <p>执行 SQL 后在这里查看查询结果。</p>
              </div>
            </div>

            <div v-else-if="activeWorkspaceTab === 'preview'" class="workspace-pane">
              <StructureView
                v-if="selectedTable"
                :db-id="dbId"
                :schema="selectedSchema"
                :table="selectedTable"
                mode="preview"
              />
              <div v-else class="empty-hint">
                <p>在左侧选择表查看数据预览。</p>
              </div>
            </div>

            <div v-else class="workspace-pane">
              <StructureView
                v-if="selectedTable"
                :db-id="dbId"
                :schema="selectedSchema"
                :table="selectedTable"
                mode="schema"
              />
              <div v-else class="empty-hint">
                <p>在左侧选择表查看结构。</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi, type QueryResult } from '../../api/database'
import ObjectTree from '../../components/ObjectTree.vue'
import StructureView from '../../components/StructureView.vue'
import SqlEditor from '../../components/SqlEditor.vue'
import TableFooter from '../../components/TableFooter.vue'

const route = useRoute()
const dbId = computed(() => route.params.id as string)
const dbName = ref('')

const selectedSchema = ref('')
const selectedTable = ref('')
const schemaMap = ref<Record<string, string[]>>({})
const editorHeight = ref(220)
const activeWorkspaceTab = ref<'result' | 'preview' | 'schema'>('preview')
const queryResult = ref<QueryResult | null>(null)
const queryError = ref('')
const queryPageSize = ref(20)
const queryCurrentPage = ref(1)

const workspaceContext = computed(() => {
  if (activeWorkspaceTab.value === 'result') {
    return queryResult.value || queryError.value ? '当前 SQL 查询' : '等待查询'
  }
  if (selectedTable.value) return `${selectedSchema.value}.${selectedTable.value}`
  return '未选择表'
})

const pagedQueryRows = computed(() => {
  if (!queryResult.value) return []
  const start = (queryCurrentPage.value - 1) * queryPageSize.value
  return queryResult.value.rows.slice(start, start + queryPageSize.value)
})

function handleSelectTable(schema: string, table: string) {
  selectedSchema.value = schema
  selectedTable.value = table
  if (activeWorkspaceTab.value === 'result' && !queryResult.value && !queryError.value) {
    activeWorkspaceTab.value = 'preview'
  } else if (activeWorkspaceTab.value !== 'schema') {
    activeWorkspaceTab.value = 'preview'
  }
}

function handleSchemaLoaded(data: Record<string, string[]>) {
  schemaMap.value = data
}

function handleQueryResult(payload: { result: QueryResult | null; error: string }) {
  queryResult.value = payload.result
  queryError.value = payload.error
  queryCurrentPage.value = 1
  if (payload.result || payload.error) {
    activeWorkspaceTab.value = 'result'
  }
}

function clearQueryResult() {
  queryResult.value = null
  queryError.value = ''
}

function formatCell(cell: unknown): string {
  if (cell === null || cell === undefined) return 'NULL'
  if (typeof cell === 'object') return JSON.stringify(cell)
  return String(cell)
}

// Resize logic
let resizing = false
let startY = 0
let startHeight = 0

function startResize(e: MouseEvent) {
  resizing = true
  startY = e.clientY
  startHeight = editorHeight.value
  document.addEventListener('mousemove', onResize)
  document.addEventListener('mouseup', stopResize)
  document.body.style.cursor = 'row-resize'
  document.body.style.userSelect = 'none'
}

function onResize(e: MouseEvent) {
  if (!resizing) return
  const delta = e.clientY - startY
  editorHeight.value = Math.max(150, Math.min(startHeight + delta, 420))
}

function stopResize() {
  resizing = false
  document.removeEventListener('mousemove', onResize)
  document.removeEventListener('mouseup', stopResize)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

onMounted(async () => {
  try {
    const res = await databaseApi.get(dbId.value)
    dbName.value = res.data.name
  } catch {
    // ignore
  }
})
</script>

<style scoped>
.page-db-manager {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 48px);
  padding: 4px;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
  font-size: 14px;
  flex-shrink: 0;
}

.breadcrumb-link {
  color: #9a5b25;
  text-decoration: none;
}

.breadcrumb-link:hover {
  text-decoration: underline;
}

.breadcrumb-sep {
  color: #c2c6cc;
}

.breadcrumb-item.active {
  color: #2c3e50;
}

.manager-layout {
  flex: 1;
  display: flex;
  border: 1px solid #dfe1e6;
  border-radius: 4px;
  background: #fff;
  overflow: hidden;
  min-height: 0;
}

.manager-sidebar {
  width: 240px;
  flex-shrink: 0;
  overflow: hidden;
  display: flex;
}

.manager-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.content-top {
  flex-shrink: 0;
  overflow: hidden;
  display: flex;
  min-height: 150px;
}

.content-top > * {
  flex: 1;
}

.resize-handle {
  height: 6px;
  background: #f0f0f0;
  cursor: row-resize;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-top: 1px solid #e8e8e8;
  border-bottom: 1px solid #e8e8e8;
}

.resize-handle:hover {
  background: #e0e4e8;
}

.resize-grip {
  width: 32px;
  height: 2px;
  background: #c2c6cc;
  border-radius: 1px;
}

.content-bottom {
  flex: 1;
  min-height: 240px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.workspace-tabs {
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 12px;
  background: #fffdf9;
  border-bottom: 1px solid #e8e4df;
  flex-shrink: 0;
}

.tab-button {
  border: 0;
  border-radius: 6px;
  padding: 7px 11px;
  background: transparent;
  color: #708096;
  font-weight: 600;
  cursor: pointer;
}

.tab-button:hover,
.tab-button.active {
  background: #fdf5ed;
  color: #9a5b25;
}

.workspace-context {
  margin-left: auto;
  color: #8a8e99;
  font-size: 12px;
  font-family: var(--font-mono);
}

.workspace-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.workspace-pane,
.query-result-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.query-status-strip {
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 12px;
  background: #faf8f5;
  border-bottom: 1px solid #e8e4df;
  flex-shrink: 0;
}

.result-info {
  font-size: 13px;
  color: #358a43;
  font-weight: 600;
}

.result-error-text {
  font-size: 13px;
  color: #e6393d;
}

.result-table-wrapper {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.result-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.result-table th {
  position: sticky;
  top: 0;
  background: #f8f5f1;
  padding: 8px 10px;
  text-align: left;
  font-weight: 600;
  color: #333;
  border-bottom: 1px solid #e8e8e8;
  white-space: nowrap;
}

.result-table td {
  padding: 7px 10px;
  border-bottom: 1px solid #f0f0f0;
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #333;
}

.result-table tbody tr:hover {
  background: #fffaf3;
}

.result-message {
  padding: 16px;
  font-size: 14px;
  color: #358a43;
}

.toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
  color: #333;
}

.empty-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #8a8e99;
  font-size: 14px;
}

@media (max-width: 768px) {
  .page-db-manager {
    height: auto;
    min-height: calc(100vh - 48px);
  }

  .manager-layout {
    flex-direction: column;
    min-height: 0;
    flex: 1;
  }

  .manager-sidebar {
    width: 100%;
    max-height: 200px;
    border-bottom: 1px solid #dfe1e6;
    overflow-y: auto;
  }

  .content-top {
    min-height: 200px !important;
    height: 250px !important;
  }

  .workspace-tabs {
    overflow-x: auto;
  }

  .workspace-context {
    display: none;
  }
}
</style>
