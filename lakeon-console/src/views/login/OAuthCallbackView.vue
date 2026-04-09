<template>
  <div class="oauth-callback">
    <div class="callback-card">
      <div v-if="error" class="error-state">
        <div class="error-icon">!</div>
        <p class="error-msg">{{ error }}</p>
        <button class="retry-btn" @click="goToLogin">返回登录</button>
      </div>
      <div v-else class="loading-state">
        <div class="spinner"></div>
        <p class="loading-text">正在完成登录...</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const error = ref('')

onMounted(async () => {
  const code = route.query.code as string
  if (!code) {
    error.value = '缺少授权码'
    return
  }

  const result = await authStore.loginWithOAuthCode(code)
  if (result.ok) {
    router.push('/dashboard')
  } else {
    error.value = result.error || '登录失败'
  }
})

function goToLogin() {
  router.push('/login')
}
</script>

<style scoped>
.oauth-callback {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--pub-bg);
}

.callback-card {
  text-align: center;
  padding: 48px;
}

.loading-state { display: flex; flex-direction: column; align-items: center; gap: 16px; }

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--pub-border);
  border-top-color: var(--pub-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.loading-text { font-size: 15px; color: var(--pub-text-2); }

.error-state { display: flex; flex-direction: column; align-items: center; gap: 12px; }

.error-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: rgba(239, 68, 68, 0.08);
  color: #ef4444;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 700;
}

.error-msg { font-size: 15px; color: var(--pub-text-2); }

.retry-btn {
  margin-top: 8px;
  padding: 10px 32px;
  background: var(--pub-primary);
  color: #fff;
  border: none;
  border-radius: 100px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.retry-btn:hover { opacity: 0.92; }
</style>
