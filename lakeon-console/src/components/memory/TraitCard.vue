<template>
  <div class="trait-card">
    <!-- Header: stage badge + subtype -->
    <div class="trait-header">
      <span class="trait-stage-badge" :style="stageBadgeStyle">{{ stageLabel }}</span>
      <span v-if="trait.trait_subtype" class="trait-subtype">{{ subtypeLabel }}</span>
    </div>

    <!-- Content -->
    <p class="trait-content">{{ trait.content }}</p>

    <!-- Footer: confidence + evidence -->
    <div class="trait-footer">
      <div class="confidence-row">
        <div class="confidence-bar">
          <div class="confidence-fill" :style="{ width: pct + '%', background: confidenceColor }"></div>
        </div>
        <span class="confidence-pct" :style="{ color: confidenceColor }">{{ pct }}%</span>
      </div>
      <div class="evidence-row">
        <span class="evidence-item" style="color: #389e0d;">+{{ trait.reinforcement_count }}</span>
        <span class="evidence-item" style="color: #cf1322;">-{{ trait.contradiction_count }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Trait } from '../../api/memory'

const props = defineProps<{ trait: Trait }>()

const stageColors: Record<string, string> = {
  core: '#c19a6b', established: '#8c7a68', emerging: '#389e0d',
  candidate: '#64748b', trend: '#a0aec0',
}
const stageLabels: Record<string, string> = {
  core: '核心', established: '稳定', emerging: '新兴',
  candidate: '候选', trend: '趋势',
}
const subtypeLabels: Record<string, string> = {
  summary: '总结', pattern: '模式', preference: '偏好',
  belief: '观点', habit: '习惯', skill: '技能',
  goal: '目标', context: '背景',
}

const stageLabel = computed(() => stageLabels[props.trait.trait_stage] || props.trait.trait_stage)
const subtypeLabel = computed(() => (props.trait.trait_subtype ? subtypeLabels[props.trait.trait_subtype] : null) || props.trait.trait_subtype)
const pct = computed(() => Math.round(props.trait.confidence * 100))

const confidenceColor = computed(() => {
  const v = props.trait.confidence
  if (v >= 0.8) return '#389e0d'
  if (v >= 0.5) return '#c19a6b'
  if (v >= 0.3) return '#d48806'
  return '#cf1322'
})

const stageBadgeStyle = computed(() => {
  const color = stageColors[props.trait.trait_stage] || '#999'
  return { background: color + '12', color, border: `1px solid ${color}25` }
})
</script>

<style scoped>
.trait-card {
  border: 1px solid #e8e0d8;
  border-radius: 8px;
  padding: 14px 16px;
  background: #fff;
  transition: border-color 0.15s;
}
.trait-card:hover { border-color: #d4c4b0; }

.trait-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.trait-stage-badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.trait-subtype {
  font-size: 11px;
  color: #8c7a68;
}

.trait-content {
  font-size: 13px;
  color: #3d3d3d;
  margin: 0 0 12px;
  line-height: 1.6;
}

.trait-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.confidence-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}
.confidence-bar {
  flex: 1;
  max-width: 80px;
  height: 4px;
  background: #f0ebe4;
  border-radius: 2px;
  overflow: hidden;
}
.confidence-fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s;
}
.confidence-pct {
  font-size: 12px;
  font-weight: 600;
  min-width: 32px;
}

.evidence-row {
  display: flex;
  gap: 8px;
  font-size: 12px;
  font-weight: 500;
}
.evidence-item {
  white-space: nowrap;
}
</style>
