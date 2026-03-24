<template>
  <div class="trait-card">
    <div class="trait-header">
      <span class="trait-stage-badge" :style="stageBadgeStyle">{{ stageLabel }}</span>
      <span v-if="trait.trait_subtype" class="trait-subtype">{{ trait.trait_subtype }}</span>
    </div>
    <p class="trait-content">{{ trait.content }}</p>
    <div class="trait-meta">
      <div class="confidence-bar-wrapper">
        <span style="font-size:12px;color:#999;">置信度</span>
        <div class="confidence-bar">
          <div class="confidence-fill" :style="{ width: (trait.confidence * 100) + '%' }"></div>
        </div>
        <span style="font-size:12px;color:#666;">{{ (trait.confidence * 100).toFixed(0) }}%</span>
      </div>
      <div style="display:flex;gap:16px;font-size:12px;color:#999;">
        <span>支持 {{ trait.reinforcement_count }}</span>
        <span>反驳 {{ trait.contradiction_count }}</span>
      </div>
    </div>
    <p v-if="trait.context" class="trait-context">{{ trait.context }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Trait } from '../../api/memory'

const props = defineProps<{ trait: Trait }>()

const stageColors: Record<string, string> = {
  core: '#e6393d', established: '#d48806', emerging: '#389e0d',
  candidate: '#1890ff', trend: '#999',
}
const stageLabels: Record<string, string> = {
  core: '核心', established: '稳定', emerging: '新兴',
  candidate: '候选', trend: '趋势',
}
const stageLabel = computed(() => stageLabels[props.trait.trait_stage] || props.trait.trait_stage)
const stageBadgeStyle = computed(() => {
  const color = stageColors[props.trait.trait_stage] || '#999'
  return { background: color + '15', color, border: `1px solid ${color}30` }
})
</script>

<style scoped>
.trait-card { border: 1px solid #ebebeb; border-radius: 6px; padding: 16px; margin-bottom: 12px; }
.trait-card:hover { border-color: #d5d8dc; }
.trait-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.trait-stage-badge { padding: 2px 8px; border-radius: 4px; font-size: 12px; font-weight: 500; }
.trait-subtype { font-size: 12px; color: #999; }
.trait-content { font-size: 14px; color: #333; margin: 0 0 12px; line-height: 1.5; }
.trait-meta { display: flex; justify-content: space-between; align-items: center; }
.confidence-bar-wrapper { display: flex; align-items: center; gap: 8px; }
.confidence-bar { width: 100px; height: 6px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.confidence-fill { height: 100%; background: #e6393d; border-radius: 3px; transition: width 0.3s; }
.trait-context { font-size: 12px; color: #999; margin-top: 8px; font-style: italic; }
</style>
