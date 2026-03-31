<template>
  <div v-if="visible" class="dialog-overlay" @click.self="$emit('close')">
    <div class="dialog-box dialog-confirm">
      <div class="dialog-header">
        <h3>添加用户</h3>
        <button class="dialog-close" @click="$emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <!-- Password Created View -->
        <div v-if="createdPassword" class="password-result">
          <div class="password-success-msg">用户创建成功</div>
          <div class="form-group">
            <label class="form-label">用户名</label>
            <code class="readonly-value">{{ createdUsername }}</code>
          </div>
          <div class="form-group">
            <label class="form-label">密码</label>
            <div class="password-display">
              <code class="password-value">{{ createdPassword }}</code>
              <button
                class="copy-btn"
                :class="{ 'copy-btn-ok': copied }"
                @click="handleCopy"
              >{{ copied ? '已复制' : '复制' }}</button>
            </div>
          </div>
          <div class="password-warning">
            请立即复制密码，关闭对话框后将无法再次查看。
          </div>
        </div>

        <!-- Create Form View -->
        <div v-else>
          <div class="form-group">
            <label class="form-label">用户名 <span class="required">*</span></label>
            <input v-model="username" class="form-input" placeholder="请输入用户名" />
          </div>
          <div class="form-group">
            <label class="form-label">角色 <span class="required">*</span></label>
            <select v-model="role" class="form-input form-select">
              <option value="ADMIN">Admin (全部权限)</option>
              <option value="WRITER">Writer (读写)</option>
              <option value="READER">Reader (只读)</option>
            </select>
          </div>
          <div class="form-group">
            <label class="form-label">密码</label>
            <input v-model="password" class="form-input" type="text" placeholder="留空自动生成" />
          </div>
          <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>
        </div>
      </div>
      <div class="dialog-footer">
        <button v-if="createdPassword" class="btn btn-primary" @click="handleDone">确定</button>
        <template v-else>
          <button class="btn btn-default" @click="$emit('close')">取消</button>
          <button
            class="btn btn-primary"
            :disabled="!username.trim() || creating"
            @click="handleCreate"
          >{{ creating ? '创建中...' : '确定' }}</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { dbuserApi } from '../../api/dbuser'
import { copyToClipboard } from '../../utils/clipboard'

const props = defineProps<{
  visible: boolean
  dbId: string
}>()

const emit = defineEmits<{
  close: []
  created: []
}>()

const username = ref('')
const role = ref('READER')
const password = ref('')
const creating = ref(false)
const errorMsg = ref('')
const createdPassword = ref('')
const createdUsername = ref('')
const copied = ref(false)

watch(() => props.visible, (val) => {
  if (val) {
    username.value = ''
    role.value = 'READER'
    password.value = ''
    creating.value = false
    errorMsg.value = ''
    createdPassword.value = ''
    createdUsername.value = ''
    copied.value = false
  }
})

async function handleCreate() {
  if (!username.value.trim()) return
  creating.value = true
  errorMsg.value = ''
  try {
    const data: { username: string; role: string; password?: string } = {
      username: username.value.trim(),
      role: role.value,
    }
    if (password.value.trim()) {
      data.password = password.value.trim()
    }
    const res = await dbuserApi.createUser(props.dbId, data)
    createdPassword.value = res.data.password
    createdUsername.value = res.data.username
  } catch (e: any) {
    errorMsg.value = e.response?.data?.error?.message || '创建失败，请重试'
  } finally {
    creating.value = false
  }
}

async function handleCopy() {
  const ok = await copyToClipboard(createdPassword.value)
  if (ok) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

function handleDone() {
  emit('created')
  emit('close')
}
</script>

<style scoped>
.password-result {
  text-align: left;
}

.password-success-msg {
  font-size: 14px;
  font-weight: 600;
  color: #52c41a;
  margin-bottom: 16px;
}

.readonly-value {
  display: block;
  font-size: 14px;
  color: #2c3e50;
  background: #f2f3f5;
  padding: 6px 10px;
  border-radius: 4px;
}

.password-display {
  display: flex;
  align-items: center;
  gap: 8px;
}

.password-value {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: #d4380d;
  background: #f2f3f5;
  padding: 6px 10px;
  border-radius: 4px;
  word-break: break-all;
}

.password-warning {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fff7e6;
  border: 1px solid #ffd591;
  border-radius: 4px;
  color: #d46b08;
  font-size: 13px;
}

.error-msg {
  color: #d4380d;
  font-size: 13px;
  margin-top: 8px;
}

.copy-btn-ok {
  background: #f6ffed !important;
  border-color: #52c41a !important;
  color: #52c41a !important;
}
</style>
