<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">{{ t('数据活动', 'Data activity') }}</h1>
    </div>

    <p class="page-desc">
      {{ t(
        '你的 DBay 数据近期的读写时间线。DBay 相信"在你这边"不只是一句话——所以每一次写入、读取、反思都留痕，你随时可以翻看。',
        "A timeline of recent reads and writes on your DBay data. \"On your side\" isn't just a slogan — every ingest, recall, and reflection leaves a trace that you can inspect."
      ) }}
    </p>

    <!-- 30-day stats -->
    <div class="activity-stats">
      <div class="stat-card">
        <div class="stat-val">{{ stats.ingests }}</div>
        <div class="stat-label">{{ t('30 天写入', '30-day writes') }}</div>
        <div class="stat-sub">{{ t('新记忆条目', 'new memory entries') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-val">{{ stats.recalls }}</div>
        <div class="stat-label">{{ t('30 天召回', '30-day recalls') }}</div>
        <div class="stat-sub">{{ t('按 last_accessed 统计，不是实际请求数', 'based on last_accessed, not raw request count') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-val">{{ stats.traits }}</div>
        <div class="stat-label">{{ t('反思洞察', 'Reflection insights') }}</div>
        <div class="stat-sub">{{ t('从你的对话里提炼出的 trait', 'traits distilled from your conversations') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-val">{{ stats.bases }}</div>
        <div class="stat-label">{{ t('活跃记忆库', 'Active memory bases') }}</div>
        <div class="stat-sub">{{ t('其中', 'of which') }} {{ stats.encryptedBases }} {{ t('个为私密', 'are private') }}</div>
      </div>
    </div>

    <div v-if="loading" class="activity-empty">{{ t('加载中…', 'Loading…') }}</div>
    <div v-else-if="grouped.length === 0" class="activity-empty">
      {{ t('没有可显示的活动。创建一个记忆库，让 Agent 写一条记忆试试。', "No activity yet. Create a memory base and have your agent write something to start.") }}
    </div>

    <!-- Timeline -->
    <div v-else class="activity-timeline">
      <div v-for="group in grouped" :key="group.day" class="activity-day">
        <div class="day-header">
          <span class="day-date">{{ formatDay(group.day) }}</span>
          <span class="day-count">{{ group.events.length }} {{ t('条', 'events') }}</span>
        </div>
        <div class="day-rows">
          <div v-for="e in group.events" :key="e.key" class="event-row">
            <div class="event-time">{{ formatTime(e.ts) }}</div>
            <div class="event-op" :class="`op-${e.type}`">{{ opLabel(e.type) }}</div>
            <div class="event-body">
              <span class="event-base">{{ e.base }}</span>
              <span class="event-detail">{{ e.detail }}</span>
              <span v-if="e.source" class="event-source">{{ e.source }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Disclosure -->
    <div class="activity-note">
      <p>
        <strong>{{ t('这里显示的是什么', 'What this page shows') }}：</strong>
        {{ t(
          '当前从你自己的记忆和知识元数据里聚合——写入来自每条记忆的 created_at，召回来自 last_accessed_at。',
          "Today this is aggregated from your own memory and knowledge metadata: writes come from each entry's created_at, recalls from last_accessed_at."
        ) }}
      </p>
      <p>
        <strong>{{ t('还没包含（正在做）', 'Not yet included (in progress)') }}：</strong>
        {{ t(
          'Digest / wiki 生成每次运行的时间戳；MCP 调用每次的 trace；API Key 用量的逐条记录；knowledge 文档 access。',
          "Per-run timestamps of digest and wiki generation; MCP call traces; per-request API key usage; knowledge document access events."
        ) }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useLocale } from '../../stores/locale'
import { listMemoryBases, listMemories, listTraits, type MemoryItem } from '../../api/memory'

const { t } = useLocale()

type EventType = 'ingest' | 'recall'

interface ActivityEvent {
  key: string
  ts: string
  type: EventType
  base: string
  detail: string
  source: string
}

interface DayGroup {
  day: string
  events: ActivityEvent[]
}

const loading = ref(true)
const events = ref<ActivityEvent[]>([])
const stats = ref({ ingests: 0, recalls: 0, traits: 0, bases: 0, encryptedBases: 0 })

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000

function _recent(ts: string | null): boolean {
  if (!ts) return false
  return Date.now() - new Date(ts).getTime() <= THIRTY_DAYS_MS
}

async function load() {
  loading.value = true
  try {
    const res = await listMemoryBases()
    const bases = Array.isArray(res.data) ? res.data : []
    const ready = bases.filter((b: any) => b.status === 'READY')
    stats.value.bases = ready.length
    stats.value.encryptedBases = ready.filter((b: any) => b.encrypted).length

    let ingests = 0
    let recalls = 0
    let traitCount = 0
    const collected: ActivityEvent[] = []

    for (const base of ready) {
      try {
        const page = await listMemories(base.id, { limit: 200 })
        const items: MemoryItem[] = page.data?.memories ?? []
        for (const m of items) {
          if (_recent(m.created_at)) ingests++
          collected.push({
            key: `in-${base.id}-${m.id}`,
            ts: m.created_at,
            type: 'ingest',
            base: base.name,
            detail: m.memory_type,
            source: String(m.metadata?.source ?? ''),
          })
          if (m.last_accessed_at && m.last_accessed_at !== m.created_at) {
            if (_recent(m.last_accessed_at)) recalls++
            collected.push({
              key: `rc-${base.id}-${m.id}`,
              ts: m.last_accessed_at,
              type: 'recall',
              base: base.name,
              detail: `${m.memory_type} · ${m.access_count || 1}×`,
              source: '',
            })
          }
        }

        try {
          const tr = await listTraits(base.id)
          traitCount += Array.isArray(tr.data) ? tr.data.length : 0
        } catch {
          // traits optional
        }
      } catch {
        // single base failure shouldn't block the whole page
      }
    }

    // sort descending
    collected.sort((a, b) => (a.ts < b.ts ? 1 : a.ts > b.ts ? -1 : 0))
    events.value = collected
    stats.value.ingests = ingests
    stats.value.recalls = recalls
    stats.value.traits = traitCount
  } finally {
    loading.value = false
  }
}

const grouped = computed<DayGroup[]>(() => {
  const byDay = new Map<string, ActivityEvent[]>()
  for (const e of events.value) {
    const day = (e.ts || '').slice(0, 10) || 'unknown'
    if (!byDay.has(day)) byDay.set(day, [])
    byDay.get(day)!.push(e)
  }
  return Array.from(byDay.entries()).map(([day, events]) => ({ day, events }))
})

function formatDay(day: string): string {
  if (!day) return ''
  const d = new Date(day + 'T00:00:00')
  return d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' })
}

function formatTime(ts: string): string {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function opLabel(op: EventType): string {
  return op === 'ingest' ? t('写入', 'ingest') : t('召回', 'recall')
}

onMounted(load)
</script>

<style scoped>
.page-desc {
  color: #666;
  font-size: 14px;
  line-height: 1.7;
  max-width: 72ch;
  margin: 0 0 24px;
}

.activity-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 32px;
}

.stat-card {
  background: #fff;
  border: 1px solid #e8e4df;
  border-radius: 6px;
  padding: 20px;
}

.stat-val {
  font-family: 'Source Serif 4', Georgia, serif;
  font-weight: 500;
  font-size: 36px;
  line-height: 1;
  color: #1a3a5c;
  margin-bottom: 6px;
  letter-spacing: -0.01em;
}

.stat-label {
  font-size: 13px;
  color: #333;
  font-weight: 500;
  margin-bottom: 4px;
}

.stat-sub {
  font-size: 11px;
  color: #999;
  line-height: 1.4;
}

.activity-timeline {
  margin-bottom: 32px;
}

.activity-day {
  margin-bottom: 24px;
}

.day-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid #e8e4df;
  margin-bottom: 8px;
}

.day-date {
  font-family: 'Source Serif 4', Georgia, serif;
  font-weight: 500;
  font-size: 16px;
  color: #1a3a5c;
}

.day-count {
  font-size: 11px;
  color: #999;
  font-family: 'JetBrains Mono', monospace;
  letter-spacing: 0.05em;
}

.day-rows {
  display: flex;
  flex-direction: column;
}

.event-row {
  display: grid;
  grid-template-columns: 60px 70px 1fr;
  gap: 16px;
  padding: 6px 0;
  font-size: 13px;
}

.event-time {
  font-family: 'JetBrains Mono', monospace;
  color: #999;
  font-size: 12px;
}

.event-op {
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 2px 8px;
  border-radius: 3px;
  align-self: center;
  text-align: center;
  border: 1px solid transparent;
}

.op-ingest {
  background: #f5f3f0;
  color: #9a5b25;
  border-color: #e8d9c6;
}

.op-recall {
  background: #f0f3f5;
  color: #1a3a5c;
  border-color: #d6dde4;
}

.event-body {
  display: flex;
  gap: 10px;
  align-items: baseline;
  color: #333;
  min-width: 0;
}

.event-base {
  font-weight: 500;
  white-space: nowrap;
}

.event-detail {
  color: #666;
  font-size: 12px;
}

.event-source {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: #9a5b25;
  background: #faf8f5;
  padding: 1px 6px;
  border-radius: 3px;
}

.activity-empty {
  text-align: center;
  color: #999;
  padding: 48px 0;
  font-size: 14px;
}

.activity-note {
  background: #faf8f5;
  border: 1px solid #e8e4df;
  border-radius: 6px;
  padding: 16px 20px;
  font-size: 13px;
  color: #555;
  line-height: 1.7;
}

.activity-note p {
  margin: 0 0 10px;
}

.activity-note p:last-child {
  margin-bottom: 0;
}

.activity-note strong {
  color: #1a3a5c;
  font-weight: 500;
}

@media (max-width: 900px) {
  .activity-stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .event-row {
    grid-template-columns: 60px 70px 1fr;
  }
}
</style>
