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
      <div class="db-card-grid">
        <div
          v-for="db in databases"
          :key="db.id"
          class="db-card"
          :class="{ 'db-card-disabled': db.status !== 'RUNNING' && db.status !== 'SUSPENDED' }"
          @click="openEditor(db)"
        >
          <div class="db-card-header">
            <span class="status-dot" :class="statusClass(db.status)"></span>
            <span class="db-card-name">{{ db.name }}</span>
          </div>
          <div class="db-card-meta">
            <span>{{ statusText(db.status) }}</span>
            <span>{{ db.compute_size }}</span>
            <span>{{ db.storage_used_gb.toFixed(1) }} / {{ db.storage_limit_gb }} GB</span>
          </div>
          <div class="db-card-action">
            <span class="open-link">打开编辑器 &rarr;</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="loading" class="loading-state"><p>加载中...</p></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'

const route = useRoute()
const router = useRouter()
const databases = ref<Database[]>([])
const loading = ref(true)

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    default: return 'dot-red'
  }
}

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
  color: #575d6c;
  margin-bottom: 16px;
}

.db-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.db-card {
  border: 1px solid #ebebeb;
  border-radius: 8px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.15s;
}

.db-card:hover {
  border-color: #0073e6;
  box-shadow: 0 2px 8px rgba(0, 115, 230, 0.08);
}

.db-card-disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.db-card-disabled:hover {
  border-color: #ebebeb;
  box-shadow: none;
}

.db-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.db-card-name {
  font-size: 16px;
  font-weight: 600;
  color: #191919;
}

.db-card-meta {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #8a8e99;
  margin-bottom: 12px;
}

.db-card-action {
  border-top: 1px solid #f5f5f5;
  padding-top: 12px;
}

.open-link {
  font-size: 13px;
  color: #0073e6;
}

.empty-hero {
  text-align: center;
  padding: 60px 0;
  color: #575d6c;
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
