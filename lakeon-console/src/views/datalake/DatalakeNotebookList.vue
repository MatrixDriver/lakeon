<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">Notebook</h1>
      <div class="page-header-actions">
        <ViewToggle v-model="viewMode" />
        <button class="btn btn-primary" @click="showCreateDialog = true">新建 Notebook</button>
      </div>
    </div>

    <!-- Card view -->
    <div v-if="viewMode === 'card'" class="card-grid">
      <ResourceCard
        v-for="nb in notebooks"
        :key="nb.id"
        :name="nb.name"
        status="active"
        statusLabel="活跃"
        :meta="[nb.image, formatDate(nb.updated_at)]"
        @click="$router.push(`/datalake/notebook/${nb.id}`)"
      />
      <div class="card-create" @click="showCreateDialog = true">
        + 新建 Notebook
      </div>
      <div v-if="notebooks.length === 0 && !loading" class="empty-state" style="grid-column: 1 / -1; text-align: center; padding: 40px;">
        <p>暂无 Notebook</p>
      </div>
    </div>

    <!-- Table view -->
    <div v-if="viewMode === 'table'" class="table-wrapper">
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
              <button class="btn btn-text btn-small btn-danger-text" @click="deleteNotebook(nb)">删除</button>
            </td>
          </tr>
          <tr v-if="notebooks.length === 0 && !loading">
            <td colspan="4" class="empty-state-cell">暂无 Notebook</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Quick tips -->
    <div v-if="!loading && notebooks.length > 0 && notebooks.length < 3" class="page-tips">
      <div class="page-tips-title">快速上手</div>
      <div class="page-tips-items">
        <span>Notebook 支持 Python 和 Ray 分布式计算</span>
        <span class="tips-sep">·</span>
        <span>可直接访问 DBay 数据库和 OBS 数据</span>
        <span class="tips-sep">·</span>
        <router-link to="/docs#datalake" class="tips-link">查看文档</router-link>
      </div>
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
            <label style="font-size:13px;font-weight:600;display:block;margin-bottom:8px;">镜像</label>
            <div class="image-cards">
              <button
                v-for="img in imageOptions"
                :key="img.value"
                type="button"
                class="image-card"
                :class="{ 'image-card-selected': newImage === img.value }"
                @click="newImage = img.value"
              >
                <span class="image-card-name">{{ img.label }}</span>
                <span class="image-card-desc">{{ img.desc }}</span>
              </button>
            </div>
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
import ViewToggle from '../../components/ViewToggle.vue'
import ResourceCard from '../../components/ResourceCard.vue'

const router = useRouter()

interface Notebook {
  id: string
  name: string
  image: string
  updated_at: string
}

const viewMode = ref<'card' | 'table'>('card')
const notebooks = ref<Notebook[]>([])
const loading = ref(false)
const showCreateDialog = ref(false)
const newName = ref('')
const newImage = ref('python-data')
const creating = ref(false)

const imageOptions = [
  { value: 'python-data', label: 'Python Data', desc: 'pandas / numpy / matplotlib' },
  { value: 'ray', label: 'Ray', desc: 'Ray 分布式计算框架' },
]

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
.image-cards {
  display: flex;
  gap: 10px;
}
.image-card {
  flex: 1;
  padding: 14px 16px;
  border: 1.5px solid #d1d5db;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, box-shadow 0.15s;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.image-card:hover {
  border-color: #93c5fd;
}
.image-card-selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
  background: #f0f5ff;
}
.image-card-name {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}
.image-card-desc {
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.3;
}
.page-tips {
  margin-top: 48px;
  padding: 16px 20px;
  background: #faf8f5;
  border-radius: 6px;
  border-left: 3px solid #c67d3a;
}
.page-tips-title {
  font-size: 13px;
  font-weight: 600;
  color: #2c3e50;
  margin-bottom: 6px;
}
.page-tips-items {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
}
.tips-sep {
  margin: 0 8px;
  color: #d5d0ca;
}
.tips-link {
  color: #9a5b25;
  text-decoration: none;
}
.tips-link:hover {
  text-decoration: underline;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.card-create {
  border: 1px dashed #d5d0ca;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.card-create:hover { border-color: #94a3b8; }
@media (max-width: 768px) {
  .card-grid { grid-template-columns: 1fr; }
}
</style>
