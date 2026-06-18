<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">{{ t('数据活动', 'Data activity') }}</h1>
    </div>

    <p class="page-desc">
      {{ t(
        '你的 Lakebase 数据近期的读写和管理活动会逐步汇总在这里。知识、记忆和数据湖活动已迁移到 dbay-agent。',
        'Recent Lakebase reads, writes, and management activity will be summarized here. Knowledge, memory, and datalake activity has moved to dbay-agent.'
      ) }}
    </p>

    <!-- 30-day stats -->
    <div class="activity-stats">
      <div class="stat-card">
        <div class="stat-val">{{ stats.ingests }}</div>
        <div class="stat-label">{{ t('30 天操作', '30-day operations') }}</div>
        <div class="stat-sub">{{ t('数据库创建、恢复、导入等', 'database create, restore, import, and more') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-val">{{ stats.recalls }}</div>
        <div class="stat-label">{{ t('审计事件', 'Audit events') }}</div>
        <div class="stat-sub">{{ t('SQL 审计开启后统计', 'counted when SQL audit is enabled') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-val">{{ stats.traits }}</div>
        <div class="stat-label">{{ t('导入任务', 'Import tasks') }}</div>
        <div class="stat-sub">{{ t('PostgreSQL 迁移任务', 'PostgreSQL migration tasks') }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-val">{{ stats.bases }}</div>
        <div class="stat-label">{{ t('活跃数据库', 'Active databases') }}</div>
        <div class="stat-sub">{{ t('来自 Lakebase 元数据', 'from Lakebase metadata') }}</div>
      </div>
    </div>

    <div class="activity-empty">
      {{ t('Lakebase 活动聚合正在收口到 operation log 和 audit log。当前请在“日志管理”和“用量”页面查看明细。', 'Lakebase activity aggregation is being consolidated around operation logs and audit logs. For now, use Logs and Usage for details.') }}
    </div>

    <!-- Timeline -->
    <div v-if="grouped.length > 0" class="activity-timeline">
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
          '后续这里会只聚合 Lakebase 数据库、LakebaseFS、导入、备份、恢复和审计相关活动。',
          'This page will aggregate Lakebase database, LakebaseFS, import, backup, restore, and audit activity.'
        ) }}
      </p>
      <p>
        <strong>{{ t('还没包含（正在做）', 'Not yet included (in progress)') }}：</strong>
        {{ t(
          '精确的 SQL 级读写统计、API Key 用量逐条记录、FS 文件级访问事件。',
          'Exact SQL-level read/write counts, per-request API key usage, and file-level FS access events.'
        ) }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLocale } from '../../stores/locale'

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

const events = ref<ActivityEvent[]>([])
const stats = ref({ ingests: 0, recalls: 0, traits: 0, bases: 0 })

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
  return op === 'ingest' ? t('写入', 'write') : t('读取', 'read')
}
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
