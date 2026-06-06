<template>
  <div class="agent-state-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">智能体数据平台</h1>
        <p class="page-subtitle">先查看任务运行列表，进入单个任务后再检查分支图、证据与治理审计。</p>
      </div>
      <button class="btn btn-primary">导入任务</button>
    </div>

    <div class="agent-tabs" role="tablist" aria-label="Agent app filters">
      <button
        v-for="filter in taskFilters"
        :key="filter.value"
        class="agent-tab"
        :class="{ active: activeFilter === filter.value }"
        type="button"
        @click="activeFilter = filter.value"
      >
        {{ filter.label }}
      </button>
    </div>

    <div class="kpi-grid">
      <div class="kpi"><span>运行中任务</span><strong>{{ kpis.running }}</strong></div>
      <div class="kpi"><span>阻塞</span><strong>{{ kpis.blocked }}</strong></div>
      <div class="kpi"><span>证据</span><strong>{{ kpis.evidence }}</strong></div>
      <div class="kpi"><span>分支</span><strong>{{ kpis.branches }}</strong></div>
      <div class="kpi"><span>策略拦截</span><strong>{{ kpis.policyBlock }}</strong></div>
    </div>

    <div class="apps-panel section-panel">
      <div class="panel-header">
        <h2>智能体应用</h2>
        <span v-if="loadingApps" class="muted">加载中</span>
        <span v-else class="muted">{{ apps.length }} 个应用</span>
      </div>
      <div v-if="apps.length" class="apps-list">
        <div v-for="app in apps" :key="app.id" class="app-row">
          <div>
            <div class="app-name">{{ app.displayName }}</div>
            <div class="muted">{{ app.key }} · {{ app.type }} · {{ app.version }}</div>
          </div>
          <div class="stage-preview">
            <span v-for="stage in app.stageSchema.slice(0, 5)" :key="stage" class="stage-pill" :title="stage">{{ stageLabel(stage) }}</span>
          </div>
          <span class="status-pill active">{{ appStatusLabel(app.status) }}</span>
        </div>
      </div>
      <div v-else-if="!loadingApps" class="empty-state">还没有智能体应用。可以先注册 PaperBench 或数据智能体模板。</div>
    </div>

    <div class="task-workbench list-only">
      <section id="tasks" class="section-panel task-panel">
        <div class="panel-header">
          <h2>任务运行</h2>
          <span v-if="loadingTasks" class="muted">加载中</span>
          <span v-else class="muted">{{ filteredTasks.length }} 个任务</span>
        </div>
        <button
          v-for="task in filteredTasks"
          :key="task.id"
          class="task-row"
          type="button"
          @click="openTask(task.id)"
        >
          <div class="task-main">
            <div class="task-name">{{ taskTitle(task) }}</div>
            <div class="muted">{{ agentLabel(task) }} · 开始 {{ shortTime(task.createdAt) }} · {{ task.id }}</div>
          </div>
          <span class="status-pill task-status" :class="statusClass(task)">{{ statusLabel(task) }}</span>
          <span class="task-stage" :title="task.currentStageId || 'pending'">{{ stageLabel(task.currentStageId) }}</span>
          <span class="task-metric"><strong>{{ task.branchCount }}</strong><small>分支</small></span>
          <span class="task-metric"><strong>{{ task.evidenceCount }}</strong><small>证据</small></span>
        </button>
        <div v-if="!loadingTasks && !filteredTasks.length" class="empty-state">还没有匹配的任务运行。</div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { agentStateApi, type AgentApp, type TaskRunSummary } from '../../api/agent-state'

const apps = ref<AgentApp[]>([])
const tasks = ref<TaskRunSummary[]>([])
const activeFilter = ref('all')
const loadingApps = ref(true)
const loadingTasks = ref(true)
const router = useRouter()

const taskFilters = [
  { label: '全部任务', value: 'all' },
  { label: 'PaperBench', value: 'paperbench' },
  { label: '数据智能体', value: 'data' },
  { label: '自定义', value: 'custom' },
]

const filteredTasks = computed(() => {
  if (activeFilter.value === 'all') return tasks.value
  if (activeFilter.value === 'custom') return tasks.value.filter((task) => !task.harnessId.includes('paper') && !task.harnessId.includes('data'))
  return tasks.value.filter((task) => task.harnessId.toLowerCase().includes(activeFilter.value))
})

const kpis = computed(() => ({
  running: tasks.value.filter((task) => statusLabel(task) === '运行中').length,
  blocked: tasks.value.filter((task) => statusLabel(task) === '阻塞').length,
  evidence: tasks.value.reduce((total, task) => total + task.evidenceCount, 0),
  branches: tasks.value.reduce((total, task) => total + task.branchCount, 0),
  policyBlock: tasks.value.filter((task) => task.latestAuditResult === 'blocked').length,
}))

const appById = computed(() => new Map(apps.value.map((app) => [app.id, app])))

onMounted(async () => {
  await Promise.all([loadApps(), loadTasks()])
})

async function loadApps() {
  try {
    apps.value = await agentStateApi.listApps()
  } finally {
    loadingApps.value = false
  }
}

async function loadTasks() {
  try {
    tasks.value = await agentStateApi.listTaskRuns()
  } finally {
    loadingTasks.value = false
  }
}

function openTask(taskRunId: string) {
  router.push(`/agent-state/runs/${taskRunId}`)
}

function taskTitle(task: TaskRunSummary) {
  return task.goal.length > 44 ? `${task.goal.slice(0, 44)}...` : task.goal
}

function agentLabel(task: TaskRunSummary) {
  const app = task.agentAppId ? appById.value.get(task.agentAppId) : undefined
  if (app) return app.displayName
  if (task.harnessId.toLowerCase().includes('paper')) return 'PaperBench Agent'
  if (task.harnessId.toLowerCase().includes('data')) return 'Data Agent'
  return `${task.harnessId} Agent`
}

function shortTime(value?: string | null) {
  if (!value) return '--'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

function statusLabel(task: TaskRunSummary) {
  if (task.latestAuditResult === 'blocked' || task.status === 'blocked') return '阻塞'
  if (task.latestAuditResult === 'allowed' || task.latestAuditResult === 'pass' || task.status === 'completed') return '完成'
  return '运行中'
}

function statusClass(task: TaskRunSummary) {
  if (statusLabel(task) === '阻塞') return 'blocked'
  if (statusLabel(task) === '完成') return 'done'
  return 'running'
}

function stageLabel(value?: string | null) {
  const labels: Record<string, string> = {
    paper_parse: '论文解析',
    claim_extract: '主张抽取',
    experiment_run: '实验运行',
    evidence_pack: '证据打包',
    report_gate: '报告门禁',
    policy_check: '策略检查',
    pending: '待处理',
  }
  return labels[value || 'pending'] || value || '待处理'
}

function appStatusLabel(value?: string | null) {
  const labels: Record<string, string> = {
    active: '启用',
    inactive: '停用',
    disabled: '停用',
    draft: '草稿',
  }
  return labels[value || ''] || value || '--'
}
</script>

<style scoped>
.agent-state-page {
  padding: 32px;
  color: #24364a;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 750;
}

.page-subtitle {
  margin: 6px 0 0;
  color: #718094;
}

.agent-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.agent-tab {
  height: 32px;
  padding: 0 13px;
  border: 1px solid #dfe5ec;
  border-radius: 4px;
  background: #fff;
  color: #566477;
  font-weight: 650;
}

.agent-tab.active {
  color: #fff;
  background: #1d2f42;
  border-color: #1d2f42;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.kpi,
.section-panel {
  border: 1px solid #e2e7ee;
  border-radius: 6px;
  background: #fff;
}

.kpi {
  padding: 13px 14px;
}

.kpi span,
.muted {
  color: #758397;
  font-size: 12px;
}

.kpi strong {
  display: block;
  margin-top: 5px;
  font-size: 22px;
}

.apps-panel {
  margin-bottom: 16px;
}

.panel-header {
  min-height: 44px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #edf0f4;
}

.panel-header h2 {
  margin: 0;
  font-size: 15px;
}

.panel-subtitle {
  margin: 3px 0 0;
  color: #758397;
  font-size: 12px;
}

.apps-list,
.task-panel {
  overflow: hidden;
}

.app-row,
.task-row {
  display: grid;
  align-items: center;
  border-bottom: 1px solid #edf0f4;
}

.app-row {
  grid-template-columns: 1fr 1.5fr auto;
  gap: 14px;
  padding: 12px 14px;
}

.app-name,
.detail-title {
  color: #a75710;
  font-weight: 700;
}

.task-name {
  color: #25364a;
  font-weight: 700;
}

.stage-preview {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.stage-pill,
.status-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid #dfe6ee;
  border-radius: 999px;
  font-size: 12px;
  color: #526173;
  background: #f8fafc;
}

.stage-pill.pending,
.status-pill.running {
  color: #8a5c00;
  background: #fff7e3;
  border-color: #f0d89b;
}

.status-pill.active,
.status-pill.done {
  color: #19733b;
  background: #ecf8ef;
  border-color: #c9ead2;
}

.status-pill.blocked {
  color: #a83939;
  background: #fff0f0;
  border-color: #f1cccc;
}

.empty-state {
  padding: 18px 14px;
  color: #758397;
}

.task-workbench {
  display: grid;
  grid-template-columns: minmax(420px, .95fr) minmax(0, 1.35fr);
  gap: 16px;
  align-items: start;
}

.task-workbench.list-only {
  display: block;
}

.task-workbench > .section-panel,
.task-detail-stack > .section-panel {
  min-width: 0;
}

#tasks,
#detail,
#stages,
#evidence,
#branches,
#audit,
#outputs {
  scroll-margin-top: 72px;
}

.task-detail-stack {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 16px;
}

.task-row {
  width: 100%;
  grid-template-columns: minmax(0, 1fr) auto minmax(96px, .45fr) 58px 72px;
  gap: 12px;
  min-height: 72px;
  padding: 10px 14px;
  border: 0;
  border-bottom: 1px solid #edf0f4;
  background: #fff;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: background 140ms ease-out, box-shadow 140ms ease-out;
}

.task-row:hover {
  background: #f9fbfd;
}

.task-row.selected {
  background: #fffaf4;
  box-shadow: inset 3px 0 0 #f08d2f;
}

.task-main {
  min-width: 0;
}

.task-main .muted {
  display: block;
  margin-top: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-name {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  line-height: 1.25;
  font-size: 13px;
}

.task-status {
  justify-self: start;
  white-space: nowrap;
}

.task-stage {
  min-width: 0;
  color: #445268;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.task-metric {
  display: inline-flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 4px;
  color: #617187;
  white-space: nowrap;
}

.task-metric strong {
  color: #25364a;
  font-size: 14px;
  font-weight: 750;
}

.task-metric small {
  color: #7b8798;
  font-size: 11px;
}

.task-overview-panel {
  overflow: hidden;
}

.task-summary {
  padding: 14px 14px 10px;
}

.task-summary .detail-title {
  margin-bottom: 6px;
  color: #25364a;
}

.task-summary p {
  margin: 0;
  line-height: 1.55;
}

.task-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  border-top: 1px solid #edf0f4;
}

.detail-tabs {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
  border-top: 1px solid #edf0f4;
  background: #fbfcfd;
}

.detail-tab {
  min-height: 32px;
  padding: 0 12px;
  border: 1px solid #dfe5ec;
  border-radius: 4px;
  background: #fff;
  color: #566477;
  font: inherit;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.detail-tab.active {
  color: #fff;
  background: #1d2f42;
  border-color: #1d2f42;
}

.task-overview-item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid #edf0f4;
}

.task-overview-item:last-child {
  border-right: 0;
}

.task-overview-item span {
  display: block;
  margin-bottom: 5px;
  color: #758397;
  font-size: 12px;
}

.task-overview-item strong {
  display: block;
  overflow: hidden;
  color: #25364a;
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.timeline {
  display: grid;
  gap: 8px;
  padding: 14px;
  overflow-x: auto;
}

.stage {
  min-width: 0;
  min-height: 58px;
  border: 1px solid #e1e7ee;
  border-radius: 6px;
  padding: 8px;
  background: #fafbfd;
  font-weight: 650;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.stage span {
  display: block;
  margin-top: 5px;
  color: #8491a0;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stage-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stage.done {
  border-color: #c7ead0;
  background: #f1fbf4;
}

.stage.current {
  border-color: #efc476;
  background: #fff8eb;
}

.evidence-box {
  padding: 12px;
}

.evidence-box p {
  margin: 0 0 10px;
  line-height: 1.55;
}

.overview-snapshot-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0;
}

.overview-snapshot-grid > div {
  min-width: 0;
  padding: 14px;
  border-right: 1px solid #edf0f4;
}

.overview-snapshot-grid > div:last-child {
  border-right: 0;
}

.overview-snapshot-grid span {
  display: block;
  margin-bottom: 6px;
  color: #758397;
  font-size: 12px;
}

.overview-snapshot-grid strong {
  display: block;
  overflow: hidden;
  color: #25364a;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.overview-snapshot-grid p {
  display: -webkit-box;
  margin: 8px 0 0;
  overflow: hidden;
  color: #6f7e91;
  font-size: 12px;
  line-height: 1.5;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.branch-workspace-panel {
  min-height: 620px;
}

.audit-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #edf0f4;
  font-size: 12px;
}

.output-row {
  padding: 12px 14px;
  border-bottom: 1px solid #edf0f4;
}

.output-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.output-row pre {
  margin: 0;
  padding: 10px;
  border: 1px solid #e4e9ef;
  border-radius: 6px;
  background: #f8fafc;
  color: #26364a;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
