<template>
  <div class="ext-login-page">
    <div class="ext-login-card">
      <div class="card-header">
        <div class="brand-logo">DBay</div>
        <div class="card-title">授权 Chrome 扩展</div>
        <div class="card-subtitle">登录后将自动完成授权并关闭此页面</div>
      </div>

      <div v-if="fatalError" class="fatal-error">
        <div class="fatal-error-title">无法完成授权</div>
        <div class="fatal-error-msg">{{ fatalError }}</div>
      </div>

      <form v-else class="login-form" @submit.prevent="handleLogin">
        <div class="form-group">
          <label class="form-label">用户名</label>
          <input
            v-model="username"
            class="form-input"
            type="text"
            placeholder="请输入用户名"
            autocomplete="username"
            :disabled="isLoading"
            @keyup.enter="focusPassword"
          />
        </div>

        <div class="form-group">
          <label class="form-label">密码</label>
          <div class="input-wrapper">
            <input
              ref="pwdInput"
              v-model="password"
              :type="showPwd ? 'text' : 'password'"
              class="form-input"
              placeholder="请输入密码"
              autocomplete="current-password"
              :disabled="isLoading"
            />
            <button class="toggle-btn" type="button" @click="showPwd = !showPwd">
              {{ showPwd ? '隐藏' : '显示' }}
            </button>
          </div>
        </div>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button
          class="login-btn"
          type="submit"
          :disabled="isLoading || !username.trim() || !password"
        >
          <span v-if="isLoading" class="spinner"></span>
          {{ isLoading ? '登录中...' : '登录并授权' }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import client from '../../api/client'

const username = ref('')
const password = ref('')
const showPwd = ref(false)
const isLoading = ref(false)
const errorMsg = ref('')
const fatalError = ref('')
const pwdInput = ref<HTMLInputElement | null>(null)

let redirectUri = ''

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const raw = params.get('redirect_uri') || ''

  if (!raw) {
    fatalError.value = '缺少 redirect_uri 参数，请通过 Chrome 扩展重新发起授权。'
    return
  }

  // Security: only allow chromiumapp.org redirect URIs
  try {
    const url = new URL(raw)
    if (!url.hostname.endsWith('.chromiumapp.org')) {
      fatalError.value = '无效的 redirect_uri，仅允许 Chrome 扩展回调地址。'
      return
    }
  } catch {
    fatalError.value = 'redirect_uri 格式无效。'
    return
  }

  redirectUri = raw
})

function focusPassword() {
  pwdInput.value?.focus()
}

async function handleLogin() {
  const user = username.value.trim()
  if (!user || !password.value) return

  errorMsg.value = ''
  isLoading.value = true

  try {
    const res = await client.post('/auth/login', {
      username: user,
      password: password.value,
    })
    const apiKey: string = res.data.api_key
    if (!apiKey) {
      errorMsg.value = '登录成功但未获取到 API Key，请联系支持。'
      return
    }
    // Redirect back to the extension with the API key in the hash
    window.location.href = redirectUri + '#key=' + encodeURIComponent(apiKey)
  } catch (e: any) {
    if (e.response?.status === 401) {
      errorMsg.value = '用户名或密码错误，请重试。'
    } else if (e.isApiOffline) {
      errorMsg.value = 'DBay API 暂时不可用，请稍后重试。'
    } else {
      errorMsg.value = e.response?.data?.message || '登录失败，请稍后重试。'
    }
  } finally {
    isLoading.value = false
  }
}
</script>

<style scoped>
.ext-login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #faf8f5;
  padding: 24px;
}

.ext-login-card {
  width: 100%;
  max-width: 380px;
  background: #fff;
  border-radius: 16px;
  padding: 36px 32px 32px;
  box-shadow: 0 4px 24px rgba(140, 100, 50, 0.10), 0 1px 4px rgba(140, 100, 50, 0.06);
  border: 1px solid rgba(193, 154, 107, 0.15);
}

.card-header {
  text-align: center;
  margin-bottom: 28px;
}

.brand-logo {
  font-size: 28px;
  font-weight: 800;
  color: #c19a6b;
  letter-spacing: 1px;
  margin-bottom: 10px;
}

.card-title {
  font-size: 17px;
  font-weight: 600;
  color: #3a2e1e;
  margin-bottom: 6px;
}

.card-subtitle {
  font-size: 13px;
  color: #9c8a72;
  line-height: 1.5;
}

.login-form {
  display: flex;
  flex-direction: column;
}

.form-group {
  margin-bottom: 18px;
}

.form-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: #5a4a32;
  margin-bottom: 6px;
}

.form-input {
  width: 100%;
  height: 40px;
  border: 1px solid #e0d3bc;
  border-radius: 8px;
  padding: 0 14px;
  font-size: 14px;
  color: #3a2e1e;
  background: #faf8f5;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
  box-sizing: border-box;
}

.form-input:focus {
  border-color: #c19a6b;
  box-shadow: 0 0 0 3px rgba(193, 154, 107, 0.12);
  background: #fff;
}

.form-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-input::placeholder {
  color: #c4b49a;
}

.input-wrapper {
  display: flex;
  align-items: center;
  border: 1px solid #e0d3bc;
  border-radius: 8px;
  overflow: hidden;
  background: #faf8f5;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.input-wrapper:focus-within {
  border-color: #c19a6b;
  box-shadow: 0 0 0 3px rgba(193, 154, 107, 0.12);
  background: #fff;
}

.input-wrapper .form-input {
  border: none;
  border-radius: 0;
  box-shadow: none;
  background: transparent;
  flex: 1;
}

.input-wrapper .form-input:focus {
  box-shadow: none;
  background: transparent;
}

.toggle-btn {
  background: none;
  border: none;
  border-left: 1px solid #e0d3bc;
  padding: 0 12px;
  height: 40px;
  font-size: 12px;
  color: #9c8a72;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.2s ease;
  flex-shrink: 0;
}

.toggle-btn:hover {
  color: #c19a6b;
}

.error-msg {
  color: #b91c1c;
  font-size: 13px;
  margin-bottom: 16px;
  padding: 10px 14px;
  background: rgba(239, 68, 68, 0.05);
  border: 1px solid rgba(239, 68, 68, 0.12);
  border-radius: 8px;
  line-height: 1.5;
}

.login-btn {
  width: 100%;
  height: 44px;
  background: #c19a6b;
  color: #fff;
  border: none;
  border-radius: 100px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: opacity 0.2s ease, transform 0.2s ease;
  margin-top: 4px;
}

.login-btn:hover:not(:disabled) {
  opacity: 0.88;
  transform: translateY(-1px);
}

.login-btn:active:not(:disabled) {
  transform: translateY(0);
}

.login-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.spinner {
  width: 15px;
  height: 15px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.fatal-error {
  text-align: center;
  padding: 20px 0;
}

.fatal-error-title {
  font-size: 15px;
  font-weight: 600;
  color: #b91c1c;
  margin-bottom: 10px;
}

.fatal-error-msg {
  font-size: 13px;
  color: #7a6550;
  line-height: 1.6;
}
</style>
