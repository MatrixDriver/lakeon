<template>
  <div class="property-panel">
    <div class="panel-header">
      <span class="panel-title">{{ node.data.label || '节点属性' }}</span>
      <button class="panel-close" @click="$emit('delete', node.id)" title="删除节点">
        <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M2 4h12M5 4V3h6v1M6 7v5M10 7v5M3 4l1 10h8l1-10"/></svg>
      </button>
    </div>

    <div class="panel-section">
      <label class="field-label">步骤 ID</label>
      <input class="field-input" :value="node.data.step?.id" readonly />
    </div>

    <div class="panel-section">
      <label class="field-label">组件</label>
      <div class="field-value">{{ node.data.step?.component || '—' }}</div>
    </div>

    <!-- 动态参数表单 -->
    <div v-if="schemaFields.length > 0" class="panel-section">
      <div class="section-title">参数配置</div>
      <div v-for="field in schemaFields" :key="field.name" class="param-field">
        <label class="field-label">
          {{ field.name }}
          <span v-if="field.description" class="field-hint" :title="field.description">?</span>
        </label>
        <!-- number -->
        <input
          v-if="field.type === 'number'"
          type="number"
          class="field-input"
          :value="currentParams[field.name] ?? field.default"
          @input="updateParam(field.name, Number(($event.target as HTMLInputElement).value))"
        />
        <!-- boolean -->
        <label v-else-if="field.type === 'boolean'" class="field-toggle">
          <input
            type="checkbox"
            :checked="currentParams[field.name] ?? field.default"
            @change="updateParam(field.name, ($event.target as HTMLInputElement).checked)"
          />
          <span>{{ currentParams[field.name] ?? field.default ? '是' : '否' }}</span>
        </label>
        <!-- enum (select) -->
        <select
          v-else-if="field.enum && field.enum.length > 0"
          class="field-input"
          :value="currentParams[field.name] ?? field.default ?? ''"
          @change="updateParam(field.name, ($event.target as HTMLSelectElement).value)"
        >
          <option v-for="opt in field.enum" :key="opt" :value="opt">{{ opt }}</option>
        </select>
        <!-- array (逗号分隔) -->
        <input
          v-else-if="field.type === 'array'"
          class="field-input"
          :value="(currentParams[field.name] ?? field.default ?? []).join(', ')"
          @input="updateParam(field.name, ($event.target as HTMLInputElement).value.split(',').map(s => s.trim()).filter(Boolean))"
          :placeholder="'逗号分隔'"
        />
        <!-- string / fallback -->
        <input
          v-else
          class="field-input"
          :value="currentParams[field.name] ?? field.default ?? ''"
          @input="updateParam(field.name, ($event.target as HTMLInputElement).value)"
        />
      </div>
    </div>

    <!-- Checkpoint 开关 -->
    <div class="panel-section">
      <label class="field-toggle">
        <input
          type="checkbox"
          :checked="node.data.step?.checkpoint"
          @change="toggleCheckpoint(($event.target as HTMLInputElement).checked)"
        />
        <span>Checkpoint（写入 OBS 快照）</span>
      </label>
    </div>

    <!-- 输出分支 -->
    <div v-if="branches.length > 0" class="panel-section">
      <div class="section-title">输出分支</div>
      <div v-for="b in branches" :key="b" class="branch-item">
        <span class="branch-dot"></span> {{ b }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Node } from '@vue-flow/core'
import { parseJsonSchema, parseOutputBranches, type PipelineComponent } from '@/api/pipeline'

interface SchemaField {
  name: string
  type: string
  default?: any
  description?: string
  enum?: string[]
}

const props = defineProps<{
  node: Node
  components: PipelineComponent[]
}>()

const emit = defineEmits<{
  'update:params': [nodeId: string, params: Record<string, any>]
  delete: [nodeId: string]
}>()

const currentParams = computed(() => props.node.data.step?.params || {})

// 从 node.data 中解析 params_schema
const schemaFields = computed<SchemaField[]>(() => {
  const raw = props.node.data.step?.params_schema || props.node.data.paramsSchema
  const schema = typeof raw === 'string' ? parseJsonSchema(raw) : (raw || {})
  return Object.entries(schema).map(([name, def]: [string, any]) => ({
    name,
    type: def?.type || 'string',
    default: def?.default,
    description: def?.description,
    enum: def?.enum,
  }))
})

const branches = computed(() =>
  parseOutputBranches(props.node.data.step?.output_branches_raw || null) ||
  props.node.data.step?.output_branches || []
)

function updateParam(key: string, value: any) {
  const params = { ...currentParams.value, [key]: value }
  emit('update:params', props.node.id, params)
}

function toggleCheckpoint(checked: boolean) {
  const step = { ...props.node.data.step, checkpoint: checked }
  emit('update:params', props.node.id, { ...currentParams.value, __checkpoint: checked })
}
</script>

<style scoped>
.property-panel {
  width: 280px; border-left: 1px solid #e8e4df; background: #fff;
  overflow-y: auto; padding-bottom: 20px;
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px 8px; border-bottom: 1px solid #f0ede8;
}
.panel-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.panel-close {
  background: none; border: none; cursor: pointer; color: #999; padding: 2px;
  border-radius: 3px; transition: all 0.12s;
}
.panel-close:hover { background: #fef2f2; color: #ef4444; }

.panel-section { padding: 10px 14px; border-bottom: 1px solid #f5f3f0; }
.section-title { font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 6px; text-transform: uppercase; }
.field-label { display: block; font-size: 11px; color: #666; margin-bottom: 3px; }
.field-hint {
  display: inline-block; width: 12px; height: 12px; text-align: center;
  border-radius: 50%; background: #e8e4df; color: #666; font-size: 9px;
  line-height: 12px; cursor: help; margin-left: 2px;
}
.field-input {
  width: 100%; padding: 5px 8px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 12px; outline: none; background: #faf9f7;
}
.field-input:focus { border-color: #2a4d6a; }
.field-input[readonly] { color: #999; }
.field-value { font-size: 12px; color: #2c3e50; }
.field-toggle {
  display: flex; align-items: center; gap: 6px; font-size: 12px; color: #2c3e50; cursor: pointer;
}

.param-field { margin-bottom: 8px; }
.branch-item { font-size: 12px; color: #666; padding: 2px 0; display: flex; align-items: center; gap: 6px; }
.branch-dot { width: 6px; height: 6px; border-radius: 50%; background: #e8825a; }
</style>
