<template>
  <div>
    <div class="section-title">基本信息</div>
    <div class="section-desc">为作业取一个名字，并选择运行类型。</div>

    <div class="field-group">
      <label class="field-label">作业名称 <span class="required">*</span></label>
      <input
        class="field-input"
        :value="name"
        @input="$emit('update:name', ($event.target as HTMLInputElement).value)"
        placeholder="例如：weekly-data-clean"
        autofocus
      />
    </div>

    <div class="field-group">
      <label class="field-label">作业类型 <span class="required">*</span></label>
      <div class="type-pills">
        <button
          v-for="t in types"
          :key="t.value"
          class="type-pill"
          :class="{ active: type === t.value }"
          @click="$emit('update:type', t.value)"
        >
          {{ t.label }}
        </button>
      </div>
      <div class="field-hint">{{ typeHints[type] }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { DatalakeJobType } from '../../../api/datalake'

defineProps<{ name: string; type: DatalakeJobType }>()
defineEmits<{
  'update:name': [value: string]
  'update:type': [value: DatalakeJobType]
}>()

const types: { value: DatalakeJobType; label: string }[] = [
  { value: 'PYTHON', label: 'Python' },
  { value: 'RAY',    label: 'Ray' },
  { value: 'FINETUNE', label: 'Finetune' },
]

const typeHints: Record<DatalakeJobType, string> = {
  PYTHON: '单容器脚本，适合数据处理、ETL、API 调用等轻量任务',
  RAY: '分布式 Ray 集群，适合大规模并行计算',
  FINETUNE: '基于 Ray Train 的模型微调，支持 Qwen/LLaMA 等',
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; line-height: 1.5; }
.field-group { margin-bottom: 18px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 6px; }
.required { color: #ef4444; }
.field-input { width: 100%; max-width: 400px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; outline: none; }
.field-input:focus { border-color: #2563eb; box-shadow: 0 0 0 2px rgba(37,99,235,.1); }
.type-pills { display: flex; gap: 8px; flex-wrap: wrap; }
.type-pill { padding: 7px 16px; border: 2px solid #e2e8f0; border-radius: 20px; font-size: 12px; font-weight: 600; color: #64748b; cursor: pointer; background: #fff; transition: all .15s; }
.type-pill:hover { border-color: #94a3b8; color: #1e293b; }
.type-pill.active { border-color: #2563eb; background: #eff6ff; color: #2563eb; }
.field-hint { font-size: 11px; color: #94a3b8; margin-top: 6px; line-height: 1.5; }
</style>
