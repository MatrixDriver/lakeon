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
          <label class="form-label">API Key</label>
          <div class="input-wrapper">
            <input
              :type="showKey ? 'text' : 'password'"
              v-model="apiKeyInput"
              class="form-input"
              placeholder="请输入 API Key (lk_...)"
              @keyup.enter="handleLogin"
            />
            <button class="toggle-btn" @click="showKey = !showKey" type="button">
              {{ showKey ? '隐藏' : '显示' }}
            </button>
          </div>
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button
          class="login-btn"
          :class="{ loading: isLoading }"
          :disabled="isLoading || !apiKeyInput.trim()"
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
            @keyup.enter="handleRegister"
          />
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button
          class="login-btn"
          :class="{ loading: isLoading }"
          :disabled="isLoading || !registerName.trim()"
          @click="handleRegister"
        >
          <span v-if="isLoading" class="spinner"></span>
          {{ isLoading ? '注册中...' : '注 册' }}
        </button>
      </div>

      <!-- Registration Success -->
      <div v-if="registeredKey" class="register-success">
        <div class="success-alert">
          注册成功！请立即复制您的 API Key，此 Key 仅显示一次。
        </div>
        <div class="key-row">
          <code class="key-value">{{ registeredKey }}</code>
          <button class="copy-btn" @click="handleCopyKey">{{ copyText }}</button>
        </div>
        <button class="login-btn" style="margin-top: 16px" @click="useKeyToLogin">
          使用此 Key 登录
        </button>
      </div>

      <div class="login-footer">
        <p v-if="tab === 'login'">没有账号？点击上方"注册"创建租户</p>
        <p v-else>已有 API Key？点击上方"登录"</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { tenantApi } from '../../api/tenant'
import { copyToClipboard } from '../../utils/clipboard'

const router = useRouter()
const authStore = useAuthStore()

const tab = ref<'login' | 'register'>('login')
const apiKeyInput = ref('')
const registerName = ref('')
const showKey = ref(false)
const isLoading = ref(false)
const errorMsg = ref('')
const registeredKey = ref('')
const copyText = ref('复制')

function switchTab(t: 'login' | 'register') {
  tab.value = t
  errorMsg.value = ''
  registeredKey.value = ''
}

async function handleLogin() {
  const key = apiKeyInput.value.trim()
  if (!key) return

  errorMsg.value = ''
  isLoading.value = true

  try {
    const ok = await authStore.login(key)
    if (ok) {
      router.push('/dashboard')
    } else {
      errorMsg.value = 'API Key 无效，请检查后重试'
    }
  } catch {
    errorMsg.value = '网络错误，请稍后重试'
  } finally {
    isLoading.value = false
  }
}

async function handleRegister() {
  const name = registerName.value.trim()
  if (!name) return

  errorMsg.value = ''
  isLoading.value = true

  try {
    const res = await tenantApi.register(name)
    const key = res.data.api_key
    if (key) {
      registeredKey.value = key
      registerName.value = ''
    }
  } catch (e: any) {
    if (e.response?.status === 409) {
      errorMsg.value = '该租户名称已存在，请换一个名称'
    } else {
      errorMsg.value = '注册失败，请稍后重试'
    }
  } finally {
    isLoading.value = false
  }
}

async function handleCopyKey() {
  await copyToClipboard(registeredKey.value)
  copyText.value = '已复制'
  setTimeout(() => { copyText.value = '复制' }, 2000)
}

async function useKeyToLogin() {
  apiKeyInput.value = registeredKey.value
  registeredKey.value = ''
  tab.value = 'login'
  await handleLogin()
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
  margin-bottom: 20px;
}

.form-label {
  display: block;
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
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
  margin-bottom: 12px;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.key-value {
  flex: 1;
  font-size: 13px;
  color: #191919;
  background: #f2f3f5;
  padding: 8px 12px;
  border-radius: 2px;
  word-break: break-all;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.copy-btn {
  background: none;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  padding: 6px 12px;
  font-size: 12px;
  color: #0073e6;
  cursor: pointer;
  white-space: nowrap;
}

.copy-btn:hover {
  border-color: #0073e6;
  background-color: #f2f6fc;
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
