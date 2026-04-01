<template>
  <div class="run-stats">
    <div class="stat-item">
      <span class="stat-label">状态</span>
      <span class="stat-value" :class="'status-' + run.status.toLowerCase()">{{ statusLabel }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">总耗时</span>
      <span class="stat-value">{{ totalDuration }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">步骤</span>
      <span class="stat-value">{{ completedSteps }} / {{ totalSteps }}</span>
    </div>
    <div class="stat-item" v-if="retentionRate">
      <span class="stat-label">留存率</span>
      <span class="stat-value">{{ retentionRate }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PipelineRun, PipelineStepRun } from '@/api/pipeline'
import { parseMetrics } from '@/api/pipeline'

const props = defineProps<{
  run: PipelineRun
  steps: PipelineStepRun[]
}>()

const statusLabel = computed(() => {
  const m: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '等待审核',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return m[props.run.status] || props.run.status
})

const totalDuration = computed(() => {
  if (!props.run.startedAt) return '—'
  const start = new Date(props.run.startedAt).getTime()
  const end = props.run.finishedAt ? new Date(props.run.finishedAt).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
})

const totalSteps = computed(() => props.steps.length)
const completedSteps = computed(() => props.steps.filter(s => s.status === 'SUCCEEDED' || s.status === 'SKIPPED').length)

const retentionRate = computed(() => {
  // 取最后一个有 retention 的 step
  for (let i = props.steps.length - 1; i >= 0; i--) {
    const m = parseMetrics(props.steps[i]!.metrics)
    if (m.retention) return m.retention
  }
  return ''
})
</script>

<style scoped>
.run-stats {
  display: flex; gap: 24px; padding: 12px 16px;
  background: #fff; border-bottom: 1px solid #e8e4df;
}
.stat-item { display: flex; flex-direction: column; }
.stat-label { font-size: 10px; color: #94a3b8; text-transform: uppercase; }
.stat-value { font-size: 14px; font-weight: 600; color: #2c3e50; margin-top: 2px; }
.status-running { color: #3b82f6; }
.status-succeeded { color: #22c55e; }
.status-failed { color: #ef4444; }
.status-paused { color: #eab308; }
.status-pending { color: #94a3b8; }
.status-cancelled { color: #94a3b8; }
</style>
