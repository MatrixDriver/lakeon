<template>
  <div>
    <div class="section-title">资源</div>

    <template v-if="type === 'PYTHON'">
      <div class="section-desc">为容器分配 CPU 和内存。CCI 固定规格，requests = limits。</div>
      <div class="resource-row">
        <div class="field-group">
          <label class="field-label">CPU</label>
          <select class="field-select" :value="cpu" @change="$emit('update:cpu', ($event.target as HTMLSelectElement).value)">
            <option value="0.5">0.5 核</option>
            <option value="1">1 核</option>
            <option value="2">2 核</option>
            <option value="4">4 核</option>
            <option value="8">8 核</option>
          </select>
        </div>
        <div class="field-group">
          <label class="field-label">内存</label>
          <select class="field-select" :value="memory" @change="$emit('update:memory', ($event.target as HTMLSelectElement).value)">
            <option value="1Gi">1 Gi</option>
            <option value="2Gi">2 Gi</option>
            <option value="4Gi">4 Gi</option>
            <option value="8Gi">8 Gi</option>
            <option value="16Gi">16 Gi</option>
          </select>
        </div>
      </div>
    </template>

    <template v-else-if="type === 'RAY'">
      <div class="section-desc">为 Ray 集群的 Head 节点和 Worker 节点分别配置资源。</div>

      <div class="subsection">
        <div class="subsection-title">Head 节点</div>
        <div class="resource-row">
          <div class="field-group">
            <label class="field-label">CPU</label>
            <select class="field-select" :value="head.cpu" @change="$emit('update:head', { ...head, cpu: ($event.target as HTMLSelectElement).value })">
              <option value="1">1 核</option>
              <option value="2">2 核</option>
              <option value="4">4 核</option>
            </select>
          </div>
          <div class="field-group">
            <label class="field-label">内存</label>
            <select class="field-select" :value="head.memory" @change="$emit('update:head', { ...head, memory: ($event.target as HTMLSelectElement).value })">
              <option value="2Gi">2 Gi</option>
              <option value="4Gi">4 Gi</option>
              <option value="8Gi">8 Gi</option>
            </select>
          </div>
        </div>
      </div>

      <div class="subsection">
        <div class="subsection-title">Worker 节点</div>
        <div class="resource-row">
          <div class="field-group">
            <label class="field-label">副本数</label>
            <select class="field-select" :value="workers.replicas" @change="$emit('update:workers', { ...workers, replicas: Number(($event.target as HTMLSelectElement).value) })">
              <option v-for="n in 8" :key="n" :value="n">{{ n }}</option>
            </select>
          </div>
          <div class="field-group">
            <label class="field-label">CPU</label>
            <select class="field-select" :value="workers.cpu" @change="$emit('update:workers', { ...workers, cpu: ($event.target as HTMLSelectElement).value })">
              <option value="1">1 核</option>
              <option value="2">2 核</option>
              <option value="4">4 核</option>
            </select>
          </div>
          <div class="field-group">
            <label class="field-label">内存</label>
            <select class="field-select" :value="workers.memory" @change="$emit('update:workers', { ...workers, memory: ($event.target as HTMLSelectElement).value })">
              <option value="2Gi">2 Gi</option>
              <option value="4Gi">4 Gi</option>
              <option value="8Gi">8 Gi</option>
            </select>
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="coming-soon">
        <div class="coming-soon-icon">🚧</div>
        <div>GPU 资源配置即将推出</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import type { DatalakeJobType } from '../../../api/datalake'
defineProps<{
  type: DatalakeJobType
  cpu: string
  memory: string
  head: { cpu: string; memory: string }
  workers: { replicas: number; cpu: string; memory: string }
}>()
defineEmits<{
  'update:cpu': [v: string]
  'update:memory': [v: string]
  'update:head': [v: { cpu: string; memory: string }]
  'update:workers': [v: { replicas: number; cpu: string; memory: string }]
}>()
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 16px; }
.resource-row { display: flex; gap: 16px; }
.field-group { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 12px; font-weight: 600; color: #374151; }
.field-select { background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; min-width: 120px; outline: none; }
.coming-soon { background: #f8fafc; border: 2px dashed #e2e8f0; border-radius: 8px; padding: 32px; text-align: center; font-size: 13px; color: #64748b; }
.coming-soon-icon { font-size: 28px; margin-bottom: 8px; }
.subsection { margin-bottom: 16px; }
.subsection-title { font-size: 13px; font-weight: 600; color: #1e293b; margin-bottom: 8px; }
</style>
