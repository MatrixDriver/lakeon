<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">调用链追踪</h1>
    </div>

    <!-- Input Bar -->
    <div class="trace-input-bar">
      <input
        type="text"
        class="trace-input"
        placeholder="输入 Request ID..."
        v-model="inputId"
        @keyup.enter="doTrace"
      />
      <button class="btn-trace" @click="doTrace" :disabled="loading">
        {{ loading ? '追踪中...' : '追踪' }}
      </button>
    </div>

    <!-- Timeline -->
    <div v-if="loading" class="empty-state">加载中...</div>
    <div v-else-if="entries.length === 0" class="empty-state">
      输入 Request ID 开始追踪
    </div>
    <div v-else class="timeline">
      <div
        v-for="(entry, idx) in entries"
        :key="idx"
        class="timeline-item"
      >
        <div class="timeline-dot" :style="{ background: componentDotColor(entry.component) }"></div>
        <div class="timeline-card">
          <div class="card-header">
            <span class="ts-text">{{ formatTs(entry.ts) }}</span>
            <span class="component-badge" :style="{ color: componentColor(entry.component) }">{{ entry.component }}</span>
            <span class="level-badge" :class="'level-' + (entry.level || '').toLowerCase()">{{ entry.level }}</span>
            <span v-if="idx > 0 && deltaMs(entries[idx - 1].ts, entry.ts) !== null" class="delta-badge">
              +{{ deltaMs(entries[idx - 1].ts, entry.ts) }}ms
            </span>
          </div>
          <div v-if="entry.logger" class="logger-line">{{ entry.logger }}</div>
          <div class="msg-line">{{ entry.msg }}</div>
          <div v-if="entry.duration_ms != null" class="duration-line">耗时 {{ entry.duration_ms }} ms</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

const props = defineProps<{ requestId?: string }>()

interface TraceEntry {
  ts: string
  level: string
  component: string
  logger?: string
  msg: string
  duration_ms?: number
}

const inputId = ref(props.requestId ?? '')
const entries = ref<TraceEntry[]>([])
const loading = ref(false)

function formatTs(ts: string): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return ts
  }
}

function deltaMs(prevTs: string, curTs: string): number | null {
  try {
    const diff = new Date(curTs).getTime() - new Date(prevTs).getTime()
    return isNaN(diff) ? null : diff
  } catch {
    return null
  }
}

function componentDotColor(c: string): string {
  if (c === 'lakeon-api') return '#2a4d6a'
  if (c === 'knowledge-pipeline') return '#3a7d5c'
  return '#aaa'
}

function componentColor(c: string): string {
  if (c === 'lakeon-api') return '#2a4d6a'
  if (c === 'knowledge-pipeline') return '#3a7d5c'
  if (c === 'pageserver') return '#8b6914'
  return '#666'
}

async function doTrace() {
  const id = inputId.value.trim()
  if (!id) return
  loading.value = true
  entries.value = []
  try {
    const { data } = await adminApi.logTrace(id)
    entries.value = Array.isArray(data) ? data : (data.entries ?? [])
  } catch (e) {
    console.error('logTrace failed', e)
    entries.value = []
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (props.requestId) {
    doTrace()
  }
})
</script>

<style scoped>
.trace-input-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 20px;
  padding: 12px 16px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
}
.trace-input {
  flex: 1;
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  font-family: monospace;
  color: #2c3e50;
  max-width: 500px;
}
.trace-input:focus { outline: none; border-color: #c67d3a; }
.btn-trace {
  height: 34px;
  padding: 0 20px;
  background: #c67d3a;
  color: #fff;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}
.btn-trace:hover { background: #a8662e; }
.btn-trace:disabled { opacity: 0.6; cursor: default; }

.empty-state {
  padding: 60px;
  text-align: center;
  color: #999;
  font-size: 14px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #ebebeb;
}

.timeline {
  position: relative;
  padding-left: 32px;
}
.timeline::before {
  content: '';
  position: absolute;
  left: 12px;
  top: 20px;
  bottom: 20px;
  width: 2px;
  background: #e8e2d9;
}
.timeline-item {
  position: relative;
  margin-bottom: 12px;
}
.timeline-dot {
  position: absolute;
  left: -26px;
  top: 16px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #d9d4cc;
}
.timeline-card {
  background: #fff;
  border: 1px solid #ebebeb;
  border-radius: 6px;
  padding: 12px 16px;
  transition: box-shadow 0.15s;
}
.timeline-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.07); }

.card-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.ts-text { font-size: 12px; color: #888; font-family: monospace; white-space: nowrap; }
.component-badge { font-size: 12px; font-weight: 600; }
.level-badge {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.4px;
}
.level-error { background: #fff0f0; color: #e74c3c; }
.level-warn  { background: #fff7e6; color: #f39c12; }
.level-info  { background: #f0fff4; color: #2ecc71; }
.level-debug { background: #f5f5f5; color: #95a5a6; }
.delta-badge {
  font-size: 11px;
  color: #9a7b5a;
  background: #fef6ee;
  padding: 1px 6px;
  border-radius: 3px;
  border: 1px solid #f0e0c8;
}
.logger-line {
  font-size: 11px;
  color: #aaa;
  font-family: monospace;
  margin-bottom: 4px;
}
.msg-line { font-size: 13px; color: #333; white-space: pre-wrap; word-break: break-all; }
.duration-line { font-size: 12px; color: #c67d3a; margin-top: 6px; }
</style>
