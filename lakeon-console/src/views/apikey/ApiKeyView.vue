<template>
  <div class="page-apikey">
    <div class="page-header">
      <h1 class="page-title">API Key</h1>
    </div>

    <div class="apikey-card">
      <div class="card-header">
        <h3>当前 API Key</h3>
      </div>
      <div class="card-body">
        <div class="key-display">
          <div class="key-row">
            <code class="key-value">{{ maskedKey }}</code>
            <button class="copy-btn" @click="handleCopy">复制</button>
          </div>
          <p class="key-hint">API Key 用于身份认证，请妥善保管，不要泄露给他人。</p>
        </div>

        <div class="key-actions">
          <button
            class="btn btn-default btn-warning-outline"
            @click="showRegenDialog = true"
          >重新生成</button>
        </div>
      </div>
    </div>

    <!-- New key display after regeneration -->
    <div v-if="newKey" class="apikey-card new-key-card">
      <div class="card-header">
        <h3>新 API Key</h3>
      </div>
      <div class="card-body">
        <div class="new-key-alert">
          请立即复制新的 API Key，此 Key 仅显示一次，关闭后无法再次查看。
        </div>
        <div class="key-row">
          <code class="key-value key-value-full">{{ newKey }}</code>
          <button class="copy-btn" @click="handleCopyNew">复制</button>
        </div>
      </div>
    </div>

    <!-- Regenerate Confirmation Dialog -->
    <div v-if="showRegenDialog" class="dialog-overlay" @click.self="showRegenDialog = false">
      <div class="dialog-box dialog-confirm">
        <div class="dialog-header">
          <h3>重新生成 API Key</h3>
          <button class="dialog-close" @click="showRegenDialog = false">&times;</button>
        </div>
        <div class="dialog-body">
          <p class="confirm-text">
            重新生成后，旧 Key 立即失效，请更新所有使用中的应用配置。确定继续？
          </p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showRegenDialog = false">取消</button>
          <button
            class="btn btn-danger"
            :disabled="regenLoading"
            @click="handleRegenerate"
          >{{ regenLoading ? '生成中...' : '确定重新生成' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { tenantApi } from '../../api/tenant'
import { copyToClipboard } from '../../utils/clipboard'
import { maskApiKey } from '../../utils/format'

const authStore = useAuthStore()

const showRegenDialog = ref(false)
const regenLoading = ref(false)
const newKey = ref('')

const maskedKey = computed(() => {
  return maskApiKey(authStore.apiKey || '')
})

async function handleCopy() {
  await copyToClipboard(authStore.apiKey)
}

async function handleCopyNew() {
  await copyToClipboard(newKey.value)
}

async function handleRegenerate() {
  if (!authStore.tenantId) return
  regenLoading.value = true
  try {
    const res = await tenantApi.regenerateKey(authStore.tenantId)
    const key = res.data.api_key
    if (key) {
      newKey.value = key
      // Update local storage and auth store
      authStore.apiKey = key
      localStorage.setItem('lakeon_api_key', key)
    }
    showRegenDialog.value = false
  } catch (e) {
    console.error('Failed to regenerate API key', e)
  } finally {
    regenLoading.value = false
  }
}
</script>

<style scoped>
.apikey-card {
  background: #fff;
  border-radius: 2px;
  border: 1px solid #dfe1e6;
  overflow: hidden;
  margin-bottom: 20px;
}

.new-key-card {
  border-color: #0073e6;
}

.card-header {
  padding: 14px 20px;
  border-bottom: 1px solid #dfe1e6;
}

.card-header h3 {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin: 0;
}

.card-body {
  padding: 20px;
}

.key-display {
  margin-bottom: 20px;
}

.key-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.key-value {
  font-size: 14px;
  color: #191919;
  background: #f2f3f5;
  padding: 8px 14px;
  border-radius: 2px;
  letter-spacing: 0.5px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.key-value-full {
  font-size: 13px;
  word-break: break-all;
  flex: 1;
}

.key-hint {
  font-size: 12px;
  color: #8a8e99;
  margin-top: 12px;
}

.key-actions {
  padding-top: 16px;
  border-top: 1px solid #dfe1e6;
}

.new-key-alert {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 2px;
  padding: 10px 16px;
  font-size: 14px;
  color: #ad6800;
  margin-bottom: 16px;
}
</style>
