<template>
  <div>
    <div class="section-title">超时 & 重试</div>
    <div class="section-desc">超时后作业自动终止。失败重试在下次作业提交时生效。</div>

    <div class="field-row">
      <div class="field-group">
        <label class="field-label">超时时间（秒）</label>
        <input
          class="field-input"
          type="number"
          min="60"
          max="86400"
          :value="timeoutSeconds"
          @input="$emit('update:timeoutSeconds', Number(($event.target as HTMLInputElement).value))"
        />
        <div class="field-hint">默认 3600 秒（1 小时）</div>
      </div>
      <div class="field-group">
        <label class="field-label">失败重试次数</label>
        <input
          class="field-input"
          type="number"
          min="0"
          max="3"
          :value="retryCount"
          @input="$emit('update:retryCount', Number(($event.target as HTMLInputElement).value))"
        />
        <div class="field-hint">范围 0–3，默认 0（不重试）</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{ timeoutSeconds: number; retryCount: number }>()
defineEmits<{ 'update:timeoutSeconds': [v: number]; 'update:retryCount': [v: number] }>()
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; }
.field-row { display: flex; gap: 24px; }
.field-group { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12px; font-weight: 600; color: #374151; }
.field-input { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; width: 140px; outline: none; }
.field-input:focus { border-color: #2a4d6a; }
.field-hint { font-size: 11px; color: #94a3b8; }
</style>
