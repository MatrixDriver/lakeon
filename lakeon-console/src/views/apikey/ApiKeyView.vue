<template>
  <div class="page-apikey">
    <div class="breadcrumb">
      <router-link to="/dashboard" class="breadcrumb-link">总览</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-item active">API Key</span>
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
.page-apikey {
  padding: 4px;
}

/* Breadcrumb */
.breadcrumb {
  margin-bottom: 20px;
  font-size: 14px;
  color: #999;
}

.breadcrumb-link {
  color: #0073e6;
  text-decoration: none;
}

.breadcrumb-link:hover {
  text-decoration: underline;
}

.breadcrumb-sep {
  margin: 0 8px;
  color: #d9d9d9;
}

.breadcrumb-item.active {
  color: #333;
  font-weight: 500;
}

/* Card */
.apikey-card {
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
  border: 1px solid #f0f0f0;
  overflow: hidden;
  margin-bottom: 20px;
}

.new-key-card {
  border-color: #91d5ff;
}

.card-header {
  padding: 16px 24px;
  border-bottom: 1px solid #f0f0f0;
}

.card-header h3 {
  font-size: 15px;
  font-weight: 500;
  color: #333;
  margin: 0;
}

.card-body {
  padding: 24px;
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
  font-size: 16px;
  color: #333;
  background: #f5f5f5;
  padding: 8px 16px;
  border-radius: 4px;
  letter-spacing: 0.5px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
}

.key-value-full {
  font-size: 13px;
  word-break: break-all;
  flex: 1;
}

.key-hint {
  font-size: 13px;
  color: #999;
  margin-top: 12px;
}

.key-actions {
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
}

.new-key-alert {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  padding: 10px 16px;
  font-size: 13px;
  color: #ad6800;
  margin-bottom: 16px;
}

/* Copy button */
.copy-btn {
  background: none;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 4px 12px;
  font-size: 13px;
  color: #0073e6;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}

.copy-btn:hover {
  border-color: #0073e6;
  background-color: #f0f7ff;
}

/* Buttons */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  padding: 0 16px;
  font-size: 14px;
  border-radius: 4px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-default {
  background-color: #fff;
  color: #333;
  border-color: #d9d9d9;
}

.btn-default:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.btn-warning-outline {
  color: #fa8c16;
  border-color: #ffc069;
}

.btn-warning-outline:hover:not(:disabled) {
  color: #d46b08;
  border-color: #fa8c16;
  background-color: #fff7e6;
}

.btn-danger {
  background-color: #ff4d4f;
  color: #fff;
  border-color: #ff4d4f;
}

.btn-danger:hover:not(:disabled) {
  background-color: #d9363e;
}

/* Dialog */
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog-box {
  background: #fff;
  border-radius: 8px;
  width: 480px;
  max-width: 90vw;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
}

.dialog-confirm {
  width: 420px;
}

.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid #f0f0f0;
}

.dialog-header h3 {
  font-size: 16px;
  font-weight: 500;
  color: #333;
  margin: 0;
}

.dialog-close {
  background: none;
  border: none;
  font-size: 20px;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
}

.dialog-close:hover {
  color: #333;
}

.dialog-body {
  padding: 24px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 24px;
  border-top: 1px solid #f0f0f0;
}

.confirm-text {
  font-size: 14px;
  color: #333;
  line-height: 1.6;
}
</style>
