<template>
  <div class="agent-state-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">Agent 工作台</h1>
        <p class="page-subtitle">按 Agent App 查看长程任务运行、Evidence、工作区分支和治理审计。</p>
      </div>
      <button class="btn btn-primary">导入任务</button>
    </div>

    <div class="agent-tabs" role="tablist" aria-label="Agent app filters">
      <button class="agent-tab active" type="button">全部任务</button>
      <button class="agent-tab" type="button">PaperBench</button>
      <button class="agent-tab" type="button">Data Agent</button>
      <button class="agent-tab" type="button">Custom</button>
    </div>

    <div class="kpi-grid">
      <div class="kpi"><span>运行中 Task</span><strong>3</strong></div>
      <div class="kpi"><span>阻塞</span><strong>1</strong></div>
      <div class="kpi"><span>Evidence</span><strong>12</strong></div>
      <div class="kpi"><span>分支</span><strong>8</strong></div>
      <div class="kpi"><span>Policy Block</span><strong>2</strong></div>
    </div>

    <div class="apps-panel section-panel">
      <div class="panel-header">
        <h2>Agent Apps</h2>
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
            <span v-for="stage in app.stageSchema.slice(0, 5)" :key="stage" class="stage-pill">{{ stage }}</span>
          </div>
          <span class="status-pill active">{{ app.status }}</span>
        </div>
      </div>
      <div v-else-if="!loadingApps" class="empty-state">还没有 Agent App。可以先注册 PaperBench 或 Data Agent 模板。</div>
    </div>

    <div class="workbench-grid">
      <section class="section-panel task-panel">
        <div class="panel-header">
          <h2>任务运行</h2>
          <span class="muted">按应用、状态、阶段筛选</span>
        </div>
        <div class="task-row selected">
          <div>
            <div class="task-name">paperbench-toy-claim</div>
            <div class="muted">论文复现实验助手</div>
          </div>
          <span class="status-pill running">运行中</span>
          <span>evidence_pack</span>
          <span>3 分支</span>
          <span>5 Evidence</span>
        </div>
        <div class="task-row">
          <div>
            <div class="task-name">data-dbt-revenue</div>
            <div class="muted">数据发布检查助手</div>
          </div>
          <span class="status-pill blocked">阻塞</span>
          <span>policy_check</span>
          <span>2 分支</span>
          <span>4 Evidence</span>
        </div>
        <div class="task-row">
          <div>
            <div class="task-name">paperbench-vision-ablation</div>
            <div class="muted">论文复现实验助手</div>
          </div>
          <span class="status-pill done">完成</span>
          <span>report_gate</span>
          <span>4 分支</span>
          <span>7 Evidence</span>
        </div>
      </section>

      <section class="section-panel detail-panel">
        <div class="panel-header">
          <h2>任务详情</h2>
          <span class="status-pill running">report gate 待验证</span>
        </div>
        <div class="detail-title">paperbench-toy-claim</div>
        <p class="muted">目标：verify the toy claim reaches 95% accuracy</p>
        <div class="timeline">
          <div class="stage done">paper_parse<span>done</span></div>
          <div class="stage done">claim_extract<span>commit</span></div>
          <div class="stage done">experiment<span>branch_1</span></div>
          <div class="stage current">evidence_pack<span>packet_1</span></div>
          <div class="stage">report_gate<span>pending</span></div>
        </div>
        <div class="evidence-box">
          <h3>Evidence Packet</h3>
          <p><strong>Claim</strong> Toy classifier reaches 95% accuracy.</p>
          <span class="stage-pill">artifact_log_1</span>
          <span class="stage-pill">metric_accuracy</span>
          <span class="stage-pill pending">evaluate pending</span>
        </div>
      </section>

      <section class="section-panel">
        <div class="panel-header"><h2>工作区分支</h2></div>
        <div class="branch-dag">
          <span>branch_root</span>
          <b>→</b>
          <span>exp_lr_1</span>
          <b>→</b>
          <span>evidence_pack</span>
        </div>
      </section>

      <section class="section-panel">
        <div class="panel-header"><h2>Policy & Audit</h2></div>
        <div class="audit-row"><span>claim_extract committed</span><span>09:41</span></div>
        <div class="audit-row"><span>artifact captured</span><span>09:48</span></div>
        <div class="audit-row"><span>evidence packet created</span><span>09:52</span></div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { agentStateApi, type AgentApp } from '../../api/agent-state'

const apps = ref<AgentApp[]>([])
const loadingApps = ref(true)

onMounted(async () => {
  try {
    apps.value = await agentStateApi.listApps()
  } finally {
    loadingApps.value = false
  }
})
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
.task-name,
.detail-title {
  color: #a75710;
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

.workbench-grid {
  display: grid;
  grid-template-columns: 1.35fr .95fr;
  gap: 16px;
}

.task-row {
  grid-template-columns: 1.2fr .6fr .8fr .55fr .65fr;
  gap: 10px;
  min-height: 64px;
  padding: 0 14px;
}

.task-row.selected {
  background: #fff8ef;
  box-shadow: inset 3px 0 0 #f08d2f;
}

.detail-panel {
  padding-bottom: 14px;
}

.detail-panel > .detail-title,
.detail-panel > p,
.timeline,
.evidence-box {
  margin-left: 14px;
  margin-right: 14px;
}

.detail-title {
  margin-top: 14px;
}

.timeline {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 8px;
  margin-top: 14px;
  margin-bottom: 14px;
}

.stage {
  min-height: 58px;
  border: 1px solid #e1e7ee;
  border-radius: 6px;
  padding: 8px;
  background: #fafbfd;
  font-weight: 650;
  font-size: 12px;
}

.stage span {
  display: block;
  margin-top: 5px;
  color: #8491a0;
  font-weight: 500;
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
  border: 1px solid #e3e8ef;
  border-radius: 6px;
  padding: 12px;
}

.evidence-box h3 {
  margin: 0 0 8px;
  font-size: 14px;
}

.branch-dag {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 18px 14px;
}

.branch-dag span {
  border: 1px solid #d7e0ea;
  border-radius: 5px;
  padding: 7px 9px;
  font-size: 12px;
  font-weight: 650;
}

.branch-dag b {
  color: #99a5b4;
}

.audit-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #edf0f4;
  font-size: 12px;
}
</style>
