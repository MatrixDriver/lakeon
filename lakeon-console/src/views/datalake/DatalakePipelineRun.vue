<template>
  <div class="run-page">
    <!-- Breadcrumb -->
    <div class="run-breadcrumb">
      <router-link to="/datalake/pipelines" class="breadcrumb-link">数据生产线</router-link>
      <span class="breadcrumb-sep"> / </span>
      <router-link :to="`/datalake/pipelines/${pipelineId}`" class="breadcrumb-link">{{ pipeline?.name || '...' }}</router-link>
      <span class="breadcrumb-sep"> / </span>
      <span>运行 {{ runId.substring(0, 12) }}...</span>
    </div>

    <!-- 统计条 -->
    <PipelineRunStats v-if="run" :run="run" :steps="stepRuns" />

    <!-- 三栏：只读 DAG + 右侧步骤详情 -->
    <div class="run-body">
      <!-- 只读 DAG 画布 -->
      <div class="run-canvas">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :node-types="nodeTypes"
          :edge-types="edgeTypes"
          :nodes-draggable="false"
          :nodes-connectable="false"
          :elements-selectable="true"
          fit-view-on-init
          @node-click="onNodeClick"
        >
          <Background />
          <Controls :show-interactive="false" />
        </VueFlow>
      </div>

      <!-- 右侧步骤详情面板 -->
      <PipelineRunStepDetail
        v-if="selectedStepRun"
        :step-run="selectedStepRun"
        :run-id="runId"
        @resume="handleResume"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, markRaw } from 'vue'
import { useRoute } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

import PipelineRunStats from './components/pipeline/PipelineRunStats.vue'
import PipelineRunStepDetail from './components/pipeline/PipelineRunStepDetail.vue'
import PipelineNodeBase from './components/pipeline/PipelineNodeBase.vue'
import PipelineNodeFanOut from './components/pipeline/PipelineNodeFanOut.vue'
import PipelineNodeMerge from './components/pipeline/PipelineNodeMerge.vue'
import PipelineNodeHumanReview from './components/pipeline/PipelineNodeHumanReview.vue'
import PipelineCustomEdge from './components/pipeline/PipelineCustomEdge.vue'

import {
  getPipeline, getPipelineVersion, getPipelineRun, listStepRuns,
  resumePipelineRun,
  type Pipeline, type PipelineRun, type PipelineStepRun,
} from '@/api/pipeline'
import { dagToFlow, parseDagYaml } from './components/pipeline/dagUtils'
import { statusColors } from './components/pipeline/nodeStyles'
import type { Node, Edge, NodeMouseEvent } from '@vue-flow/core'

const route = useRoute()
const pipelineId = computed(() => route.params.id as string)
const runId = computed(() => route.params.runId as string)

const pipeline = ref<Pipeline | null>(null)
const run = ref<PipelineRun | null>(null)
const stepRuns = ref<PipelineStepRun[]>([])
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const selectedStepRun = ref<PipelineStepRun | null>(null)

const nodeTypes = {
  pipelineNode: markRaw(PipelineNodeBase),
  fanOutNode: markRaw(PipelineNodeFanOut),
  mergeNode: markRaw(PipelineNodeMerge),
  humanReviewNode: markRaw(PipelineNodeHumanReview),
}
const edgeTypes = { pipelineEdge: markRaw(PipelineCustomEdge) }

let pollTimer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  await loadData()
  // 轮询更新运行状态（运行中时每 3 秒）
  pollTimer = setInterval(async () => {
    if (run.value && ['RUNNING', 'PAUSED', 'PENDING'].includes(run.value.status)) {
      await refreshRunState()
    }
  }, 3000)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})

async function loadData() {
  try {
    const [pRes, rRes] = await Promise.all([
      getPipeline(pipelineId.value),
      getPipelineRun(runId.value),
    ])
    pipeline.value = pRes.data
    run.value = rRes.data

    // 加载 DAG 定义
    const vRes = await getPipelineVersion(pipelineId.value, rRes.data.pipelineVersion)
    const dag = parseDagYaml(vRes.data.dagYaml)
    const flow = dagToFlow(dag.steps)
    nodes.value = flow.nodes
    edges.value = flow.edges

    // 加载步骤运行状态
    await refreshRunState()
  } catch (err) {
    console.error('Failed to load run data', err)
  }
}

async function refreshRunState() {
  try {
    const [rRes, sRes] = await Promise.all([
      getPipelineRun(runId.value),
      listStepRuns(runId.value),
    ])
    run.value = rRes.data
    stepRuns.value = sRes.data

    // 更新节点视觉状态
    const stepMap = new Map(sRes.data.map(s => [s.stepId, s]))
    nodes.value = nodes.value.map(n => {
      const sr = stepMap.get(n.id)
      if (!sr) return n
      const colors = statusColors[sr.status] || statusColors.PENDING
      return {
        ...n,
        data: {
          ...n.data,
          runStatus: sr.status,
          metrics: sr.metrics,
        },
        style: {
          background: colors.bg,
          borderColor: colors.border,
          borderWidth: '2px',
          borderStyle: 'solid',
          borderRadius: '8px',
        },
      }
    })

    // 更新 edge 动画（RUNNING 步骤的入边）
    const runningSteps = new Set(sRes.data.filter(s => s.status === 'RUNNING').map(s => s.stepId))
    edges.value = edges.value.map(e => ({
      ...e,
      animated: runningSteps.has(e.target),
    }))
  } catch (err) {
    console.error('Failed to refresh run state', err)
  }
}

function onNodeClick(event: NodeMouseEvent) {
  const sr = stepRuns.value.find(s => s.stepId === event.node.id)
  selectedStepRun.value = sr || null
}

async function handleResume(stepId: string, decision: 'approve' | 'reject') {
  try {
    await resumePipelineRun(runId.value, stepId, decision)
    await refreshRunState()
  } catch (err) {
    console.error('Failed to resume', err)
    alert('恢复失败')
  }
}
</script>

<style scoped>
.run-page { display: flex; flex-direction: column; height: calc(100vh - 56px); }
.run-breadcrumb { padding: 10px 16px; font-size: 13px; color: #999; }
.breadcrumb-link { color: #2a4d6a; text-decoration: none; }
.breadcrumb-link:hover { text-decoration: underline; }
.breadcrumb-sep { margin: 0 4px; }
.run-body { display: flex; flex: 1; overflow: hidden; }
.run-canvas { flex: 1; position: relative; background: #faf9f7; }
</style>
