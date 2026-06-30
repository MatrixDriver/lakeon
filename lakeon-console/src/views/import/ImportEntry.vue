<template>
  <div class="page-import-entry">
    <div class="page-header">
      <h1 class="page-title">数据迁移</h1>
    </div>

    <!-- If no databases, show prompt -->
    <div v-if="!loading && databases.length === 0" class="empty-hero">
      <p>暂无数据库，请先创建一个目标数据库后再进行数据迁移。</p>
      <router-link to="/databases" class="btn btn-primary">前往创建</router-link>
    </div>

    <!-- Database selector + import tasks -->
    <template v-else-if="!loading">
      <!-- Select target database -->
      <div class="select-section">
        <div class="select-prompt">选择目标数据库，从外部 PostgreSQL 导入或同步数据</div>
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
              <span
                v-for="(count, status) in dbImportCounts[db.id]"
                :key="status"
                class="task-status-chip"
                :class="taskStatusClass(status as string)"
              >{{ count }} {{ taskStatusText(status as string) }}</span>
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
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in importTasks" :key="task.id" class="task-row">
                <td class="clickable-cell" @click="selectedTaskId = task.id">{{ taskSourceText(task) }}</td>
                <td class="clickable-cell" @click="selectedTaskId = task.id">{{ modeText(task.mode) }}</td>
                <td class="clickable-cell" @click="selectedTaskId = task.id">
                  <template v-if="task.mode === 'SYNC'">
                    <span v-if="task.replay_lag_seconds != null" class="sync-lag">
                      延迟 {{ formatLag(task.replay_lag_seconds) }}
                    </span>
                    <span v-else class="progress-text">-</span>
                  </template>
                  <template v-else>
                    <div class="progress-wrap">
                      <div class="progress-bar"><div class="progress-fill" :style="{ width: taskProgress(task) + '%' }"></div></div>
                      <span class="progress-text">{{ taskProgress(task) }}%</span>
                    </div>
                  </template>
                </td>
                <td class="clickable-cell" @click="selectedTaskId = task.id">
                  <span class="status-tag" :class="taskStatusClass(task.status)">{{ taskStatusText(task.status) }}</span>
                </td>
                <td class="clickable-cell" @click="selectedTaskId = task.id">{{ formatDate(task.created_at) }}</td>
                <td class="action-cell" @click.stop>
                  <template v-if="task.mode === 'SYNC'">
                    <button v-if="['RUNNING','SYNCING','CATCHING_UP'].includes(task.status)" class="action-link" :disabled="actionLoading" @click="handleTaskPause(task)">暂停</button>
                    <button v-if="task.status === 'PAUSED'" class="action-link" :disabled="actionLoading" @click="handleTaskResume(task)">恢复</button>
                    <button v-if="['RUNNING','SYNCING','CATCHING_UP','PAUSED'].includes(task.status)" class="action-link action-danger" :disabled="actionLoading" @click="handleTaskStop(task)">停止</button>
                  </template>
                  <template v-else>
                    <button v-if="task.status === 'RUNNING'" class="action-link" :disabled="actionLoading" @click="handleTaskPause(task)">暂停</button>
                    <button v-if="task.status === 'PAUSED'" class="action-link" :disabled="actionLoading" @click="handleTaskResume(task)">恢复</button>
                    <button v-if="['RUNNING','PAUSED','PENDING'].includes(task.status)" class="action-link action-danger" :disabled="actionLoading" @click="handleTaskCancel(task)">取消</button>
                    <button v-if="['FAILED','PARTIAL'].includes(task.status)" class="action-link" :disabled="actionLoading" @click="handleTaskRetry(task)">重试</button>
                  </template>
                  <button class="action-link" @click="selectedTaskId = task.id">详情</button>
                </td>
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
import { useRoute } from 'vue-router'
import { databaseApi, type Database } from '../../api/database'
import { importApi } from '../../api/import'
import { formatDate } from '../../utils/format'
import ImportWizard from '../database/ImportWizard.vue'
import ImportTaskDetail from '../database/ImportTaskDetail.vue'

// Use ImportTask from import API
import type { ImportTask } from '../../api/import'

const route = useRoute()
const databases = ref<Database[]>([])
const loading = ref(true)
const selectedDb = ref<Database | null>(null)
const importTasks = ref<ImportTask[]>([])
const tasksLoading = ref(false)
const showWizard = ref(false)
const selectedTaskId = ref<string | null>(null)
const dbImportCounts = ref<Record<string, Record<string, number>>>({})
const actionLoading = ref(false)

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
    case 'RUNNING': case 'SYNCING': return 'tag-blue'
    case 'CATCHING_UP': return 'tag-cyan'
    case 'FAILED': return 'tag-red'
    case 'PAUSED': return 'tag-orange'
    case 'PARTIAL': return 'tag-orange'
    case 'PENDING': case 'CANCELLED': return 'tag-gray'
    default: return ''
  }
}

function taskStatusText(status: string): string {
  const map: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
    COMPLETED: '已完成', FAILED: '失败', PARTIAL: '部分完成', CANCELLED: '已取消',
    SYNCING: '同步中', CATCHING_UP: '追赶中',
  }
  return map[status] || status
}

function modeText(mode: string): string {
  if (mode === 'FULL') return '全库'
  if (mode === 'SELECTIVE') return '指定表'
  return '持续同步'
}

function taskSourceText(task: ImportTask): string {
  return task.connector_name || task.connector_id || `${task.source_host}:${task.source_port}/${task.source_dbname}`
}

function formatLag(seconds: number): string {
  if (seconds < 1) return '< 1s'
  if (seconds < 60) return Math.round(seconds) + 's'
  if (seconds < 3600) return Math.round(seconds / 60) + 'min'
  return (seconds / 3600).toFixed(1) + 'h'
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

async function handleTaskPause(task: ImportTask) {
  if (!selectedDb.value) return
  actionLoading.value = true
  try { await importApi.pause(selectedDb.value.id, task.id); loadTasks(selectedDb.value.id) }
  catch (e) { console.error('Pause failed', e) }
  finally { actionLoading.value = false }
}

async function handleTaskResume(task: ImportTask) {
  if (!selectedDb.value) return
  actionLoading.value = true
  try { await importApi.resume(selectedDb.value.id, task.id); loadTasks(selectedDb.value.id) }
  catch (e) { console.error('Resume failed', e) }
  finally { actionLoading.value = false }
}

async function handleTaskCancel(task: ImportTask) {
  if (!selectedDb.value || !confirm('确定取消该导入任务？')) return
  actionLoading.value = true
  try { await importApi.cancel(selectedDb.value.id, task.id); loadTasks(selectedDb.value.id) }
  catch (e) { console.error('Cancel failed', e) }
  finally { actionLoading.value = false }
}

async function handleTaskStop(task: ImportTask) {
  if (!selectedDb.value || !confirm('确定停止该同步任务？')) return
  actionLoading.value = true
  try { await importApi.stop(selectedDb.value.id, task.id, true); loadTasks(selectedDb.value.id) }
  catch (e) { console.error('Stop failed', e) }
  finally { actionLoading.value = false }
}

async function handleTaskRetry(task: ImportTask) {
  if (!selectedDb.value) return
  actionLoading.value = true
  try { await importApi.retry(selectedDb.value.id, task.id); loadTasks(selectedDb.value.id) }
  catch (e) { console.error('Retry failed', e) }
  finally { actionLoading.value = false }
}

watch(selectedDb, (db) => {
  if (db) loadTasks(db.id)
  else importTasks.value = []
})

async function loadImportCounts() {
  const entries = await Promise.allSettled(
    databases.value.map(async (db) => {
      const taskRes = await importApi.list(db.id)
      if (taskRes.data.length === 0) return null
      const counts: Record<string, number> = {}
      for (const t of taskRes.data) {
        counts[t.status] = (counts[t.status] || 0) + 1
      }
      return [db.id, counts] as const
    })
  )

  for (const entry of entries) {
    if (entry.status === 'fulfilled' && entry.value) {
      const [dbId, counts] = entry.value
      dbImportCounts.value[dbId] = counts
    }
  }
}

onMounted(async () => {
  try {
    const res = await databaseApi.list()
    databases.value = res.data
    // Auto-select database from query param ?db=xxx
    const dbParam = route.query.db as string
    if (dbParam) {
      const match = databases.value.find(d => d.id === dbParam)
      if (match) selectedDb.value = match
    }
    void loadImportCounts()
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
  border-color: #c67d3a;
  box-shadow: 0 2px 8px rgba(0, 115, 230, 0.08);
}

.db-card-selected {
  border-color: #c67d3a;
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
  color: #2c3e50;
}

.db-card-meta {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #8a8e99;
}

.db-card-tasks {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.task-status-chip {
  display: inline-block;
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 10px;
  font-weight: 500;
  line-height: 18px;
}

.task-status-chip.tag-green { background: #e6f9ed; color: #1a9e3f; }
.task-status-chip.tag-blue { background: #fdf5ed; color: #9a5b25; }
.task-status-chip.tag-red { background: #fff0f0; color: #e63e3e; }
.task-status-chip.tag-orange { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: #9a5b25; }
.task-status-chip.tag-gray { background: #f5f5f5; color: #8a8e99; }
.task-status-chip.tag-cyan { background: #e6fffb; color: #13c2c2; }

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
  background: #9a5b25;
  border-radius: 3px;
  transition: width 0.3s;
}

.progress-text {
  font-size: 12px;
  color: #8a8e99;
}

/* Status tags */
.tag-blue { background: #fdf5ed; color: #9a5b25; }
.tag-orange { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: #9a5b25; }
.tag-gray { background: #f5f5f5; color: #8a8e99; }
.tag-cyan { background: #e6fffb; color: #13c2c2; }
.sync-lag { font-size: 13px; color: #64748b; }

.task-row:hover {
  background: #f8f5f1;
}

.clickable-cell {
  cursor: pointer;
}

.action-cell {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: nowrap;
}

.action-link {
  background: none;
  border: none;
  color: #9a5b25;
  cursor: pointer;
  font-size: 13px;
  padding: 2px 4px;
  white-space: nowrap;
}

.action-link:hover {
  text-decoration: underline;
}

.action-link:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-link.action-danger {
  color: #e63e3e;
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

@media (max-width: 768px) {
  .db-card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
