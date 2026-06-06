<template>
  <div class="agent-run-detail-page">
    <div class="detail-page-header">
      <button class="back-link" type="button" @click="router.push('/agent-state')">返回任务列表</button>
      <div class="detail-heading-row">
        <div>
          <h1 class="page-title">{{ task ? taskTitle(task) : '任务详情' }}</h1>
          <p class="page-subtitle">{{ task?.goal || '查看单次智能体运行的阶段、工作区分支、证据与治理审计。' }}</p>
        </div>
        <span v-if="loadingDetail" class="muted">加载中</span>
        <span v-else-if="task" class="status-pill" :class="statusClass(task)">{{ statusLabel(task) }}</span>
      </div>
    </div>

    <div v-if="task" class="detail-shell">
      <section id="detail" class="section-panel task-overview-panel">
        <div class="panel-header">
          <h2>任务概览</h2>
          <span class="muted">{{ task.harnessId }} · {{ task.id }}</span>
        </div>
        <div class="task-overview-grid">
          <div class="task-overview-item">
            <span>当前阶段</span>
            <strong>{{ stageLabel(task.currentStageId) }}</strong>
          </div>
          <div class="task-overview-item">
            <span>工作区</span>
            <strong>{{ task.workspaceId || '--' }}</strong>
          </div>
          <div class="task-overview-item">
            <span>最新分支</span>
            <strong>{{ task.latestBranchId || '--' }}</strong>
          </div>
          <div class="task-overview-item">
            <span>最新证据</span>
            <strong>{{ task.latestEvidencePacketId || '--' }}</strong>
          </div>
        </div>
        <div class="detail-tabs" role="tablist" aria-label="任务详情视图">
          <button
            v-for="tab in detailTabs"
            :key="tab.value"
            type="button"
            class="detail-tab"
            :class="{ active: activeDetailTab === tab.value }"
            @click="setDetailTab(tab.value)"
          >
            {{ tab.label }}
          </button>
        </div>
      </section>

      <template v-if="activeDetailTab === 'overview'">
        <section id="stages" class="section-panel stages-panel">
          <div class="panel-header">
            <h2>执行阶段</h2>
            <span class="muted">{{ stageCards.length }} 个阶段</span>
          </div>
          <div v-if="stageCards.length" class="timeline" :style="{ gridTemplateColumns: `repeat(${stageCards.length}, minmax(120px, 1fr))` }">
            <div
              v-for="stage in stageCards"
              :key="stage.id"
              class="stage"
              :class="{ done: stage.done, current: stage.current }"
            >
              <div class="stage-label" :title="stage.rawLabel">{{ stage.label }}</div>
              <span>{{ stage.meta }}</span>
            </div>
          </div>
          <div v-else class="empty-state">还没有阶段运行记录。</div>
        </section>

        <section class="section-panel overview-snapshot-panel">
          <div class="panel-header">
            <h2>运行快照</h2>
            <span class="muted">证据、分支与审计摘要</span>
          </div>
          <div class="overview-snapshot-grid">
            <div>
              <span>最新证据</span>
              <strong>{{ latestEvidence?.id || '--' }}</strong>
              <p>{{ latestEvidence?.claim || '还没有证据包。' }}</p>
            </div>
            <div>
              <span>工作区分支</span>
              <strong>{{ selectedDetail?.branches.length || 0 }} 个</strong>
              <p>{{ task.latestBranchId || '还没有工作区分支。' }}</p>
            </div>
            <div>
              <span>治理审计</span>
              <strong>{{ selectedDetail?.auditEvents.length || 0 }} 条</strong>
              <p>{{ latestAuditEvent?.action || '还没有治理审计事件。' }}</p>
            </div>
          </div>
        </section>
      </template>

      <section v-if="activeDetailTab === 'branches'" id="branches" class="section-panel branches-panel">
        <div class="panel-header">
          <div>
            <h2>分支图</h2>
            <p class="panel-subtitle">查看当前 Run 内工作区分支、产物、证据与治理结果的演化关系。</p>
          </div>
          <span class="muted">{{ selectedDetail?.branches.length || 0 }} 个分支</span>
        </div>
        <AgentRunBranchGraph
          :detail="selectedDetail"
          :stage-label="stageLabel"
          :evidence-status-label="evidenceStatusLabel"
          :short-time="shortTime"
        />
      </section>

      <section v-if="activeDetailTab === 'evidence'" id="evidence" class="section-panel evidence-panel">
        <div class="panel-header">
          <h2>证据包</h2>
          <span class="muted">{{ selectedDetail?.evidencePackets.length || 0 }} 个</span>
        </div>
        <div v-for="packet in selectedDetail?.evidencePackets || []" :key="packet.id" class="evidence-box">
          <p><strong>主张</strong> {{ packet.claim || packet.id }}</p>
          <span v-for="ref in packet.evidenceRefs" :key="ref" class="stage-pill">{{ ref }}</span>
          <span class="stage-pill pending">{{ evidenceStatusLabel(packet.status) }}</span>
        </div>
        <div v-if="!selectedDetail?.evidencePackets.length" class="empty-state">还没有证据包。</div>
      </section>

      <section v-if="activeDetailTab === 'audit'" id="audit" class="section-panel audit-panel">
        <div class="panel-header">
          <h2>治理审计</h2>
          <span class="muted">{{ selectedDetail?.auditEvents.length || 0 }} 条</span>
        </div>
        <div v-for="event in selectedDetail?.auditEvents || []" :key="event.id" class="audit-row">
          <span>{{ event.action }} · {{ event.result }}</span>
          <span>{{ shortTime(event.createdAt) }}</span>
        </div>
        <div v-if="!selectedDetail?.auditEvents.length" class="empty-state">还没有治理审计事件。</div>
      </section>

      <section v-if="activeDetailTab === 'outputs'" id="outputs" class="section-panel output-panel">
        <div class="panel-header">
          <h2>运行输出</h2>
          <span class="muted">{{ outputRows.length }} 条</span>
        </div>
        <div v-for="row in outputRows" :key="row.id" class="output-row">
          <div class="output-meta">
            <span class="stage-pill">{{ row.kind }}</span>
            <span class="muted">{{ shortTime(row.createdAt) }}</span>
          </div>
          <pre>{{ row.text }}</pre>
        </div>
        <div v-if="!outputRows.length" class="empty-state">还没有可查看的运行输出。</div>
      </section>
    </div>

    <section v-else-if="!loadingDetail" class="section-panel empty-state">没有找到这个任务运行。</section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentStateApi, type TaskRunDetail, type TaskRunSummary } from '../../api/agent-state'
import AgentRunBranchGraph from '../../components/agent-state/AgentRunBranchGraph.vue'

type DetailTab = 'overview' | 'branches' | 'evidence' | 'audit' | 'outputs'

const route = useRoute()
const router = useRouter()
const selectedDetail = ref<TaskRunDetail | null>(null)
const activeDetailTab = ref<DetailTab>('overview')
const loadingDetail = ref(false)

const detailTabs: { label: string; value: DetailTab; hash: string }[] = [
  { label: '概览', value: 'overview', hash: '#detail' },
  { label: '分支图', value: 'branches', hash: '#branches' },
  { label: '证据', value: 'evidence', hash: '#evidence' },
  { label: '治理审计', value: 'audit', hash: '#audit' },
  { label: '输出', value: 'outputs', hash: '#outputs' },
]

const task = computed(() => selectedDetail.value?.task || null)

const latestEvidence = computed(() => {
  const packets = selectedDetail.value?.evidencePackets || []
  return packets[packets.length - 1] || null
})

const latestAuditEvent = computed(() => {
  const events = selectedDetail.value?.auditEvents || []
  return events[events.length - 1] || null
})

const stageCards = computed(() => {
  const stages = selectedDetail.value?.stages || []
  return stages.map((stage) => ({
    id: stage.id,
    rawLabel: stage.stageId,
    label: stageLabel(stage.stageId),
    meta: stage.branchId || stage.contextPackId || stage.status,
    done: stage.status === 'done' || stage.status === 'completed',
    current: task.value?.currentStageId === stage.stageId,
  }))
})

const outputRows = computed(() => [
  ...(selectedDetail.value?.commits || []).map((commit) => ({
    id: `commit:${commit.id}`,
    kind: 'commit',
    text: commit.summary || commit.id,
    createdAt: commit.createdAt,
  })),
  ...(selectedDetail.value?.artifacts || []).map((artifact) => ({
    id: `artifact:${artifact.id}`,
    kind: artifact.kind,
    text: `${artifact.id} · branch=${artifact.branchId}`,
    createdAt: artifact.createdAt,
  })),
  ...(selectedDetail.value?.auditEvents || [])
    .filter((event) => event.action.startsWith('workflow_trace:') || event.reason)
    .map((event) => ({
      id: `audit:${event.id}`,
      kind: event.action.replace('workflow_trace:', 'trace:'),
      text: event.reason ? `${event.result}\n${event.reason}` : event.result,
      createdAt: event.createdAt,
    })),
])

watch(
  () => route.params.taskRunId,
  async (taskRunId) => {
    syncTabFromHash(route.hash)
    const id = Array.isArray(taskRunId) ? taskRunId[0] : taskRunId
    if (!id) return
    await loadTaskRun(id)
  },
  { immediate: true },
)

watch(() => route.hash, (hash) => {
  syncTabFromHash(hash)
})

async function loadTaskRun(taskRunId: string) {
  loadingDetail.value = true
  try {
    selectedDetail.value = await agentStateApi.getTaskRun(taskRunId)
  } finally {
    loadingDetail.value = false
  }
}

function setDetailTab(tab: DetailTab) {
  activeDetailTab.value = tab
  const hash = detailTabs.find((item) => item.value === tab)?.hash || '#detail'
  if (route.hash !== hash) {
    router.replace({ hash })
  }
}

function syncTabFromHash(hash: string) {
  const next: Record<string, DetailTab> = {
    '#detail': 'overview',
    '#stages': 'overview',
    '#branches': 'branches',
    '#evidence': 'evidence',
    '#audit': 'audit',
    '#outputs': 'outputs',
  }
  activeDetailTab.value = next[hash] || 'overview'
}

function taskTitle(value: TaskRunSummary) {
  return value.goal.length > 56 ? `${value.goal.slice(0, 56)}...` : value.goal
}

function statusLabel(value: TaskRunSummary) {
  if (value.latestAuditResult === 'blocked' || value.status === 'blocked') return '阻塞'
  if (value.latestAuditResult === 'allowed' || value.latestAuditResult === 'pass' || value.status === 'completed') return '完成'
  return '运行中'
}

function statusClass(value: TaskRunSummary) {
  if (statusLabel(value) === '阻塞') return 'blocked'
  if (statusLabel(value) === '完成') return 'done'
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

function evidenceStatusLabel(value?: string | null) {
  const labels: Record<string, string> = {
    pending: '待验证',
    supported: '已支持',
    rejected: '已驳回',
    blocked: '已阻塞',
  }
  return labels[value || ''] || value || '--'
}

function shortTime(value?: string | null) {
  if (!value) return '--'
  return new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>

<style scoped>
.agent-run-detail-page {
  padding: 32px;
  color: #24364a;
}

.detail-page-header {
  margin-bottom: 18px;
}

.back-link {
  margin: 0 0 14px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #a9601f;
  font: inherit;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.detail-heading-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 750;
}

.page-subtitle {
  max-width: 980px;
  margin: 6px 0 0;
  color: #718094;
  line-height: 1.55;
}

.detail-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section-panel {
  min-width: 0;
  border: 1px solid #e2e7ee;
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
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

.muted {
  color: #758397;
  font-size: 12px;
}

.status-pill,
.stage-pill {
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

.task-overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-bottom: 1px solid #edf0f4;
}

.task-overview-item {
  min-width: 0;
  padding: 12px 14px;
  border-right: 1px solid #edf0f4;
}

.task-overview-item:last-child {
  border-right: 0;
}

.task-overview-item span,
.overview-snapshot-grid span {
  display: block;
  margin-bottom: 5px;
  color: #758397;
  font-size: 12px;
}

.task-overview-item strong,
.overview-snapshot-grid strong {
  display: block;
  overflow: hidden;
  color: #25364a;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-tabs {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
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

.timeline {
  display: grid;
  gap: 0;
  padding: 16px 14px;
  overflow-x: auto;
}

.stage {
  min-width: 120px;
  padding: 12px;
  border: 1px solid #e5eaf0;
  border-right: 0;
  background: #fafbfc;
}

.stage:last-child {
  border-right: 1px solid #e5eaf0;
}

.stage.done {
  background: #f4fbf6;
}

.stage.current {
  border-color: #efb35a;
  background: #fff8ec;
}

.stage-label {
  margin-bottom: 8px;
  color: #25364a;
  font-weight: 700;
}

.stage span {
  color: #758397;
  font-size: 12px;
}

.overview-snapshot-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.overview-snapshot-grid > div {
  padding: 14px;
  border-right: 1px solid #edf0f4;
}

.overview-snapshot-grid > div:last-child {
  border-right: 0;
}

.overview-snapshot-grid p {
  margin: 8px 0 0;
  color: #66768a;
  font-size: 12px;
  line-height: 1.45;
}

.evidence-box {
  padding: 16px 14px;
  border-bottom: 1px solid #edf0f4;
}

.evidence-box p {
  margin: 0 0 10px;
  line-height: 1.55;
}

.evidence-box .stage-pill {
  margin-right: 6px;
}

.audit-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border-bottom: 1px solid #edf0f4;
  color: #445268;
  font-size: 13px;
}

.output-row {
  padding: 14px;
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
  padding: 12px;
  border-radius: 4px;
  background: #f6f8fb;
  color: #25364a;
  white-space: pre-wrap;
}

.empty-state {
  padding: 18px 14px;
  color: #758397;
}

#detail,
#stages,
#evidence,
#branches,
#audit,
#outputs {
  scroll-margin-top: 72px;
}

@media (max-width: 900px) {
  .agent-run-detail-page {
    padding: 20px;
  }

  .detail-heading-row,
  .panel-header,
  .audit-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .task-overview-grid,
  .overview-snapshot-grid {
    grid-template-columns: 1fr;
  }

  .task-overview-item,
  .overview-snapshot-grid > div {
    border-right: 0;
    border-bottom: 1px solid #edf0f4;
  }

  .detail-tabs {
    overflow-x: auto;
  }
}
</style>
