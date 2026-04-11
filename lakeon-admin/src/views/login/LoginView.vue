<template>
  <div class="login-page">
    <div class="login-shell">
      <div class="login-brand">
        <div class="login-logo">DBay</div>
        <div class="login-brand-tag">运维控制台</div>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <h1 class="login-title">管理员登录</h1>
        <p class="login-lede">使用 Admin Token 进入 Lakeon 运维控制台。</p>

        <label class="login-field">
          <span class="login-field-lbl">Admin Token</span>
          <div class="login-input-wrap">
            <input
              :type="showToken ? 'text' : 'password'"
              v-model="tokenInput"
              class="login-input"
              placeholder="粘贴或输入管理员 Token"
              autocomplete="off"
              @keyup.enter="handleLogin"
            />
            <button
              class="login-input-toggle"
              @click="showToken = !showToken"
              type="button"
              tabindex="-1"
            >{{ showToken ? '隐藏' : '显示' }}</button>
          </div>
        </label>

        <div v-if="errorMsg" class="login-error" role="alert">{{ errorMsg }}</div>

        <button
          class="login-submit"
          :disabled="isLoading || !tokenInput.trim()"
          type="submit"
        >
          <span v-if="isLoading" class="login-spinner" aria-hidden="true"></span>
          {{ isLoading ? '登录中' : '登 录' }}
        </button>

        <div class="login-note">仅限平台管理员使用 · 所有登录行为记录在审计日志</div>
      </form>
    </div>

    <footer class="login-footprint">
      <span>Lakeon · Serverless PostgreSQL</span>
      <span class="login-sep" aria-hidden="true"></span>
      <span>Harbor Editorial</span>
    </footer>
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
    const result = await authStore.login(token)
    if (result.ok) {
      router.push('/databases')
    } else {
      errorMsg.value = result.error || 'Admin Token 无效，请检查后重试'
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
  flex-direction: column;
  align-items: center;
  justify-content: space-between;
  min-height: 100vh;
  padding: var(--space-4xl) var(--space-xl) var(--space-2xl);
  background: var(--c-bg-alt);
  background-image:
    radial-gradient(ellipse 800px 400px at 50% -10%, color-mix(in oklch, var(--c-accent) 6%, transparent), transparent 70%);
}

.login-shell {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--space-3xl);
  width: 100%;
  max-width: 420px;
  flex: 1;
  place-content: center;
  padding: var(--space-xl) 0;
}

/* ───────── Brand ───────── */
.login-brand {
  text-align: center;
}

.login-logo {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 56px;
  line-height: 1;
  color: var(--c-primary);
  letter-spacing: -0.02em;
}

.login-brand-tag {
  margin-top: var(--space-sm);
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  color: var(--c-accent-text);
}

/* ───────── Form ───────── */
.login-form {
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
  padding: var(--space-2xl) var(--space-2xl) var(--space-xl);
  box-shadow: 0 12px 40px -16px rgb(42 77 106 / 0.14),
              0 2px 6px -2px rgb(42 77 106 / 0.06);
}

.login-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 24px;
  line-height: 1.2;
  color: var(--c-text);
  letter-spacing: -0.01em;
  margin: 0;
}

.login-lede {
  margin: var(--space-xs) 0 var(--space-xl);
  font-size: 13px;
  color: var(--c-text-2);
  line-height: 1.55;
}

.login-field {
  display: block;
  margin-bottom: var(--space-lg);
}

.login-field-lbl {
  display: block;
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
  margin-bottom: var(--space-xs);
}

.login-input-wrap {
  display: flex;
  align-items: stretch;
  border: 1px solid var(--c-border);
  border-radius: 4px;
  background: #fff;
  transition: border-color 160ms ease-out, box-shadow 160ms ease-out;
}

.login-input-wrap:hover {
  border-color: var(--c-text-3);
}

.login-input-wrap:focus-within {
  border-color: var(--c-accent);
  box-shadow: 0 0 0 3px rgb(from var(--c-accent) r g b / 0.15);
}

.login-input {
  flex: 1;
  border: none;
  outline: none;
  padding: var(--space-md) var(--space-md);
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--c-text);
  background: transparent;
  letter-spacing: -0.01em;
}

.login-input::placeholder {
  color: var(--c-text-3);
  font-family: var(--font-sans);
  letter-spacing: 0;
}

.login-input-toggle {
  background: none;
  border: none;
  border-left: 1px solid var(--c-border-light);
  padding: 0 var(--space-md);
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-2);
  cursor: pointer;
  transition: color 160ms ease-out, background 160ms ease-out;
}

.login-input-toggle:hover {
  color: var(--c-accent-text);
  background-color: var(--c-accent-light);
}

.login-error {
  margin-bottom: var(--space-md);
  padding: var(--space-sm) var(--space-md);
  font-size: 12px;
  color: var(--cs-severe);
  background: color-mix(in oklch, var(--cs-severe) 5%, #fff);
  border: 1px solid color-mix(in oklch, var(--cs-severe) 20%, #fff);
  border-radius: 4px;
  line-height: 1.5;
}

.login-submit {
  width: 100%;
  height: 40px;
  background-color: var(--c-accent);
  color: #fff;
  border: none;
  border-radius: 4px;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  transition: background-color 160ms ease-out;
}

.login-submit:hover:not(:disabled) {
  background-color: var(--c-accent-hover);
}

.login-submit:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 2px;
}

.login-submit:disabled {
  background-color: color-mix(in oklch, var(--c-accent) 40%, #fff);
  cursor: not-allowed;
}

.login-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgb(255 255 255 / 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: login-spin 0.7s linear infinite;
}

@keyframes login-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .login-spinner { animation-duration: 2s; }
}

.login-note {
  margin-top: var(--space-xl);
  padding-top: var(--space-md);
  border-top: 1px solid var(--c-border-light);
  font-size: 11px;
  color: var(--c-text-3);
  line-height: 1.5;
  text-align: center;
}

/* ───────── Footprint ───────── */
.login-footprint {
  display: inline-flex;
  align-items: center;
  gap: var(--space-md);
  font-family: var(--font-sans);
  font-size: 11px;
  color: var(--c-text-3);
  letter-spacing: 0.04em;
}

.login-sep {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--c-border);
}

/* ───────── Mobile ───────── */
@media (max-width: 480px) {
  .login-page {
    padding: var(--space-xl) var(--space-lg) var(--space-lg);
  }

  .login-shell {
    gap: var(--space-xl);
  }

  .login-logo {
    font-size: 48px;
  }

  .login-form {
    padding: var(--space-xl) var(--space-lg) var(--space-lg);
  }

  .login-submit {
    height: 44px;
  }
}
</style>
