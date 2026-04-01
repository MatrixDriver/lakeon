<template>
  <div class="editor-page">
    <!-- Breadcrumb -->
    <div class="editor-breadcrumb">
      <router-link to="/datalake/pipelines" class="breadcrumb-link">数据生产线</router-link>
      <span class="breadcrumb-sep"> / </span>
      <span>{{ isNew ? '新建' : pipeline?.name || '编辑' }}</span>
    </div>

    <!-- Pipeline name + data type (简易 header) -->
    <div class="editor-name-row">
      <input
        v-model="pipelineName"
        class="name-input"
        placeholder="生产线名称"
      />
      <select v-model="dataType" class="type-select">
        <option value="">通用</option>
        <option value="TEXT">文本</option>
        <option value="VIDEO">视频</option>
        <option value="IMAGE">图片</option>
        <option value="AUDIO">音频</option>
        <option value="DOCUMENT">文档</option>
      </select>
    </div>

    <!-- Toolbar -->
    <PipelineToolbar
      v-model:mode="viewMode"
      :can-undo="undoStack.length > 0"
      :can-redo="redoStack.length > 0"
      @undo="undo"
      @redo="redo"
      @auto-layout="handleAutoLayout"
      @fit-view="handleFitView"
      @save-draft="handleSaveDraft"
      @publish="handlePublish"
    />

    <!-- 三栏布局 -->
    <div class="editor-body">
      <!-- 左栏：组件面板 -->
      <PipelineComponentPanel
        v-if="viewMode === 'dag'"
        :components="components"
        @drag-start="onComponentDragStart"
      />

      <!-- 中央：DAG 画布 or YAML 编辑器 -->
      <div class="editor-canvas" ref="canvasRef" @drop="onDrop" @dragover.prevent>
        <VueFlow
          v-if="viewMode === 'dag'"
          v-model:nodes="nodes"
          v-model:edges="edges"
          :node-types="nodeTypes"
          :edge-types="edgeTypes"
          :default-edge-options="{ type: 'pipelineEdge', animated: false }"
          :snap-to-grid="true"
          :snap-grid="[20, 20]"
          fit-view-on-init
          @node-click="onNodeClick"
          @pane-click="selectedNode = null"
          @connect="onConnect"
        >
          <Background />
          <Controls />
          <MiniMap />
        </VueFlow>
        <PipelineYamlEditor
          v-if="viewMode === 'yaml'"
          :value="yamlText"
          @update:value="onYamlChange"
        />
      </div>

      <!-- 右栏：属性面板 -->
      <PipelinePropertyPanel
        v-if="viewMode === 'dag' && selectedNode"
        :node="selectedNode"
        :components="components"
        @update:params="onParamsUpdate"
        @delete="onDeleteNode"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, computed, onMounted, markRaw, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

import PipelineToolbar from './components/pipeline/PipelineToolbar.vue'
import PipelineComponentPanel from './components/pipeline/PipelineComponentPanel.vue'
import PipelinePropertyPanel from './components/pipeline/PipelinePropertyPanel.vue'
import PipelineYamlEditor from './components/pipeline/PipelineYamlEditor.vue'
import PipelineNodeBase from './components/pipeline/PipelineNodeBase.vue'
import PipelineNodeFanOut from './components/pipeline/PipelineNodeFanOut.vue'
import PipelineNodeMerge from './components/pipeline/PipelineNodeMerge.vue'
import PipelineNodeHumanReview from './components/pipeline/PipelineNodeHumanReview.vue'
import PipelineCustomEdge from './components/pipeline/PipelineCustomEdge.vue'

import {
  getPipeline, getPipelineVersion, createPipeline, publishPipelineVersion,
  listComponents, getComponentLatestVersion,
  type Pipeline, type PipelineComponent, type PipelineComponentVersion,
} from '@/api/pipeline'
import {
  dagToFlow, flowToDag, autoLayout, parseDagYaml, serializeDagYaml,
  type DagStep, type DagDefinition,
} from './components/pipeline/dagUtils'
import { useVueFlow, type Node, type Edge, type Connection, type NodeMouseEvent } from '@vue-flow/core'

const route = useRoute()
const router = useRouter()

const isNew = computed(() => route.name === 'DatalakePipelineNew')
const pipelineId = computed(() => route.params.id as string | undefined)

// 状态
const pipeline = ref<Pipeline | null>(null)
const components = ref<PipelineComponent[]>([])
const componentVersions = ref<Map<string, PipelineComponentVersion>>(new Map())
const pipelineName = ref('')
const dataType = ref('')
const viewMode = ref<'dag' | 'yaml'>('dag')
// Use shallowRef to avoid Vue Flow's deeply recursive type instantiation issues
const nodes = shallowRef<Node[]>([])
const edges = shallowRef<Edge[]>([])
const selectedNode = ref<Node | null>(null)
const yamlText = ref('')
const canvasRef = ref<HTMLElement | null>(null)
const { project } = useVueFlow()

// 撤销/重做 (use any[] to avoid Vue Flow's deep type recursion)
interface FlowSnapshot { nodes: any[]; edges: any[] }
const undoStack = ref<FlowSnapshot[]>([])
const redoStack = ref<FlowSnapshot[]>([])

// 自定义节点/边类型注册
const nodeTypes: Record<string, any> = {
  pipelineNode: markRaw(PipelineNodeBase),
  fanOutNode: markRaw(PipelineNodeFanOut),
  mergeNode: markRaw(PipelineNodeMerge),
  humanReviewNode: markRaw(PipelineNodeHumanReview),
}
const edgeTypes: Record<string, any> = {
  pipelineEdge: markRaw(PipelineCustomEdge),
}

// ── 初始化 ──

onMounted(async () => {
  // 加载组件库
  try {
    const res = await listComponents()
    components.value = res.data
    // 预加载每个组件的最新版本（用于属性面板的 params_schema）
    for (const comp of components.value) {
      try {
        const vRes = await getComponentLatestVersion(comp.id)
        componentVersions.value.set(comp.id, vRes.data)
      } catch { /* 忽略单个组件加载失败 */ }
    }
  } catch (err) {
    console.error('Failed to load components', err)
  }

  // 编辑模式：加载已有 pipeline
  if (!isNew.value && pipelineId.value) {
    try {
      const pRes = await getPipeline(pipelineId.value)
      pipeline.value = pRes.data
      pipelineName.value = pRes.data.name
      dataType.value = pRes.data.dataType || ''
      const vRes = await getPipelineVersion(pipelineId.value, pRes.data.latestVersion)
      const dag = parseDagYaml(vRes.data.dagYaml)
      const flow = dagToFlow(dag.steps)
      nodes.value = flow.nodes
      edges.value = flow.edges
      yamlText.value = vRes.data.dagYaml
    } catch (err) {
      console.error('Failed to load pipeline', err)
    }
  }

  // 从模板创建
  const templateId = route.query.template as string
  if (isNew.value && templateId) {
    try {
      const tRes = await getPipeline(templateId)
      pipelineName.value = tRes.data.name + ' (副本)'
      dataType.value = tRes.data.dataType || ''
      const vRes = await getPipelineVersion(templateId, tRes.data.latestVersion)
      const dag = parseDagYaml(vRes.data.dagYaml)
      const flow = dagToFlow(dag.steps)
      nodes.value = flow.nodes
      edges.value = flow.edges
      yamlText.value = vRes.data.dagYaml
    } catch (err) {
      console.error('Failed to load template', err)
    }
  }
})

// ── DAG ↔ YAML 双向同步 ──

function syncDagToYaml() {
  const steps = flowToDag(nodes.value as Node[], edges.value as Edge[])
  const dag: DagDefinition = {
    name: pipelineName.value,
    data_type: dataType.value || undefined,
    steps,
  }
  yamlText.value = serializeDagYaml(dag)
}

function onYamlChange(newYaml: string) {
  yamlText.value = newYaml
  try {
    const dag = parseDagYaml(newYaml)
    const flow = dagToFlow(dag.steps)
    nodes.value = flow.nodes
    edges.value = flow.edges
    if (dag.name) pipelineName.value = dag.name
    if (dag.data_type) dataType.value = dag.data_type
  } catch {
    // YAML 解析失败时不更新 DAG
  }
}

// 切换视图时同步
watch(viewMode, (newMode, oldMode) => {
  if (oldMode === 'dag' && newMode === 'yaml') syncDagToYaml()
})

// ── 画布操作 ──

function pushUndo() {
  undoStack.value.push({
    nodes: JSON.parse(JSON.stringify(nodes.value)),
    edges: JSON.parse(JSON.stringify(edges.value)),
  })
  redoStack.value = []
}

function undo() {
  const state = undoStack.value.pop()
  if (!state) return
  redoStack.value.push({
    nodes: JSON.parse(JSON.stringify(nodes.value)),
    edges: JSON.parse(JSON.stringify(edges.value)),
  })
  nodes.value = state.nodes
  edges.value = state.edges
}

function redo() {
  const state = redoStack.value.pop()
  if (!state) return
  undoStack.value.push({
    nodes: JSON.parse(JSON.stringify(nodes.value)),
    edges: JSON.parse(JSON.stringify(edges.value)),
  })
  nodes.value = state.nodes
  edges.value = state.edges
}

function onNodeClick(event: NodeMouseEvent) {
  selectedNode.value = event.node
}

function onConnect(connection: Connection) {
  pushUndo()
  const newEdge: Edge = {
    id: `e-${connection.source}-${connection.target}`,
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle || undefined,
    targetHandle: connection.targetHandle || undefined,
    type: 'pipelineEdge',
  }
  edges.value = [...edges.value, newEdge]
}

// 拖拽添加节点
let dragComponent: PipelineComponent | null = null

function onComponentDragStart(component: PipelineComponent) {
  dragComponent = component
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  // 从 dataTransfer 读取组件 ID（比依赖 emit 链更可靠）
  const compId = event.dataTransfer?.getData('application/pipeline-component')
  const comp = compId ? components.value.find(c => c.id === compId) : dragComponent
  if (!comp || !canvasRef.value) return
  pushUndo()

  const bounds = canvasRef.value.getBoundingClientRect()
  const position = project({
    x: event.clientX - bounds.left,
    y: event.clientY - bounds.top,
  })

  const compVersion = componentVersions.value.get(comp.id)
  const step: DagStep = {
    id: comp.name + '_' + Date.now().toString(36),
    component: comp.name,
    component_version: compVersion?.version || 1,
    params: {},
    inputs: {},
    outputs: {},
  }

  // 确定节点类型
  let nodeType = 'pipelineNode'
  if (compVersion?.executionMode === 'HUMAN_REVIEW') nodeType = 'humanReviewNode'

  const newNode: Node = {
    id: step.id,
    type: nodeType,
    position,
    data: {
      step,
      label: comp.displayName,
      category: comp.category,
    },
  }

  // shallowRef 需要赋值新数组才能触发响应式更新
  nodes.value = [...nodes.value, newNode]
  dragComponent = null
}

function onParamsUpdate(nodeId: string, params: Record<string, any>) {
  pushUndo()
  const node = nodes.value.find(n => n.id === nodeId)
  if (node) {
    node.data = { ...node.data, step: { ...node.data.step, params } }
  }
}

function onDeleteNode(nodeId: string) {
  pushUndo()
  nodes.value = nodes.value.filter(n => n.id !== nodeId)
  edges.value = edges.value.filter(e => e.source !== nodeId && e.target !== nodeId)
  selectedNode.value = null
}

function handleAutoLayout() {
  pushUndo()
  nodes.value = autoLayout(nodes.value, edges.value)
}

function handleFitView() {
  // Vue Flow 内置 fitView 通过 useVueFlow 调用
  // 简化版：触发自定义事件，实际由 VueFlow 实例处理
}

// ── 保存/发布 ──

async function handleSaveDraft() {
  syncDagToYaml()
  try {
    if (isNew.value) {
      const res = await createPipeline({
        name: pipelineName.value,
        data_type: dataType.value || undefined,
        dag_yaml: yamlText.value,
      })
      router.replace(`/datalake/pipelines/${res.data.id}/edit`)
    } else if (pipelineId.value) {
      await publishPipelineVersion(pipelineId.value, {
        dag_yaml: yamlText.value,
        changelog: '草稿保存',
      })
    }
  } catch (err) {
    console.error('Failed to save', err)
    alert('保存失败')
  }
}

async function handlePublish() {
  syncDagToYaml()
  if (!pipelineName.value) { alert('请填写生产线名称'); return }
  if (nodes.value.length === 0) { alert('请至少添加一个步骤'); return }

  try {
    let pid = pipelineId.value
    if (isNew.value) {
      const res = await createPipeline({
        name: pipelineName.value,
        data_type: dataType.value || undefined,
        dag_yaml: yamlText.value,
      })
      pid = res.data.id
    }
    if (pid) {
      await publishPipelineVersion(pid, {
        dag_yaml: yamlText.value,
        changelog: '发布版本',
      })
      router.push(`/datalake/pipelines/${pid}`)
    }
  } catch (err) {
    console.error('Failed to publish', err)
    alert('发布失败')
  }
}
</script>

<style scoped>
.editor-page { display: flex; flex-direction: column; height: calc(100vh - 56px); }
.editor-breadcrumb { padding: 10px 16px; font-size: 13px; color: #999; }
.breadcrumb-link { color: #2a4d6a; text-decoration: none; }
.breadcrumb-link:hover { text-decoration: underline; }
.breadcrumb-sep { margin: 0 4px; }
.editor-name-row { display: flex; align-items: center; gap: 10px; padding: 0 16px 8px; }
.name-input {
  flex: 1; font-size: 18px; font-weight: 600; border: none; border-bottom: 2px solid transparent;
  outline: none; padding: 4px 0; color: #2c3e50; background: transparent;
}
.name-input:focus { border-bottom-color: #2a4d6a; }
.name-input::placeholder { color: #ccc; }
.type-select {
  font-size: 12px; padding: 4px 8px; border: 1px solid #e8e4df; border-radius: 4px;
  background: #fff; color: #666; cursor: pointer;
}

.editor-body { display: flex; flex: 1; overflow: hidden; }
.editor-canvas { flex: 1; position: relative; background: #faf9f7; }
</style>
