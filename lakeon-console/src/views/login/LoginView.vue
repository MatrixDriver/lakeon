<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <router-link to="/" class="login-logo-link">
          <h1 class="login-logo">DBay</h1>
          <p class="login-tagline">数据港湾</p>
        </router-link>
        <p class="login-subtitle">Agent 时代的数据基础设施</p>
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
            class="form-input"
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
          <label class="form-label">用户名 <span class="required">*</span></label>
          <input
            v-model="registerUsername"
            class="form-input"
            :class="{ 'input-error': usernameTaken }"
            placeholder="请输入用户名"
            @blur="checkUsername"
            @input="resetUsernameCheck"
          />
          <p v-if="usernameTaken" class="field-error">该用户名已被注册，请更换一个</p>
          <p v-else-if="usernameAvailable" class="field-ok">用户名可用</p>
        </div>
        <div class="form-group">
          <label class="form-label">密码 <span class="required">*</span></label>
          <input
            v-model="registerPassword"
            type="password"
            class="form-input"
            placeholder="设置登录密码（至少 6 位）"
          />
        </div>
        <div class="form-group">
          <label class="form-label">确认密码 <span class="required">*</span></label>
          <input
            v-model="registerConfirm"
            type="password"
            class="form-input"
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
const registerUsername = ref('')
const registerPassword = ref('')
const registerConfirm = ref('')
const isLoading = ref(false)
const errorMsg = ref('')
const registerSuccess = ref(false)

const usernameTaken = ref(false)
const usernameAvailable = ref(false)

const registerFormValid = computed(() =>
  registerUsername.value.trim() &&
  !usernameTaken.value &&
  registerPassword.value.length >= 6 && registerConfirm.value === registerPassword.value
)

async function checkUsername() {
  const name = registerUsername.value.trim()
  if (!name) {
    usernameTaken.value = false
    usernameAvailable.value = false
    return
  }
  try {
    const res = await tenantApi.checkUsername(name)
    usernameTaken.value = !res.data.available
    usernameAvailable.value = res.data.available
  } catch {
    usernameTaken.value = false
    usernameAvailable.value = false
  }
}

function resetUsernameCheck() {
  usernameTaken.value = false
  usernameAvailable.value = false
}

function focusPassword() {
  loginPwdInput.value?.focus()
}

function switchTab(t: 'login' | 'register') {
  tab.value = t
  errorMsg.value = ''
  registerSuccess.value = false
  usernameTaken.value = false
  usernameAvailable.value = false
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
      username: registerUsername.value.trim(),
      password: registerPassword.value,
    })
    registerSuccess.value = true
    registerUsername.value = ''
    registerPassword.value = ''
    registerConfirm.value = ''
  } catch (e: any) {
    if (e.response?.status === 409) {
      usernameTaken.value = true
      errorMsg.value = '该用户名已被注册，请更换一个'
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
  background: var(--pub-bg);
}

.login-card {
  width: 420px;
  background: var(--pub-surface);
  border-radius: 16px;
  border: 1px solid var(--pub-border);
  padding: 48px 40px 36px;
}

.login-header {
  text-align: center;
  margin-bottom: 28px;
}

.login-logo-link {
  text-decoration: none;
  display: block;
}

.login-logo {
  font-size: 36px;
  font-weight: 800;
  color: var(--pub-primary);
  margin-bottom: 2px;
  letter-spacing: 1px;
}

.login-tagline {
  font-size: 15px;
  color: var(--pub-text);
  font-weight: 500;
  margin-bottom: 6px;
}

.login-subtitle {
  font-size: 13px;
  color: var(--pub-text-3);
}

.login-tabs {
  display: flex;
  border-bottom: 1px solid var(--pub-border);
  margin-bottom: 24px;
}

.login-tab {
  flex: 1;
  background: none;
  border: none;
  padding: 10px 0;
  font-size: 15px;
  color: var(--pub-text-2);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.login-tab.active {
  color: var(--pub-primary);
  border-bottom-color: var(--pub-primary);
  font-weight: 600;
}

.login-tab:hover:not(.active) {
  color: var(--pub-text);
}

.form-group {
  margin-bottom: 18px;
}

.form-label {
  display: block;
  font-size: 14px;
  color: var(--pub-text);
  margin-bottom: 6px;
  font-weight: 500;
}

.required {
  color: #e6393d;
}

.input-wrapper {
  display: flex;
  align-items: center;
  border: 1px solid var(--pub-border);
  border-radius: 8px;
  overflow: hidden;
  transition: border-color 0.2s;
  background: var(--pub-surface);
}

.input-wrapper:focus-within {
  border-color: var(--pub-primary);
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.form-input {
  width: 100%;
  border: 1px solid var(--pub-border);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 14px;
  color: var(--pub-text);
  background: var(--pub-surface);
  outline: none;
  transition: border-color 0.2s;
}

.form-input:focus {
  border-color: var(--pub-primary);
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.input-wrapper .form-input {
  border: none;
  border-radius: 0;
  box-shadow: none;
}

.form-input::placeholder {
  color: var(--pub-text-4, #aaa);
}

.toggle-btn {
  background: none;
  border: none;
  border-left: 1px solid var(--pub-border);
  padding: 10px 12px;
  font-size: 13px;
  color: var(--pub-primary);
  cursor: pointer;
  white-space: nowrap;
}

.toggle-btn:hover {
  background: var(--pub-hover);
}

.input-error {
  border-color: #ff4d4f !important;
}

.field-error {
  color: #ff4d4f;
  font-size: 12px;
  margin-top: 4px;
}

.field-ok {
  color: #52c41a;
  font-size: 12px;
  margin-top: 4px;
}

.error-msg {
  color: #ff4d4f;
  font-size: 13px;
  margin-bottom: 16px;
  padding: 8px 12px;
  background: rgba(255, 77, 79, 0.06);
  border: 1px solid rgba(255, 77, 79, 0.2);
  border-radius: 8px;
}

.login-btn {
  width: 100%;
  height: 42px;
  background: var(--pub-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: opacity 0.15s;
}

.login-btn:hover:not(:disabled) {
  opacity: 0.9;
}

.login-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
  background: rgba(82, 196, 26, 0.06);
  border: 1px solid rgba(82, 196, 26, 0.2);
  border-radius: 8px;
  padding: 10px 16px;
  font-size: 14px;
  color: #389e0d;
}

.login-footer {
  text-align: center;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--pub-border);
}

.login-footer p {
  font-size: 13px;
  color: var(--pub-text-3);
}

@media (max-width: 480px) {
  .login-card {
    width: 100%;
    min-height: 100vh;
    border-radius: 0;
    border: none;
    padding: 40px 24px 32px;
  }

  .login-page {
    align-items: stretch;
  }

  .login-logo {
    font-size: 30px;
  }

  .login-btn {
    height: 46px;
    font-size: 16px;
  }

  .form-input {
    padding: 12px;
    font-size: 16px;
  }

  .toggle-btn {
    padding: 12px;
  }
}
</style>
