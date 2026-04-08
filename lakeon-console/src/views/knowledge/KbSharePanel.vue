<template>
  <div class="share-panel">
    <!-- Current user info -->
    <div class="share-current-user">
      <span style="color: #8c7a68; font-size: 12px;">我的用户名：</span>
      <span style="font-weight: 600; font-size: 13px; color: #3d3d3d;">{{ authStore.username || authStore.tenantName }}</span>
    </div>

    <!-- Invite form with search -->
    <div class="share-invite-row">
      <div class="share-search-wrap">
        <input
          v-model="inviteInput"
          class="share-invite-input"
          placeholder="搜索用户名..."
          @input="handleSearch"
          @keyup.enter="handleInvite"
          @focus="showDropdown = suggestions.length > 0"
          @blur="hideDropdownDelayed"
          :disabled="inviting"
        />
        <!-- Search dropdown -->
        <div v-if="showDropdown && suggestions.length > 0" class="share-dropdown">
          <div v-for="u in suggestions" :key="u.username" class="share-dropdown-item" @mousedown.prevent="selectUser(u)">
            <span class="share-dropdown-username">{{ u.username }}</span>
            <span class="share-dropdown-name">{{ u.name }}</span>
          </div>
        </div>
      </div>
      <button class="btn btn-primary btn-small" @click="handleInvite" :disabled="inviting || !inviteInput.trim()">
        {{ inviting ? '邀请中...' : '邀请' }}
      </button>
    </div>

    <!-- Messages -->
    <div v-if="errorMsg" class="share-msg share-msg-error">{{ errorMsg }}</div>
    <div v-if="successMsg" class="share-msg share-msg-success">{{ successMsg }}</div>

    <!-- Members list -->
    <div class="share-members-title">成员列表</div>
    <div v-if="loading" class="share-loading">加载中...</div>
    <div v-else-if="shares.length === 0" class="share-empty">暂无共享成员</div>
    <div v-else class="share-members-list">
      <div v-for="share in shares" :key="share.id" class="share-member-row">
        <div class="share-member-info">
          <span class="share-member-name">{{ share.username }}</span>
          <span class="share-member-role" :class="share.role === 'admin' ? 'role-admin' : 'role-member'">
            {{ share.role === 'admin' ? '管理员' : '成员' }}
          </span>
        </div>
        <div class="share-member-meta">
          {{ formatDate(share.created_at) }}
        </div>
        <button class="btn btn-text btn-small btn-danger-text" @click="handleRemove(share)">移除</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listShares, createShare, deleteShare, type KbShare } from '../../api/knowledge'
import { useAuthStore } from '../../stores/auth'
import client from '../../api/client'

const props = defineProps<{ kbId: string }>()
const authStore = useAuthStore()

const shares = ref<KbShare[]>([])
const loading = ref(false)
const inviting = ref(false)
const inviteInput = ref('')
const errorMsg = ref('')
const successMsg = ref('')
const suggestions = ref<{ username: string; name: string }[]>([])
const showDropdown = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

function clearMessages() {
  errorMsg.value = ''
  successMsg.value = ''
}

function formatDate(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleDateString('zh-CN')
}

function handleSearch() {
  const q = inviteInput.value.trim()
  if (q.length < 2) {
    suggestions.value = []
    showDropdown.value = false
    return
  }
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(async () => {
    try {
      const res = await client.get('/users/search', { params: { q } })
      suggestions.value = res.data.filter((u: any) => u.username !== authStore.username)
      showDropdown.value = suggestions.value.length > 0
    } catch {
      suggestions.value = []
    }
  }, 300)
}

function selectUser(u: { username: string; name: string }) {
  inviteInput.value = u.username
  showDropdown.value = false
  suggestions.value = []
}

function hideDropdownDelayed() {
  setTimeout(() => { showDropdown.value = false }, 200)
}

async function loadShares() {
  loading.value = true
  clearMessages()
  try {
    const res = await listShares(props.kbId)
    shares.value = res.data
  } catch (e: any) {
    errorMsg.value = '加载成员失败：' + (e?.response?.data?.error?.message || e.message || '未知错误')
  } finally {
    loading.value = false
  }
}

async function handleInvite() {
  const username = inviteInput.value.trim()
  if (!username || inviting.value) return
  inviting.value = true
  clearMessages()
  try {
    await createShare(props.kbId, username)
    inviteInput.value = ''
    successMsg.value = `已成功邀请 ${username}`
    await loadShares()
  } catch (e: any) {
    errorMsg.value = '邀请失败：' + (e?.response?.data?.error?.message || e.message || '未知错误')
  } finally {
    inviting.value = false
  }
}

async function handleRemove(share: KbShare) {
  if (!confirm(`确认移除成员 "${share.username}"？`)) return
  clearMessages()
  try {
    await deleteShare(props.kbId, share.id)
    successMsg.value = `已移除 ${share.username}`
    await loadShares()
  } catch (e: any) {
    errorMsg.value = '移除失败：' + (e?.response?.data?.error?.message || e.message || '未知错误')
  }
}

onMounted(loadShares)
</script>

<style scoped>
.share-panel {
  background: #faf8f5;
  border: 1px solid #e8e0d8;
  border-radius: 8px;
  padding: 16px 18px;
  min-width: 320px;
  max-width: 480px;
}
.share-current-user {
  margin-bottom: 12px;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #ede6dc;
  border-radius: 6px;
}
.share-invite-row {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.share-search-wrap {
  flex: 1;
  position: relative;
}
.share-invite-input {
  width: 100%;
  padding: 6px 10px;
  border: 1px solid #d4c4b0;
  border-radius: 5px;
  font-size: 13px;
  outline: none;
  background: #fff;
  color: #3d3d3d;
  box-sizing: border-box;
}
.share-invite-input:focus {
  border-color: #c19a6b;
}
.share-invite-input:disabled {
  background: #f5f0ea;
}
.share-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #e0d8ce;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
  z-index: 10;
  max-height: 200px;
  overflow-y: auto;
  margin-top: 2px;
}
.share-dropdown-item {
  padding: 8px 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.share-dropdown-item:hover {
  background: #faf6f0;
}
.share-dropdown-username {
  font-weight: 600;
  color: #3d3d3d;
}
.share-dropdown-name {
  color: #9c8a72;
  font-size: 12px;
}
.share-msg {
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 4px;
  margin-bottom: 8px;
}
.share-msg-error {
  background: #fff1f0;
  color: #cf1322;
  border: 1px solid #ffa39e;
}
.share-msg-success {
  background: #f6ffed;
  color: #389e0d;
  border: 1px solid #b7eb8f;
}
.share-members-title {
  font-size: 12px;
  font-weight: 600;
  color: #8c7a68;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 8px;
}
.share-loading,
.share-empty {
  font-size: 13px;
  color: #bbb;
  text-align: center;
  padding: 16px 0;
}
.share-members-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.share-member-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: #fff;
  border: 1px solid #ede6dc;
  border-radius: 6px;
}
.share-member-info {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
}
.share-member-name {
  font-size: 13px;
  font-weight: 500;
  color: #3d3d3d;
}
.share-member-role {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 500;
}
.role-admin {
  background: #fff7e6;
  color: #d46b08;
  border: 1px solid #ffd591;
}
.role-member {
  background: #f0f7ff;
  color: #1677ff;
  border: 1px solid #91caff;
}
.share-member-meta {
  font-size: 11px;
  color: #bbb;
  white-space: nowrap;
}
</style>
