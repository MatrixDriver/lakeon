<template>
  <div class="page-account">
    <div class="page-header">
      <h1 class="page-title">账户设置</h1>
    </div>

    <p class="page-desc">管理您的账户信息和安全设置。</p>

    <!-- Profile Section -->
    <div class="section-card">
      <div class="section-header">
        <h3>基本信息</h3>
      </div>
      <div class="section-body">
        <div class="info-row">
          <span class="info-label">用户名</span>
          <span class="info-value">{{ authStore.tenantName }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">账户 ID</span>
          <span class="info-value mono">{{ authStore.tenantId }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">显示名称</span>
          <span class="info-value" v-if="!editingName">
            {{ displayName }}
            <button class="action-link" @click="startEditName">修改</button>
          </span>
          <span class="info-value" v-else>
            <input v-model="newName" class="form-input inline-input" maxlength="64" @keyup.enter="saveName" />
            <button class="btn btn-primary btn-small" :disabled="nameLoading" @click="saveName">保存</button>
            <button class="btn btn-default btn-small" @click="editingName = false">取消</button>
          </span>
        </div>
      </div>
    </div>

    <!-- Change Password Section -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>修改密码</h3>
      </div>
      <div class="section-body">
        <div class="form-group">
          <label class="form-label">当前密码</label>
          <input v-model="currentPassword" type="password" class="form-input" autocomplete="current-password" />
        </div>
        <div class="form-group">
          <label class="form-label">新密码</label>
          <input v-model="newPassword" type="password" class="form-input" autocomplete="new-password" />
          <p class="form-hint">密码长度不少于 6 位</p>
        </div>
        <div class="form-group">
          <label class="form-label">确认新密码</label>
          <input v-model="confirmPassword" type="password" class="form-input" autocomplete="new-password" @keyup.enter="handleChangePassword" />
        </div>
        <div v-if="pwdError" class="error-msg">{{ pwdError }}</div>
        <div v-if="pwdSuccess" class="success-msg">{{ pwdSuccess }}</div>
        <button
          class="btn btn-primary"
          :disabled="pwdLoading || !currentPassword || !newPassword || !confirmPassword"
          @click="handleChangePassword"
        >{{ pwdLoading ? '修改中...' : '修改密码' }}</button>
      </div>
    </div>

    <!-- Quota Section -->
    <div class="section-card" style="margin-top: 24px;">
      <div class="section-header">
        <h3>账户配额</h3>
      </div>
      <div class="section-body">
        <div class="info-row">
          <span class="info-label">最大数据库数</span>
          <span class="info-value">{{ quota.maxDatabases }}</span>
        </div>
        <div class="info-row">
          <span class="info-label">最大存储空间</span>
          <span class="info-value">{{ quota.maxStorageGb }} GB</span>
        </div>
        <div class="info-row">
          <span class="info-label">最大计算规格</span>
          <span class="info-value">{{ quota.maxComputeCu }} CU</span>
        </div>
        <p class="quota-note">如需调整配额，请联系管理员。</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useAuthStore } from '../../stores/auth'
import client from '../../api/client'

const authStore = useAuthStore()

const displayName = ref('')
const editingName = ref(false)
const newName = ref('')
const nameLoading = ref(false)

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const pwdLoading = ref(false)
const pwdError = ref('')
const pwdSuccess = ref('')

const quota = ref({ maxDatabases: 0, maxStorageGb: 0, maxComputeCu: 0 })

function startEditName() {
  newName.value = displayName.value
  editingName.value = true
}

async function saveName() {
  if (!newName.value.trim()) return
  nameLoading.value = true
  try {
    const res = await client.patch('/account/profile', { name: newName.value.trim() })
    displayName.value = res.data.name
    authStore.setTenant(authStore.tenantId, res.data.name)
    editingName.value = false
  } catch (e: any) {
    alert(e.response?.data?.error?.message || '修改失败')
  } finally {
    nameLoading.value = false
  }
}

async function handleChangePassword() {
  pwdError.value = ''
  pwdSuccess.value = ''
  if (newPassword.value !== confirmPassword.value) {
    pwdError.value = '两次输入的密码不一致'
    return
  }
  if (newPassword.value.length < 6) {
    pwdError.value = '新密码长度不能少于 6 位'
    return
  }
  pwdLoading.value = true
  try {
    await client.post('/account/change-password', {
      current_password: currentPassword.value,
      new_password: newPassword.value,
    })
    pwdSuccess.value = '密码修改成功'
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
  } catch (e: any) {
    pwdError.value = e.response?.data?.error?.message || e.response?.data?.message || '修改失败'
  } finally {
    pwdLoading.value = false
  }
}

async function fetchProfile() {
  try {
    const res = await client.get('/tenants/me')
    displayName.value = res.data.name || ''
    quota.value = {
      maxDatabases: res.data.max_databases ?? 3,
      maxStorageGb: res.data.max_storage_gb ?? 50,
      maxComputeCu: res.data.max_compute_cu ?? 4,
    }
  } catch (e) {
    console.error('Failed to load profile', e)
  }
}

onMounted(fetchProfile)
</script>

<style scoped>
.page-desc {
  font-size: 14px;
  color: #575d6c;
  margin-bottom: 24px;
  line-height: 1.6;
}

.section-header {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.section-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #191919;
}

.section-body {
  padding: 20px;
}

.info-row {
  display: flex;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid #f5f5f5;
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  width: 120px;
  font-size: 14px;
  color: #575d6c;
  flex-shrink: 0;
}

.info-value {
  font-size: 14px;
  color: #191919;
  display: flex;
  align-items: center;
  gap: 8px;
}

.info-value.mono {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
  color: #575d6c;
}

.inline-input {
  width: 200px;
  padding: 4px 8px;
  font-size: 14px;
}

.action-link {
  background: none;
  border: none;
  color: #0073e6;
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

.action-link:hover {
  text-decoration: underline;
}

.form-group {
  margin-bottom: 16px;
  max-width: 360px;
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

.error-msg {
  color: #e6393d;
  font-size: 13px;
  margin-bottom: 12px;
}

.success-msg {
  color: #52c41a;
  font-size: 13px;
  margin-bottom: 12px;
}

.quota-note {
  font-size: 13px;
  color: #8a8e99;
  margin-top: 12px;
}
</style>
