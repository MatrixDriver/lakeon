<template>
  <div class="branch-graph">
    <div v-if="!branchNodes.length" class="empty-state">还没有工作区分支。</div>
    <div v-else class="branch-graph-workspace">
      <div class="branch-graph-card">
        <div class="branch-graph-header">
          <h3>工作区分支图</h3>
          <div class="legend">
            <span><i class="dot active"></i>活跃</span>
            <span><i class="dot verified"></i>已验证</span>
            <span><i class="dot blocked"></i>阻塞</span>
          </div>
        </div>
        <div class="branch-graph-canvas" data-test="branch-graph-canvas">
          <div class="branch-graph-plane" :style="{ width: `${canvasSize.width}px`, height: `${canvasSize.height}px` }">
            <svg class="branch-graph-edges" :width="canvasSize.width" :height="canvasSize.height" aria-hidden="true">
              <line
                v-for="edge in edges"
                :key="`${edge.from}-${edge.to}`"
                :x1="edge.x1"
                :y1="edge.y1"
                :x2="edge.x2"
                :y2="edge.y2"
                class="branch-edge"
              />
            </svg>
            <button
              v-for="node in branchNodes"
              :key="node.id"
              class="branch-node"
              :class="[node.statusClass, { selected: node.id === selectedNodeId }]"
              :style="{ left: `${node.x}px`, top: `${node.y}px` }"
              :data-test="`branch-node-${node.branch.id}`"
              type="button"
              @click="selectedNodeId = node.id"
            >
              <span class="branch-node-title" :title="node.branch.name || node.branch.id">{{ node.branch.name || node.branch.id }}</span>
              <span class="branch-node-id">{{ node.branch.id }}</span>
              <span class="branch-node-meta">{{ node.stageName }} · {{ nodeCounts(node).artifacts }} 产物 · {{ nodeCounts(node).evidence }} 证据</span>
            </button>
          </div>
        </div>
      </div>

      <div class="branch-inspector" data-test="branch-graph-inspector">
        <div class="branch-inspector-header">
          <h3>选中节点详情</h3>
          <span class="status-pill" :class="selectedNode?.statusClass">{{ selectedNode?.statusLabel || '--' }}</span>
        </div>
        <div v-if="selectedNode" class="branch-inspector-body">
          <section>
            <h4>节点上下文</h4>
            <dl>
              <div><dt>分支</dt><dd>{{ selectedNode.branch.id }}</dd></div>
              <div><dt>父分支</dt><dd>{{ selectedNode.displayParentBranchId || 'workspace root' }}</dd></div>
              <div><dt>阶段</dt><dd>{{ selectedNode.stageName }}</dd></div>
              <div><dt>创建</dt><dd>{{ shortTime(selectedNode.branch.createdAt) }}</dd></div>
            </dl>
          </section>

          <section>
            <h4>Evidence 与产物</h4>
            <p v-if="selectedEvidence">{{ selectedEvidence.claim || selectedEvidence.id }}</p>
            <p v-else class="muted">当前分支还没有 Evidence。</p>
            <div class="chip-row artifact-list" data-test="branch-artifact-list">
              <span
                v-for="artifact in selectedArtifacts"
                :key="artifact.id"
                class="chip"
                data-test="branch-artifact-chip"
              >
                {{ artifact.kind }} · {{ artifact.id }}
              </span>
              <span v-if="selectedEvidence" class="chip evidence-chip">{{ evidenceStatusLabel(selectedEvidence.status) }}</span>
            </div>
            <p v-if="selectedCommit" class="commit-summary">{{ selectedCommit.summary || selectedCommit.id }}</p>
          </section>

          <section>
            <h4>治理结果</h4>
            <dl>
              <div><dt>审计</dt><dd>{{ selectedAuditEvents.length }} events</dd></div>
              <div><dt>拦截</dt><dd>{{ selectedBlockedAudits.length }} block</dd></div>
              <div><dt>最近</dt><dd>{{ latestAudit?.action || '--' }}</dd></div>
            </dl>
            <p v-if="latestAudit?.reason" class="audit-reason">{{ latestAudit.reason }}</p>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  ArtifactDetail,
  AuditEventDetail,
  BranchDetail,
  EvidencePacketDetail,
  StateCommitDetail,
  TaskRunDetail,
} from '../../api/agent-state'

const props = defineProps<{
  detail: TaskRunDetail | null
  stageLabel: (value?: string | null) => string
  evidenceStatusLabel: (value?: string | null) => string
  shortTime: (value?: string | null) => string
}>()

const nodeWidth = 178
const nodeHeight = 86
const columnGap = 250
const rowGap = 122
const marginX = 24
const marginY = 28

const selectedNodeId = ref<string | null>(null)

const stagesById = computed(() => {
  const map = new Map<string, string>()
  for (const stage of props.detail?.stages || []) {
    map.set(stage.id, props.stageLabel(stage.stageId))
  }
  return map
})

const branchDepths = computed(() => {
  const branches = props.detail?.branches || []
  const byId = new Map(branches.map((branch) => [branch.id, branch]))
  const depths = new Map<string, number>()

  function depthFor(branch: BranchDetail): number {
    if (depths.has(branch.id)) return depths.get(branch.id) || 0
    const parentBranchId = displayParentBranchId(branch)
    if (!parentBranchId || !byId.has(parentBranchId)) {
      depths.set(branch.id, 0)
      return 0
    }
    const parentDepth = depthFor(byId.get(parentBranchId) as BranchDetail)
    depths.set(branch.id, parentDepth + 1)
    return parentDepth + 1
  }

  for (const branch of branches) depthFor(branch)
  return depths
})

const branchNodes = computed(() => {
  const rowByDepth = new Map<number, number>()
  return (props.detail?.branches || []).map((branch) => {
    const depth = branchDepths.value.get(branch.id) || 0
    const row = rowByDepth.get(depth) || 0
    rowByDepth.set(depth, row + 1)
    const statusClass = branchStatusClass(branch.status)
    return {
      id: `branch:${branch.id}`,
      branch,
      displayParentBranchId: displayParentBranchId(branch),
      statusClass,
      statusLabel: branchStatusLabel(branch.status),
      stageName: branch.stageRunId ? stagesById.value.get(branch.stageRunId) || branch.stageRunId : '待处理',
      x: marginX + depth * columnGap,
      y: marginY + row * rowGap,
    }
  })
})

const branchNodesById = computed(() => new Map(branchNodes.value.map((node) => [node.branch.id, node])))

const edges = computed(() => {
  return branchNodes.value
    .filter((node) => node.displayParentBranchId && branchNodesById.value.has(node.displayParentBranchId))
    .map((node) => {
      const parent = branchNodesById.value.get(node.displayParentBranchId as string)
      return {
        from: parent?.id,
        to: node.id,
        x1: (parent?.x || 0) + nodeWidth,
        y1: (parent?.y || 0) + nodeHeight / 2,
        x2: node.x,
        y2: node.y + nodeHeight / 2,
      }
    })
})

const canvasSize = computed(() => {
  const maxX = Math.max(...branchNodes.value.map((node) => node.x), 0)
  const maxY = Math.max(...branchNodes.value.map((node) => node.y), 0)
  return {
    width: maxX + nodeWidth + marginX,
    height: maxY + nodeHeight + marginY,
  }
})

const selectedNode = computed(() => branchNodes.value.find((node) => node.id === selectedNodeId.value) || branchNodes.value[0] || null)
const selectedBranchId = computed(() => selectedNode.value?.branch.id || '')
const selectedArtifacts = computed(() => byBranch(props.detail?.artifacts || [], selectedBranchId.value))
const selectedCommit = computed(() => byBranch(props.detail?.commits || [], selectedBranchId.value)[0] || null)
const selectedEvidence = computed(() => byBranch(props.detail?.evidencePackets || [], selectedBranchId.value)[0] || null)
const selectedAuditEvents = computed(() => byBranch(props.detail?.auditEvents || [], selectedBranchId.value))
const selectedBlockedAudits = computed(() => selectedAuditEvents.value.filter((event) => ['blocked', 'denied'].includes(event.result)))
const latestAudit = computed(() => selectedAuditEvents.value[selectedAuditEvents.value.length - 1] || null)
const inferredRootBranchId = computed(() => {
  const branches = props.detail?.branches || []
  return props.detail?.workspace?.rootBranchId
    || branches.find((branch) => branch.name === 'root')?.id
    || null
})

watch(
  () => props.detail?.task.id,
  () => {
    selectedNodeId.value = null
  },
)

watch(
  branchNodes,
  (nodes) => {
    if (!nodes.length) {
      selectedNodeId.value = null
    } else if (!selectedNodeId.value || !nodes.some((node) => node.id === selectedNodeId.value)) {
      selectedNodeId.value = nodes[0]?.id || null
    }
  },
  { immediate: true },
)

function byBranch<T extends ArtifactDetail | StateCommitDetail | EvidencePacketDetail | AuditEventDetail>(rows: T[], branchId: string): T[] {
  return rows.filter((row) => row.branchId === branchId)
}

function nodeCounts(node: { branch: BranchDetail }) {
  const branchId = node.branch.id
  return {
    artifacts: (props.detail?.artifacts || []).filter((artifact) => artifact.branchId === branchId).length,
    evidence: (props.detail?.evidencePackets || []).filter((packet) => packet.branchId === branchId).length,
  }
}

function displayParentBranchId(branch: BranchDetail) {
  if (branch.parentBranchId) return branch.parentBranchId
  const rootBranchId = inferredRootBranchId.value
  if (!rootBranchId || branch.id === rootBranchId || branch.name === 'root') return null
  return rootBranchId
}

function branchStatusClass(status?: string | null) {
  const normalized = (status || '').toLowerCase()
  if (['blocked', 'failed', 'error'].includes(normalized)) return 'blocked'
  if (['verified', 'done', 'completed', 'merged'].includes(normalized)) return 'verified'
  return 'active'
}

function branchStatusLabel(status?: string | null) {
  const normalized = (status || '').toLowerCase()
  if (['blocked', 'failed', 'error'].includes(normalized)) return '阻塞'
  if (['verified', 'done', 'completed', 'merged'].includes(normalized)) return '已验证'
  return '活跃'
}
</script>

<style scoped>
.branch-graph {
  min-width: 0;
}

.branch-graph-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 360px);
  gap: 14px;
  align-items: start;
  padding: 0 14px 14px;
}

.branch-graph-card,
.branch-inspector {
  min-width: 0;
  border: 1px solid #edf0f4;
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
}

.branch-graph-header,
.branch-inspector-header {
  min-height: 44px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.branch-graph-header h3,
.branch-inspector-header h3 {
  margin: 0;
  font-size: 14px;
}

.legend,
.chip-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.legend {
  color: #758397;
  font-size: 12px;
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 5px;
  border-radius: 999px;
}

.dot.active { background: #bf7a34; }
.dot.verified { background: #2d8a56; }
.dot.blocked { background: #b94747; }

.branch-graph-canvas {
  height: clamp(320px, calc(100vh - 340px), 520px);
  overflow: auto;
  border-top: 1px solid #edf0f4;
  background:
    linear-gradient(#f3f6f9 1px, transparent 1px),
    linear-gradient(90deg, #f3f6f9 1px, transparent 1px);
  background-size: 28px 28px;
}

.branch-graph-plane {
  position: relative;
  min-width: 100%;
  min-height: 100%;
}

.branch-graph-edges {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.branch-edge {
  stroke: #aab8c9;
  stroke-width: 2;
  stroke-linecap: round;
}

.branch-node {
  position: absolute;
  width: 178px;
  height: 86px;
  border: 1px solid #d8e1ec;
  border-radius: 7px;
  padding: 10px 11px;
  background: #fff;
  box-shadow: 0 8px 18px rgba(23, 38, 58, .08);
  color: #25364a;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.branch-node:hover {
  border-color: #c8d4e2;
  background: #fbfcfd;
}

.branch-node.selected {
  border-color: #e09a3b;
  box-shadow: 0 0 0 2px #fde6bd, 0 8px 18px rgba(23, 38, 58, .08);
}

.branch-node.verified {
  border-left: 4px solid #2d8a56;
}

.branch-node.blocked {
  border-left: 4px solid #b94747;
  background: #fff8f8;
}

.branch-node.active {
  border-left: 4px solid #bf7a34;
}

.branch-node-title,
.branch-node-id,
.branch-node-meta {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-node-title {
  font-weight: 750;
  font-size: 13px;
}

.branch-node-id {
  margin-top: 4px;
  color: #687789;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 11px;
}

.branch-node-meta {
  margin-top: 7px;
  color: #758397;
  font-size: 11px;
}

.branch-inspector {
  position: sticky;
  top: 76px;
  max-height: calc(100vh - 108px);
  overflow: hidden;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid #dfe6ee;
  border-radius: 999px;
  color: #526173;
  background: #f8fafc;
  font-size: 12px;
}

.status-pill.active {
  color: #8a5c00;
  background: #fff7e3;
  border-color: #f0d89b;
}

.status-pill.verified {
  color: #19733b;
  background: #ecf8ef;
  border-color: #c9ead2;
}

.status-pill.blocked {
  color: #a83939;
  background: #fff0f0;
  border-color: #f1cccc;
}

.branch-inspector-body {
  display: flex;
  max-height: calc(100vh - 154px);
  overflow: auto;
  overscroll-behavior: contain;
  flex-direction: column;
  gap: 12px;
  padding: 0 12px 12px;
}

.branch-inspector-body section {
  min-width: 0;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 12px;
  background: #fbfcfd;
}

.branch-inspector-body h4 {
  margin: 0 0 10px;
  font-size: 13px;
}

dl {
  margin: 0;
}

dl div {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 8px;
  margin-bottom: 7px;
  font-size: 12px;
}

dt {
  color: #758397;
}

dd {
  margin: 0;
  overflow: hidden;
  color: #2b3b50;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

p {
  margin: 0 0 10px;
  color: #2b3b50;
  font-size: 12px;
  line-height: 1.5;
}

.muted {
  color: #758397;
}

.chip {
  display: inline-flex;
  max-width: 100%;
  min-height: 22px;
  align-items: center;
  padding: 0 7px;
  border: 1px solid #dfe6ee;
  border-radius: 999px;
  background: #fff;
  color: #526173;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.artifact-list {
  max-height: 132px;
  overflow: auto;
  align-content: flex-start;
  padding-right: 2px;
}

.evidence-chip {
  color: #19733b;
  background: #ecf8ef;
  border-color: #c9ead2;
}

.commit-summary,
.audit-reason {
  margin-top: 10px;
  color: #5d6c7f;
}

.empty-state {
  padding: 18px 14px;
  color: #758397;
}

@media (max-width: 1100px) {
  .branch-graph-workspace {
    grid-template-columns: 1fr;
  }

  .branch-inspector {
    position: static;
    max-height: none;
  }

  .branch-inspector-body {
    max-height: none;
  }
}

</style>
