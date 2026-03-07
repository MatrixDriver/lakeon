<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <h1 class="login-logo">Lakeon</h1>
        <p class="login-subtitle">Serverless PostgreSQL Console</p>
      </div>

      <!-- Tab Switch -->
      <div class="login-tabs">
        <button
          class="login-tab"
          :class="{ active: tab === 'login' }"
          @click="switchTab('login')"
        >登录</button>
        <button
          class="login-tab"
          :class="{ active: tab === 'register' }"
          @click="switchTab('register')"
        >注册</button>
      </div>

      <!-- Login Form -->
      <div class="login-form" v-if="tab === 'login'">
        <div class="form-group">
          <label class="form-label">用户名</label>
          <input
            v-model="loginUsername"
            class="form-input form-input-full"
            placeholder="请输入用户名"
            @keyup.enter="focusPassword"
          />
        </div>
        <div class="form-group">
          <label class="form-label">密码</label>
          <div class="input-wrapper">
            <input
              ref="loginPwdInput"
              :type="showPwd ? 'text' : 'password'"
              v-model="loginPassword"
              class="form-input"
              placeholder="请输入密码"
              @keyup.enter="handleLogin"
            />
            <button class="toggle-btn" @click="showPwd = !showPwd" type="button">
              {{ showPwd ? '隐藏' : '显示' }}
            </button>
          </div>
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button
          class="login-btn"
          :class="{ loading: isLoading }"
          :disabled="isLoading || !loginUsername.trim() || !loginPassword"
          @click="handleLogin"
        >
          <span v-if="isLoading" class="spinner"></span>
          {{ isLoading ? '登录中...' : '登 录' }}
        </button>
      </div>

      <!-- Register Form -->
      <div class="login-form" v-if="tab === 'register'">
        <div class="form-group">
          <label class="form-label">租户名称 <span class="required">*</span></label>
          <input
            v-model="registerName"
            class="form-input form-input-full"
            placeholder="请输入租户名称（如公司名或项目名）"
          />
        </div>
        <div class="form-group">
          <label class="form-label">用户名 <span class="required">*</span></label>
          <input
            v-model="registerUsername"
            class="form-input form-input-full"
            placeholder="用于登录的用户名"
          />
        </div>
        <div class="form-group">
          <label class="form-label">密码 <span class="required">*</span></label>
          <input
            v-model="registerPassword"
            type="password"
            class="form-input form-input-full"
            placeholder="设置登录密码（至少 6 位）"
          />
        </div>
        <div class="form-group">
          <label class="form-label">确认密码 <span class="required">*</span></label>
          <input
            v-model="registerConfirm"
            type="password"
            class="form-input form-input-full"
            placeholder="再次输入密码"
            @keyup.enter="handleRegister"
          />
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button
          class="login-btn"
          :class="{ loading: isLoading }"
          :disabled="isLoading || !registerFormValid"
          @click="handleRegister"
        >
          <span v-if="isLoading" class="spinner"></span>
          {{ isLoading ? '注册中...' : '注 册' }}
        </button>
      </div>

      <!-- Registration Success -->
      <div v-if="registerSuccess" class="register-success">
        <div class="success-alert">
          注册成功！现在可以使用用户名和密码登录。
        </div>
        <button class="login-btn" style="margin-top: 12px" @click="goToLogin">
          前往登录
        </button>
      </div>

      <div class="login-footer">
        <p v-if="tab === 'login'">没有账号？点击上方"注册"创建租户</p>
        <p v-else>已有账号？点击上方"登录"</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { tenantApi } from '../../api/tenant'

const router = useRouter()
const authStore = useAuthStore()

const tab = ref<'login' | 'register'>('login')
const loginUsername = ref('')
const loginPassword = ref('')
const showPwd = ref(false)
const loginPwdInput = ref<HTMLInputElement | null>(null)
const registerName = ref('')
const registerUsername = ref('')
const registerPassword = ref('')
const registerConfirm = ref('')
const isLoading = ref(false)
const errorMsg = ref('')
const registerSuccess = ref(false)

const registerFormValid = computed(() =>
  registerName.value.trim() && registerUsername.value.trim() &&
  registerPassword.value.length >= 6 && registerConfirm.value === registerPassword.value
)

function focusPassword() {
  loginPwdInput.value?.focus()
}

function switchTab(t: 'login' | 'register') {
  tab.value = t
  errorMsg.value = ''
  registerSuccess.value = false
}

async function handleLogin() {
  const username = loginUsername.value.trim()
  if (!username || !loginPassword.value) return

  errorMsg.value = ''
  isLoading.value = true

  try {
    const result = await authStore.login(username, loginPassword.value)
    if (result.ok) {
      router.push('/dashboard')
    } else {
      errorMsg.value = result.error || '登录失败'
    }
  } finally {
    isLoading.value = false
  }
}

async function handleRegister() {
  if (!registerFormValid.value) return

  if (registerPassword.value !== registerConfirm.value) {
    errorMsg.value = '两次输入的密码不一致'
    return
  }
  if (registerPassword.value.length < 6) {
    errorMsg.value = '密码长度至少 6 位'
    return
  }

  errorMsg.value = ''
  isLoading.value = true

  try {
    await tenantApi.register({
      name: registerName.value.trim(),
      username: registerUsername.value.trim(),
      password: registerPassword.value,
    })
    registerSuccess.value = true
    registerName.value = ''
    registerUsername.value = ''
    registerPassword.value = ''
    registerConfirm.value = ''
  } catch (e: any) {
    if (e.response?.status === 409) {
      errorMsg.value = '该租户名称或用户名已存在'
    } else {
      errorMsg.value = e.response?.data?.message || '注册失败，请稍后重试'
    }
  } finally {
    isLoading.value = false
  }
}

function goToLogin() {
  registerSuccess.value = false
  tab.value = 'login'
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f5f5f5;
}

.login-card {
  width: 420px;
  background: #fff;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  padding: 48px 40px 36px;
}

.login-header {
  text-align: center;
  margin-bottom: 28px;
}

.login-logo {
  font-size: 32px;
  font-weight: 700;
  color: #e6393d;
  margin-bottom: 8px;
  letter-spacing: 1px;
}

.login-subtitle {
  font-size: 14px;
  color: #999;
}

.login-tabs {
  display: flex;
  border-bottom: 1px solid #e8e8e8;
  margin-bottom: 24px;
}

.login-tab {
  flex: 1;
  background: none;
  border: none;
  padding: 10px 0;
  font-size: 15px;
  color: #575d6c;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.login-tab.active {
  color: #0073e6;
  border-bottom-color: #0073e6;
  font-weight: 600;
}

.login-tab:hover:not(.active) {
  color: #191919;
}

.form-group {
  margin-bottom: 18px;
}

.form-label {
  display: block;
  font-size: 14px;
  color: #333;
  margin-bottom: 6px;
  font-weight: 500;
}

.required {
  color: #e6393d;
}

.input-wrapper {
  display: flex;
  align-items: center;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  overflow: hidden;
  transition: border-color 0.2s;
}

.input-wrapper:focus-within {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.form-input {
  flex: 1;
  border: none;
  outline: none;
  padding: 10px 12px;
  font-size: 14px;
  color: #333;
  background: transparent;
}

.form-input-full {
  width: 100%;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
  transition: border-color 0.2s;
}

.form-input-full:focus {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.form-input::placeholder {
  color: #bfbfbf;
}

.toggle-btn {
  background: none;
  border: none;
  border-left: 1px solid #d9d9d9;
  padding: 10px 12px;
  font-size: 13px;
  color: #0073e6;
  cursor: pointer;
  white-space: nowrap;
}

.toggle-btn:hover {
  background-color: #f5f7fa;
}

.error-msg {
  color: #ff4d4f;
  font-size: 13px;
  margin-bottom: 16px;
  padding: 8px 12px;
  background: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 2px;
}

.login-btn {
  width: 100%;
  height: 40px;
  background-color: #e6393d;
  color: #fff;
  border: none;
  border-radius: 2px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: background-color 0.2s;
}

.login-btn:hover:not(:disabled) {
  background-color: #cc2f33;
}

.login-btn:disabled {
  background-color: #f5a3a5;
  cursor: not-allowed;
}

.login-btn.loading {
  background-color: #d43438;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.register-success {
  margin-top: 16px;
}

.success-alert {
  background: #f6ffed;
  border: 1px solid #b7eb8f;
  border-radius: 2px;
  padding: 10px 16px;
  font-size: 14px;
  color: #389e0d;
}

.login-footer {
  text-align: center;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #e8e8e8;
}

.login-footer p {
  font-size: 13px;
  color: #999;
}
</style>
