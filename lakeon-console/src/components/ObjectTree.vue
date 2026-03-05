<template>
  <div class="object-tree">
    <div class="tree-header">
      <span class="tree-title">对象浏览</span>
      <button class="tree-refresh" @click="loadSchemas" title="刷新">↻</button>
    </div>
    <div v-if="loading" class="tree-loading">加载中...</div>
    <div v-else class="tree-body">
      <div v-for="schema in schemas" :key="schema.name" class="tree-node">
        <div class="tree-item schema-item" @click="toggleSchema(schema.name)">
          <span class="tree-arrow" :class="{ expanded: expandedSchemas.has(schema.name) }">▶</span>
          <span class="tree-icon schema-icon">S</span>
          <span class="tree-label">{{ schema.name }}</span>
        </div>
        <div v-if="expandedSchemas.has(schema.name)" class="tree-children">
          <!-- Tables group -->
          <div class="tree-node">
            <div class="tree-item group-item" @click="toggleGroup(schema.name, 'tables')">
              <span class="tree-arrow" :class="{ expanded: isGroupExpanded(schema.name, 'tables') }">▶</span>
              <span class="tree-icon group-icon">T</span>
              <span class="tree-label">表</span>
              <span class="tree-count">{{ getTablesCount(schema.name, 'BASE TABLE') }}</span>
            </div>
            <div v-if="isGroupExpanded(schema.name, 'tables')" class="tree-children">
              <div
                v-for="t in getTablesByType(schema.name, 'BASE TABLE')"
                :key="t.name"
                class="tree-item table-item"
                :class="{ selected: selectedSchema === schema.name && selectedTable === t.name }"
                @click="selectTable(schema.name, t.name)"
              >
                <span class="tree-icon table-icon">⊞</span>
                <span class="tree-label">{{ t.name }}</span>
                <span class="tree-row-count" v-if="t.row_count_estimate > 0">~{{ formatCount(t.row_count_estimate) }}</span>
              </div>
              <div v-if="getTablesCount(schema.name, 'BASE TABLE') === 0" class="tree-empty">无表</div>
            </div>
          </div>
          <!-- Views group -->
          <div class="tree-node">
            <div class="tree-item group-item" @click="toggleGroup(schema.name, 'views')">
              <span class="tree-arrow" :class="{ expanded: isGroupExpanded(schema.name, 'views') }">▶</span>
              <span class="tree-icon group-icon">V</span>
              <span class="tree-label">视图</span>
              <span class="tree-count">{{ getTablesCount(schema.name, 'VIEW') }}</span>
            </div>
            <div v-if="isGroupExpanded(schema.name, 'views')" class="tree-children">
              <div
                v-for="v in getTablesByType(schema.name, 'VIEW')"
                :key="v.name"
                class="tree-item table-item"
                :class="{ selected: selectedSchema === schema.name && selectedTable === v.name }"
                @click="selectTable(schema.name, v.name)"
              >
                <span class="tree-icon view-icon">⊟</span>
                <span class="tree-label">{{ v.name }}</span>
              </div>
              <div v-if="getTablesCount(schema.name, 'VIEW') === 0" class="tree-empty">无视图</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { databaseApi, type SchemaInfo, type TableInfo } from '../api/database'

const props = defineProps<{ dbId: string }>()
const emit = defineEmits<{
  select: [schema: string, table: string]
}>()

const schemas = ref<SchemaInfo[]>([])
const loading = ref(false)
const expandedSchemas = ref<Set<string>>(new Set())
const expandedGroups = ref<Set<string>>(new Set())
const tablesMap = ref<Record<string, TableInfo[]>>({})
const selectedSchema = ref('')
const selectedTable = ref('')

function formatCount(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return String(n)
}

async function loadSchemas() {
  loading.value = true
  try {
    const res = await databaseApi.listSchemas(props.dbId)
    schemas.value = res.data
    // Auto-expand public schema
    if (res.data.some(s => s.name === 'public')) {
      if (!expandedSchemas.value.has('public')) {
        await toggleSchema('public')
      }
    }
  } catch (e) {
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
  try {
    const res = await databaseApi.listTables(props.dbId, schema)
    tablesMap.value[schema] = res.data
  } catch (e) {
    console.error('Failed to load tables for schema', schema, e)
  }
}

function toggleGroup(schema: string, group: string) {
  const key = schema + '.' + group
  if (expandedGroups.value.has(key)) {
    expandedGroups.value.delete(key)
  } else {
    expandedGroups.value.add(key)
  }
}

function isGroupExpanded(schema: string, group: string): boolean {
  return expandedGroups.value.has(schema + '.' + group)
}

function getTablesByType(schema: string, type: string): TableInfo[] {
  return (tablesMap.value[schema] || []).filter(t => t.type === type)
}

function getTablesCount(schema: string, type: string): number {
  return getTablesByType(schema, type).length
}

function selectTable(schema: string, table: string) {
  selectedSchema.value = schema
  selectedTable.value = table
  emit('select', schema, table)
}

async function refresh() {
  // Reload tables for all expanded schemas
  for (const schema of expandedSchemas.value) {
    await loadTables(schema)
  }
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

.tree-loading {
  padding: 20px;
  text-align: center;
  color: #8a8e99;
}

.tree-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
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
  width: 14px;
  font-size: 8px;
  color: #8a8e99;
  transition: transform 0.15s;
  text-align: center;
  flex-shrink: 0;
}

.tree-arrow.expanded {
  transform: rotate(90deg);
}

.tree-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 2px;
  font-size: 10px;
  font-weight: 700;
  flex-shrink: 0;
}

.schema-icon { background: #e6f4ff; color: #0073e6; }
.group-icon { background: #f2f3f5; color: #575d6c; }
.table-icon { color: #0073e6; font-size: 14px; }
.view-icon { color: #52c41a; font-size: 14px; }

.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.tree-count {
  margin-left: auto;
  font-size: 11px;
  color: #8a8e99;
  padding: 0 4px;
}

.tree-row-count {
  margin-left: auto;
  font-size: 11px;
  color: #8a8e99;
}

.tree-children {
  padding-left: 16px;
}

.tree-empty {
  padding: 4px 8px 4px 36px;
  color: #8a8e99;
  font-size: 12px;
}

.table-item {
  padding-left: 12px;
}
</style>
