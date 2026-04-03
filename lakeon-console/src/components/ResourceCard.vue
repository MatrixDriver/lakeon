<template>
  <div class="resource-card" @click="$emit('click')">
    <div class="rc-top">
      <span class="rc-name">{{ name }}</span>
      <span class="rc-status" :class="statusClass">{{ statusLabel }}</span>
    </div>
    <div class="rc-bottom">
      <div class="rc-meta">
        <slot name="meta">
          <span v-for="(item, i) in meta" :key="i">{{ item }}</span>
        </slot>
      </div>
      <div class="rc-actions" v-if="$slots.actions">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string
  status?: string
  statusLabel?: string
  meta?: string[]
}>()

defineEmits<{ click: [] }>()

const statusClass = computed(() => {
  const s = (props.status || '').toLowerCase()
  if (['running', 'ready', 'active', '运行中', '就绪'].includes(s)) return 'status-on'
  if (['starting', 'creating', '唤醒中', '创建中'].includes(s)) return 'status-starting'
  if (['error', 'failed', '异常', '失败'].includes(s)) return 'status-error'
  return 'status-off'
})
</script>

<style scoped>
.resource-card {
  border: 1px solid #e8e4df; border-radius: 8px; padding: 14px 16px;
  cursor: pointer; transition: box-shadow 0.15s; background: #fff;
}
.resource-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.rc-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.rc-name { font-size: 13px; font-weight: 600; color: #2c3e50; }
.rc-status { font-size: 10px; padding: 2px 6px; border-radius: 3px; }
.status-on { background: #ecfdf5; color: #16a34a; }
.status-starting { background: #eff6ff; color: #2563eb; }
.status-off { background: #f5f3f0; color: #94a3b8; }
.status-error { background: #fef2f2; color: #e6393d; }
.rc-bottom { display: flex; justify-content: space-between; align-items: center; }
.rc-meta { font-size: 11px; color: #94a3b8; display: flex; gap: 12px; }
.rc-actions { opacity: 0; transition: opacity 0.15s; }
.resource-card:hover .rc-actions { opacity: 1; }
</style>
