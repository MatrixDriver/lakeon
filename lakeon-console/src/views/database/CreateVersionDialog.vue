<template>
  <div v-if="visible" class="dialog-overlay" @click.self="emit('close')">
    <div class="dialog-box dialog-confirm">
      <div class="dialog-header">
        <h3>创建版本</h3>
        <button class="dialog-close" @click="emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <div class="form-group">
          <label class="form-label">版本名称 <span class="required">*</span></label>
          <input v-model="name" class="form-input" placeholder="例: 添加用户表" />
        </div>
        <div class="form-group">
          <label class="form-label">描述 <span class="form-hint">(可选)</span></label>
          <textarea v-model="description" class="form-input form-textarea" rows="3" placeholder="可选描述"></textarea>
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
import { ref, watch } from 'vue'
import { versionApi } from '../../api/version'

const props = defineProps<{
  visible: boolean
  dbId: string
  branchId: string
}>()

const emit = defineEmits<{
  close: []
  created: []
}>()

const name = ref('')
const description = ref('')
const loading = ref(false)

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
</style>
