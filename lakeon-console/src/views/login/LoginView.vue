<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <h1 class="login-logo">Lakeon</h1>
        <p class="login-subtitle">Serverless PostgreSQL Console</p>
      </div>

      <div class="login-form">
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

      <div class="login-footer">
        <p>请使用您的租户 API Key 登录管理控制台</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const apiKeyInput = ref('')
const showKey = ref(false)
const isLoading = ref(false)
const errorMsg = ref('')

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
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #e8f4fd 0%, #f0f0f5 100%);
}

.login-card {
  width: 420px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);
  padding: 48px 40px 36px;
}

.login-header {
  text-align: center;
  margin-bottom: 36px;
}

.login-logo {
  font-size: 32px;
  font-weight: 700;
  color: #0073e6;
  margin-bottom: 8px;
  letter-spacing: 1px;
}

.login-subtitle {
  font-size: 14px;
  color: #999;
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
  border-radius: 4px;
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
  border-radius: 4px;
}

.login-btn {
  width: 100%;
  height: 40px;
  background-color: #0073e6;
  color: #fff;
  border: none;
  border-radius: 4px;
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
  background-color: #005bb5;
}

.login-btn:disabled {
  background-color: #a0cfff;
  cursor: not-allowed;
}

.login-btn.loading {
  background-color: #3395ff;
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
  border-top: 1px solid #f0f0f0;
}

.login-footer p {
  font-size: 13px;
  color: #999;
}
</style>
