<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <h1 class="login-logo">DBay</h1>
        <p class="login-subtitle">运维控制台</p>
        <p class="login-desc">管理员登录</p>
      </div>

      <div class="login-form">
        <div class="form-group">
          <label class="form-label">Admin Token</label>
          <div class="input-wrapper">
            <input
              :type="showToken ? 'text' : 'password'"
              v-model="tokenInput"
              class="form-input"
              placeholder="请输入管理员 Token"
              @keyup.enter="handleLogin"
            />
            <button class="toggle-btn" @click="showToken = !showToken" type="button">
              {{ showToken ? '隐藏' : '显示' }}
            </button>
          </div>
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button
          class="login-btn"
          :class="{ loading: isLoading }"
          :disabled="isLoading || !tokenInput.trim()"
          @click="handleLogin"
        >
          <span v-if="isLoading" class="spinner"></span>
          {{ isLoading ? '登录中...' : '登 录' }}
        </button>
      </div>

      <div class="login-footer">
        <p>仅限平台管理员使用</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminAuthStore } from '../../stores/auth'

const router = useRouter()
const authStore = useAdminAuthStore()

const tokenInput = ref('')
const showToken = ref(false)
const isLoading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  const token = tokenInput.value.trim()
  if (!token) return

  errorMsg.value = ''
  isLoading.value = true

  try {
    const ok = await authStore.login(token)
    if (ok) {
      router.push('/dashboard')
    } else {
      errorMsg.value = 'Admin Token 无效，请检查后重试'
    }
  } catch {
    errorMsg.value = '网络错误，请稍后重试'
  } finally {
    isLoading.value = false
  }
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
  font-size: 16px;
  color: #191919;
  font-weight: 600;
  margin-bottom: 4px;
}

.login-desc {
  font-size: 14px;
  color: #999;
}

.login-form {
  margin-top: 24px;
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
