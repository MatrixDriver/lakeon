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
        <ObjectTree ref="objectTreeRef" :db-id="dbId" @select="handleSelectTable" />
      </div>

      <!-- Right: Content Area -->
      <div class="manager-content">
        <!-- Tab Bar -->
        <div class="tab-header">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="tab-btn"
            :class="{ active: activeTab === tab.key }"
            @click="activeTab = tab.key"
          >{{ tab.label }}</button>
          <div class="tab-actions">
            <button
              v-if="selectedSchema"
              class="btn btn-primary btn-small"
              @click="showCreateTable = true"
            >新建表</button>
          </div>
        </div>

        <!-- Tab Content -->
        <div class="tab-body">
          <DataGrid
            v-if="activeTab === 'data'"
            ref="dataGridRef"
            :db-id="dbId"
            :schema="selectedSchema"
            :table="selectedTable"
          />
          <StructureView
            v-if="activeTab === 'structure'"
            :db-id="dbId"
            :schema="selectedSchema"
            :table="selectedTable"
          />
          <SqlEditor
            v-if="activeTab === 'sql'"
            :db-id="dbId"
            @executed="handleSqlExecuted"
          />
        </div>
      </div>
    </div>

    <!-- Create Table Dialog -->
    <CreateTableDialog
      v-if="showCreateTable"
      :db-id="dbId"
      :schema="selectedSchema || 'public'"
      @close="showCreateTable = false"
      @created="handleTableCreated"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi } from '../../api/database'
import ObjectTree from '../../components/ObjectTree.vue'
import DataGrid from '../../components/DataGrid.vue'
import StructureView from '../../components/StructureView.vue'
import SqlEditor from '../../components/SqlEditor.vue'
import CreateTableDialog from '../../components/CreateTableDialog.vue'

const route = useRoute()
const dbId = computed(() => route.params.id as string)
const dbName = ref('')

const activeTab = ref('data')
const tabs = [
  { key: 'data', label: '数据' },
  { key: 'structure', label: '结构' },
  { key: 'sql', label: 'SQL' },
]

const selectedSchema = ref('')
const selectedTable = ref('')
const showCreateTable = ref(false)

const objectTreeRef = ref<InstanceType<typeof ObjectTree>>()

function handleSelectTable(schema: string, table: string) {
  selectedSchema.value = schema
  selectedTable.value = table
  if (activeTab.value === 'sql') {
    activeTab.value = 'data'
  }
}

function handleSqlExecuted() {
  objectTreeRef.value?.refresh()
}

function handleTableCreated() {
  showCreateTable.value = false
  objectTreeRef.value?.refresh()
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
  color: #0073e6;
  text-decoration: none;
}

.breadcrumb-link:hover {
  text-decoration: underline;
}

.breadcrumb-sep {
  color: #c2c6cc;
}

.breadcrumb-item.active {
  color: #191919;
}

.manager-layout {
  flex: 1;
  display: flex;
  border: 1px solid #dfe1e6;
  border-radius: 2px;
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
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tab-header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #ebebeb;
  padding: 0 16px;
  flex-shrink: 0;
}

.tab-btn {
  background: none;
  border: none;
  padding: 12px 16px;
  font-size: 14px;
  color: #575d6c;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: #0073e6;
}

.tab-btn.active {
  color: #191919;
  border-bottom-color: #0073e6;
  font-weight: 600;
}

.tab-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
  padding: 4px 0;
}

.tab-body {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 16px;
  border: 1px solid transparent;
  border-radius: 2px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  text-decoration: none;
}

.btn-primary {
  background: #0073e6;
  color: #fff;
  border-color: #0073e6;
}

.btn-primary:hover {
  background: #005bb5;
}

.btn-small {
  padding: 3px 10px;
  font-size: 13px;
}
</style>
