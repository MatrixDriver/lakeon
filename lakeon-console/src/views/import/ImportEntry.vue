<template>
  <div class="page-import-entry">
    <div class="page-header">
      <h1 class="page-title">数据导入</h1>
    </div>

    <!-- If no databases, show prompt -->
    <div v-if="!loading && databases.length === 0" class="empty-hero">
      <p>暂无数据库，请先创建一个目标数据库后再进行数据导入。</p>
      <router-link to="/databases" class="btn btn-primary">前往创建</router-link>
    </div>

    <!-- Database selector + import tasks -->
    <template v-else-if="!loading">
      <!-- Select target database -->
      <div class="select-section">
        <div class="select-prompt">选择目标数据库，从外部 PostgreSQL 导入数据</div>
        <div class="db-card-grid">
          <div
            v-for="db in databases"
            :key="db.id"
            class="db-card"
            :class="{
              'db-card-disabled': db.status !== 'RUNNING' && db.status !== 'SUSPENDED',
              'db-card-selected': selectedDb?.id === db.id
            }"
            @click="selectDb(db)"
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
            <div class="db-card-tasks" v-if="dbImportCounts[db.id]">
              {{ dbImportCounts[db.id] }} 个导入任务
            </div>
          </div>
        </div>
      </div>

      <!-- Action buttons when database selected -->
      <div v-if="selectedDb" class="action-bar">
        <button class="btn btn-primary" @click="showWizard = true">
          新建导入任务
        </button>
        <router-link :to="`/databases/${selectedDb.id}`" class="btn btn-default">
          查看数据库详情
        </router-link>
      </div>

      <!-- Import tasks for selected database -->
      <div v-if="selectedDb && !selectedTaskId && importTasks.length > 0" class="section-card" style="margin-top: 24px;">
        <div class="section-header">
          <h3>导入任务 — {{ selectedDb.name }}</h3>
        </div>
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>来源数据库</th>
                <th>模式</th>
                <th>进度</th>
                <th>状态</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in importTasks" :key="task.id" class="task-row" @click="selectedTaskId = task.id">
                <td>{{ task.source_host }}:{{ task.source_port }}/{{ task.source_dbname }}</td>
                <td>{{ task.mode === 'FULL' ? '全库' : '指定表' }}</td>
                <td>
                  <div class="progress-wrap">
                    <div class="progress-bar"><div class="progress-fill" :style="{ width: taskProgress(task) + '%' }"></div></div>
                    <span class="progress-text">{{ taskProgress(task) }}%</span>
                  </div>
                </td>
                <td>
                  <span class="status-tag" :class="taskStatusClass(task.status)">{{ taskStatusText(task.status) }}</span>
                </td>
                <td>{{ formatDate(task.created_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Task Detail View -->
      <div v-if="selectedDb && selectedTaskId" class="section-card" style="margin-top: 24px;">
        <ImportTaskDetail
          :db-id="selectedDb.id"
          :task-id="selectedTaskId"
          @back="selectedTaskId = null"
          @updated="loadTasks(selectedDb!.id)"
        />
      </div>

      <div v-if="selectedDb && !selectedTaskId && importTasks.length === 0 && !tasksLoading" class="empty-tasks">
        该数据库暂无导入任务，点击上方按钮新建导入。
      </div>
    </template>

    <div v-if="loading" class="loading-state"><p>加载中...</p></div>

    <!-- Import Wizard Dialog -->
    <ImportWizard v-if="selectedDb" :visible="showWizard" :db-id="selectedDb.id" @close="handleWizardClose" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { databaseApi, type Database } from '../../api/database'
import { importApi } from '../../api/import'
import { formatDate } from '../../utils/format'
import ImportWizard from '../database/ImportWizard.vue'
import ImportTaskDetail from '../database/ImportTaskDetail.vue'

// Use ImportTask from import API
import type { ImportTask } from '../../api/import'

const databases = ref<Database[]>([])
const loading = ref(true)
const selectedDb = ref<Database | null>(null)
const importTasks = ref<ImportTask[]>([])
const tasksLoading = ref(false)
const showWizard = ref(false)
const selectedTaskId = ref<string | null>(null)
const dbImportCounts = ref<Record<string, number>>({})

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

function taskStatusClass(status: string): string {
  switch (status) {
    case 'COMPLETED': return 'tag-green'
    case 'RUNNING': return 'tag-blue'
    case 'FAILED': return 'tag-red'
    case 'PAUSED': return 'tag-orange'
    case 'CANCELLED': return 'tag-gray'
    default: return ''
  }
}

function taskStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
    COMPLETED: '已完成', FAILED: '失败', PARTIAL: '部分完成', CANCELLED: '已取消',
  }
  return map[status] || status
}

function taskProgress(task: ImportTask): number {
  if (task.total_tables === 0) return 0
  return Math.round((task.completed_tables / task.total_tables) * 100)
}

function selectDb(db: Database) {
  if (db.status !== 'RUNNING' && db.status !== 'SUSPENDED') return
  selectedTaskId.value = null
  selectedDb.value = db
}

async function loadTasks(dbId: string) {
  tasksLoading.value = true
  try {
    const res = await importApi.list(dbId)
    importTasks.value = res.data
  } catch (e) {
    importTasks.value = []
  } finally {
    tasksLoading.value = false
  }
}

function handleWizardClose() {
  showWizard.value = false
  if (selectedDb.value) {
    loadTasks(selectedDb.value.id)
  }
}

watch(selectedDb, (db) => {
  if (db) loadTasks(db.id)
  else importTasks.value = []
})

onMounted(async () => {
  try {
    const res = await databaseApi.list()
    databases.value = res.data
    // Load import task counts for each database
    for (const db of databases.value) {
      try {
        const taskRes = await importApi.list(db.id)
        if (taskRes.data.length > 0) {
          dbImportCounts.value[db.id] = taskRes.data.length
        }
      } catch { /* ignore */ }
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

.db-card-selected {
  border-color: #0073e6;
  background: #f0f7ff;
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
}

.db-card-tasks {
  margin-top: 8px;
  font-size: 12px;
  color: #0073e6;
}

.action-bar {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

/* Progress bar */
.progress-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.progress-bar {
  width: 100px;
  height: 6px;
  background: #e8e8e8;
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #0073e6;
  border-radius: 3px;
  transition: width 0.3s;
}

.progress-text {
  font-size: 12px;
  color: #8a8e99;
}

/* Status tags */
.tag-blue { background: #e6f7ff; color: #0073e6; }
.tag-orange { background: #fff7e6; color: #d48806; }
.tag-gray { background: #f5f5f5; color: #8a8e99; }

.task-row {
  cursor: pointer;
  transition: background 0.15s;
}

.task-row:hover {
  background: #f5f7fa;
}

.empty-tasks {
  text-align: center;
  padding: 40px 0;
  color: #8a8e99;
  font-size: 14px;
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

@media (max-width: 768px) {
  .db-card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
