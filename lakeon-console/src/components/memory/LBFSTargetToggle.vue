<script setup lang="ts">
import { computed, ref } from 'vue'
import { setLBFSMemoryTarget } from '@/api/lbfs'

const props = defineProps<{
  baseId: string
  currentTargetBaseId: string | null
}>()

const emit = defineEmits<{
  (e: 'changed', newTargetId: string): void
  (e: 'error', err: unknown): void
}>()

const isActive = computed(() => props.baseId === props.currentTargetBaseId)
const submitting = ref(false)

async function setTarget() {
  if (isActive.value || submitting.value) return
  submitting.value = true
  try {
    await setLBFSMemoryTarget(props.baseId)
    emit('changed', props.baseId)
  } catch (err) {
    console.error('set LakebaseFS target failed', err)
    emit('error', err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <label class="target-radio" :class="{ active: isActive, disabled: submitting }" @click.stop>
    <input
      type="radio"
      :checked="isActive"
      :value="baseId"
      name="lbfs-target"
      :disabled="submitting"
      @change="setTarget"
    />
    <span class="target-label">LakebaseFS 目标</span>
  </label>
</template>

<style scoped>
.target-radio {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  cursor: pointer;
  user-select: none;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
  transition: background-color 0.15s ease;
}
.target-radio:hover {
  background-color: var(--color-warm-accent-soft, #f3eae0);
}
.target-radio.active .target-label {
  color: var(--color-warm-accent, #c97b3f);
  font-weight: 500;
}
.target-radio.disabled {
  cursor: wait;
  opacity: 0.65;
}
.target-radio input[type='radio'] {
  accent-color: var(--color-warm-accent, #c97b3f);
}
.target-label {
  font-size: 0.875rem;
  color: var(--color-text-secondary, #5c5142);
}
</style>
