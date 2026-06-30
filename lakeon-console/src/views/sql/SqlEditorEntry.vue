<template>
  <div class="page-sql-entry">
    <div class="page-header">
      <h1 class="page-title">SQL 编辑器</h1>
    </div>

    <!-- If no databases, show prompt -->
    <div v-if="!loading && databases.length === 0" class="empty-hero">
      <p>暂无数据库，请先创建一个数据库后再使用 SQL 编辑器。</p>
      <router-link to="/databases" class="btn btn-primary">前往创建</router-link>
    </div>

    <!-- Database selector -->
    <div v-else-if="!loading" class="select-section">
      <div class="select-prompt">选择数据库后进入编辑器</div>
      <div class="card-grid db-selector-grid">
        <ResourceCard
          v-for="db in databases"
          :key="db.id"
          :name="db.name"
          :status="db.status"
          :status-label="statusText(db.status)"
          :meta="[db.compute_size, `${db.storage_used_gb.toFixed(1)} / ${db.storage_limit_gb} GB`]"
          class="db-selector-card"
          :class="{ 'db-card-disabled': db.status !== 'RUNNING' && db.status !== 'SUSPENDED' }"
          @click="openEditor(db)"
        >
          <template #actions>
            <span class="open-link">打开编辑器 &rarr;</span>
          </template>
        </ResourceCard>
      </div>
    </div>

    <div v-if="loading" class="loading-state"><p>加载中...</p></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import ResourceCard from '../../components/ResourceCard.vue'

const route = useRoute()
const router = useRouter()
const databases = ref<Database[]>([])
const loading = ref(true)

function statusText(status: string): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'SUSPENDED': return '已挂起'
    case 'CREATING': return '创建中'
    default: return '异常'
  }
}

function openEditor(db: Database) {
  if (db.status !== 'RUNNING' && db.status !== 'SUSPENDED') return
  router.push(`/databases/${db.id}/manager`)
}

onMounted(async () => {
  try {
    const res = await databaseApi.list()
    databases.value = res.data
    // Auto-navigate if ?db=xxx is provided
    const dbParam = route.query.db as string
    if (dbParam) {
      const match = databases.value.find(d => d.id === dbParam)
      if (match) { openEditor(match); return }
    }
  } catch (e) {
    console.error('Failed to load databases', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.select-prompt {
  font-size: 15px;
  color: #64748b;
  margin-bottom: 16px;
}

.db-selector-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-md);
  margin-top: var(--space-md);
  padding: 0;
}

.db-card-disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.db-card-disabled:hover {
  box-shadow: none;
}

.open-link {
  font-size: 12px;
  font-weight: 500;
  color: #9a5b25;
  white-space: nowrap;
}

.empty-hero {
  text-align: center;
  padding: 60px 0;
  color: #64748b;
}

.empty-hero p {
  margin-bottom: 16px;
}

.loading-state {
  text-align: center;
  padding: 60px 0;
  color: #8a8e99;
}
</style>
