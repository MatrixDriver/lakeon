<template>
  <div v-if="visible" class="dialog-overlay" @click.self="emit('close')">
    <div class="dialog-box dialog-confirm">
      <div class="dialog-header">
        <h3>创建分支</h3>
        <button class="dialog-close" @click="emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <div class="form-group">
          <label class="form-label">分支名称 <span class="required">*</span></label>
          <input v-model="name" class="form-input" placeholder="请输入分支名称" />
        </div>
        <div class="form-group">
          <label class="form-label">源分支</label>
          <select v-model="parentId" class="form-input">
            <option v-for="b in branches" :key="b.id" :value="b.id">
              {{ b.name }}{{ b.is_default ? ' (默认)' : '' }}
            </option>
          </select>
        </div>
        <div class="form-group">
          <label class="form-label">Ancestor LSN <span class="form-hint">(可选，留空使用最新位置)</span></label>
          <input v-model="ancestorLsn" class="form-input" placeholder="例如 0/16B3F80" />
        </div>
      </div>
      <div class="dialog-footer">
        <button class="btn btn-default" @click="emit('close')">取消</button>
        <button
          class="btn btn-primary"
          :disabled="!name.trim() || creating"
          @click="handleCreate"
        >{{ creating ? '创建中...' : '确定' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { branchApi, type Branch } from '../../api/branch'

const props = defineProps<{
  visible: boolean
  branches: Branch[]
  preselectedParentId?: string
  dbId: string
}>()

const emit = defineEmits<{
  close: []
  created: []
}>()

const name = ref('')
const parentId = ref('')
const ancestorLsn = ref('')
const creating = ref(false)

watch(() => props.visible, (v) => {
  if (v) {
    name.value = ''
    ancestorLsn.value = ''
    parentId.value = props.preselectedParentId || (props.branches.find(b => b.is_default)?.id ?? '')
  }
})

watch(() => props.preselectedParentId, (id) => {
  if (id) parentId.value = id
})

async function handleCreate() {
  if (!name.value.trim()) return
  creating.value = true
  try {
    await branchApi.create(props.dbId, {
      name: name.value.trim(),
      parent_branch_id: parentId.value || undefined,
      ancestor_lsn: ancestorLsn.value.trim() || undefined,
    })
    emit('created')
    emit('close')
  } catch (e) {
    console.error('Failed to create branch', e)
  } finally {
    creating.value = false
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

.form-input:focus {
  border-color: #0073e6;
  outline: none;
}
</style>
