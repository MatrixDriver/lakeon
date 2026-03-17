<template>
  <div v-if="visible" class="dialog-overlay" @click.self="emit('close')">
    <div class="dialog-box dialog-confirm">
      <div class="dialog-header">
        <h3>创建版本</h3>
        <button class="dialog-close" @click="emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <div class="form-group">
          <label class="form-label">版本号 <span class="required">*</span></label>
          <input v-model="name" class="form-input" :placeholder="namePlaceholder" />
          <div v-if="lastVersionName" class="form-tip">上一个版本: {{ lastVersionName }}</div>
        </div>
        <div class="form-group">
          <label class="form-label">描述 <span class="form-hint">(可选)</span></label>
          <textarea v-model="description" class="form-input form-textarea" rows="3" placeholder="本次变更说明"></textarea>
        </div>
      </div>
      <div class="dialog-footer">
        <button class="btn btn-default" @click="emit('close')">取消</button>
        <button
          class="btn btn-primary"
          :disabled="!name.trim() || loading"
          @click="handleCreate"
        >{{ loading ? '创建中...' : '创建版本' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { versionApi } from '../../api/version'

const props = defineProps<{
  visible: boolean
  dbId: string
  branchId: string
  lastVersionName?: string
}>()

const emit = defineEmits<{
  close: []
  created: []
}>()

const name = ref('')
const description = ref('')
const loading = ref(false)

const namePlaceholder = computed(() => {
  if (!props.lastVersionName) return '例: v1.0.0'
  // Try to suggest next version
  const last = props.lastVersionName!
  const match = last.match(/^(v?\d+\.\d+\.)(\d+)$/)
  if (match) return `例: ${match[1]!}${parseInt(match[2]!) + 1}`
  const matchSimple = last.match(/^(v?)(\d+)$/)
  if (matchSimple) return `例: ${matchSimple[1]!}${parseInt(matchSimple[2]!) + 1}`
  return '例: v1.0.0'
})

watch(() => props.visible, (v) => {
  if (v) {
    name.value = ''
    description.value = ''
  }
})

async function handleCreate() {
  if (!name.value.trim()) return
  loading.value = true
  try {
    await versionApi.create(props.dbId, props.branchId, {
      name: name.value.trim(),
      description: description.value.trim() || undefined,
    })
    emit('created')
    emit('close')
  } catch (e) {
    console.error('Failed to create version', e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-group {
  margin-bottom: 16px;
}

.form-label {
  display: block;
  font-size: 14px;
  color: #191919;
  margin-bottom: 6px;
  font-weight: 500;
}

.form-hint {
  font-weight: 400;
  color: #8a8e99;
  font-size: 12px;
}

.required {
  color: #ff4d4f;
}

.form-input {
  width: 100%;
  height: 32px;
  padding: 0 8px;
  font-size: 14px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
  box-sizing: border-box;
}

.form-textarea {
  height: auto;
  padding: 6px 8px;
  resize: vertical;
  font-family: inherit;
}

.form-input:focus {
  border-color: #0073e6;
  outline: none;
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #8a8e99;
}
</style>
