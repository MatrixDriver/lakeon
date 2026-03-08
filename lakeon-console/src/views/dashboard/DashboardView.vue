<template>
  <div class="page-dashboard">
    <div class="page-header">
      <h1 class="page-title">总览</h1>
    </div>

    <div v-if="apiOffline" class="offline-banner">
      DBay API 当前不可用，服务可能正在维护中。Landing page 仍可正常访问。
    </div>

    <!-- Status Bar (Huawei style horizontal stats) -->
    <div class="status-bar">
      <div class="status-bar-item">
        <span class="status-bar-label">数据库总数</span>
        <span class="status-bar-count">{{ stats.total }}</span>
      </div>
      <div class="status-bar-item">
        <span class="status-dot dot-green"></span>
        <span class="status-bar-label">运行中</span>
        <span class="status-bar-count">{{ stats.running }}</span>
      </div>
      <div class="status-bar-item">
        <span class="status-dot dot-gray"></span>
        <span class="status-bar-label">已挂起</span>
        <span class="status-bar-count">{{ stats.suspended }}</span>
      </div>
      <div class="status-bar-item">
        <span class="status-dot dot-red"></span>
        <span class="status-bar-label">异常</span>
        <span class="status-bar-count" :class="{ 'has-error': stats.error > 0 }">{{ stats.error }}</span>
      </div>
    </div>

    <!-- Recent Operations -->
    <div class="section-card">
      <div class="section-header">
        <h3>最近操作</h3>
      </div>
      <TableToolbar v-model="opsSearch" placeholder="搜索数据库名称或操作类型" :loading="loading" @refresh="fetchData" />
      <div class="table-wrapper">
        <table class="data-table" v-if="filteredOps.length > 0">
          <thead>
            <tr>
              <th>数据库</th>
              <th>操作类型</th>
              <th>状态</th>
              <th>耗时</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="op in pagedOps" :key="op.id">
              <td>{{ op.databaseName }}</td>
              <td>{{ op.operationType }}</td>
              <td>
                <span class="status-tag" :class="op.status === 'SUCCESS' ? 'tag-green' : 'tag-red'">
                  {{ op.status }}
                </span>
              </td>
              <td>{{ formatDuration(op.durationMs) }}</td>
              <td>{{ formatDate(op.startedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty-state">
          <p v-if="loading">加载中...</p>
          <p v-else>暂无操作记录</p>
        </div>
      </div>
      <TableFooter
        v-if="filteredOps.length > 0"
        :total="filteredOps.length"
        v-model:pageSize="opsPageSize"
        v-model:currentPage="opsCurrentPage"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { databaseApi } from '../../api/database'
import { operationApi, type OperationLog } from '../../api/operation'
import { formatDuration, formatDate } from '../../utils/format'
import TableToolbar from '../../components/TableToolbar.vue'
import TableFooter from '../../components/TableFooter.vue'

const stats = reactive({ total: 0, running: 0, suspended: 0, error: 0 })
const recentOps = ref<OperationLog[]>([])
const loading = ref(true)
const apiOffline = ref(false)
const opsSearch = ref('')
const opsPageSize = ref(10)
const opsCurrentPage = ref(1)

const filteredOps = computed(() => {
  const q = opsSearch.value.toLowerCase()
  if (!q) return recentOps.value
  return recentOps.value.filter(op =>
    op.databaseName.toLowerCase().includes(q) ||
    op.operationType.toLowerCase().includes(q)
  )
})

const pagedOps = computed(() => {
  const start = (opsCurrentPage.value - 1) * opsPageSize.value
  return filteredOps.value.slice(start, start + opsPageSize.value)
})

watch([opsSearch, opsPageSize], () => { opsCurrentPage.value = 1 })

async function fetchData() {
  loading.value = true
  try {
    const [dbRes, opsRes] = await Promise.all([
      databaseApi.list(),
      operationApi.getRecent(),
    ])

    const databases = dbRes.data
    stats.total = databases.length
    stats.running = databases.filter(d => d.status === 'RUNNING').length
    stats.suspended = databases.filter(d => d.status === 'SUSPENDED').length
    stats.error = databases.filter(d => !['RUNNING', 'SUSPENDED', 'CREATING'].includes(d.status)).length

    recentOps.value = opsRes.data
  } catch (e: any) {
    if (e.isApiOffline) {
      apiOffline.value = true
    }
    console.error('Failed to load dashboard data', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchData())
</script>

<style scoped>
.offline-banner {
  background: #fff3e0;
  border: 1px solid #ffb74d;
  color: #e65100;
  padding: 12px 16px;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 14px;
}
</style>
