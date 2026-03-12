<template>
  <div class="page-audit">
    <div class="page-header">
      <h1 class="page-title">操作日志</h1>
    </div>

    <div class="section-card">
      <TableToolbar v-model="search" placeholder="搜索数据库名称或操作类型" :loading="loading" @refresh="fetchOps">
        <template #extra>
          <select v-model="typeFilter" class="filter-select" @change="currentPage = 1">
            <option value="">全部类型</option>
            <option value="CREATE">创建</option>
            <option value="RESUME">唤醒</option>
            <option value="SUSPEND">挂起</option>
            <option value="DELETE">删除</option>
            <option value="IMPORT">导入</option>
            <option value="UPDATE">更新</option>
            <option value="RESET_PASSWORD">重置密码</option>
          </select>
          <select v-model="statusFilter" class="filter-select" @change="currentPage = 1">
            <option value="">全部状态</option>
            <option value="SUCCESS">成功</option>
            <option value="FAILURE">失败</option>
          </select>
        </template>
      </TableToolbar>
      <div class="table-wrapper">
        <table class="data-table" v-if="filteredOps.length > 0">
          <thead>
            <tr>
              <th>数据库</th>
              <th>操作类型</th>
              <th>状态</th>
              <th>耗时</th>
              <th>开始时间</th>
              <th>错误信息</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="op in pagedOps" :key="op.id" :class="{ 'row-error': op.status === 'FAILURE' }">
              <td>
                <router-link v-if="op.databaseId" :to="`/databases/${op.databaseId}`" class="db-name-link">{{ op.databaseName }}</router-link>
                <span v-else>{{ op.databaseName }}</span>
              </td>
              <td>
                <span class="op-type-badge" :class="'op-' + op.operationType.toLowerCase()">
                  {{ OP_LABELS[op.operationType] || op.operationType }}
                </span>
                <span v-if="op.operationType === 'RESUME' && op.resumeType" class="resume-type-tag" :class="'rt-' + op.resumeType.toLowerCase()">
                  {{ op.resumeType === 'WARM' ? '热启动' : '冷启动' }}
                </span>
              </td>
              <td>
                <span class="status-tag" :class="op.status === 'SUCCESS' ? 'tag-green' : 'tag-red'">
                  {{ op.status === 'SUCCESS' ? '成功' : '失败' }}
                </span>
              </td>
              <td>{{ formatDuration(op.durationMs) }}</td>
              <td>{{ formatDate(op.startedAt) }}</td>
              <td>
                <span v-if="op.errorMessage" class="error-text" :title="op.errorMessage">
                  {{ op.errorMessage.length > 50 ? op.errorMessage.slice(0, 50) + '...' : op.errorMessage }}
                </span>
                <span v-else class="text-muted">-</span>
              </td>
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
        v-model:pageSize="pageSize"
        v-model:currentPage="currentPage"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { operationApi, type OperationLog } from '../../api/operation'
import { formatDuration, formatDate } from '../../utils/format'
import TableToolbar from '../../components/TableToolbar.vue'
import TableFooter from '../../components/TableFooter.vue'

const OP_LABELS: Record<string, string> = {
  CREATE: '创建',
  RESUME: '唤醒',
  SUSPEND: '挂起',
  DELETE: '删除',
  IMPORT: '导入',
  UPDATE: '更新',
  RESET_PASSWORD: '重置密码',
}

const allOps = ref<OperationLog[]>([])
const loading = ref(true)
const search = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const pageSize = ref(20)
const currentPage = ref(1)

const filteredOps = computed(() => {
  let ops = allOps.value
  if (typeFilter.value) {
    ops = ops.filter(op => op.operationType === typeFilter.value)
  }
  if (statusFilter.value) {
    ops = ops.filter(op => op.status === statusFilter.value)
  }
  const q = search.value.toLowerCase()
  if (q) {
    ops = ops.filter(op =>
      op.databaseName.toLowerCase().includes(q) ||
      op.operationType.toLowerCase().includes(q) ||
      (OP_LABELS[op.operationType] || '').includes(q)
    )
  }
  return ops
})

const pagedOps = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredOps.value.slice(start, start + pageSize.value)
})

watch([search, typeFilter, statusFilter, pageSize], () => { currentPage.value = 1 })

async function fetchOps() {
  loading.value = true
  try {
    const res = await operationApi.getRecent()
    allOps.value = res.data
  } catch (e) {
    console.error('Failed to load operations', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => fetchOps())
</script>

<style scoped>
.filter-select {
  padding: 6px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #333;
  background: #fff;
  cursor: pointer;
}

.filter-select:hover {
  border-color: #0073e6;
}

.db-name-link {
  color: #0073e6;
  text-decoration: none;
}

.db-name-link:hover {
  text-decoration: underline;
}

.text-muted {
  color: #ccc;
}

.row-error {
  background: #fff8f8;
}

.op-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  background: #f5f5f5;
  color: #575d6c;
}

.op-create { background: #e6f7ff; color: #0073e6; }
.op-resume { background: #f6ffed; color: #389e0d; }
.op-suspend { background: #f5f5f5; color: #8a8e99; }
.op-delete { background: #fff1f0; color: #cf1322; }
.op-import { background: #f9f0ff; color: #722ed1; }
.op-update { background: #fff7e6; color: #d48806; }

.error-text {
  color: #cf1322;
  font-size: 12px;
}

.resume-type-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
}

.rt-warm {
  background: #f6ffed;
  color: #389e0d;
}

.rt-cold {
  background: #e6f7ff;
  color: #0073e6;
}
</style>
