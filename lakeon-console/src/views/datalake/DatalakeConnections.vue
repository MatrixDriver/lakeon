<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">数据源连接</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="showCreateModal = true">新建连接</button>
      </div>
    </div>

    <p class="page-desc">通过华为云 IAM 委托 (Agency) 安全授权 DBay 读取您的 OBS 桶数据，用于数据湖作业和生产线。</p>

    <!-- Connection list (table) -->
    <div v-if="connections.length > 0" class="table-wrapper" style="margin-top: 20px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>OBS 桶</th>
            <th>基础路径</th>
            <th>状态</th>
            <th>上次测试</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="conn in connections" :key="conn.id">
            <td>
              <span class="conn-name">{{ conn.name }}</span>
              <div class="conn-id-hint">{{ conn.id }}</div>
            </td>
            <td style="color: #64748b;">{{ conn.bucket }}</td>
            <td style="color: #64748b;">{{ conn.base_path || '/' }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + statusColor(conn.status)"></span>
              {{ statusText(conn.status) }}
            </td>
            <td style="color: #999;">{{ formatTime(conn.last_tested_at) }}</td>
            <td style="color: #999;">{{ formatTime(conn.created_at) }}</td>
            <td>
              <button
                class="btn btn-text btn-small"
                :disabled="testing === conn.id"
                @click="handleTest(conn.id)"
              >{{ testing === conn.id ? '测试中...' : '测试' }}</button>
              <button
                class="btn btn-text btn-small"
                @click="handleBrowse(conn)"
              >浏览</button>
              <button
                class="btn btn-text btn-small btn-danger-text"
                @click="handleDelete(conn.id, conn.name)"
              >删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-if="connections.length === 0 && !loading" class="empty-state" style="margin-top: 64px;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
        <polyline points="3.27 6.96 12 12.01 20.73 6.96"/>
        <line x1="12" y1="22.08" x2="12" y2="12"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">还没有数据源连接</p>
      <p style="color: #999; font-size: 13px;">通过华为云 IAM 委托安全地连接您的 OBS 桶</p>
      <button class="btn btn-primary" style="margin-top: 16px;" @click="showCreateModal = true">新建连接</button>
    </div>

    <div v-if="loading" style="text-align: center; padding: 40px; color: #999;">加载中...</div>

    <!-- Create connection modal -->
    <div v-if="showCreateModal" class="modal-overlay" @click.self="showCreateModal = false">
      <div class="modal-box" style="max-width: 620px;">
        <h2 class="modal-title">新建数据源连接</h2>

        <!-- Step indicator -->
        <div class="steps-bar">
          <div class="step" :class="{ active: createStep === 1, done: createStep > 1 }" @click="createStep = 1">
            <span class="step-num">1</span> 创建委托
          </div>
          <div class="step-arrow">&rarr;</div>
          <div class="step" :class="{ active: createStep === 2 }">
            <span class="step-num">2</span> 填写连接信息
          </div>
        </div>

        <!-- Step 1: Guide to create agency -->
        <div v-if="createStep === 1" class="step-content">
          <p style="color: #555; line-height: 1.7; margin-bottom: 16px;">
            请在华为云 IAM 控制台为 DBay 创建一个委托（Agency），允许 DBay 以只读方式访问您的 OBS 桶。
          </p>

          <div class="info-card">
            <div class="info-label">DBay 华为云账号名（被委托方）</div>
            <div class="info-value copyable" @click="copyText(platformAccountName)">
              <code>{{ platformAccountName || '(未配置)' }}</code>
              <span class="copy-hint">点击复制</span>
            </div>
          </div>

          <div class="guide-steps">
            <div class="guide-step">
              <span class="guide-num">1</span>
              登录华为云控制台 &rarr; 统一身份认证 (IAM) &rarr; 委托
            </div>
            <div class="guide-step">
              <span class="guide-num">2</span>
              点击"创建委托"，委托类型选择"普通账号"
            </div>
            <div class="guide-step">
              <span class="guide-num">3</span>
              被委托方选择"其他账号"，账号名填写上方的 DBay 账号名
            </div>
            <div class="guide-step">
              <span class="guide-num">4</span>
              权限选择 "OBS ReadOnlyAccess" （或自定义 OBS 桶策略）
            </div>
            <div class="guide-step">
              <span class="guide-num">5</span>
              完成创建后，记下委托名称，进入下一步
            </div>
          </div>

          <div style="text-align: right; margin-top: 24px;">
            <button class="btn btn-primary" @click="createStep = 2">下一步</button>
          </div>
        </div>

        <!-- Step 2: Fill connection details -->
        <div v-if="createStep === 2" class="step-content">
          <div class="form-group">
            <label>连接名称</label>
            <input v-model="form.name" class="form-input" placeholder="例如：我的训练数据" />
          </div>
          <div class="form-group">
            <label>您的华为云域名</label>
            <input v-model="form.domain_name" class="form-input" placeholder="您的华为云主账号域名" />
          </div>
          <div class="form-group">
            <label>委托名称 (Agency Name)</label>
            <input v-model="form.agency_name" class="form-input" placeholder="在上一步中创建的委托名称" />
          </div>
          <div class="form-group">
            <label>OBS 桶名</label>
            <input v-model="form.bucket" class="form-input" placeholder="obs-bucket-name" />
          </div>
          <div class="form-group">
            <label>基础路径（可选）</label>
            <input v-model="form.base_path" class="form-input" placeholder="data/training/ （留空表示桶根目录）" />
          </div>
          <div class="form-group">
            <label>OBS Endpoint（可选）</label>
            <input v-model="form.obs_endpoint" class="form-input" placeholder="https://obs.cn-north-4.myhuaweicloud.com" />
          </div>

          <div v-if="createError" class="form-error">{{ createError }}</div>

          <div style="display: flex; justify-content: space-between; margin-top: 24px;">
            <button class="btn btn-secondary" @click="createStep = 1">上一步</button>
            <div style="display: flex; gap: 8px;">
              <button class="btn btn-secondary" @click="showCreateModal = false">取消</button>
              <button class="btn btn-primary" :disabled="creating" @click="handleCreate">
                {{ creating ? '保存中...' : '保存' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Browse modal -->
    <div v-if="browseModal" class="modal-overlay" @click.self="browseModal = false">
      <div class="modal-box" style="max-width: 700px;">
        <h2 class="modal-title">浏览文件 - {{ browseConnName }}</h2>

        <div class="browse-path">
          <span style="color: #999;">路径:</span>
          <code>{{ browsePath || '/' }}</code>
          <button v-if="browsePath" class="btn btn-text btn-small" @click="browseUp">上级目录</button>
        </div>

        <div v-if="browseLoading" style="text-align: center; padding: 30px; color: #999;">加载中...</div>

        <div v-else-if="browseItems.length === 0" style="text-align: center; padding: 30px; color: #999;">
          此路径下没有文件
        </div>

        <div v-else class="browse-list">
          <div
            v-for="item in browseItems"
            :key="item.key"
            class="browse-item"
            :class="{ clickable: item.type === 'directory' }"
            @click="item.type === 'directory' ? browseInto(item.key) : null"
          >
            <span class="browse-icon">{{ item.type === 'directory' ? '\uD83D\uDCC1' : '\uD83D\uDCC4' }}</span>
            <span class="browse-name">{{ item.name }}</span>
            <span v-if="item.size != null" class="browse-size">{{ formatFileSize(item.size) }}</span>
          </div>
        </div>

        <div style="text-align: right; margin-top: 16px;">
          <button class="btn btn-secondary" @click="browseModal = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import client from '../../api/client'
import type { ObsConnection, ObsBrowseItem } from '../../api/obs-connection'

const connections = ref<ObsConnection[]>([])
const loading = ref(false)
const testing = ref<string | null>(null)
const showCreateModal = ref(false)
const createStep = ref(1)
const creating = ref(false)
const createError = ref('')
const platformAccountId = ref('')
const platformAccountName = ref('')

const form = ref({
  name: '',
  domain_name: '',
  agency_name: '',
  bucket: '',
  base_path: '',
  obs_endpoint: '',
})

// Browse state
const browseModal = ref(false)
const browseConnId = ref('')
const browseConnName = ref('')
const browsePath = ref('')
const browseItems = ref<ObsBrowseItem[]>([])
const browseLoading = ref(false)

function statusColor(status: string) {
  if (status === 'ACTIVE') return 'green'
  if (status === 'FAILED') return 'red'
  if (status === 'TESTING') return 'blue'
  return 'gray'
}

function statusText(status: string) {
  const map: Record<string, string> = {
    ACTIVE: '正常',
    FAILED: '失败',
    TESTING: '测试中',
  }
  return map[status] || status
}

function formatTime(t: string | null) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

async function fetchConnections() {
  loading.value = true
  try {
    const resp = await client.get('/obs-connections')
    connections.value = resp.data?.data ?? resp.data ?? []
  } catch (e: any) {
    console.error('Failed to load connections:', e)
  } finally {
    loading.value = false
  }
}

async function fetchPlatformInfo() {
  try {
    const resp = await client.get('/obs-connections/platform-info')
    const data = resp.data?.data ?? resp.data
    platformAccountId.value = data?.hwcloud_account_id || ''
    platformAccountName.value = data?.hwcloud_account_name || ''
  } catch {
    // non-critical
  }
}

function copyText(text: string) {
  if (text) {
    navigator.clipboard.writeText(text)
  }
}

async function handleCreate() {
  createError.value = ''
  if (!form.value.name.trim()) { createError.value = '请填写连接名称'; return }
  if (!form.value.domain_name.trim()) { createError.value = '请填写华为云域名'; return }
  if (!form.value.agency_name.trim()) { createError.value = '请填写委托名称'; return }
  if (!form.value.bucket.trim()) { createError.value = '请填写 OBS 桶名'; return }

  creating.value = true
  try {
    await client.post('/obs-connections', {
      name: form.value.name.trim(),
      domain_name: form.value.domain_name.trim(),
      agency_name: form.value.agency_name.trim(),
      bucket: form.value.bucket.trim(),
      base_path: form.value.base_path.trim() || undefined,
      obs_endpoint: form.value.obs_endpoint.trim() || undefined,
    })
    showCreateModal.value = false
    resetForm()
    await fetchConnections()
  } catch (e: any) {
    createError.value = e.response?.data?.error?.message || e.message || '创建失败'
  } finally {
    creating.value = false
  }
}

function resetForm() {
  form.value = { name: '', domain_name: '', agency_name: '', bucket: '', base_path: '', obs_endpoint: '' }
  createStep.value = 1
  createError.value = ''
}

async function handleTest(id: string) {
  testing.value = id
  try {
    await client.post(`/obs-connections/${id}/test`)
    await fetchConnections()
  } catch (e: any) {
    alert('测试失败: ' + (e.response?.data?.error?.message || e.message))
    await fetchConnections()
  } finally {
    testing.value = null
  }
}

async function handleDelete(id: string, name: string) {
  if (!confirm(`确认删除连接"${name}"？此操作不可撤销。`)) return
  try {
    await client.delete(`/obs-connections/${id}`)
    await fetchConnections()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function handleBrowse(conn: ObsConnection) {
  browseConnId.value = conn.id
  browseConnName.value = conn.name
  browsePath.value = conn.base_path || ''
  browseModal.value = true
  await loadBrowse()
}

async function loadBrowse() {
  browseLoading.value = true
  browseItems.value = []
  try {
    const params: Record<string, string> = {}
    if (browsePath.value) params.path = browsePath.value
    const resp = await client.get(`/obs-connections/${browseConnId.value}/browse`, { params })
    browseItems.value = resp.data?.data ?? resp.data ?? []
  } catch (e: any) {
    console.error('Browse failed:', e)
  } finally {
    browseLoading.value = false
  }
}

function browseInto(key: string) {
  browsePath.value = key
  loadBrowse()
}

function browseUp() {
  if (!browsePath.value) return
  let p = browsePath.value
  if (p.endsWith('/')) p = p.slice(0, -1)
  const idx = p.lastIndexOf('/')
  browsePath.value = idx >= 0 ? p.substring(0, idx + 1) : ''
  loadBrowse()
}

onMounted(() => {
  fetchConnections()
  fetchPlatformInfo()
})
</script>

<style scoped>
.page-desc {
  color: #888;
  font-size: 14px;
  margin-top: 4px;
}

.conn-name {
  font-weight: 500;
  color: #333;
}

.conn-id-hint {
  font-size: 11px;
  color: #bbb;
  margin-top: 2px;
  font-family: monospace;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.dot-green { background-color: var(--c-success); }
.dot-blue { background-color: var(--c-primary); }
.dot-red { background-color: var(--cs-severe); }
.dot-gray { background-color: #d9d9d9; }

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-box {
  background: #fff;
  border-radius: 12px;
  padding: 28px 32px;
  width: 90vw;
  max-height: 85vh;
  overflow-y: auto;
  box-shadow: 0 12px 40px rgba(0,0,0,.15);
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
  margin: 0 0 20px 0;
}

/* Steps */
.steps-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #eee;
}

.step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #999;
  cursor: pointer;
}

.step.active {
  color: #9a5b25;
  font-weight: 500;
}

.step.done {
  color: #386b47;
}

.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #f0f0f0;
  font-size: 12px;
  font-weight: 600;
}

.step.active .step-num {
  background: #9a5b25;
  color: #fff;
}

.step.done .step-num {
  background: #386b47;
  color: #fff;
}

.step-arrow {
  color: #ccc;
}

.step-content {
  min-height: 200px;
}

/* Info card */
.info-card {
  background: #faf8f5;
  border: 1px solid #e8e0d6;
  border-radius: 8px;
  padding: 14px 18px;
  margin-bottom: 20px;
}

.info-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 6px;
}

.info-value {
  font-size: 16px;
  font-weight: 500;
}

.info-value.copyable {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-value.copyable:hover .copy-hint {
  opacity: 1;
}

.copy-hint {
  font-size: 11px;
  color: #9a5b25;
  opacity: 0;
  transition: opacity 0.15s;
}

/* Guide steps */
.guide-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.guide-step {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 14px;
  color: #555;
  line-height: 1.6;
}

.guide-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #f0ebe4;
  color: #9a5b25;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

/* Form */
.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: #555;
  margin-bottom: 6px;
}

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #d5d0ca;
  border-radius: 6px;
  font-size: 14px;
  background: #fff;
  transition: border-color 0.15s;
  box-sizing: border-box;
}

.form-input:focus {
  outline: none;
  border-color: #c67d3a;
  box-shadow: 0 0 0 2px rgba(198,125,58,.12);
}

.form-error {
  color: #e6393d;
  font-size: 13px;
  margin-top: 8px;
}

/* Browse */
.browse-path {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 13px;
}

.browse-path code {
  background: #f5f5f5;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}

.browse-list {
  max-height: 400px;
  overflow-y: auto;
  border: 1px solid #eee;
  border-radius: 8px;
}

.browse-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-bottom: 1px solid #f5f5f5;
  font-size: 14px;
}

.browse-item:last-child {
  border-bottom: none;
}

.browse-item.clickable {
  cursor: pointer;
}

.browse-item.clickable:hover {
  background: #faf8f5;
}

.browse-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.browse-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.browse-size {
  color: #999;
  font-size: 12px;
  flex-shrink: 0;
}
</style>
