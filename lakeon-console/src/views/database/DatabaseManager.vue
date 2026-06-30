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
          <SqlEditor :db-id="dbId" :schema="schemaMap" @result-state-change="handleSqlResultStateChange" />
        </div>
        <!-- Resize Handle -->
        <div class="resize-handle" @mousedown="startResize">
          <div class="resize-grip"></div>
        </div>
        <!-- Bottom: Table Structure -->
        <div class="content-bottom">
          <StructureView
            v-if="selectedTable"
            :db-id="dbId"
            :schema="selectedSchema"
            :table="selectedTable"
          />
          <div v-else class="empty-hint">
            <p>在左侧选择表查看结构和数据，在上方编写 SQL 查询</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi } from '../../api/database'
import ObjectTree from '../../components/ObjectTree.vue'
import StructureView from '../../components/StructureView.vue'
import SqlEditor from '../../components/SqlEditor.vue'

const route = useRoute()
const dbId = computed(() => route.params.id as string)
const dbName = ref('')

const selectedSchema = ref('')
const selectedTable = ref('')
const schemaMap = ref<Record<string, string[]>>({})
const editorHeight = ref(280)
const defaultEditorHeight = 280
const resultEditorHeight = 560

function handleSelectTable(schema: string, table: string) {
  selectedSchema.value = schema
  selectedTable.value = table
}

function handleSchemaLoaded(data: Record<string, string[]>) {
  schemaMap.value = data
}

function handleSqlResultStateChange(hasResult: boolean) {
  if (hasResult) {
    editorHeight.value = Math.min(
      Math.max(editorHeight.value, resultEditorHeight),
      Math.max(resultEditorHeight, window.innerHeight - 180)
    )
  } else if (editorHeight.value > resultEditorHeight) {
    editorHeight.value = resultEditorHeight
  } else if (editorHeight.value === resultEditorHeight) {
    editorHeight.value = defaultEditorHeight
  }
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
  editorHeight.value = Math.max(120, Math.min(startHeight + delta, window.innerHeight - 200))
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
  min-height: 120px;
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
  min-height: 100px;
  overflow: auto;
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
}
</style>
