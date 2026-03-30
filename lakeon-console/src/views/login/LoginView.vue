<template>
  <div class="login-page">
    <!-- Left: Brand panel -->
    <div class="brand-panel">
      <router-link to="/" class="brand-logo-link">
        <h1 class="brand-logo">DBay</h1>
        <p class="brand-name">数据港湾</p>
      </router-link>
      <h2 class="brand-headline">Agent 时代的<br>数据基础设施</h2>
      <div class="brand-features">
        <div class="brand-feature">
          <span class="feature-dot" style="background: #4da6ff;"></span>
          <div>
            <div class="feature-title">Serverless PostgreSQL</div>
            <div class="feature-desc">按需弹性，空闲自动休眠，内置 pgvector</div>
          </div>
        </div>
        <div class="brand-feature">
          <span class="feature-dot" style="background: #a78bfa;"></span>
          <div>
            <div class="feature-title">记忆库 + 知识库</div>
            <div class="feature-desc">MCP 一键接入，AI Agent 即刻获得长期记忆</div>
          </div>
        </div>
        <div class="brand-feature">
          <span class="feature-dot" style="background: #34d399;"></span>
          <div>
            <div class="feature-title">数据湖</div>
            <div class="feature-desc">Python / Ray / 微调，Serverless 算力</div>
          </div>
        </div>
      </div>
      <div class="brand-footer">
        <span>dbay.cloud</span>
      </div>
    </div>

    <!-- Right: Form panel -->
    <div class="form-panel">
      <div class="form-card">
        <!-- Tab Switch -->
        <div class="login-tabs">
          <button class="login-tab" :class="{ active: tab === 'login' }" @click="switchTab('login')">登录</button>
          <button class="login-tab" :class="{ active: tab === 'register' }" @click="switchTab('register')">注册</button>
        </div>

        <!-- Login Form -->
        <div class="login-form" v-if="tab === 'login'">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input v-model="loginUsername" class="form-input" placeholder="请输入用户名" @keyup.enter="focusPassword" />
          </div>
          <div class="form-group">
            <label class="form-label">密码</label>
            <div class="input-wrapper">
              <input ref="loginPwdInput" :type="showPwd ? 'text' : 'password'" v-model="loginPassword"
                     class="form-input" placeholder="请输入密码" @keyup.enter="handleLogin" />
              <button class="toggle-btn" @click="showPwd = !showPwd" type="button">
                {{ showPwd ? '隐藏' : '显示' }}
              </button>
            </div>
          </div>

          <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

          <button class="login-btn" :class="{ loading: isLoading }"
                  :disabled="isLoading || !loginUsername.trim() || !loginPassword" @click="handleLogin">
            <span v-if="isLoading" class="spinner"></span>
            {{ isLoading ? '登录中...' : '登 录' }}
          </button>
        </div>

        <!-- Register Form -->
        <div class="login-form" v-if="tab === 'register'">
          <div class="form-group">
            <label class="form-label">用户名 <span class="required">*</span></label>
            <input v-model="registerUsername" class="form-input" :class="{ 'input-error': usernameTaken }"
                   placeholder="请输入用户名" @blur="checkUsername" @input="resetUsernameCheck" />
            <p v-if="usernameTaken" class="field-error">该用户名已被注册，请更换一个</p>
            <p v-else-if="usernameAvailable" class="field-ok">用户名可用</p>
          </div>
          <div class="form-group">
            <label class="form-label">密码 <span class="required">*</span></label>
            <input v-model="registerPassword" type="password" class="form-input" placeholder="设置登录密码（至少 6 位）" />
          </div>
          <div class="form-group">
            <label class="form-label">确认密码 <span class="required">*</span></label>
            <input v-model="registerConfirm" type="password" class="form-input" placeholder="再次输入密码"
                   @keyup.enter="handleRegister" />
          </div>

          <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

          <button class="login-btn" :class="{ loading: isLoading }"
                  :disabled="isLoading || !registerFormValid" @click="handleRegister">
            <span v-if="isLoading" class="spinner"></span>
            {{ isLoading ? '注册中...' : '注 册' }}
          </button>
        </div>

        <!-- Registration Success -->
        <div v-if="registerSuccess" class="register-success">
          <div class="success-alert">注册成功！现在可以使用用户名和密码登录。</div>
          <button class="login-btn" style="margin-top: 12px" @click="goToLogin">前往登录</button>
        </div>

        <div class="login-footer">
          <p v-if="tab === 'login'">没有账号？点击上方「注册」</p>
          <p v-else>已有账号？点击上方「登录」</p>
        </div>
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
  min-height: 100vh;
}

/* ── Left: Brand panel ── */
.brand-panel {
  width: 480px;
  flex-shrink: 0;
  background: linear-gradient(135deg, #0a1628 0%, #0d2847 50%, #0a1628 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px 52px;
  position: relative;
  overflow: hidden;
}

.brand-panel::before {
  content: '';
  position: absolute;
  top: -120px;
  right: -120px;
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(0, 115, 230, 0.12) 0%, transparent 70%);
  pointer-events: none;
}

.brand-panel::after {
  content: '';
  position: absolute;
  bottom: -80px;
  left: -80px;
  width: 300px;
  height: 300px;
  background: radial-gradient(circle, rgba(77, 166, 255, 0.08) 0%, transparent 70%);
  pointer-events: none;
}

.brand-logo-link {
  text-decoration: none;
  display: block;
  margin-bottom: 44px;
}

.brand-logo {
  font-size: 40px;
  font-weight: 800;
  color: #4da6ff;
  letter-spacing: 1px;
  margin: 0;
}

.brand-name {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.5);
  margin: 4px 0 0;
  letter-spacing: 4px;
}

.brand-headline {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.35;
  margin: 0 0 48px;
  color: rgba(255, 255, 255, 0.95);
}

.brand-features {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.brand-feature {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.feature-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 5px;
}

.feature-title {
  font-size: 14px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
  margin-bottom: 3px;
}

.feature-desc {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.4);
  line-height: 1.5;
}

.brand-footer {
  margin-top: auto;
  padding-top: 56px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.2);
  letter-spacing: 1.5px;
}

/* ── Right: Form panel ── */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--pub-bg);
  padding: 48px;
}

.form-card {
  width: 100%;
  max-width: 400px;
}

.login-tabs {
  display: flex;
  gap: 6px;
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 24px;
  padding: 4px;
  margin-bottom: 32px;
}

.login-tab {
  flex: 1;
  background: none;
  border: none;
  padding: 10px 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--pub-text-2);
  cursor: pointer;
  border-radius: 20px;
  transition: all 0.25s ease;
}

.login-tab.active {
  color: var(--pub-primary);
  background: var(--pub-bg);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  font-weight: 600;
}

.login-tab:hover:not(.active) {
  color: var(--pub-text);
}

.form-group { margin-bottom: 20px; }
.form-label { display: block; font-size: 14px; color: var(--pub-text); margin-bottom: 6px; font-weight: 500; }
.required { color: #ef4444; }

.input-wrapper {
  display: flex;
  align-items: center;
  border: 1px solid var(--pub-border);
  border-radius: 8px;
  overflow: hidden;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
  background: var(--pub-surface);
}

.input-wrapper:focus-within {
  border-color: var(--pub-primary);
  box-shadow: 0 0 0 3px rgba(0, 115, 230, 0.08);
}

.form-input {
  width: 100%;
  border: 1px solid var(--pub-border);
  border-radius: 8px;
  padding: 0 14px;
  height: 40px;
  font-size: 14px;
  color: var(--pub-text);
  background: var(--pub-surface);
  outline: none;
  transition: border-color 0.25s ease, box-shadow 0.25s ease;
}

.form-input:focus {
  border-color: var(--pub-primary);
  box-shadow: 0 0 0 3px rgba(0, 115, 230, 0.08);
}

.input-wrapper .form-input { border: none; border-radius: 0; box-shadow: none; }
.form-input::placeholder { color: var(--pub-text-4, #aaa); }

.toggle-btn {
  background: none;
  border: none;
  border-left: 1px solid var(--pub-border);
  padding: 0 14px;
  height: 40px;
  font-size: 12px;
  color: var(--pub-text-3);
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.25s ease, background 0.25s ease;
}

.toggle-btn:hover {
  color: var(--pub-primary);
  background: var(--pub-hover);
}

.input-error { border-color: #ef4444 !important; }
.field-error { color: #ef4444; font-size: 12px; margin-top: 4px; }
.field-ok { color: #22c55e; font-size: 12px; margin-top: 4px; }

.error-msg {
  color: #dc2626;
  font-size: 13px;
  margin-bottom: 16px;
  padding: 10px 14px;
  background: rgba(239, 68, 68, 0.05);
  border: 1px solid rgba(239, 68, 68, 0.12);
  border-radius: 10px;
}

.login-btn {
  width: 100%;
  height: 46px;
  background: var(--pub-primary);
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
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.login-btn:hover:not(:disabled) {
  opacity: 0.92;
  transform: translateY(-1px);
}
.login-btn:active:not(:disabled) {
  transform: translateY(0);
}
.login-btn:disabled { opacity: 0.45; cursor: not-allowed; }

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.register-success { margin-top: 16px; }

.success-alert {
  background: rgba(34, 197, 94, 0.06);
  border: 1px solid rgba(34, 197, 94, 0.15);
  border-radius: 10px;
  padding: 10px 16px;
  font-size: 14px;
  color: #16a34a;
}

.login-footer {
  text-align: center;
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid var(--pub-border);
}

.login-footer p { font-size: 13px; color: var(--pub-text-3); }

/* ── Mobile ── */
@media (max-width: 768px) {
  .login-page { flex-direction: column; }

  .brand-panel {
    width: 100%;
    padding: 36px 24px;
  }

  .brand-headline { font-size: 24px; margin-bottom: 24px; }
  .brand-features { display: none; }
  .brand-footer { display: none; }

  .form-panel { padding: 32px 24px; }
  .form-card { max-width: 100%; }
  .login-btn { height: 48px; font-size: 16px; }
  .form-input { height: 44px; padding: 0 12px; font-size: 16px; }
  .toggle-btn { padding: 0 12px; height: 44px; }
}
</style>
