<template>
  <div class="step-detail-panel">
    <div class="panel-header">
      <span class="panel-title">{{ stepRun.step_id }}</span>
      <span class="step-status" :class="'status-' + stepRun.status.toLowerCase()">{{ statusLabel }}</span>
    </div>

    <!-- 指标 -->
    <div class="panel-section" v-if="Object.keys(metrics).length > 0">
      <div class="section-title">运行指标</div>
      <div class="metrics-grid">
        <div v-for="(val, key) in metrics" :key="key" class="metric-item">
          <span class="metric-label">{{ key }}</span>
          <span class="metric-value">{{ val }}</span>
        </div>
      </div>
    </div>

    <!-- 时间 -->
    <div class="panel-section">
      <div class="section-title">时间</div>
      <div class="time-row">
        <span class="time-label">开始</span>
        <span>{{ formatTime(stepRun.started_at) }}</span>
      </div>
      <div class="time-row">
        <span class="time-label">结束</span>
        <span>{{ formatTime(stepRun.finished_at) }}</span>
      </div>
      <div class="time-row">
        <span class="time-label">耗时</span>
        <span>{{ duration }}</span>
      </div>
    </div>

    <!-- 错误信息 -->
    <div class="panel-section error-section" v-if="stepRun.error">
      <div class="section-title">错误</div>
      <pre class="error-text">{{ stepRun.error }}</pre>
    </div>

    <!-- Checkpoint 预览 -->
    <div class="panel-section" v-if="stepRun.checkpoint_path">
      <div class="section-title">Checkpoint</div>
      <div class="checkpoint-path">{{ stepRun.checkpoint_path }}</div>
      <button class="btn btn-secondary btn-small" style="margin-top: 6px;">预览数据</button>
    </div>

    <!-- 日志 -->
    <div class="panel-section log-section">
      <div class="section-title">
        日志
        <button class="btn btn-text btn-small" @click="loadLogs" :disabled="logsLoading">
          {{ logsLoading ? '加载中...' : '刷新' }}
        </button>
      </div>
      <pre class="log-output" v-if="logs">{{ logs }}</pre>
      <div v-else class="log-empty">点击「刷新」加载日志</div>
    </div>

    <!-- 人工审核操作 -->
    <div class="panel-section" v-if="stepRun.status === 'PAUSED'">
      <div class="section-title">人工审核</div>
      <PipelineRunHumanReview
        :step-run="stepRun"
        @approve="$emit('resume', stepRun.step_id, 'approve')"
        @reject="$emit('resume', stepRun.step_id, 'reject')"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { PipelineStepRun } from '@/api/pipeline'
import { parseMetrics, getStepRunLogs } from '@/api/pipeline'
import PipelineRunHumanReview from './PipelineRunHumanReview.vue'

const props = defineProps<{
  stepRun: PipelineStepRun
  runId: string
}>()

defineEmits<{
  resume: [stepId: string, decision: 'approve' | 'reject']
}>()

const logs = ref('')
const logsLoading = ref(false)

const metrics = computed(() => parseMetrics(props.stepRun.metrics))

const statusLabel = computed(() => {
  const m: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '等待审核',
    SUCCEEDED: '已完成', FAILED: '失败', SKIPPED: '已跳过',
  }
  return m[props.stepRun.status] || props.stepRun.status
})

const duration = computed(() => {
  if (!props.stepRun.started_at) return '—'
  const start = new Date(props.stepRun.started_at).getTime()
  const end = props.stepRun.finished_at ? new Date(props.stepRun.finished_at).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  return `${Math.floor(sec / 60)}m ${sec % 60}s`
})

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await getStepRunLogs(props.runId, props.stepRun.step_id)
    logs.value = res.data.logs
  } catch {
    logs.value = '加载日志失败'
  } finally {
    logsLoading.value = false
  }
}
</script>

<style scoped>
.step-detail-panel {
  width: 320px; border-left: 1px solid #e8e4df; background: #fff;
  overflow-y: auto; padding-bottom: 20px;
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px; border-bottom: 1px solid #f0ede8;
}
.panel-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.step-status { font-size: 11px; padding: 2px 8px; border-radius: 3px; }
.status-running { background: #e8f4fd; color: #2a4d6a; }
.status-succeeded { background: color-mix(in oklch, var(--c-success) 8%, #fff); color: #386b47; }
.status-failed { background: color-mix(in oklch, var(--cs-severe) 8%, #fff); color: #c6333a; }
.status-paused { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: #eab308; }
.status-pending { background: #f5f3f0; color: #94a3b8; }
.status-skipped { background: #f5f3f0; color: #94a3b8; }

.panel-section { padding: 10px 14px; border-bottom: 1px solid #f5f3f0; }
.section-title {
  font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 6px;
  text-transform: uppercase; display: flex; align-items: center; justify-content: space-between;
}

.metrics-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.metric-item { display: flex; flex-direction: column; }
.metric-label { font-size: 10px; color: #999; }
.metric-value { font-size: 13px; font-weight: 600; color: #2c3e50; }

.time-row { display: flex; justify-content: space-between; font-size: 12px; padding: 2px 0; }
.time-label { color: #999; }

.error-section { background: color-mix(in oklch, var(--cs-severe) 8%, #fff); }
.error-text { font-size: 11px; color: #c6333a; white-space: pre-wrap; word-break: break-all; margin: 0; }

.checkpoint-path { font-size: 11px; color: #666; font-family: monospace; word-break: break-all; }

.log-output {
  font-size: 11px; font-family: monospace; background: #1e1e1e; color: #d4d4d4;
  padding: 8px; border-radius: 4px; max-height: 300px; overflow-y: auto;
  white-space: pre-wrap; margin: 0;
}
.log-empty { font-size: 12px; color: #ccc; text-align: center; padding: 16px 0; }
</style>
