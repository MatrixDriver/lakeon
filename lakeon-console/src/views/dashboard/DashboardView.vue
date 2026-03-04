<template>
  <div class="page-dashboard">
    <div class="breadcrumb">
      <span class="breadcrumb-item active">总览</span>
    </div>

    <!-- Stat Cards -->
    <div class="stat-cards">
      <div class="stat-card">
        <div class="stat-indicator" style="background-color: #1890ff;"></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">数据库总数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-indicator" style="background-color: #52c41a;"></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.running }}</div>
          <div class="stat-label">运行中</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-indicator" style="background-color: #d9d9d9;"></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.suspended }}</div>
          <div class="stat-label">已挂起</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-indicator" style="background-color: #ff4d4f;"></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.error }}</div>
          <div class="stat-label">异常</div>
        </div>
      </div>
    </div>

    <!-- Recent Operations -->
    <div class="section-card">
      <div class="section-header">
        <h3>最近操作</h3>
      </div>
      <div class="table-wrapper">
        <table class="data-table" v-if="recentOps.length > 0">
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
            <tr v-for="op in recentOps" :key="op.id">
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
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { databaseApi } from '../../api/database'
import { operationApi, type OperationLog } from '../../api/operation'
import { formatDuration, formatDate } from '../../utils/format'

const stats = reactive({ total: 0, running: 0, suspended: 0, error: 0 })
const recentOps = ref<OperationLog[]>([])
const loading = ref(true)

onMounted(async () => {
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
  } catch (e) {
    console.error('Failed to load dashboard data', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.page-dashboard {
  padding: 4px;
}

.breadcrumb {
  margin-bottom: 20px;
  font-size: 14px;
  color: #999;
}

.breadcrumb-item.active {
  color: #333;
  font-weight: 500;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}

.stat-card {
  background: #fff;
  border-radius: 6px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
}

.stat-indicator {
  width: 8px;
  height: 40px;
  border-radius: 4px;
  flex-shrink: 0;
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  color: #333;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: #999;
  margin-top: 4px;
}

.section-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
  overflow: hidden;
}

.section-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.section-header h3 {
  font-size: 15px;
  font-weight: 500;
  color: #333;
  margin: 0;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th {
  padding: 12px 20px;
  text-align: left;
  font-size: 13px;
  font-weight: 500;
  color: #666;
  background: #fafafa;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}

.data-table td {
  padding: 12px 20px;
  font-size: 14px;
  color: #333;
  border-bottom: 1px solid #f5f5f5;
}

.data-table tbody tr:hover {
  background-color: #fafafa;
}

.status-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.tag-green {
  color: #52c41a;
  background-color: #f6ffed;
  border: 1px solid #b7eb8f;
}

.tag-red {
  color: #ff4d4f;
  background-color: #fff2f0;
  border: 1px solid #ffccc7;
}

.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: #999;
  font-size: 14px;
}
</style>
