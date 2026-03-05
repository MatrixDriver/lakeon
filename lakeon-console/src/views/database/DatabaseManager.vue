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
        <ObjectTree :db-id="dbId" @select="handleSelectTable" />
      </div>

      <!-- Right: Table Structure -->
      <div class="manager-content">
        <StructureView
          v-if="selectedTable"
          :db-id="dbId"
          :schema="selectedSchema"
          :table="selectedTable"
        />
        <div v-else class="empty-hint">
          <p>请在左侧选择一个表查看结构</p>
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

const route = useRoute()
const dbId = computed(() => route.params.id as string)
const dbName = ref('')

const selectedSchema = ref('')
const selectedTable = ref('')

function handleSelectTable(schema: string, table: string) {
  selectedSchema.value = schema
  selectedTable.value = table
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
  min-width: 0;
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
</style>
