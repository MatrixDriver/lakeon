<template>
  <div class="object-tree">
    <div class="tree-header">
      <span class="tree-title">表</span>
      <button class="tree-refresh" @click="loadSchemas" title="刷新">↻</button>
    </div>
    <div v-if="loading" class="tree-loading">加载中...</div>
    <div v-else-if="error" class="tree-error">{{ error }}</div>
    <div v-else class="tree-body">
      <div v-for="schema in schemas" :key="schema.name" class="tree-node">
        <div class="tree-item schema-item" @click="toggleSchema(schema.name)">
          <span class="tree-arrow" :class="{ expanded: expandedSchemas.has(schema.name) }">▶</span>
          <span class="tree-label schema-label">{{ schema.name }}</span>
          <span class="tree-count">{{ getTableCount(schema.name) }}</span>
        </div>
        <div v-if="expandedSchemas.has(schema.name)" class="tree-children">
          <div v-if="loadingSchema === schema.name" class="tree-loading-inline">加载中...</div>
          <template v-else>
            <div
              v-for="t in getTables(schema.name)"
              :key="t.name"
              class="tree-item table-item"
              :class="{ selected: selectedSchema === schema.name && selectedTable === t.name }"
              @click="selectTable(schema.name, t.name)"
            >
              <span class="table-type-icon" :class="t.type === 'VIEW' ? 'type-view' : 'type-table'">{{ t.type === 'VIEW' ? 'V' : 'T' }}</span>
              <span class="tree-label">{{ t.name }}</span>
            </div>
            <div v-if="getTables(schema.name).length === 0" class="tree-empty">无表</div>
          </template>
        </div>
      </div>
      <div v-if="schemas.length === 0 && !loading" class="tree-empty" style="padding: 20px 12px;">无 Schema</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { databaseApi, type SchemaInfo, type TableInfo } from '../api/database'

const props = defineProps<{ dbId: string }>()
const emit = defineEmits<{
  select: [schema: string, table: string]
  'schema-loaded': [data: Record<string, string[]>]
}>()

const schemas = ref<SchemaInfo[]>([])
const loading = ref(false)
const error = ref('')
const loadingSchema = ref('')
const expandedSchemas = ref<Set<string>>(new Set())
const tablesMap = ref<Record<string, TableInfo[]>>({})
const selectedSchema = ref('')
const selectedTable = ref('')

async function loadSchemas() {
  loading.value = true
  error.value = ''
  try {
    const res = await databaseApi.listSchemas(props.dbId)
    schemas.value = res.data
    // Auto-expand public schema and load its tables
    if (res.data.some(s => s.name === 'public')) {
      expandedSchemas.value.add('public')
      await loadTables('public')
    } else if (res.data.length > 0) {
      // If no public schema, expand the first one
      const first = res.data[0]!.name
      expandedSchemas.value.add(first)
      await loadTables(first)
    }
  } catch (e: unknown) {
    const err = e as { response?: { data?: { error?: { message?: string }; message?: string } }; message?: string }
    error.value = err.response?.data?.error?.message || err.response?.data?.message || err.message || '加载失败'
    console.error('Failed to load schemas', e)
  } finally {
    loading.value = false
  }
}

async function toggleSchema(name: string) {
  if (expandedSchemas.value.has(name)) {
    expandedSchemas.value.delete(name)
  } else {
    expandedSchemas.value.add(name)
    if (!tablesMap.value[name]) {
      await loadTables(name)
    }
  }
}

async function loadTables(schema: string) {
  loadingSchema.value = schema
  try {
    const res = await databaseApi.listTables(props.dbId, schema)
    tablesMap.value[schema] = res.data
    emitSchemaMap()
  } catch (e) {
    console.error('Failed to load tables for schema', schema, e)
  } finally {
    loadingSchema.value = ''
  }
}

function emitSchemaMap() {
  const map: Record<string, string[]> = {}
  for (const [schema, tables] of Object.entries(tablesMap.value)) {
    map[schema] = tables.map(t => t.name)
  }
  emit('schema-loaded', map)
}

function getTables(schema: string): TableInfo[] {
  return tablesMap.value[schema] || []
}

function getTableCount(schema: string): number {
  return getTables(schema).length
}

function selectTable(schema: string, table: string) {
  selectedSchema.value = schema
  selectedTable.value = table
  emit('select', schema, table)
}

async function refresh() {
  tablesMap.value = {}
  await loadSchemas()
}

defineExpose({ refresh, loadSchemas })

onMounted(loadSchemas)
</script>

<style scoped>
.object-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
  border-right: 1px solid #dfe1e6;
  font-size: 13px;
  user-select: none;
  width: 100%;
}

.tree-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 12px 8px;
  border-bottom: 1px solid #ebebeb;
}

.tree-title {
  font-weight: 600;
  color: #191919;
  font-size: 13px;
}

.tree-refresh {
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  color: #575d6c;
  padding: 2px 6px;
  border-radius: 2px;
}

.tree-refresh:hover {
  background: #f2f3f5;
  color: #0073e6;
}

.tree-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}

.tree-loading, .tree-error {
  padding: 20px 12px;
  text-align: center;
  color: #8a8e99;
  font-size: 13px;
}

.tree-error {
  color: #d4380d;
}

.tree-loading-inline {
  padding: 4px 12px 4px 32px;
  color: #8a8e99;
  font-size: 12px;
}

.tree-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  cursor: pointer;
  white-space: nowrap;
}

.tree-item:hover {
  background: #f2f3f5;
}

.tree-item.selected {
  background: #e6f4ff;
  color: #0073e6;
}

.tree-arrow {
  display: inline-block;
  width: 12px;
  font-size: 8px;
  color: #8a8e99;
  transition: transform 0.15s;
  text-align: center;
  flex-shrink: 0;
}

.tree-arrow.expanded {
  transform: rotate(90deg);
}

.schema-label {
  font-weight: 500;
}

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.tree-count {
  margin-left: auto;
  font-size: 11px;
  color: #8a8e99;
}

.tree-children {
  padding-left: 12px;
}

.tree-empty {
  padding: 4px 12px 4px 32px;
  color: #8a8e99;
  font-size: 12px;
}

.table-item {
  padding-left: 28px;
}

.table-type-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 2px;
  font-size: 10px;
  font-weight: 700;
  flex-shrink: 0;
}

.type-table { background: #e6f4ff; color: #0073e6; }
.type-view { background: #f6ffed; color: #52c41a; }
</style>
