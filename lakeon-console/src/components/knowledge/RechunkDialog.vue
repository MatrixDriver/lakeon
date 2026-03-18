<template>
  <div v-if="visible" class="dialog-overlay" @click.self="handleClose">
    <div class="dialog-box rechunk-dialog">

      <!-- Phase 1: Parameter input -->
      <template v-if="phase === 'input'">
        <div class="dialog-header">
          <h3>重新切片</h3>
          <button class="dialog-close" @click="handleClose">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">最大 Token 数</label>
            <input
              v-model.number="maxTokens"
              type="number"
              class="form-input"
              min="50"
              max="4096"
              placeholder="默认 400"
            />
            <div class="form-hint">每个切片的最大 token 数，建议 200–600</div>
          </div>
          <div class="form-group">
            <label class="form-label">重叠比例：{{ overlapPercent }}%</label>
            <input
              v-model.number="overlapPercent"
              type="range"
              min="0"
              max="30"
              step="1"
              class="form-range"
            />
            <div class="range-labels">
              <span>0%</span>
              <span>30%</span>
            </div>
            <div class="form-hint">相邻切片之间共享的内容比例</div>
          </div>
          <div class="form-group">
            <label class="form-label">自定义分隔符 <span class="optional">（可选）</span></label>
            <input
              v-model="customSeparator"
              type="text"
              class="form-input"
              placeholder="例如：\n\n 或 ---"
            />
            <div class="form-hint">留空则使用默认分隔策略</div>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="handleClose">取消</button>
          <button class="btn btn-primary" @click="startRechunk" :disabled="submitting">
            {{ submitting ? '提交中...' : '开始重新切片' }}
          </button>
        </div>
      </template>

      <!-- Phase 2: Progress -->
      <template v-else-if="phase === 'progress'">
        <div class="dialog-header">
          <h3>重新切片中</h3>
        </div>
        <div class="dialog-body progress-body">
          <div class="spinner"></div>
          <div class="progress-msg">正在处理文档，请稍候...</div>
          <div class="progress-sub">每 2 秒检查一次处理状态</div>
        </div>
      </template>

      <!-- Phase 3: Comparison -->
      <template v-else-if="phase === 'comparison'">
        <div class="dialog-header">
          <h3>切片完成 — 效果对比</h3>
          <button class="dialog-close" @click="handleClose">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="comparison-grid">
            <!-- Old version -->
            <div class="comparison-col old-col">
              <div class="comparison-header">旧版本</div>
              <div class="stat-row">
                <span class="stat-label">总切片数</span>
                <span class="stat-value blue">{{ oldStats?.total_chunks ?? '—' }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">平均字数</span>
                <span class="stat-value green">{{ oldStats ? Math.round(oldStats.avg_char_count) : '—' }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">异常切片</span>
                <span class="stat-value orange">{{ oldStats?.anomaly_count ?? '—' }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">疑似重复</span>
                <span class="stat-value red">{{ oldStats?.duplicate_count ?? '—' }}</span>
              </div>
            </div>

            <div class="comparison-arrow">&#8594;</div>

            <!-- New version -->
            <div class="comparison-col new-col">
              <div class="comparison-header">新版本</div>
              <div class="stat-row">
                <span class="stat-label">总切片数</span>
                <span class="stat-value blue">{{ newStats?.total_chunks ?? '—' }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">平均字数</span>
                <span class="stat-value green">{{ newStats ? Math.round(newStats.avg_char_count) : '—' }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">异常切片</span>
                <span class="stat-value orange">{{ newStats?.anomaly_count ?? '—' }}</span>
              </div>
              <div class="stat-row">
                <span class="stat-label">疑似重复</span>
                <span class="stat-value red">{{ newStats?.duplicate_count ?? '—' }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-danger-outline" @click="handleRollback" :disabled="rollingBack">
            {{ rollingBack ? '回滚中...' : '回滚到旧版本' }}
          </button>
          <button class="btn btn-primary" @click="handleKeep">保留新版本</button>
        </div>
      </template>

    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import {
  rechunk,
  rechunkRollback,
  getDocument,
  getChunkStats,
  type ChunkStats,
} from '../../api/knowledge'

const props = defineProps<{
  kbId: string
  docId: string
  oldStats: ChunkStats | null
  visible: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'completed'): void
}>()

// Form fields
const maxTokens = ref(400)
const overlapPercent = ref(10)
const customSeparator = ref('')

// State
type Phase = 'input' | 'progress' | 'comparison'
const phase = ref<Phase>('input')
const submitting = ref(false)
const rollingBack = ref(false)
const branchId = ref('')
const newStats = ref<ChunkStats | null>(null)

let pollTimer: ReturnType<typeof setTimeout> | null = null

// Reset to input phase when dialog is opened
watch(() => props.visible, (val) => {
  if (val) {
    phase.value = 'input'
    maxTokens.value = 400
    overlapPercent.value = 10
    customSeparator.value = ''
    submitting.value = false
    rollingBack.value = false
    branchId.value = ''
    newStats.value = null
    stopPolling()
  } else {
    stopPolling()
  }
})

function stopPolling() {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function handleClose() {
  stopPolling()
  emit('close')
}

async function startRechunk() {
  if (submitting.value) return
  submitting.value = true
  try {
    const params: { max_tokens?: number; overlap_ratio?: number; custom_separator?: string } = {
      max_tokens: maxTokens.value,
      overlap_ratio: overlapPercent.value / 100,
    }
    if (customSeparator.value.trim()) {
      params.custom_separator = customSeparator.value.trim()
    }
    const resp = await rechunk(props.kbId, props.docId, params)
    branchId.value = resp.data.branch_id
    phase.value = 'progress'
    schedulePolling()
  } catch (e: any) {
    alert('重新切片失败: ' + (e.response?.data?.error || e.message))
  } finally {
    submitting.value = false
  }
}

function schedulePolling() {
  pollTimer = setTimeout(pollStatus, 2000)
}

async function pollStatus() {
  try {
    const resp = await getDocument(props.docId)
    const doc = resp.data
    if (doc.status === 'READY') {
      // Fetch new stats then show comparison
      try {
        const statsResp = await getChunkStats(props.kbId, props.docId)
        newStats.value = statsResp.data
      } catch {
        newStats.value = null
      }
      phase.value = 'comparison'
      return
    }
    // Still processing — keep polling
    schedulePolling()
  } catch {
    // Retry on error
    schedulePolling()
  }
}

async function handleRollback() {
  if (rollingBack.value || !branchId.value) return
  rollingBack.value = true
  try {
    await rechunkRollback(props.kbId, props.docId, branchId.value)
    emit('completed')
  } catch (e: any) {
    alert('回滚失败: ' + (e.response?.data?.error || e.message))
  } finally {
    rollingBack.value = false
  }
}

function handleKeep() {
  emit('completed')
}
</script>

<style scoped>
.rechunk-dialog {
  width: 560px;
  max-width: 95vw;
}

.form-group {
  margin-bottom: 20px;
}

.form-label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: #333;
  margin-bottom: 6px;
}

.optional {
  font-weight: 400;
  color: #999;
}

.form-input {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 14px;
  color: #333;
  outline: none;
  box-sizing: border-box;
  transition: border-color 0.2s;
}

.form-input:focus {
  border-color: #0073e6;
}

.form-range {
  width: 100%;
  margin: 6px 0 2px;
  accent-color: #0073e6;
  cursor: pointer;
}

.range-labels {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #999;
  margin-bottom: 4px;
}

.form-hint {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

/* Progress phase */
.progress-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 40px 24px;
  gap: 16px;
}

.spinner {
  width: 36px;
  height: 36px;
  border: 3px solid #e5e5e5;
  border-top-color: #0073e6;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.progress-msg {
  font-size: 15px;
  color: #333;
  font-weight: 500;
}

.progress-sub {
  font-size: 13px;
  color: #999;
}

/* Comparison phase */
.comparison-grid {
  display: flex;
  align-items: stretch;
  gap: 0;
}

.comparison-col {
  flex: 1;
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  padding: 16px;
}

.old-col {
  background: #fafafa;
}

.new-col {
  background: #f6ffed;
  border-color: #b7eb8f;
}

.comparison-header {
  font-size: 13px;
  font-weight: 600;
  color: #666;
  margin-bottom: 16px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.comparison-arrow {
  display: flex;
  align-items: center;
  padding: 0 16px;
  font-size: 24px;
  color: #0073e6;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
}

.stat-row:last-child {
  border-bottom: none;
}

.stat-label {
  font-size: 13px;
  color: #666;
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
}

.stat-value.blue   { color: #0073e6; }
.stat-value.green  { color: #52c41a; }
.stat-value.orange { color: #fa8c16; }
.stat-value.red    { color: #e6393d; }

/* Rollback button */
.btn-danger-outline {
  height: 32px;
  padding: 0 16px;
  border: 1px solid #e6393d;
  border-radius: 4px;
  background: #fff;
  color: #e6393d;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-danger-outline:hover:not(:disabled) {
  background: #fff1f0;
}

.btn-danger-outline:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
