<template>
  <div class="invite-codes">
    <div class="page-header">
      <h2>邀请码管理</h2>
      <button class="create-btn" @click="showCreate = true">生成邀请码</button>
    </div>

    <!-- Create Dialog -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog">
        <h3>生成邀请码</h3>
        <div class="form-group">
          <label>使用次数限制</label>
          <div class="radio-group">
            <label class="radio-label">
              <input type="radio" v-model="createForm.unlimited" :value="true" />
              <span>不限次数</span>
            </label>
            <label class="radio-label">
              <input type="radio" v-model="createForm.unlimited" :value="false" />
              <span>限定次数</span>
            </label>
          </div>
          <input
            v-if="!createForm.unlimited"
            type="number"
            v-model.number="createForm.maxUses"
            min="1"
            placeholder="最大使用次数"
            class="form-input"
          />
        </div>
        <div class="dialog-actions">
          <button class="btn-secondary" @click="showCreate = false">取消</button>
          <button class="btn-primary" @click="handleCreate" :disabled="creating">
            {{ creating ? '生成中...' : '生成' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Codes Table -->
    <div class="table-container">
      <table class="data-table">
        <thead>
          <tr>
            <th>邀请码</th>
            <th>使用次数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>过期时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="code in codes" :key="code.code">
            <td>
              <code class="code-text">{{ code.code }}</code>
              <button class="copy-btn" @click="copyCode(code.code)" :title="'复制'">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
                </svg>
              </button>
            </td>
            <td>
              <span v-if="code.max_uses < 0">{{ code.used_count }} / 不限</span>
              <span v-else>{{ code.used_count }} / {{ code.max_uses }}</span>
            </td>
            <td>
              <span class="status-tag" :class="code.valid ? 'tag-active' : 'tag-expired'">
                {{ code.valid ? '有效' : '已失效' }}
              </span>
            </td>
            <td>{{ formatTime(code.created_at) }}</td>
            <td>{{ code.expires_at ? formatTime(code.expires_at) : '永不过期' }}</td>
            <td>
              <button class="delete-btn" @click="handleDelete(code.code)">删除</button>
            </td>
          </tr>
          <tr v-if="codes.length === 0 && !loading">
            <td colspan="6" class="empty-cell">暂无邀请码</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

interface InviteCode {
  code: string
  max_uses: number
  used_count: number
  valid: boolean
  created_at: string
  expires_at: string | null
  created_by: string
}

const codes = ref<InviteCode[]>([])
const loading = ref(false)
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ unlimited: true, maxUses: 5 })

async function loadCodes() {
  loading.value = true
  try {
    const { data } = await adminApi.listInviteCodes()
    codes.value = data
  } catch (e) {
    console.error('Failed to load invite codes', e)
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  creating.value = true
  try {
    const body: Record<string, number> = {}
    if (!createForm.value.unlimited) {
      body.max_uses = createForm.value.maxUses
    }
    await adminApi.createInviteCode(body)
    showCreate.value = false
    createForm.value = { unlimited: true, maxUses: 5 }
    await loadCodes()
  } catch (e) {
    console.error('Failed to create invite code', e)
  } finally {
    creating.value = false
  }
}

async function handleDelete(code: string) {
  if (!confirm(`确定删除邀请码 ${code}？`)) return
  try {
    await adminApi.deleteInviteCode(code)
    await loadCodes()
  } catch (e) {
    console.error('Failed to delete invite code', e)
  }
}

function copyCode(code: string) {
  navigator.clipboard.writeText(code)
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

onMounted(loadCodes)
</script>

<style scoped>
.invite-codes {
  max-width: 960px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-header h2 {
  font-size: 20px;
  font-weight: 700;
  color: #2c3e50;
  margin: 0;
}

.create-btn {
  padding: 8px 20px;
  background: #9a5b25;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.create-btn:hover {
  background: #7d4a1e;
}

/* Dialog */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog {
  background: #fff;
  border-radius: 12px;
  padding: 28px;
  width: 400px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.dialog h3 {
  margin: 0 0 20px;
  font-size: 17px;
  font-weight: 600;
  color: #2c3e50;
}

.form-group {
  margin-bottom: 20px;
}

.form-group > label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: #4a5568;
  margin-bottom: 8px;
}

.radio-group {
  display: flex;
  gap: 20px;
  margin-bottom: 8px;
}

.radio-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #333;
  cursor: pointer;
}

.form-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 13px;
  box-sizing: border-box;
}

.form-input:focus {
  outline: none;
  border-color: #9a5b25;
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.btn-secondary {
  padding: 8px 16px;
  background: #fff;
  color: #333;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.btn-secondary:hover {
  background: #f5f5f5;
}

.btn-primary {
  padding: 8px 20px;
  background: #9a5b25;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.btn-primary:hover:not(:disabled) {
  background: #7d4a1e;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Table */
.table-container {
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  overflow: hidden;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-table th {
  background: #f9fafb;
  padding: 10px 16px;
  text-align: left;
  font-weight: 600;
  color: #374151;
  border-bottom: 1px solid #e5e5e5;
}

.data-table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f3f4f6;
  color: #333;
}

.data-table tr:last-child td {
  border-bottom: none;
}

.data-table tr:hover {
  background: #faf8f5;
}

.code-text {
  font-family: 'SF Mono', 'Consolas', monospace;
  font-size: 15px;
  font-weight: 600;
  color: #9a5b25;
  letter-spacing: 2px;
  background: #faf5ee;
  padding: 3px 8px;
  border-radius: 4px;
}

.copy-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: #9ca3af;
  padding: 2px 4px;
  margin-left: 6px;
  vertical-align: middle;
}

.copy-btn:hover {
  color: #9a5b25;
}

.status-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.tag-active {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}

.tag-expired {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}

.delete-btn {
  background: none;
  border: none;
  color: #9ca3af;
  font-size: 13px;
  cursor: pointer;
}

.delete-btn:hover {
  color: #dc2626;
}

.empty-cell {
  text-align: center;
  color: #9ca3af;
  padding: 32px !important;
}
</style>
