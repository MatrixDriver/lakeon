<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">Notebook</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="showCreateDialog = true">新建 Notebook</button>
      </div>
    </div>

    <!-- Table -->
    <div class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>镜像</th>
            <th>最后修改</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="nb in notebooks" :key="nb.id">
            <td>
              <a class="nb-link" @click="$router.push(`/datalake/notebook/${nb.id}`)">{{ nb.name }}</a>
            </td>
            <td><span class="nb-image-tag">{{ nb.image }}</span></td>
            <td>{{ formatDate(nb.updated_at) }}</td>
            <td>
              <button class="btn btn-text btn-small" @click="renameNotebook(nb)">重命名</button>
              <button class="btn btn-text btn-small" style="color:#e53e3e;" @click="deleteNotebook(nb)">删除</button>
            </td>
          </tr>
          <tr v-if="notebooks.length === 0 && !loading">
            <td colspan="4" class="empty-state-cell">暂无 Notebook</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="loading" style="text-align:center;padding:40px;color:#999;">加载中...</div>

    <!-- Create Dialog -->
    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="showCreateDialog = false">
      <div class="dialog-box" style="width:400px;">
        <div class="dialog-header">
          <h3>新建 Notebook</h3>
          <button class="dialog-close" @click="showCreateDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div style="margin-bottom:12px;">
            <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">名称</label>
            <input v-model="newName" class="form-input" style="width:100%;" placeholder="我的 Notebook" @keyup.enter="createNotebook" />
          </div>
          <div>
            <label style="font-size:13px;font-weight:600;display:block;margin-bottom:4px;">镜像</label>
            <select v-model="newImage" class="form-select" style="width:100%;">
              <option value="python-data">python-data</option>
              <option value="ray">ray</option>
            </select>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn" @click="showCreateDialog = false">取消</button>
          <button class="btn btn-primary" @click="createNotebook" :disabled="!newName.trim() || creating">
            {{ creating ? '创建中...' : '创建' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { notebooksApi } from '../../api/notebooks'

const router = useRouter()

interface Notebook {
  id: string
  name: string
  image: string
  updated_at: string
}

const notebooks = ref<Notebook[]>([])
const loading = ref(false)
const showCreateDialog = ref(false)
const newName = ref('')
const newImage = ref('python-data')
const creating = ref(false)

function formatDate(ts: string): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString('zh-CN')
}

async function fetchNotebooks() {
  loading.value = true
  try {
    const { data } = await notebooksApi.list()
    notebooks.value = data
  } catch {
    notebooks.value = []
  } finally {
    loading.value = false
  }
}

async function createNotebook() {
  if (!newName.value.trim() || creating.value) return
  creating.value = true
  try {
    const { data } = await notebooksApi.create(newName.value.trim(), newImage.value)
    showCreateDialog.value = false
    newName.value = ''
    newImage.value = 'python-data'
    router.push(`/datalake/notebook/${data.id}`)
  } catch (e: any) {
    alert('Failed to create notebook: ' + (e.response?.data?.message || e.message))
  } finally {
    creating.value = false
  }
}

async function renameNotebook(nb: Notebook) {
  const name = prompt('新名称:', nb.name)
  if (!name || !name.trim() || name.trim() === nb.name) return
  try {
    await notebooksApi.rename(nb.id, name.trim())
    await fetchNotebooks()
  } catch (e: any) {
    alert('Failed to rename: ' + (e.response?.data?.message || e.message))
  }
}

async function deleteNotebook(nb: Notebook) {
  if (!confirm(`确认删除 "${nb.name}"？此操作不可恢复。`)) return
  try {
    await notebooksApi.remove(nb.id)
    await fetchNotebooks()
  } catch (e: any) {
    alert('Failed to delete: ' + (e.response?.data?.message || e.message))
  }
}

onMounted(fetchNotebooks)
</script>

<style scoped>
.nb-link {
  cursor: pointer;
  color: #2563eb;
  text-decoration: none;
  font-weight: 500;
}
.nb-link:hover {
  text-decoration: underline;
}
.nb-image-tag {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  background: #f1f5f9;
  border-radius: 4px;
  color: #475569;
  font-family: monospace;
}
.empty-state-cell {
  text-align: center;
  padding: 40px;
  color: #9ca3af;
  font-size: 14px;
}
.form-input {
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  color: #374151;
  outline: none;
  box-sizing: border-box;
}
.form-input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.1);
}
.form-select {
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  color: #374151;
  outline: none;
  box-sizing: border-box;
  background: white;
}
</style>
