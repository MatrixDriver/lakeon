<template>
  <div class="page-recycle">
    <div class="page-header">
      <h1 class="page-title">回收站</h1>
    </div>
    <p class="page-desc">已删除的数据库会保留 7 天，过期后将被永久删除。</p>

    <div v-if="loading" class="empty-state">加载中...</div>
    <div v-else-if="!databases.length" class="empty-state">
      <div class="empty-icon">
        <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#c4b5a0" stroke-width="1.5"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
      </div>
      <div class="empty-text">回收站为空</div>
    </div>

    <div v-else class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>删除时间</th>
            <th>剩余天数</th>
            <th>规格</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="db in databases" :key="db.id">
            <td class="db-name">{{ db.name }}</td>
            <td>{{ formatTime(db.deleted_at) }}</td>
            <td>
              <span class="days-badge" :class="daysRemaining(db.deleted_at) <= 1 ? 'days-urgent' : ''">
                {{ daysRemaining(db.deleted_at) }} 天
              </span>
            </td>
            <td>{{ db.compute_size || '1cu' }}</td>
            <td class="actions">
              <button class="btn btn-primary btn-small" @click="restoreDb(db)" :disabled="restoring === db.id">
                {{ restoring === db.id ? '恢复中...' : '恢复' }}
              </button>
              <button class="btn btn-danger-outline btn-small" @click="purgeDb(db)" :disabled="purging === db.id">
                {{ purging === db.id ? '删除中...' : '永久删除' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { databaseApi } from '@/api/database'

const databases = ref<any[]>([])
const loading = ref(true)
const restoring = ref<string | null>(null)
const purging = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await databaseApi.listDeleted()
    databases.value = res.data
  } catch (e) {
    console.error('Failed to load recycle bin', e)
  } finally {
    loading.value = false
  }
}

function formatTime(ts: string) {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function daysRemaining(deletedAt: string): number {
  if (!deletedAt) return 7
  const deleted = new Date(deletedAt).getTime()
  const expiry = deleted + 7 * 24 * 60 * 60 * 1000
  const remaining = Math.ceil((expiry - Date.now()) / (24 * 60 * 60 * 1000))
  return Math.max(0, remaining)
}

async function restoreDb(db: any) {
  if (!confirm(`确定恢复数据库 "${db.name}" ？`)) return
  restoring.value = db.id
  try {
    await databaseApi.restore(db.id)
    databases.value = databases.value.filter(d => d.id !== db.id)
  } catch (e) {
    alert('恢复失败')
    console.error(e)
  } finally {
    restoring.value = null
  }
}

async function purgeDb(db: any) {
  if (!confirm(`确定永久删除 "${db.name}" ？此操作不可撤销！`)) return
  purging.value = db.id
  try {
    await databaseApi.delete(db.id)
    databases.value = databases.value.filter(d => d.id !== db.id)
  } catch (e) {
    alert('删除失败')
    console.error(e)
  } finally {
    purging.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.page-recycle { max-width: 900px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.page-title { font-size: 20px; font-weight: 600; color: #1e293b; }
.page-desc { color: #64748b; font-size: 13px; margin-bottom: 24px; }

.empty-state { text-align: center; padding: 60px 0; }
.empty-icon { margin-bottom: 12px; }
.empty-text { color: #94a3b8; font-size: 14px; }

.table-wrapper { overflow-x: auto; }
.data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.data-table th { text-align: left; padding: 10px 12px; color: #64748b; font-weight: 500; border-bottom: 1px solid #e2e8f0; }
.data-table td { padding: 12px; border-bottom: 1px solid #f1f5f9; }
.db-name { font-weight: 500; color: #1e293b; }

.days-badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; background: #f0f5ff; color: #3b82f6; }
.days-urgent { background: #fef2f2; color: #ef4444; font-weight: 600; }

.actions { display: flex; gap: 8px; }
.btn { border: none; border-radius: 6px; padding: 5px 12px; font-size: 12px; cursor: pointer; transition: all 0.15s; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: #9a5b25; color: #fff; }
.btn-primary:hover:not(:disabled) { background: #7d4a1e; }
.btn-danger-outline { background: #fff; color: #e53e3e; border: 1px solid #fed7d7; }
.btn-danger-outline:hover:not(:disabled) { background: #fef2f2; }
.btn-small { padding: 4px 10px; }
</style>
