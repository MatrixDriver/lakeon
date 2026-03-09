<template>
  <div class="page-apikey">
    <div class="page-header">
      <h1 class="page-title">API Key</h1>
    </div>

    <p class="page-desc">
      API Key 用于通过 API 访问您的数据库资源。每个 Key 拥有完整的账户权限，请妥善保管，不要泄露给他人。
      您可以创建多个 Key 用于不同的应用或环境，便于独立管理和轮换。
    </p>

    <!-- New key alert (shown once after creation) -->
    <div v-if="newKey" class="new-key-alert">
      <div class="alert-title">请立即复制新的 API Key，此 Key 仅显示一次，关闭后无法再次查看。</div>
      <div class="key-row">
        <code class="key-value key-full">{{ newKey }}</code>
        <button class="copy-btn" @click="copyKey(newKey)">{{ copyState === 'new' ? '已复制' : '复制' }}</button>
        <button class="close-btn" @click="newKey = ''">&times;</button>
      </div>
    </div>

    <!-- Key Table -->
    <div class="section-card">
      <div class="section-toolbar">
        <span class="key-count">共 {{ keys.length }} 个 Key</span>
        <button class="btn btn-primary btn-small" @click="showCreateDialog = true">创建 API Key</button>
      </div>
      <div class="table-wrapper">
        <table class="data-table" v-if="keys.length > 0">
          <thead>
            <tr>
              <th>名称</th>
              <th>Key</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="key in keys" :key="key.id">
              <td class="td-name">{{ key.name }}</td>
              <td><code class="key-value">{{ key.masked_key }}</code></td>
              <td>{{ formatDate(key.created_at) }}</td>
              <td class="td-actions">
                <button class="action-link action-danger" @click="confirmDelete(key)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else-if="!loading" class="empty-state">
          <p>暂无 API Key，请创建一个</p>
        </div>
        <div v-else class="empty-state"><p>加载中...</p></div>
      </div>
    </div>

    <!-- Create Dialog -->
    <div v-if="showCreateDialog" class="dialog-overlay" @click.self="showCreateDialog = false">
      <div class="dialog-box">
        <div class="dialog-header">
          <h3>创建 API Key</h3>
          <button class="dialog-close" @click="showCreateDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">名称</label>
            <input
              v-model="createName"
              class="form-input"
              placeholder="例如：生产环境、开发测试、CI/CD"
              maxlength="64"
              @keyup.enter="handleCreate"
            />
            <p class="form-hint">为 Key 取一个有意义的名称，便于识别用途</p>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreateDialog = false">取消</button>
          <button
            class="btn btn-primary"
            :disabled="!createName.trim() || createLoading"
            @click="handleCreate"
          >{{ createLoading ? '创建中...' : '创建' }}</button>
        </div>
      </div>
    </div>

    <!-- Delete Confirmation -->
    <div v-if="deleteTarget" class="dialog-overlay" @click.self="deleteTarget = null">
      <div class="dialog-box dialog-confirm">
        <div class="dialog-header">
          <h3>删除 API Key</h3>
          <button class="dialog-close" @click="deleteTarget = null">&times;</button>
        </div>
        <div class="dialog-body">
          <p class="confirm-text">
            确定删除 API Key「{{ deleteTarget.name }}」吗？删除后使用该 Key 的应用将无法访问。
          </p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="deleteLoading" @click="handleDelete">
            {{ deleteLoading ? '删除中...' : '确定删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { tenantApi, type ApiKeyItem } from '../../api/tenant'
import { useAuthStore } from '../../stores/auth'
import { copyToClipboard } from '../../utils/clipboard'

const authStore = useAuthStore()

const keys = ref<ApiKeyItem[]>([])
const loading = ref(false)
const newKey = ref('')
const copyState = ref('')

const showCreateDialog = ref(false)
const createName = ref('')
const createLoading = ref(false)

const deleteTarget = ref<ApiKeyItem | null>(null)
const deleteLoading = ref(false)

function formatDate(iso: string): string {
  if (!iso) return '-'
  const d = new Date(iso)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function fetchKeys() {
  loading.value = true
  try {
    const res = await tenantApi.listApiKeys()
    keys.value = res.data
  } catch (e) {
    console.error('Failed to load API keys', e)
  } finally {
    loading.value = false
  }
}

async function copyKey(key: string) {
  await copyToClipboard(key)
  copyState.value = 'new'
  setTimeout(() => { copyState.value = '' }, 2000)
}

async function handleCreate() {
  const name = createName.value.trim()
  if (!name) return
  createLoading.value = true
  try {
    const res = await tenantApi.createApiKey(name)
    const created = res.data
    if (created.api_key) {
      newKey.value = created.api_key
      // If this is the first key or user wants to switch, update auth
      if (!authStore.apiKey || keys.value.length === 0) {
        authStore.apiKey = created.api_key
        localStorage.setItem('lakeon_api_key', created.api_key)
      }
    }
    showCreateDialog.value = false
    createName.value = ''
    fetchKeys()
  } catch (e: any) {
    alert(e.response?.data?.error?.message || '创建失败')
  } finally {
    createLoading.value = false
  }
}

function confirmDelete(key: ApiKeyItem) {
  deleteTarget.value = key
}

async function handleDelete() {
  if (!deleteTarget.value) return
  deleteLoading.value = true
  try {
    await tenantApi.deleteApiKey(deleteTarget.value.id)
    deleteTarget.value = null
    fetchKeys()
  } catch (e: any) {
    alert(e.response?.data?.error?.message || '删除失败')
  } finally {
    deleteLoading.value = false
  }
}

onMounted(fetchKeys)
</script>

<style scoped>
.page-desc {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 20px;
  line-height: 1.6;
}

.new-key-alert {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  padding: 16px 20px;
  margin-bottom: 20px;
}

.alert-title {
  font-size: 14px;
  color: #ad6800;
  margin-bottom: 12px;
  font-weight: 500;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.key-value {
  font-size: 13px;
  color: #191919;
  background: #f2f3f5;
  padding: 6px 12px;
  border-radius: 2px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  letter-spacing: 0.3px;
}

.key-full {
  flex: 1;
  word-break: break-all;
  font-size: 12px;
}

.close-btn {
  background: none;
  border: none;
  font-size: 18px;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.key-count {
  font-size: 13px;
  color: #8a8e99;
}

.td-name {
  font-weight: 500;
}

.td-actions {
  white-space: nowrap;
}

.action-link {
  background: none;
  border: none;
  font-size: 13px;
  cursor: pointer;
  padding: 2px 8px;
}

.action-danger {
  color: #e6393d;
}

.action-danger:hover {
  text-decoration: underline;
}

.form-group {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin-bottom: 6px;
}

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  font-size: 14px;
  outline: none;
  box-sizing: border-box;
}

.form-input:focus {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.form-hint {
  font-size: 12px;
  color: #8a8e99;
  margin-top: 6px;
}
</style>
