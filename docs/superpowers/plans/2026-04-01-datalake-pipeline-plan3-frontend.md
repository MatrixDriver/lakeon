# 数据生产线 Plan 3: 前端 — DAG 编辑器 + 运行监控

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Pipeline 前端全链路 — DAG 可视化编辑器、运行实时监控、组件库管理，让用户通过拖拽和连线完成数据生产线的定义与观测。

**Architecture:** 在 lakeon-console 中新增 pipeline 子模块。DAG 画布基于 Vue Flow（@vue-flow/core），YAML 编辑使用 CodeMirror（项目已有）。API 层对接 Plan 1 的后端接口。所有页面遵循现有 ConsoleLayout + 暖色调港湾风格。

**Tech Stack:** Vue 3 Composition API, TypeScript, @vue-flow/core, @vue-flow/background, @vue-flow/controls, CodeMirror 6 (YAML), Pinia (可选), Axios

**Spec:** `docs/superpowers/specs/2026-04-01-datalake-pipeline-design.md` 第 6 章

---

## File Structure

```
lakeon-console/src/
├── api/
│   └── pipeline.ts                                    (新建)
├── views/datalake/
│   ├── DatalakePipelines.vue                          (新建: 列表页)
│   ├── DatalakePipelineEditor.vue                     (新建: DAG 编辑器主页)
│   ├── DatalakePipelineDetail.vue                     (新建: 详情页)
│   ├── DatalakePipelineRun.vue                        (新建: 运行监控页)
│   ├── DatalakeComponents.vue                         (新建: 组件库页)
│   ├── DatalakeComponentRegister.vue                  (新建: 组件注册页)
│   └── components/
│       ├── pipeline/
│       │   ├── PipelineComponentPanel.vue              (新建: 左栏组件面板)
│       │   ├── PipelinePropertyPanel.vue               (新建: 右栏属性面板)
│       │   ├── PipelineYamlEditor.vue                  (新建: YAML 视图)
│       │   ├── PipelineToolbar.vue                     (新建: 工具栏)
│       │   ├── PipelineNodeBase.vue                    (新建: 自定义节点模板)
│       │   ├── PipelineNodeFanOut.vue                  (新建: fan-out 节点)
│       │   ├── PipelineNodeMerge.vue                   (新建: merge 节点)
│       │   ├── PipelineNodeHumanReview.vue             (新建: 人工审核节点)
│       │   ├── PipelineCustomEdge.vue                  (新建: 自定义连线)
│       │   ├── PipelineRunStepDetail.vue               (新建: 步骤详情面板)
│       │   ├── PipelineRunHumanReview.vue              (新建: 人工审核面板)
│       │   ├── PipelineRunStats.vue                    (新建: 运行统计条)
│       │   ├── dagUtils.ts                             (新建: DAG↔YAML 转换)
│       │   └── nodeStyles.ts                           (新建: 节点颜色/样式常量)
│       └── ...
├── router/
│   └── index.ts                                       (修改: 添加路由)
```

---

## Task 1: 依赖安装 + 路由配置

**Files:**
- Modify: `lakeon-console/package.json`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: 安装 Vue Flow 依赖**

```bash
cd lakeon-console && npm install @vue-flow/core @vue-flow/background @vue-flow/controls @vue-flow/minimap
```

> 项目已有 CodeMirror 6（@codemirror/*），YAML 语言支持后续 Task 7 手动添加 `@codemirror/lang-yaml`。

- [ ] **Step 2: 添加路由**

在 `lakeon-console/src/router/index.ts` 的 ConsoleLayout children 中，`datalake/monitor` 之后添加：

```typescript
      // Datalake — Pipeline
      { path: 'datalake/pipelines', name: 'DatalakePipelines', component: () => import('../views/datalake/DatalakePipelines.vue') },
      { path: 'datalake/pipelines/new', name: 'DatalakePipelineNew', component: () => import('../views/datalake/DatalakePipelineEditor.vue') },
      { path: 'datalake/pipelines/:id', name: 'DatalakePipelineDetail', component: () => import('../views/datalake/DatalakePipelineDetail.vue') },
      { path: 'datalake/pipelines/:id/edit', name: 'DatalakePipelineEdit', component: () => import('../views/datalake/DatalakePipelineEditor.vue') },
      { path: 'datalake/pipelines/:id/runs/:runId', name: 'DatalakePipelineRun', component: () => import('../views/datalake/DatalakePipelineRun.vue') },
      // Datalake — Components
      { path: 'datalake/components', name: 'DatalakeComponents', component: () => import('../views/datalake/DatalakeComponents.vue') },
      { path: 'datalake/components/register', name: 'DatalakeComponentRegister', component: () => import('../views/datalake/DatalakeComponentRegister.vue') },
```

注意：`pipelines/new` 必须在 `pipelines/:id` 之前，避免 `new` 被当作动态 id。

- [ ] **Step 3: 更新 datalake redirect**

将现有的 `{ path: 'datalake', redirect: '/datalake/datasets' }` 保持不变（Pipeline 是子功能，不替换默认入口）。

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/package.json lakeon-console/package-lock.json lakeon-console/src/router/index.ts
git commit -m "feat(pipeline): install vue-flow and add pipeline routes"
```

---

## Task 2: API 层 — 类型定义 + 请求函数

**Files:**
- Create: `lakeon-console/src/api/pipeline.ts`

- [ ] **Step 1: 编写 pipeline.ts**

```typescript
import api from './client'

// ── 枚举类型 ──

export type PipelineRunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type StepRunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
export type ComponentCategory = 'DATA_PREP' | 'EXTRACT' | 'CLEAN' | 'FILTER' | 'QC' | 'LABEL' | 'PUBLISH'
export type ComponentDataType = 'TEXT' | 'VIDEO' | 'IMAGE' | 'AUDIO' | 'DOCUMENT' | 'UNIVERSAL'
export type ComponentExecutionMode = 'FUNCTION' | 'HUMAN_REVIEW'

// ── Pipeline ──

export interface Pipeline {
  id: string
  tenantId: string
  name: string
  description: string | null
  dataType: string | null
  isTemplate: boolean
  sourceTemplateId: string | null
  latestVersion: number
  createdAt: string
  updatedAt: string
}

export interface PipelineVersion {
  id: string
  pipelineId: string
  version: number
  dagYaml: string
  status: string
  changelog: string | null
  createdAt: string
}

export interface CreatePipelineRequest {
  name: string
  description?: string
  data_type?: string
  is_template?: boolean
  source_template_id?: string
  dag_yaml: string
}

export interface UpdatePipelineRequest {
  name?: string
  description?: string
}

export interface PublishVersionRequest {
  dag_yaml: string
  changelog?: string
}

// ── Pipeline Component ──

export interface PipelineComponent {
  id: string
  tenantId: string | null
  name: string
  displayName: string
  category: ComponentCategory
  dataType: ComponentDataType
  description: string | null
  latestVersion: number
  createdAt: string
  updatedAt: string
}

export interface PipelineComponentVersion {
  id: string
  componentId: string
  version: number
  entrypoint: string
  paramsSchema: string | null
  inputSchema: string | null
  outputSchema: string | null
  outputBranches: string | null
  requiresGpu: boolean
  requiresModel: string | null
  executionMode: ComponentExecutionMode
  status: string
  changelog: string | null
  createdAt: string
}

export interface RegisterComponentRequest {
  name: string
  display_name: string
  category: ComponentCategory
  data_type: ComponentDataType
  description?: string
  entrypoint: string
  params_schema?: string
  input_schema?: string
  output_schema?: string
  output_branches?: string[]
  requires_gpu?: boolean
  requires_model?: string
  execution_mode?: ComponentExecutionMode
}

// ── Pipeline Run ──

export interface PipelineRun {
  id: string
  pipelineId: string
  pipelineVersion: number
  tenantId: string
  inputDatasetId: string | null
  inputDatasetVersion: number | null
  outputDatasetVersionId: string | null
  status: PipelineRunStatus
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface PipelineStepRun {
  id: string
  runId: string
  stepId: string
  componentId: string | null
  componentVersion: number | null
  status: StepRunStatus
  inputRef: string | null
  outputRef: string | null
  checkpointPath: string | null
  metrics: string | null
  error: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface TriggerRunRequest {
  pipeline_version?: number
  input_dataset_id?: string
  input_dataset_version?: number
}

// ── 辅助类型：解析后的 metrics ──

export interface StepMetrics {
  input_count?: number
  output_count?: number
  drop_count?: number
  retention?: string
  duration_ms?: number
  [key: string]: unknown
}

// ── API 函数 ──

// Pipeline CRUD
export function listPipelines(params?: { is_template?: boolean }) {
  return api.get<Pipeline[]>('/pipelines', { params })
}

export function getPipeline(id: string) {
  return api.get<Pipeline>(`/pipelines/${id}`)
}

export function createPipeline(body: CreatePipelineRequest) {
  return api.post<Pipeline>('/pipelines', body)
}

export function updatePipeline(id: string, body: UpdatePipelineRequest) {
  return api.put<Pipeline>(`/pipelines/${id}`, body)
}

export function deletePipeline(id: string) {
  return api.delete(`/pipelines/${id}`)
}

// Pipeline Versions
export function listPipelineVersions(pipelineId: string) {
  return api.get<PipelineVersion[]>(`/pipelines/${pipelineId}/versions`)
}

export function getPipelineVersion(pipelineId: string, version: number) {
  return api.get<PipelineVersion>(`/pipelines/${pipelineId}/versions/${version}`)
}

export function publishPipelineVersion(pipelineId: string, body: PublishVersionRequest) {
  return api.post<PipelineVersion>(`/pipelines/${pipelineId}/versions`, body)
}

// Pipeline Components
export function listComponents(params?: { category?: string; data_type?: string }) {
  return api.get<PipelineComponent[]>('/pipeline-components', { params })
}

export function getComponent(id: string) {
  return api.get<PipelineComponent>(`/pipeline-components/${id}`)
}

export function getComponentVersions(componentId: string) {
  return api.get<PipelineComponentVersion[]>(`/pipeline-components/${componentId}/versions`)
}

export function getComponentLatestVersion(componentId: string) {
  return api.get<PipelineComponentVersion>(`/pipeline-components/${componentId}/versions/latest`)
}

export function registerComponent(body: RegisterComponentRequest) {
  return api.post<PipelineComponent>('/pipeline-components', body)
}

// Pipeline Runs
export function listPipelineRuns(pipelineId: string, params?: { status?: string }) {
  return api.get<PipelineRun[]>(`/pipeline-runs`, { params: { pipeline_id: pipelineId, ...params } })
}

export function getPipelineRun(runId: string) {
  return api.get<PipelineRun>(`/pipeline-runs/${runId}`)
}

export function triggerPipelineRun(pipelineId: string, body: TriggerRunRequest) {
  return api.post<PipelineRun>(`/pipeline-runs`, { pipeline_id: pipelineId, ...body })
}

export function cancelPipelineRun(runId: string) {
  return api.post(`/pipeline-runs/${runId}/cancel`)
}

export function resumePipelineRun(runId: string, stepId: string, decision: 'approve' | 'reject') {
  return api.post(`/pipeline-runs/${runId}/resume`, { step_id: stepId, decision })
}

// Step Runs
export function listStepRuns(runId: string) {
  return api.get<PipelineStepRun[]>(`/pipeline-runs/${runId}/steps`)
}

export function getStepRunLogs(runId: string, stepId: string) {
  return api.get<{ logs: string }>(`/pipeline-runs/${runId}/steps/${stepId}/logs`)
}

// ── 辅助函数 ──

export function parseMetrics(raw: string | null): StepMetrics {
  if (!raw) return {}
  try { return JSON.parse(raw) } catch { return {} }
}

export function parseOutputBranches(raw: string | null): string[] {
  if (!raw) return []
  try { return JSON.parse(raw) } catch { return [] }
}

export function parseJsonSchema(raw: string | null): Record<string, any> {
  if (!raw) return {}
  try { return JSON.parse(raw) } catch { return {} }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/pipeline.ts
git commit -m "feat(pipeline): add pipeline API types and functions"
```

---

## Task 3: 节点样式常量 + DAG/YAML 转换工具

**Files:**
- Create: `lakeon-console/src/views/datalake/components/pipeline/nodeStyles.ts`
- Create: `lakeon-console/src/views/datalake/components/pipeline/dagUtils.ts`

- [ ] **Step 1: 编写 nodeStyles.ts**

```typescript
// nodeStyles.ts — 节点分类颜色和样式常量

import type { ComponentCategory, StepRunStatus } from '@/api/pipeline'

/** 组件分类 → 颜色映射 */
export const categoryColors: Record<ComponentCategory, { bg: string; border: string; text: string; icon: string }> = {
  DATA_PREP: { bg: '#fef9ee', border: '#f0c674', text: '#92700c', icon: '📥' },
  EXTRACT:   { bg: '#eef6fe', border: '#6ca6e0', text: '#1a5276', icon: '✂️' },
  CLEAN:     { bg: '#eefbf4', border: '#52c07e', text: '#1a6b3c', icon: '🧹' },
  FILTER:    { bg: '#fff5f0', border: '#e8825a', text: '#8b3a0e', icon: '🔍' },
  QC:        { bg: '#f5eeff', border: '#9b7dd4', text: '#4a2d7a', icon: '✅' },
  LABEL:     { bg: '#eef5fe', border: '#5b9bd5', text: '#1a3d6b', icon: '🏷️' },
  PUBLISH:   { bg: '#f0faf5', border: '#3aa76d', text: '#145a32', icon: '📦' },
}

/** 分类中文显示名 */
export const categoryLabels: Record<ComponentCategory, string> = {
  DATA_PREP: '数据准备',
  EXTRACT:   '提取',
  CLEAN:     '清洗',
  FILTER:    '过滤',
  QC:        '质检',
  LABEL:     '标注',
  PUBLISH:   '发布',
}

/** 步骤运行状态 → 节点颜色 */
export const statusColors: Record<StepRunStatus, { bg: string; border: string; pulse?: boolean }> = {
  PENDING:   { bg: '#f5f3f0', border: '#d1ccc4' },
  RUNNING:   { bg: '#e8f4fd', border: '#3b82f6', pulse: true },
  PAUSED:    { bg: '#fef9c3', border: '#eab308' },
  SUCCEEDED: { bg: '#ecfdf5', border: '#22c55e' },
  FAILED:    { bg: '#fef2f2', border: '#ef4444' },
  SKIPPED:   { bg: '#f5f3f0', border: '#94a3b8' },
}

/** 特殊节点类型颜色 */
export const specialNodeColors = {
  fanOut:      { bg: '#fff7ed', border: '#f97316' },
  merge:       { bg: '#f0f9ff', border: '#0ea5e9' },
  humanReview: { bg: '#fdf4ff', border: '#a855f7' },
}

/** 节点默认尺寸 */
export const NODE_WIDTH = 220
export const NODE_HEIGHT = 72
```

- [ ] **Step 2: 编写 dagUtils.ts — YAML 解析与 Vue Flow 节点转换**

```typescript
// dagUtils.ts — DAG YAML ↔ Vue Flow nodes/edges 双向转换

import type { Node, Edge } from '@vue-flow/core'
import { NODE_WIDTH, NODE_HEIGHT } from './nodeStyles'

/** DAG YAML 中的步骤定义 */
export interface DagStep {
  id: string
  component?: string
  component_version?: number
  type?: string                    // 'merge' 等特殊类型
  params?: Record<string, any>
  inputs?: Record<string, string>
  outputs?: Record<string, string>
  depends_on?: string[]
  fan_out?: boolean
  condition?: string               // "rule_filter.needs_crop"
  checkpoint?: boolean
  execution_mode?: string          // 'HUMAN_REVIEW'
  output_branches?: string[]
  output_dataset?: { name: string; format: string }
}

/** 解析 YAML 文本中 steps 部分，返回步骤列表 */
export interface DagDefinition {
  name?: string
  data_type?: string
  description?: string
  steps: DagStep[]
}

/**
 * 简易 YAML 解析（仅处理 pipeline DAG 格式）。
 * 生产环境应使用 js-yaml 库，此处为减少依赖采用 JSON 中间格式。
 * 如果后端返回的 dagYaml 是 JSON 兼容格式则直接 parse，否则需要安装 js-yaml。
 *
 * 推荐：Task 7 安装 js-yaml 后替换此函数。
 */
export function parseDagYaml(yamlText: string): DagDefinition {
  // 尝试 JSON 解析（后端可能返回 JSON 格式）
  try {
    return JSON.parse(yamlText)
  } catch {
    // 需要 js-yaml —— Task 7 中处理
    console.warn('YAML parsing requires js-yaml library, returning empty DAG')
    return { steps: [] }
  }
}

/**
 * 将 DagDefinition 序列化为 YAML 文本。
 * 同上，完整实现在 Task 7 中替换为 js-yaml dump。
 */
export function serializeDagYaml(dag: DagDefinition): string {
  return JSON.stringify(dag, null, 2)
}

/**
 * 从 DagStep[] 生成 Vue Flow nodes + edges。
 * 使用简单的自上而下布局：每行一个节点，fan-out 后并排。
 */
export function dagToFlow(steps: DagStep[]): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = []
  const edges: Edge[] = []

  // 构建依赖图
  const stepMap = new Map<string, DagStep>()
  steps.forEach(s => stepMap.set(s.id, s))

  // 计算每个节点的依赖（显式 depends_on + 隐式 inputs 引用）
  const deps = new Map<string, string[]>()
  for (const step of steps) {
    const d: string[] = [...(step.depends_on || [])]
    if (step.inputs) {
      for (const ref of Object.values(step.inputs)) {
        if (ref.startsWith('$input')) continue
        const upstream = ref.split('.')[0]
        if (upstream && !d.includes(upstream)) d.push(upstream)
      }
    }
    if (step.condition) {
      const upstream = step.condition.split('.')[0]
      if (upstream && !d.includes(upstream)) d.push(upstream)
    }
    deps.set(step.id, d)
  }

  // 拓扑排序分层
  const layers: string[][] = []
  const assigned = new Set<string>()
  const remaining = new Set(steps.map(s => s.id))

  while (remaining.size > 0) {
    const layer: string[] = []
    for (const id of remaining) {
      const d = deps.get(id) || []
      if (d.every(dep => assigned.has(dep))) {
        layer.push(id)
      }
    }
    if (layer.length === 0) {
      // 循环依赖兜底，把剩余全部放入
      layer.push(...remaining)
    }
    layers.push(layer)
    layer.forEach(id => { assigned.add(id); remaining.delete(id) })
  }

  // 布局
  const GAP_X = 280
  const GAP_Y = 120

  for (let layerIdx = 0; layerIdx < layers.length; layerIdx++) {
    const layer = layers[layerIdx]
    const totalWidth = layer.length * GAP_X
    const startX = -(totalWidth - GAP_X) / 2

    for (let colIdx = 0; colIdx < layer.length; colIdx++) {
      const stepId = layer[colIdx]
      const step = stepMap.get(stepId)!

      // 确定节点类型
      let nodeType = 'pipelineNode'
      if (step.type === 'merge') nodeType = 'mergeNode'
      else if (step.fan_out) nodeType = 'fanOutNode'
      else if (step.execution_mode === 'HUMAN_REVIEW') nodeType = 'humanReviewNode'

      nodes.push({
        id: stepId,
        type: nodeType,
        position: { x: startX + colIdx * GAP_X, y: layerIdx * GAP_Y },
        data: { step, label: step.component || step.type || stepId },
      })

      // 生成 edges
      const d = deps.get(stepId) || []
      for (const depId of d) {
        const sourceStep = stepMap.get(depId)
        // 条件分支时标注 branch label
        let label: string | undefined
        if (step.condition) {
          const parts = step.condition.split('.')
          if (parts[0] === depId && parts[1]) label = parts[1]
        }

        edges.push({
          id: `e-${depId}-${stepId}`,
          source: depId,
          target: stepId,
          sourceHandle: label ? `branch-${label}` : undefined,
          label,
          animated: false,
          type: 'pipelineEdge',
        })
      }
    }
  }

  return { nodes, edges }
}

/**
 * 从 Vue Flow nodes + edges 反向生成 DagStep[]。
 * 用于 DAG → YAML 同步。
 */
export function flowToDag(nodes: Node[], edges: Edge[]): DagStep[] {
  const steps: DagStep[] = []

  for (const node of nodes) {
    const step: DagStep = { ...node.data.step }
    // 根据 edges 更新 depends_on
    const incoming = edges.filter(e => e.target === node.id)
    if (incoming.length > 0) {
      step.depends_on = incoming.map(e => e.source)
    }
    steps.push(step)
  }

  return steps
}

/**
 * 自动布局：重新计算节点位置（拓扑排序后重新排列）。
 */
export function autoLayout(nodes: Node[], edges: Edge[]): Node[] {
  const steps = flowToDag(nodes, edges)
  const { nodes: layoutNodes } = dagToFlow(steps)

  // 合并新位置到现有节点（保留 data 等其他属性）
  const posMap = new Map(layoutNodes.map(n => [n.id, n.position]))
  return nodes.map(n => ({
    ...n,
    position: posMap.get(n.id) || n.position,
  }))
}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/datalake/components/pipeline/
git commit -m "feat(pipeline): add node styles and DAG/YAML conversion utils"
```

---

## Task 4: Pipeline 列表页

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakePipelines.vue`

- [ ] **Step 1: 编写 DatalakePipelines.vue**

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">数据生产线</h1>
      <div class="page-header-actions">
        <ViewToggle v-model="viewMode" />
        <button class="btn btn-primary" @click="showCreateMenu = !showCreateMenu">
          新建生产线
        </button>
        <!-- 创建下拉菜单 -->
        <div v-if="showCreateMenu" class="create-menu" @mouseleave="showCreateMenu = false">
          <div class="create-menu-item" @click="router.push('/datalake/pipelines/new')">
            <span class="create-icon">+</span>
            <div>
              <div class="create-label">空白创建</div>
              <div class="create-desc">从空画布开始</div>
            </div>
          </div>
          <div class="create-menu-divider"></div>
          <div class="create-menu-section">从模板创建</div>
          <div
            v-for="tpl in templates"
            :key="tpl.id"
            class="create-menu-item"
            @click="createFromTemplate(tpl)"
          >
            <span class="create-icon tpl-icon">{{ dataTypeIcon(tpl.dataType) }}</span>
            <div>
              <div class="create-label">{{ tpl.name }}</div>
              <div class="create-desc">{{ tpl.description || tpl.dataType }}</div>
            </div>
          </div>
          <div v-if="templates.length === 0" class="create-menu-empty">暂无模板</div>
        </div>
      </div>
    </div>

    <!-- Status filter tabs -->
    <div class="status-tabs">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        class="status-tab"
        :class="{ active: statusFilter === tab.value }"
        @click="statusFilter = tab.value"
      >
        {{ tab.label }}
        <span v-if="tab.count" class="tab-count">{{ tab.count }}</span>
      </button>
    </div>

    <!-- Card view -->
    <div v-if="viewMode === 'card' && filteredPipelines.length > 0" class="card-grid">
      <ResourceCard
        v-for="p in filteredPipelines"
        :key="p.id"
        :name="p.name"
        :status="latestRunStatus(p.id)"
        :statusLabel="latestRunStatusLabel(p.id)"
        :meta="[p.dataType || '通用', `v${p.latestVersion}`, formatTime(p.updatedAt)]"
        @click="router.push(`/datalake/pipelines/${p.id}`)"
      />
    </div>

    <!-- Table view -->
    <div v-if="viewMode === 'table' && filteredPipelines.length > 0" class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>数据类型</th>
            <th>最新版本</th>
            <th>最近运行</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in filteredPipelines" :key="p.id">
            <td>
              <router-link :to="`/datalake/pipelines/${p.id}`" class="name-link">{{ p.name }}</router-link>
              <div class="id-hint">{{ p.id }}</div>
            </td>
            <td><span class="type-tag">{{ p.dataType || '通用' }}</span></td>
            <td>v{{ p.latestVersion }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + statusDotClass(latestRunStatus(p.id))"></span>
              {{ latestRunStatusLabel(p.id) || '—' }}
            </td>
            <td style="color: #999;">{{ formatTime(p.updatedAt) }}</td>
            <td>
              <router-link :to="`/datalake/pipelines/${p.id}/edit`" class="btn btn-text btn-small btn-accent-text">编辑</router-link>
              <button class="btn btn-text btn-small btn-danger-text" @click="handleDelete(p)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-if="filteredPipelines.length === 0 && !loading" class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M4 5a1 1 0 0 1 1-1h4l2 2h8a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5z"/>
        <line x1="12" y1="10" x2="12" y2="16"/><line x1="9" y1="13" x2="15" y2="13"/>
      </svg>
      <div style="margin-top: 12px; color: #999;">
        {{ statusFilter ? '当前筛选无结果' : '尚未创建数据生产线' }}
      </div>
      <button v-if="!statusFilter" class="btn btn-primary" style="margin-top: 16px;" @click="router.push('/datalake/pipelines/new')">
        创建第一条生产线
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ViewToggle from '@/components/ViewToggle.vue'
import ResourceCard from '@/components/ResourceCard.vue'
import { listPipelines, deletePipeline, type Pipeline } from '@/api/pipeline'

const router = useRouter()
const loading = ref(true)
const pipelines = ref<Pipeline[]>([])
const templates = ref<Pipeline[]>([])
const viewMode = ref<'card' | 'table'>('card')
const statusFilter = ref('')
const showCreateMenu = ref(false)

// latestRunStatus 需要缓存每个 pipeline 的最近运行状态
// 实际实现需要额外 API，这里预留
const runStatusMap = ref<Record<string, string>>({})

const statusTabs = computed(() => [
  { label: '全部', value: '', count: pipelines.value.length },
])

const filteredPipelines = computed(() => {
  if (!statusFilter.value) return pipelines.value
  return pipelines.value.filter(p => runStatusMap.value[p.id] === statusFilter.value)
})

function latestRunStatus(pipelineId: string): string {
  return runStatusMap.value[pipelineId] || ''
}

function latestRunStatusLabel(pipelineId: string): string {
  const s = latestRunStatus(pipelineId)
  const labels: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return labels[s] || ''
}

function statusDotClass(status: string): string {
  if (['RUNNING'].includes(status)) return 'blue'
  if (['SUCCEEDED'].includes(status)) return 'green'
  if (['FAILED'].includes(status)) return 'red'
  if (['PAUSED'].includes(status)) return 'yellow'
  return 'gray'
}

function dataTypeIcon(dt: string | null): string {
  const icons: Record<string, string> = { VIDEO: '🎬', TEXT: '📄', IMAGE: '🖼', AUDIO: '🔊', DOCUMENT: '📑' }
  return icons[dt || ''] || '📊'
}

function formatTime(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function loadData() {
  loading.value = true
  try {
    const [allRes, tplRes] = await Promise.all([
      listPipelines(),
      listPipelines({ is_template: true }),
    ])
    pipelines.value = allRes.data.filter(p => !p.isTemplate)
    templates.value = tplRes.data
  } catch (err) {
    console.error('Failed to load pipelines', err)
  } finally {
    loading.value = false
  }
}

function createFromTemplate(tpl: Pipeline) {
  showCreateMenu.value = false
  router.push({ path: '/datalake/pipelines/new', query: { template: tpl.id } })
}

async function handleDelete(p: Pipeline) {
  if (!confirm(`确认删除生产线「${p.name}」？`)) return
  try {
    await deletePipeline(p.id)
    pipelines.value = pipelines.value.filter(x => x.id !== p.id)
  } catch (err) {
    console.error('Failed to delete pipeline', err)
  }
}

onMounted(loadData)
</script>

<style scoped>
/* 复用项目全局 .page-container / .page-header / .data-table 等样式 */
/* 仅补充此页面特有的样式 */

.create-menu {
  position: absolute; right: 0; top: 100%; margin-top: 4px;
  background: #fff; border: 1px solid #e8e4df; border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.08); min-width: 240px; z-index: 10;
  padding: 6px 0;
}
.create-menu-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 14px; cursor: pointer; transition: background 0.12s;
}
.create-menu-item:hover { background: #f8f5f1; }
.create-icon { font-size: 18px; width: 28px; text-align: center; }
.create-label { font-size: 13px; font-weight: 500; color: #2c3e50; }
.create-desc { font-size: 11px; color: #999; margin-top: 1px; }
.create-menu-divider { height: 1px; background: #e8e4df; margin: 4px 0; }
.create-menu-section { font-size: 11px; color: #94a3b8; padding: 6px 14px 2px; }
.create-menu-empty { font-size: 12px; color: #ccc; padding: 8px 14px; }

.name-link { color: #2a4d6a; text-decoration: none; font-weight: 500; }
.name-link:hover { text-decoration: underline; }
.id-hint { font-size: 11px; color: #bbb; margin-top: 2px; }
.type-tag { font-size: 11px; padding: 2px 8px; border-radius: 3px; background: #f5f3f0; color: #666; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakePipelines.vue
git commit -m "feat(pipeline): add pipeline list page with card/table views"
```

---

## Task 5: DAG 编辑器 — 基础画布 + 自定义节点

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakePipelineEditor.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeBase.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeFanOut.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeMerge.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeHumanReview.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineCustomEdge.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineToolbar.vue`

- [ ] **Step 1: 编写 PipelineNodeBase.vue — 通用组件节点**

```vue
<template>
  <div
    class="pipeline-node"
    :class="{ selected: selected }"
    :style="nodeStyle"
  >
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">{{ icon }}</span>
      <span class="node-label">{{ data.step?.component || data.label }}</span>
    </div>
    <div class="node-category">{{ categoryLabel }}</div>
    <!-- 运行监控模式下的 metrics 气泡 -->
    <div v-if="metricsText" class="node-metrics">{{ metricsText }}</div>
    <!-- 多输出端口（条件分支） -->
    <Handle
      v-if="!hasBranches"
      type="source"
      :position="Position.Bottom"
    />
    <Handle
      v-for="(branch, i) in branches"
      :key="branch"
      type="source"
      :id="'branch-' + branch"
      :position="Position.Bottom"
      :style="{ left: `${(i + 1) * 100 / (branches.length + 1)}%` }"
    />
    <div v-if="hasBranches" class="branch-labels">
      <span v-for="branch in branches" :key="branch" class="branch-label">{{ branch }}</span>
    </div>
    <!-- checkpoint 标识 -->
    <div v-if="data.step?.checkpoint" class="checkpoint-badge" title="Checkpoint">CP</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { categoryColors, categoryLabels } from './nodeStyles'
import { parseMetrics, parseOutputBranches, type ComponentCategory } from '@/api/pipeline'

const props = defineProps<{
  data: any
  selected?: boolean
}>()

const category = computed<ComponentCategory>(() => props.data.step?.category || 'DATA_PREP')
const colors = computed(() => categoryColors[category.value] || categoryColors.DATA_PREP)
const icon = computed(() => colors.value.icon)
const categoryLabel = computed(() => categoryLabels[category.value] || category.value)

const branches = computed(() => parseOutputBranches(props.data.step?.output_branches_raw || null)
  || props.data.step?.output_branches || [])
const hasBranches = computed(() => branches.value.length > 0)

const metricsText = computed(() => {
  const m = parseMetrics(props.data.metrics || null)
  if (m.input_count != null && m.output_count != null) {
    return `${m.input_count} → ${m.output_count}${m.retention ? ` (${m.retention})` : ''}`
  }
  return ''
})

const nodeStyle = computed(() => ({
  background: colors.value.bg,
  borderColor: colors.value.border,
  color: colors.value.text,
}))
</script>

<style scoped>
.pipeline-node {
  border: 2px solid; border-radius: 8px; padding: 10px 14px;
  min-width: 180px; font-size: 12px; position: relative;
  transition: box-shadow 0.15s;
}
.pipeline-node.selected { box-shadow: 0 0 0 3px rgba(42, 77, 106, 0.25); }
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; }
.node-icon { font-size: 14px; }
.node-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.node-category { font-size: 10px; opacity: 0.7; margin-top: 2px; }
.node-metrics {
  margin-top: 4px; font-size: 10px; padding: 2px 6px;
  background: rgba(0,0,0,0.05); border-radius: 3px; display: inline-block;
}
.branch-labels {
  display: flex; justify-content: space-around; margin-top: 4px;
  font-size: 9px; opacity: 0.6;
}
.checkpoint-badge {
  position: absolute; top: -6px; right: -6px;
  background: #2a4d6a; color: #fff; font-size: 8px; font-weight: 700;
  padding: 1px 4px; border-radius: 3px;
}
</style>
```

- [ ] **Step 2: 编写 PipelineNodeFanOut.vue**

```vue
<template>
  <div class="pipeline-node fanout-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">⑂</span>
      <span class="node-label">{{ data.step?.component || 'Fan-out' }}</span>
    </div>
    <div class="node-sub">1 → N 裂变</div>
    <div v-if="metricsText" class="node-metrics">{{ metricsText }}</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { parseMetrics } from '@/api/pipeline'

const props = defineProps<{ data: any; selected?: boolean }>()

const metricsText = computed(() => {
  const m = parseMetrics(props.data.metrics || null)
  if (m.output_count != null) return `→ ${m.output_count} items`
  return ''
})
</script>

<style scoped>
.fanout-node {
  border: 2px solid #f97316; background: #fff7ed; border-radius: 8px;
  padding: 10px 14px; min-width: 160px; font-size: 12px; position: relative;
}
.fanout-node.selected { box-shadow: 0 0 0 3px rgba(249, 115, 22, 0.25); }
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #9a3412; }
.node-icon { font-size: 16px; }
.node-sub { font-size: 10px; color: #c2410c; opacity: 0.7; margin-top: 2px; }
.node-metrics { margin-top: 4px; font-size: 10px; padding: 2px 6px; background: rgba(0,0,0,0.05); border-radius: 3px; }
</style>
```

- [ ] **Step 3: 编写 PipelineNodeMerge.vue**

```vue
<template>
  <div class="pipeline-node merge-node" :class="{ selected }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">⊕</span>
      <span class="node-label">合并</span>
    </div>
    <div class="node-sub">N → 1 汇聚</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
defineProps<{ data: any; selected?: boolean }>()
</script>

<style scoped>
.merge-node {
  border: 2px solid #0ea5e9; background: #f0f9ff; border-radius: 8px;
  padding: 10px 14px; min-width: 140px; font-size: 12px; position: relative;
}
.merge-node.selected { box-shadow: 0 0 0 3px rgba(14, 165, 233, 0.25); }
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #0369a1; }
.node-icon { font-size: 16px; }
.node-sub { font-size: 10px; color: #0284c7; opacity: 0.7; margin-top: 2px; }
</style>
```

- [ ] **Step 4: 编写 PipelineNodeHumanReview.vue**

```vue
<template>
  <div class="pipeline-node review-node" :class="{ selected, paused: isPaused }">
    <Handle type="target" :position="Position.Top" />
    <div class="node-header">
      <span class="node-icon">👤</span>
      <span class="node-label">{{ data.step?.component || '人工审核' }}</span>
    </div>
    <div class="node-sub">HUMAN_REVIEW — 需人工确认后继续</div>
    <div v-if="isPaused" class="pause-badge">等待审核</div>
    <Handle type="source" :position="Position.Bottom" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'

const props = defineProps<{ data: any; selected?: boolean }>()

const isPaused = computed(() => props.data.runStatus === 'PAUSED')
</script>

<style scoped>
.review-node {
  border: 2px solid #a855f7; background: #fdf4ff; border-radius: 8px;
  padding: 10px 14px; min-width: 180px; font-size: 12px; position: relative;
}
.review-node.selected { box-shadow: 0 0 0 3px rgba(168, 85, 247, 0.25); }
.review-node.paused { animation: pulse-border 2s infinite; }
@keyframes pulse-border {
  0%, 100% { border-color: #a855f7; }
  50% { border-color: #eab308; box-shadow: 0 0 8px rgba(234, 179, 8, 0.3); }
}
.node-header { display: flex; align-items: center; gap: 6px; font-weight: 600; color: #6b21a8; }
.node-icon { font-size: 14px; }
.node-sub { font-size: 10px; color: #7e22ce; opacity: 0.7; margin-top: 2px; }
.pause-badge {
  position: absolute; top: -8px; right: -8px;
  background: #eab308; color: #fff; font-size: 9px; font-weight: 700;
  padding: 2px 6px; border-radius: 4px;
}
</style>
```

- [ ] **Step 5: 编写 PipelineCustomEdge.vue**

```vue
<template>
  <BaseEdge :id="id" :path="path[0]" :marker-end="markerEnd" :style="edgeStyle" />
  <EdgeLabelRenderer v-if="data?.label">
    <div
      class="edge-label"
      :style="{
        position: 'absolute',
        transform: `translate(-50%, -50%) translate(${path[1]}px,${path[2]}px)`,
        pointerEvents: 'all',
      }"
    >
      {{ data.label }}
    </div>
  </EdgeLabelRenderer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@vue-flow/core'

const props = defineProps<EdgeProps>()

const path = computed(() => getBezierPath({
  sourceX: props.sourceX,
  sourceY: props.sourceY,
  targetX: props.targetX,
  targetY: props.targetY,
  sourcePosition: props.sourcePosition,
  targetPosition: props.targetPosition,
}))

const edgeStyle = computed(() => ({
  stroke: props.data?.animated ? '#3b82f6' : '#b0aaA0',
  strokeWidth: 2,
}))
</script>

<style scoped>
.edge-label {
  font-size: 10px; color: #666; background: #fff;
  padding: 1px 6px; border-radius: 3px; border: 1px solid #e8e4df;
  white-space: nowrap;
}
</style>
```

- [ ] **Step 6: 编写 PipelineToolbar.vue**

```vue
<template>
  <div class="pipeline-toolbar">
    <div class="toolbar-left">
      <button class="tb-btn" @click="$emit('undo')" title="撤销" :disabled="!canUndo">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><path d="M2 8l4-4v2.5C10 6.5 13 8 14 12c-1.5-3-4-4-8-4V10L2 8z"/></svg>
      </button>
      <button class="tb-btn" @click="$emit('redo')" title="重做" :disabled="!canRedo">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><path d="M14 8l-4-4v2.5C6 6.5 3 8 2 12c1.5-3 4-4 8-4V10l4-2z"/></svg>
      </button>
      <div class="tb-sep"></div>
      <button class="tb-btn" @click="$emit('autoLayout')" title="自动布局">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><rect x="1" y="1" width="5" height="5" rx="1"/><rect x="10" y="1" width="5" height="5" rx="1"/><rect x="5.5" y="10" width="5" height="5" rx="1"/><line x1="3.5" y1="6" x2="3.5" y2="10" stroke="currentColor" stroke-width="1"/><line x1="12.5" y1="6" x2="12.5" y2="10" stroke="currentColor" stroke-width="1"/></svg>
      </button>
      <button class="tb-btn" @click="$emit('fitView')" title="适应视图">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="2" y="2" width="12" height="12" rx="1"/><line x1="2" y1="8" x2="5" y2="8"/><line x1="11" y1="8" x2="14" y2="8"/><line x1="8" y1="2" x2="8" y2="5"/><line x1="8" y1="11" x2="8" y2="14"/></svg>
      </button>
    </div>
    <div class="toolbar-center">
      <div class="view-switch">
        <button class="vs-btn" :class="{ active: mode === 'dag' }" @click="$emit('update:mode', 'dag')">DAG</button>
        <button class="vs-btn" :class="{ active: mode === 'yaml' }" @click="$emit('update:mode', 'yaml')">YAML</button>
      </div>
    </div>
    <div class="toolbar-right">
      <button class="btn btn-secondary" @click="$emit('saveDraft')">保存草稿</button>
      <button class="btn btn-primary" @click="$emit('publish')">发布版本</button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  mode: 'dag' | 'yaml'
  canUndo: boolean
  canRedo: boolean
}>()

defineEmits<{
  undo: []
  redo: []
  autoLayout: []
  fitView: []
  saveDraft: []
  publish: []
  'update:mode': [mode: 'dag' | 'yaml']
}>()
</script>

<style scoped>
.pipeline-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6px 12px; background: #fff; border-bottom: 1px solid #e8e4df;
}
.toolbar-left, .toolbar-right { display: flex; align-items: center; gap: 4px; }
.tb-btn {
  padding: 4px 8px; border: none; background: transparent; cursor: pointer;
  color: #666; border-radius: 4px; transition: all 0.12s;
  display: flex; align-items: center;
}
.tb-btn:hover:not(:disabled) { background: #f5f3f0; color: #2c3e50; }
.tb-btn:disabled { opacity: 0.3; cursor: default; }
.tb-sep { width: 1px; height: 16px; background: #e8e4df; margin: 0 4px; }
.view-switch { display: flex; border: 1px solid #e8e4df; border-radius: 4px; overflow: hidden; }
.vs-btn {
  padding: 3px 12px; border: none; background: #fff; cursor: pointer;
  font-size: 12px; color: #666; transition: all 0.12s;
}
.vs-btn:not(:last-child) { border-right: 1px solid #e8e4df; }
.vs-btn.active { background: #2a4d6a; color: #fff; }
</style>
```

- [ ] **Step 7: 编写 DatalakePipelineEditor.vue — 主编辑器页面**

```vue
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
      <div class="editor-canvas" ref="canvasRef">
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
          @drop="onDrop"
          @dragover.prevent
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
import { ref, computed, onMounted, markRaw, watch } from 'vue'
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
import type { Node, Edge, Connection } from '@vue-flow/core'

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
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const selectedNode = ref<Node | null>(null)
const yamlText = ref('')
const canvasRef = ref<HTMLElement | null>(null)

// 撤销/重做
const undoStack = ref<{ nodes: Node[]; edges: Edge[] }[]>([])
const redoStack = ref<{ nodes: Node[]; edges: Edge[] }[]>([])

// 自定义节点/边类型注册
const nodeTypes = {
  pipelineNode: markRaw(PipelineNodeBase),
  fanOutNode: markRaw(PipelineNodeFanOut),
  mergeNode: markRaw(PipelineNodeMerge),
  humanReviewNode: markRaw(PipelineNodeHumanReview),
}
const edgeTypes = {
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
  const steps = flowToDag(nodes.value, edges.value)
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

function onNodeClick(_event: MouseEvent, node: Node) {
  selectedNode.value = node
}

function onConnect(connection: Connection) {
  pushUndo()
  edges.value.push({
    id: `e-${connection.source}-${connection.target}`,
    source: connection.source,
    target: connection.target,
    sourceHandle: connection.sourceHandle || undefined,
    targetHandle: connection.targetHandle || undefined,
    type: 'pipelineEdge',
  })
}

// 拖拽添加节点
let dragComponent: PipelineComponent | null = null

function onComponentDragStart(component: PipelineComponent) {
  dragComponent = component
}

function onDrop(event: DragEvent) {
  if (!dragComponent || !canvasRef.value) return
  pushUndo()

  const bounds = canvasRef.value.getBoundingClientRect()
  const position = {
    x: event.clientX - bounds.left - 90,
    y: event.clientY - bounds.top - 36,
  }

  const compVersion = componentVersions.value.get(dragComponent.id)
  const step: DagStep = {
    id: dragComponent.name + '_' + Date.now().toString(36),
    component: dragComponent.name,
    component_version: compVersion?.version || 1,
    params: {},
    inputs: {},
    outputs: {},
  }

  // 确定节点类型
  let nodeType = 'pipelineNode'
  if (compVersion?.executionMode === 'HUMAN_REVIEW') nodeType = 'humanReviewNode'

  nodes.value.push({
    id: step.id,
    type: nodeType,
    position,
    data: {
      step,
      label: dragComponent.displayName,
      category: dragComponent.category,
    },
  })

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
```

- [ ] **Step 8: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakePipelineEditor.vue lakeon-console/src/views/datalake/components/pipeline/
git commit -m "feat(pipeline): add DAG editor with custom nodes, toolbar, and canvas"
```

---

## Task 6: 左栏组件面板 — PipelineComponentPanel.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineComponentPanel.vue`

- [ ] **Step 1: 编写 PipelineComponentPanel.vue**

```vue
<template>
  <div class="component-panel">
    <div class="panel-header">
      <span class="panel-title">组件库</span>
    </div>
    <input
      v-model="search"
      class="panel-search"
      placeholder="搜索组件..."
    />
    <div class="panel-groups">
      <div v-for="group in filteredGroups" :key="group.category" class="comp-group">
        <div
          class="group-header"
          :style="{ borderLeftColor: groupColor(group.category) }"
          @click="toggleGroup(group.category)"
        >
          <span class="group-icon">{{ groupIcon(group.category) }}</span>
          <span class="group-label">{{ groupLabel(group.category) }}</span>
          <span class="group-count">{{ group.items.length }}</span>
          <span class="group-chevron" :class="{ expanded: expandedGroups.has(group.category) }">&#9654;</span>
        </div>
        <div v-if="expandedGroups.has(group.category)" class="group-items">
          <div
            v-for="comp in group.items"
            :key="comp.id"
            class="comp-item"
            draggable="true"
            @dragstart="onDragStart($event, comp)"
          >
            <div class="comp-name">{{ comp.displayName }}</div>
            <div class="comp-desc">{{ comp.description || comp.name }}</div>
            <div v-if="!comp.tenantId" class="comp-badge">内置</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { PipelineComponent, ComponentCategory } from '@/api/pipeline'
import { categoryColors, categoryLabels } from './nodeStyles'

const props = defineProps<{
  components: PipelineComponent[]
}>()

const emit = defineEmits<{
  dragStart: [component: PipelineComponent]
}>()

const search = ref('')
const expandedGroups = ref(new Set<string>(['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']))

interface CompGroup {
  category: string
  items: PipelineComponent[]
}

const allGroups = computed<CompGroup[]>(() => {
  const map = new Map<string, PipelineComponent[]>()
  const order: ComponentCategory[] = ['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']
  for (const cat of order) map.set(cat, [])
  for (const comp of props.components) {
    const list = map.get(comp.category) || []
    list.push(comp)
    map.set(comp.category, list)
  }
  return order.filter(cat => (map.get(cat) || []).length > 0).map(cat => ({
    category: cat,
    items: map.get(cat)!,
  }))
})

const filteredGroups = computed<CompGroup[]>(() => {
  if (!search.value.trim()) return allGroups.value
  const q = search.value.trim().toLowerCase()
  return allGroups.value
    .map(g => ({
      ...g,
      items: g.items.filter(c =>
        c.displayName.toLowerCase().includes(q) ||
        c.name.toLowerCase().includes(q) ||
        (c.description || '').toLowerCase().includes(q)
      ),
    }))
    .filter(g => g.items.length > 0)
})

function groupColor(cat: string): string {
  return categoryColors[cat as ComponentCategory]?.border || '#ccc'
}
function groupIcon(cat: string): string {
  return categoryColors[cat as ComponentCategory]?.icon || '?'
}
function groupLabel(cat: string): string {
  return categoryLabels[cat as ComponentCategory] || cat
}

function toggleGroup(cat: string) {
  if (expandedGroups.value.has(cat)) expandedGroups.value.delete(cat)
  else expandedGroups.value.add(cat)
}

function onDragStart(event: DragEvent, comp: PipelineComponent) {
  event.dataTransfer?.setData('application/pipeline-component', comp.id)
  emit('dragStart', comp)
}
</script>

<style scoped>
.component-panel {
  width: 220px; border-right: 1px solid #e8e4df; background: #fff;
  display: flex; flex-direction: column; overflow: hidden;
}
.panel-header { padding: 12px 14px 8px; }
.panel-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.panel-search {
  margin: 0 10px 8px; padding: 5px 8px; border: 1px solid #e8e4df;
  border-radius: 4px; font-size: 12px; outline: none;
}
.panel-search:focus { border-color: #2a4d6a; }
.panel-groups { flex: 1; overflow-y: auto; padding-bottom: 12px; }

.group-header {
  display: flex; align-items: center; gap: 6px; padding: 6px 12px;
  cursor: pointer; border-left: 3px solid; font-size: 12px;
  transition: background 0.12s;
}
.group-header:hover { background: #f8f5f1; }
.group-icon { font-size: 13px; }
.group-label { font-weight: 500; color: #2c3e50; flex: 1; }
.group-count { font-size: 10px; color: #999; }
.group-chevron {
  font-size: 8px; color: #999; transition: transform 0.15s; display: inline-block;
}
.group-chevron.expanded { transform: rotate(90deg); }

.group-items { padding: 2px 0; }
.comp-item {
  padding: 6px 12px 6px 24px; cursor: grab; transition: background 0.12s;
  position: relative;
}
.comp-item:hover { background: #f5f3f0; }
.comp-item:active { cursor: grabbing; }
.comp-name { font-size: 12px; font-weight: 500; color: #2c3e50; }
.comp-desc { font-size: 10px; color: #999; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.comp-badge {
  position: absolute; right: 10px; top: 8px;
  font-size: 9px; padding: 1px 4px; border-radius: 2px;
  background: #eef6fe; color: #1a5276;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/components/pipeline/PipelineComponentPanel.vue
git commit -m "feat(pipeline): add component panel with category grouping and drag support"
```

---

## Task 7: 右栏属性面板 — PipelinePropertyPanel.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelinePropertyPanel.vue`

- [ ] **Step 1: 编写 PipelinePropertyPanel.vue**

属性面板根据组件的 `params_schema`（JSON Schema）动态渲染表单控件。

```vue
<template>
  <div class="property-panel">
    <div class="panel-header">
      <span class="panel-title">{{ node.data.label || '节点属性' }}</span>
      <button class="panel-close" @click="$emit('delete', node.id)" title="删除节点">
        <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor"><path d="M2 4h12M5 4V3h6v1M6 7v5M10 7v5M3 4l1 10h8l1-10"/></svg>
      </button>
    </div>

    <div class="panel-section">
      <label class="field-label">步骤 ID</label>
      <input class="field-input" :value="node.data.step?.id" readonly />
    </div>

    <div class="panel-section">
      <label class="field-label">组件</label>
      <div class="field-value">{{ node.data.step?.component || '—' }}</div>
    </div>

    <!-- 动态参数表单 -->
    <div v-if="schemaFields.length > 0" class="panel-section">
      <div class="section-title">参数配置</div>
      <div v-for="field in schemaFields" :key="field.name" class="param-field">
        <label class="field-label">
          {{ field.name }}
          <span v-if="field.description" class="field-hint" :title="field.description">?</span>
        </label>
        <!-- number -->
        <input
          v-if="field.type === 'number'"
          type="number"
          class="field-input"
          :value="currentParams[field.name] ?? field.default"
          @input="updateParam(field.name, Number(($event.target as HTMLInputElement).value))"
        />
        <!-- boolean -->
        <label v-else-if="field.type === 'boolean'" class="field-toggle">
          <input
            type="checkbox"
            :checked="currentParams[field.name] ?? field.default"
            @change="updateParam(field.name, ($event.target as HTMLInputElement).checked)"
          />
          <span>{{ currentParams[field.name] ?? field.default ? '是' : '否' }}</span>
        </label>
        <!-- array (逗号分隔) -->
        <input
          v-else-if="field.type === 'array'"
          class="field-input"
          :value="(currentParams[field.name] ?? field.default ?? []).join(', ')"
          @input="updateParam(field.name, ($event.target as HTMLInputElement).value.split(',').map(s => s.trim()).filter(Boolean))"
          :placeholder="'逗号分隔'"
        />
        <!-- string / fallback -->
        <input
          v-else
          class="field-input"
          :value="currentParams[field.name] ?? field.default ?? ''"
          @input="updateParam(field.name, ($event.target as HTMLInputElement).value)"
        />
      </div>
    </div>

    <!-- Checkpoint 开关 -->
    <div class="panel-section">
      <label class="field-toggle">
        <input
          type="checkbox"
          :checked="node.data.step?.checkpoint"
          @change="toggleCheckpoint(($event.target as HTMLInputElement).checked)"
        />
        <span>Checkpoint（写入 OBS 快照）</span>
      </label>
    </div>

    <!-- 输出分支 -->
    <div v-if="branches.length > 0" class="panel-section">
      <div class="section-title">输出分支</div>
      <div v-for="b in branches" :key="b" class="branch-item">
        <span class="branch-dot"></span> {{ b }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { Node } from '@vue-flow/core'
import { parseJsonSchema, parseOutputBranches, type PipelineComponent, type PipelineComponentVersion } from '@/api/pipeline'

interface SchemaField {
  name: string
  type: string
  default?: any
  description?: string
}

const props = defineProps<{
  node: Node
  components: PipelineComponent[]
}>()

const emit = defineEmits<{
  'update:params': [nodeId: string, params: Record<string, any>]
  delete: [nodeId: string]
}>()

const currentParams = computed(() => props.node.data.step?.params || {})

// 从 node.data 中解析 params_schema
const schemaFields = computed<SchemaField[]>(() => {
  const raw = props.node.data.step?.params_schema || props.node.data.paramsSchema
  const schema = typeof raw === 'string' ? parseJsonSchema(raw) : (raw || {})
  return Object.entries(schema).map(([name, def]: [string, any]) => ({
    name,
    type: def?.type || 'string',
    default: def?.default,
    description: def?.description,
  }))
})

const branches = computed(() =>
  parseOutputBranches(props.node.data.step?.output_branches_raw || null) ||
  props.node.data.step?.output_branches || []
)

function updateParam(key: string, value: any) {
  const params = { ...currentParams.value, [key]: value }
  emit('update:params', props.node.id, params)
}

function toggleCheckpoint(checked: boolean) {
  // 更新 step 的 checkpoint 字段（通过 params update 回调简化处理）
  const step = { ...props.node.data.step, checkpoint: checked }
  emit('update:params', props.node.id, { ...currentParams.value, __checkpoint: checked })
}
</script>

<style scoped>
.property-panel {
  width: 280px; border-left: 1px solid #e8e4df; background: #fff;
  overflow-y: auto; padding-bottom: 20px;
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px 8px; border-bottom: 1px solid #f0ede8;
}
.panel-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.panel-close {
  background: none; border: none; cursor: pointer; color: #999; padding: 2px;
  border-radius: 3px; transition: all 0.12s;
}
.panel-close:hover { background: #fef2f2; color: #ef4444; }

.panel-section { padding: 10px 14px; border-bottom: 1px solid #f5f3f0; }
.section-title { font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 6px; text-transform: uppercase; }
.field-label { display: block; font-size: 11px; color: #666; margin-bottom: 3px; }
.field-hint {
  display: inline-block; width: 12px; height: 12px; text-align: center;
  border-radius: 50%; background: #e8e4df; color: #666; font-size: 9px;
  line-height: 12px; cursor: help; margin-left: 2px;
}
.field-input {
  width: 100%; padding: 5px 8px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 12px; outline: none; background: #faf9f7;
}
.field-input:focus { border-color: #2a4d6a; }
.field-input[readonly] { color: #999; }
.field-value { font-size: 12px; color: #2c3e50; }
.field-toggle {
  display: flex; align-items: center; gap: 6px; font-size: 12px; color: #2c3e50; cursor: pointer;
}

.param-field { margin-bottom: 8px; }
.branch-item { font-size: 12px; color: #666; padding: 2px 0; display: flex; align-items: center; gap: 6px; }
.branch-dot { width: 6px; height: 6px; border-radius: 50%; background: #e8825a; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/components/pipeline/PipelinePropertyPanel.vue
git commit -m "feat(pipeline): add property panel with dynamic schema-driven form"
```

---

## Task 8: YAML 视图 — PipelineYamlEditor.vue

**Files:**
- Install: `@codemirror/lang-yaml`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineYamlEditor.vue`

- [ ] **Step 1: 安装 YAML 语言支持**

```bash
cd lakeon-console && npm install @codemirror/lang-yaml
```

- [ ] **Step 2: 编写 PipelineYamlEditor.vue**

```vue
<template>
  <div class="yaml-editor-wrap">
    <div class="yaml-toolbar">
      <span class="yaml-filename">pipeline.yaml</span>
      <button class="btn btn-text btn-small" @click="formatYaml">格式化</button>
    </div>
    <div ref="editorContainer" class="yaml-editor-container"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { EditorView, basicSetup } from 'codemirror'
import { EditorState } from '@codemirror/state'
import { yaml } from '@codemirror/lang-yaml'
import { oneDark } from '@codemirror/theme-one-dark'

const props = defineProps<{ value: string }>()
const emit = defineEmits<{ 'update:value': [value: string] }>()

const editorContainer = ref<HTMLElement | null>(null)
let view: EditorView | null = null
let updating = false

onMounted(() => {
  if (!editorContainer.value) return

  const state = EditorState.create({
    doc: props.value,
    extensions: [
      basicSetup,
      yaml(),
      oneDark,
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !updating) {
          emit('update:value', update.state.doc.toString())
        }
      }),
      EditorView.theme({
        '&': { height: '100%', fontSize: '13px' },
        '.cm-scroller': { overflow: 'auto' },
      }),
    ],
  })

  view = new EditorView({ state, parent: editorContainer.value })
})

watch(() => props.value, (newVal) => {
  if (!view) return
  const current = view.state.doc.toString()
  if (current !== newVal) {
    updating = true
    view.dispatch({
      changes: { from: 0, to: current.length, insert: newVal },
    })
    updating = false
  }
})

function formatYaml() {
  // 基础格式化：重新序列化
  // 完整实现需要 js-yaml parse + dump
  emit('update:value', props.value)
}

onBeforeUnmount(() => {
  view?.destroy()
})
</script>

<style scoped>
.yaml-editor-wrap { display: flex; flex-direction: column; height: 100%; }
.yaml-toolbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px 12px; background: #282c34; color: #abb2bf; font-size: 12px;
}
.yaml-filename { font-family: monospace; }
.yaml-editor-container { flex: 1; overflow: hidden; }
</style>
```

- [ ] **Step 3: 同时安装 js-yaml 用于 dagUtils.ts 的完整 YAML 支持**

```bash
cd lakeon-console && npm install js-yaml && npm install -D @types/js-yaml
```

- [ ] **Step 4: 更新 dagUtils.ts 使用 js-yaml**

在 `dagUtils.ts` 中替换简易解析函数：

```typescript
// 在文件顶部添加
import jsYaml from 'js-yaml'

// 替换 parseDagYaml
export function parseDagYaml(yamlText: string): DagDefinition {
  try {
    const parsed = jsYaml.load(yamlText) as DagDefinition
    return parsed && parsed.steps ? parsed : { steps: [] }
  } catch {
    return { steps: [] }
  }
}

// 替换 serializeDagYaml
export function serializeDagYaml(dag: DagDefinition): string {
  return jsYaml.dump(dag, {
    indent: 2,
    lineWidth: 120,
    noRefs: true,
    sortKeys: false,
  })
}
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/package.json lakeon-console/package-lock.json lakeon-console/src/views/datalake/components/pipeline/PipelineYamlEditor.vue lakeon-console/src/views/datalake/components/pipeline/dagUtils.ts
git commit -m "feat(pipeline): add YAML editor with CodeMirror and js-yaml parsing"
```

---

## Task 9: Pipeline 详情页

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakePipelineDetail.vue`

- [ ] **Step 1: 编写 DatalakePipelineDetail.vue**

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="breadcrumb">
          <router-link to="/datalake/pipelines" class="breadcrumb-link">数据生产线</router-link>
          <span class="breadcrumb-sep"> / </span>
          <span>{{ pipeline?.name || '...' }}</span>
        </div>
        <div class="detail-meta" v-if="pipeline">
          <span class="meta-tag">{{ pipeline.dataType || '通用' }}</span>
          <span class="meta-id">{{ pipeline.id }}</span>
        </div>
      </div>
      <div class="page-header-actions">
        <button class="btn btn-secondary" @click="router.push(`/datalake/pipelines/${pipelineId}/edit`)">编辑</button>
        <button class="btn btn-primary" @click="showTriggerDialog = true">触发运行</button>
      </div>
    </div>

    <!-- Tab 切换 -->
    <div class="detail-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="detail-tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >{{ tab.label }}</button>
    </div>

    <!-- 版本列表 -->
    <div v-if="activeTab === 'versions'" class="section">
      <table class="data-table">
        <thead>
          <tr>
            <th>版本</th>
            <th>状态</th>
            <th>变更说明</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in versions" :key="v.id">
            <td><strong>v{{ v.version }}</strong></td>
            <td>
              <span class="version-status" :class="v.status.toLowerCase()">{{ versionStatusLabel(v.status) }}</span>
            </td>
            <td style="color: #666;">{{ v.changelog || '—' }}</td>
            <td style="color: #999;">{{ formatTime(v.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="versions.length === 0 && !loading" class="empty-hint">暂无版本</div>
    </div>

    <!-- 运行历史 -->
    <div v-if="activeTab === 'runs'" class="section">
      <table class="data-table">
        <thead>
          <tr>
            <th>运行 ID</th>
            <th>版本</th>
            <th>状态</th>
            <th>开始时间</th>
            <th>耗时</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id">
            <td>
              <router-link
                :to="`/datalake/pipelines/${pipelineId}/runs/${run.id}`"
                class="name-link"
              >{{ run.id }}</router-link>
            </td>
            <td>v{{ run.pipelineVersion }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + runDotClass(run.status)"></span>
              {{ runStatusLabel(run.status) }}
            </td>
            <td style="color: #999;">{{ formatTime(run.startedAt || run.createdAt) }}</td>
            <td style="color: #666;">{{ runDuration(run) }}</td>
            <td>
              <router-link
                :to="`/datalake/pipelines/${pipelineId}/runs/${run.id}`"
                class="btn btn-text btn-small btn-accent-text"
              >查看</router-link>
              <button
                v-if="run.status === 'RUNNING' || run.status === 'PAUSED'"
                class="btn btn-text btn-small btn-danger-text"
                @click="handleCancel(run.id)"
              >取消</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="runs.length === 0 && !loading" class="empty-hint">暂无运行记录</div>
    </div>

    <!-- 触发运行弹窗 -->
    <div v-if="showTriggerDialog" class="dialog-overlay" @click.self="showTriggerDialog = false">
      <div class="dialog">
        <h3>触发运行</h3>
        <div class="dialog-field">
          <label>Pipeline 版本</label>
          <select v-model="triggerForm.version">
            <option :value="undefined">最新版本 (v{{ pipeline?.latestVersion }})</option>
            <option v-for="v in versions" :key="v.version" :value="v.version">v{{ v.version }}</option>
          </select>
        </div>
        <div class="dialog-field">
          <label>输入数据集 ID（可选）</label>
          <input v-model="triggerForm.inputDatasetId" placeholder="ds_xxx" />
        </div>
        <div class="dialog-actions">
          <button class="btn btn-secondary" @click="showTriggerDialog = false">取消</button>
          <button class="btn btn-primary" @click="handleTrigger" :disabled="triggering">
            {{ triggering ? '提交中...' : '确认运行' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getPipeline, listPipelineVersions, listPipelineRuns,
  triggerPipelineRun, cancelPipelineRun,
  type Pipeline, type PipelineVersion, type PipelineRun,
} from '@/api/pipeline'

const route = useRoute()
const router = useRouter()
const pipelineId = computed(() => route.params.id as string)

const loading = ref(true)
const pipeline = ref<Pipeline | null>(null)
const versions = ref<PipelineVersion[]>([])
const runs = ref<PipelineRun[]>([])
const activeTab = ref('runs')
const showTriggerDialog = ref(false)
const triggering = ref(false)
const triggerForm = ref<{ version?: number; inputDatasetId?: string }>({})

const tabs = [
  { key: 'runs', label: '运行历史' },
  { key: 'versions', label: '版本列表' },
]

function versionStatusLabel(s: string): string {
  const m: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', DEPRECATED: '已废弃' }
  return m[s] || s
}

function runStatusLabel(s: string): string {
  const m: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return m[s] || s
}

function runDotClass(s: string): string {
  if (s === 'RUNNING') return 'blue'
  if (s === 'SUCCEEDED') return 'green'
  if (s === 'FAILED') return 'red'
  if (s === 'PAUSED') return 'yellow'
  return 'gray'
}

function runDuration(run: PipelineRun): string {
  if (!run.startedAt) return '—'
  const start = new Date(run.startedAt).getTime()
  const end = run.finishedAt ? new Date(run.finishedAt).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function loadData() {
  loading.value = true
  try {
    const [pRes, vRes, rRes] = await Promise.all([
      getPipeline(pipelineId.value),
      listPipelineVersions(pipelineId.value),
      listPipelineRuns(pipelineId.value),
    ])
    pipeline.value = pRes.data
    versions.value = vRes.data
    runs.value = rRes.data
  } catch (err) {
    console.error('Failed to load pipeline detail', err)
  } finally {
    loading.value = false
  }
}

async function handleTrigger() {
  triggering.value = true
  try {
    const res = await triggerPipelineRun(pipelineId.value, {
      pipeline_version: triggerForm.value.version,
      input_dataset_id: triggerForm.value.inputDatasetId || undefined,
    })
    showTriggerDialog.value = false
    router.push(`/datalake/pipelines/${pipelineId.value}/runs/${res.data.id}`)
  } catch (err) {
    console.error('Failed to trigger run', err)
    alert('触发运行失败')
  } finally {
    triggering.value = false
  }
}

async function handleCancel(runId: string) {
  if (!confirm('确认取消此运行？')) return
  try {
    await cancelPipelineRun(runId)
    await loadData()
  } catch (err) {
    console.error('Failed to cancel run', err)
  }
}

onMounted(loadData)
</script>

<style scoped>
.breadcrumb { font-size: 13px; color: #999; }
.breadcrumb-link { color: #2a4d6a; text-decoration: none; }
.breadcrumb-link:hover { text-decoration: underline; }
.breadcrumb-sep { margin: 0 4px; }
.detail-meta { margin-top: 4px; display: flex; gap: 8px; align-items: center; }
.meta-tag { font-size: 11px; padding: 2px 8px; border-radius: 3px; background: #f5f3f0; color: #666; }
.meta-id { font-size: 11px; color: #bbb; }

.detail-tabs { display: flex; gap: 0; border-bottom: 1px solid #e8e4df; margin-bottom: 16px; }
.detail-tab {
  padding: 8px 16px; border: none; background: none; cursor: pointer;
  font-size: 13px; color: #666; border-bottom: 2px solid transparent;
  transition: all 0.12s;
}
.detail-tab.active { color: #2a4d6a; border-bottom-color: #2a4d6a; font-weight: 500; }
.detail-tab:hover:not(.active) { color: #2c3e50; }

.section { margin-top: 8px; }
.name-link { color: #2a4d6a; text-decoration: none; font-weight: 500; }
.name-link:hover { text-decoration: underline; }
.empty-hint { text-align: center; color: #ccc; padding: 32px 0; font-size: 13px; }
.version-status { font-size: 11px; padding: 2px 8px; border-radius: 3px; }
.version-status.draft { background: #f5f3f0; color: #94a3b8; }
.version-status.published { background: #ecfdf5; color: #16a34a; }
.version-status.deprecated { background: #fef2f2; color: #ef4444; }

/* 弹窗 */
.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3); display: flex;
  align-items: center; justify-content: center; z-index: 100;
}
.dialog {
  background: #fff; border-radius: 10px; padding: 24px; width: 400px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
}
.dialog h3 { margin: 0 0 16px; font-size: 16px; color: #2c3e50; }
.dialog-field { margin-bottom: 12px; }
.dialog-field label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.dialog-field input, .dialog-field select {
  width: 100%; padding: 6px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 13px; outline: none;
}
.dialog-field input:focus, .dialog-field select:focus { border-color: #2a4d6a; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakePipelineDetail.vue
git commit -m "feat(pipeline): add pipeline detail page with versions and run history"
```

---

## Task 10: 运行监控页 — DatalakePipelineRun.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakePipelineRun.vue`
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineRunStats.vue`

- [ ] **Step 1: 编写 PipelineRunStats.vue — 顶部统计条**

```vue
<template>
  <div class="run-stats">
    <div class="stat-item">
      <span class="stat-label">状态</span>
      <span class="stat-value" :class="'status-' + run.status.toLowerCase()">{{ statusLabel }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">总耗时</span>
      <span class="stat-value">{{ totalDuration }}</span>
    </div>
    <div class="stat-item">
      <span class="stat-label">步骤</span>
      <span class="stat-value">{{ completedSteps }} / {{ totalSteps }}</span>
    </div>
    <div class="stat-item" v-if="retentionRate">
      <span class="stat-label">留存率</span>
      <span class="stat-value">{{ retentionRate }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PipelineRun, PipelineStepRun } from '@/api/pipeline'
import { parseMetrics } from '@/api/pipeline'

const props = defineProps<{
  run: PipelineRun
  steps: PipelineStepRun[]
}>()

const statusLabel = computed(() => {
  const m: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '等待审核',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return m[props.run.status] || props.run.status
})

const totalDuration = computed(() => {
  if (!props.run.startedAt) return '—'
  const start = new Date(props.run.startedAt).getTime()
  const end = props.run.finishedAt ? new Date(props.run.finishedAt).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
})

const totalSteps = computed(() => props.steps.length)
const completedSteps = computed(() => props.steps.filter(s => s.status === 'SUCCEEDED' || s.status === 'SKIPPED').length)

const retentionRate = computed(() => {
  // 取最后一个有 retention 的 step
  for (let i = props.steps.length - 1; i >= 0; i--) {
    const m = parseMetrics(props.steps[i].metrics)
    if (m.retention) return m.retention
  }
  return ''
})
</script>

<style scoped>
.run-stats {
  display: flex; gap: 24px; padding: 12px 16px;
  background: #fff; border-bottom: 1px solid #e8e4df;
}
.stat-item { display: flex; flex-direction: column; }
.stat-label { font-size: 10px; color: #94a3b8; text-transform: uppercase; }
.stat-value { font-size: 14px; font-weight: 600; color: #2c3e50; margin-top: 2px; }
.status-running { color: #3b82f6; }
.status-succeeded { color: #22c55e; }
.status-failed { color: #ef4444; }
.status-paused { color: #eab308; }
.status-pending { color: #94a3b8; }
.status-cancelled { color: #94a3b8; }
</style>
```

- [ ] **Step 2: 编写 DatalakePipelineRun.vue**

```vue
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
import { ref, computed, onMounted, onBeforeUnmount, markRaw, watch } from 'vue'
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
import type { Node, Edge } from '@vue-flow/core'

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

function onNodeClick(_event: MouseEvent, node: Node) {
  const sr = stepRuns.value.find(s => s.stepId === node.id)
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
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakePipelineRun.vue lakeon-console/src/views/datalake/components/pipeline/PipelineRunStats.vue
git commit -m "feat(pipeline): add run monitoring page with live DAG status and polling"
```

---

## Task 11: 步骤详情面板 — PipelineRunStepDetail.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineRunStepDetail.vue`

- [ ] **Step 1: 编写 PipelineRunStepDetail.vue**

```vue
<template>
  <div class="step-detail-panel">
    <div class="panel-header">
      <span class="panel-title">{{ stepRun.stepId }}</span>
      <span class="step-status" :class="'status-' + stepRun.status.toLowerCase()">{{ statusLabel }}</span>
    </div>

    <!-- 指标 -->
    <div class="panel-section" v-if="Object.keys(metrics).length > 0">
      <div class="section-title">运行指标</div>
      <div class="metrics-grid">
        <div v-for="(val, key) in metrics" :key="key" class="metric-item">
          <span class="metric-label">{{ key }}</span>
          <span class="metric-value">{{ val }}</span>
        </div>
      </div>
    </div>

    <!-- 时间 -->
    <div class="panel-section">
      <div class="section-title">时间</div>
      <div class="time-row">
        <span class="time-label">开始</span>
        <span>{{ formatTime(stepRun.startedAt) }}</span>
      </div>
      <div class="time-row">
        <span class="time-label">结束</span>
        <span>{{ formatTime(stepRun.finishedAt) }}</span>
      </div>
      <div class="time-row">
        <span class="time-label">耗时</span>
        <span>{{ duration }}</span>
      </div>
    </div>

    <!-- 错误信息 -->
    <div class="panel-section error-section" v-if="stepRun.error">
      <div class="section-title">错误</div>
      <pre class="error-text">{{ stepRun.error }}</pre>
    </div>

    <!-- Checkpoint 预览 -->
    <div class="panel-section" v-if="stepRun.checkpointPath">
      <div class="section-title">Checkpoint</div>
      <div class="checkpoint-path">{{ stepRun.checkpointPath }}</div>
      <button class="btn btn-secondary btn-small" style="margin-top: 6px;">预览数据</button>
    </div>

    <!-- 日志 -->
    <div class="panel-section log-section">
      <div class="section-title">
        日志
        <button class="btn btn-text btn-small" @click="loadLogs" :disabled="logsLoading">
          {{ logsLoading ? '加载中...' : '刷新' }}
        </button>
      </div>
      <pre class="log-output" v-if="logs">{{ logs }}</pre>
      <div v-else class="log-empty">点击「刷新」加载日志</div>
    </div>

    <!-- 人工审核操作 -->
    <div class="panel-section" v-if="stepRun.status === 'PAUSED'">
      <div class="section-title">人工审核</div>
      <PipelineRunHumanReview
        :step-run="stepRun"
        @approve="$emit('resume', stepRun.stepId, 'approve')"
        @reject="$emit('resume', stepRun.stepId, 'reject')"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { PipelineStepRun } from '@/api/pipeline'
import { parseMetrics, getStepRunLogs } from '@/api/pipeline'
import PipelineRunHumanReview from './PipelineRunHumanReview.vue'

const props = defineProps<{
  stepRun: PipelineStepRun
  runId: string
}>()

defineEmits<{
  resume: [stepId: string, decision: 'approve' | 'reject']
}>()

const logs = ref('')
const logsLoading = ref(false)

const metrics = computed(() => parseMetrics(props.stepRun.metrics))

const statusLabel = computed(() => {
  const m: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '等待审核',
    SUCCEEDED: '已完成', FAILED: '失败', SKIPPED: '已跳过',
  }
  return m[props.stepRun.status] || props.stepRun.status
})

const duration = computed(() => {
  if (!props.stepRun.startedAt) return '—'
  const start = new Date(props.stepRun.startedAt).getTime()
  const end = props.stepRun.finishedAt ? new Date(props.stepRun.finishedAt).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  return `${Math.floor(sec / 60)}m ${sec % 60}s`
})

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await getStepRunLogs(props.runId, props.stepRun.stepId)
    logs.value = res.data.logs
  } catch (err) {
    logs.value = '加载日志失败'
  } finally {
    logsLoading.value = false
  }
}
</script>

<style scoped>
.step-detail-panel {
  width: 320px; border-left: 1px solid #e8e4df; background: #fff;
  overflow-y: auto; padding-bottom: 20px;
}
.panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px; border-bottom: 1px solid #f0ede8;
}
.panel-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.step-status { font-size: 11px; padding: 2px 8px; border-radius: 3px; }
.status-running { background: #e8f4fd; color: #3b82f6; }
.status-succeeded { background: #ecfdf5; color: #22c55e; }
.status-failed { background: #fef2f2; color: #ef4444; }
.status-paused { background: #fef9c3; color: #eab308; }
.status-pending { background: #f5f3f0; color: #94a3b8; }
.status-skipped { background: #f5f3f0; color: #94a3b8; }

.panel-section { padding: 10px 14px; border-bottom: 1px solid #f5f3f0; }
.section-title {
  font-size: 11px; font-weight: 600; color: #94a3b8; margin-bottom: 6px;
  text-transform: uppercase; display: flex; align-items: center; justify-content: space-between;
}

.metrics-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
.metric-item { display: flex; flex-direction: column; }
.metric-label { font-size: 10px; color: #999; }
.metric-value { font-size: 13px; font-weight: 600; color: #2c3e50; }

.time-row { display: flex; justify-content: space-between; font-size: 12px; padding: 2px 0; }
.time-label { color: #999; }

.error-section { background: #fef2f2; }
.error-text { font-size: 11px; color: #ef4444; white-space: pre-wrap; word-break: break-all; margin: 0; }

.checkpoint-path { font-size: 11px; color: #666; font-family: monospace; word-break: break-all; }

.log-output {
  font-size: 11px; font-family: monospace; background: #1e1e1e; color: #d4d4d4;
  padding: 8px; border-radius: 4px; max-height: 300px; overflow-y: auto;
  white-space: pre-wrap; margin: 0;
}
.log-empty { font-size: 12px; color: #ccc; text-align: center; padding: 16px 0; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/components/pipeline/PipelineRunStepDetail.vue
git commit -m "feat(pipeline): add step detail panel with metrics, logs, and checkpoint"
```

---

## Task 12: 人工审核面板 — PipelineRunHumanReview.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/components/pipeline/PipelineRunHumanReview.vue`

- [ ] **Step 1: 编写 PipelineRunHumanReview.vue**

```vue
<template>
  <div class="human-review">
    <div class="review-hint">
      此步骤需要人工审核确认后才能继续执行。
    </div>

    <!-- 数据预览区域（checkpoint 数据） -->
    <div v-if="previewItems.length > 0" class="preview-grid">
      <div
        v-for="(item, idx) in previewItems"
        :key="idx"
        class="preview-item"
        :class="{ selected: selectedItems.has(idx) }"
        @click="toggleItem(idx)"
      >
        <!-- 视频/图片缩略图 -->
        <div class="preview-thumb">
          <img v-if="item.thumbnail" :src="item.thumbnail" />
          <div v-else class="preview-placeholder">{{ item.name || `#${idx + 1}` }}</div>
        </div>
        <div class="preview-info">
          <span class="preview-name">{{ item.name || `Item ${idx + 1}` }}</span>
          <span v-if="item.meta" class="preview-meta">{{ item.meta }}</span>
        </div>
        <div class="preview-check">
          <svg v-if="selectedItems.has(idx)" viewBox="0 0 16 16" width="14" height="14" fill="#22c55e">
            <path d="M13.78 4.22a.75.75 0 0 1 0 1.06l-7.25 7.25a.75.75 0 0 1-1.06 0L2.22 9.28a.75.75 0 0 1 1.06-1.06L6 10.94l6.72-6.72a.75.75 0 0 1 1.06 0z"/>
          </svg>
        </div>
      </div>
    </div>

    <div v-else class="review-text-hint">
      暂无可预览的数据。请根据上游步骤结果决定是否通过。
    </div>

    <!-- 操作按钮 -->
    <div class="review-actions">
      <button class="btn btn-danger" @click="$emit('reject')">
        淘汰 / 拒绝
      </button>
      <button class="btn btn-primary" @click="$emit('approve')">
        通过 / 继续
      </button>
    </div>

    <div class="review-note">
      <span v-if="previewItems.length > 0">
        已选 {{ selectedItems.size }} / {{ previewItems.length }} 项。通过后将以选中项继续执行。
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { PipelineStepRun } from '@/api/pipeline'

interface PreviewItem {
  name?: string
  thumbnail?: string
  meta?: string
}

const props = defineProps<{
  stepRun: PipelineStepRun
}>()

defineEmits<{
  approve: []
  reject: []
}>()

const previewItems = ref<PreviewItem[]>([])
const selectedItems = ref(new Set<number>())

function toggleItem(idx: number) {
  if (selectedItems.value.has(idx)) {
    selectedItems.value.delete(idx)
  } else {
    selectedItems.value.add(idx)
  }
}

onMounted(() => {
  // 尝试从 checkpoint 数据中加载预览
  // 实际实现需要从 OBS checkpoint 路径加载
  // Phase 1 使用 outputRef 中的简单列表
  if (props.stepRun.outputRef) {
    try {
      const refs = JSON.parse(props.stepRun.outputRef)
      if (Array.isArray(refs)) {
        previewItems.value = refs.map((r: any, i: number) => ({
          name: r.name || `Item ${i + 1}`,
          thumbnail: r.thumbnail,
          meta: r.meta || r.path,
        }))
        // 默认全选
        previewItems.value.forEach((_, i) => selectedItems.value.add(i))
      }
    } catch { /* ignore parse errors */ }
  }
})
</script>

<style scoped>
.human-review { padding: 0; }
.review-hint {
  font-size: 12px; color: #92700c; background: #fef9ee;
  padding: 8px 10px; border-radius: 4px; margin-bottom: 10px;
}

.preview-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px;
  max-height: 240px; overflow-y: auto; margin-bottom: 10px;
}
.preview-item {
  border: 2px solid #e8e4df; border-radius: 6px; padding: 4px;
  cursor: pointer; transition: all 0.12s; position: relative;
}
.preview-item.selected { border-color: #22c55e; background: #f0fdf4; }
.preview-item:hover { border-color: #2a4d6a; }
.preview-thumb { width: 100%; aspect-ratio: 1; overflow: hidden; border-radius: 4px; }
.preview-thumb img { width: 100%; height: 100%; object-fit: cover; }
.preview-placeholder {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  background: #f5f3f0; color: #999; font-size: 11px;
}
.preview-info { padding: 2px 0; }
.preview-name { font-size: 10px; color: #2c3e50; display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.preview-meta { font-size: 9px; color: #999; }
.preview-check { position: absolute; top: 4px; right: 4px; }

.review-text-hint { font-size: 12px; color: #999; padding: 12px 0; text-align: center; }

.review-actions { display: flex; gap: 8px; margin-top: 10px; }
.review-actions .btn { flex: 1; }
.btn-danger {
  background: #fef2f2; color: #ef4444; border: 1px solid #fca5a5;
  padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px;
}
.btn-danger:hover { background: #fee2e2; }

.review-note { font-size: 11px; color: #999; margin-top: 8px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/components/pipeline/PipelineRunHumanReview.vue
git commit -m "feat(pipeline): add human review panel with preview grid and approve/reject"
```

---

## Task 13: 组件库页面 — DatalakeComponents.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakeComponents.vue`

- [ ] **Step 1: 编写 DatalakeComponents.vue**

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">组件库</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="router.push('/datalake/components/register')">注册组件</button>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filter-row">
      <select v-model="categoryFilter" class="filter-select">
        <option value="">全部类别</option>
        <option v-for="cat in categories" :key="cat" :value="cat">{{ categoryLabel(cat) }}</option>
      </select>
      <select v-model="dataTypeFilter" class="filter-select">
        <option value="">全部数据类型</option>
        <option value="TEXT">文本</option>
        <option value="VIDEO">视频</option>
        <option value="IMAGE">图片</option>
        <option value="AUDIO">音频</option>
        <option value="DOCUMENT">文档</option>
        <option value="UNIVERSAL">通用</option>
      </select>
      <input v-model="search" class="filter-search" placeholder="搜索组件..." />
    </div>

    <!-- 组件网格 -->
    <div class="comp-grid" v-if="filtered.length > 0">
      <div
        v-for="comp in filtered"
        :key="comp.id"
        class="comp-card"
        :style="{ borderLeftColor: catColor(comp.category) }"
        @click="selectComponent(comp)"
      >
        <div class="comp-card-header">
          <span class="comp-card-icon">{{ catIcon(comp.category) }}</span>
          <span class="comp-card-name">{{ comp.displayName }}</span>
          <span v-if="!comp.tenantId" class="builtin-badge">内置</span>
        </div>
        <div class="comp-card-desc">{{ comp.description || comp.name }}</div>
        <div class="comp-card-meta">
          <span class="meta-tag">{{ comp.dataType }}</span>
          <span class="meta-tag">v{{ comp.latestVersion }}</span>
          <span class="meta-tag">{{ categoryLabel(comp.category) }}</span>
        </div>
      </div>
    </div>

    <div v-if="filtered.length === 0 && !loading" class="empty-state" style="margin-top: 48px; text-align: center; color: #999;">
      暂无匹配的组件
    </div>

    <!-- 组件详情弹窗 -->
    <div v-if="selected" class="dialog-overlay" @click.self="selected = null">
      <div class="dialog comp-detail-dialog">
        <h3>{{ selected.displayName }}</h3>
        <div class="comp-detail-row"><label>名称</label><span>{{ selected.name }}</span></div>
        <div class="comp-detail-row"><label>类别</label><span>{{ categoryLabel(selected.category) }}</span></div>
        <div class="comp-detail-row"><label>数据类型</label><span>{{ selected.dataType }}</span></div>
        <div class="comp-detail-row"><label>版本</label><span>v{{ selected.latestVersion }}</span></div>
        <div class="comp-detail-row"><label>来源</label><span>{{ selected.tenantId ? '自定义' : '平台内置' }}</span></div>
        <div class="comp-detail-row" v-if="selected.description">
          <label>描述</label><span>{{ selected.description }}</span>
        </div>
        <div class="dialog-actions">
          <button class="btn btn-secondary" @click="selected = null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listComponents, type PipelineComponent, type ComponentCategory } from '@/api/pipeline'
import { categoryColors, categoryLabels } from './components/pipeline/nodeStyles'

const router = useRouter()
const loading = ref(true)
const components = ref<PipelineComponent[]>([])
const categoryFilter = ref('')
const dataTypeFilter = ref('')
const search = ref('')
const selected = ref<PipelineComponent | null>(null)

const categories: ComponentCategory[] = ['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']

function categoryLabel(cat: string): string { return categoryLabels[cat as ComponentCategory] || cat }
function catColor(cat: string): string { return categoryColors[cat as ComponentCategory]?.border || '#ccc' }
function catIcon(cat: string): string { return categoryColors[cat as ComponentCategory]?.icon || '?' }

const filtered = computed(() => {
  let list = components.value
  if (categoryFilter.value) list = list.filter(c => c.category === categoryFilter.value)
  if (dataTypeFilter.value) list = list.filter(c => c.dataType === dataTypeFilter.value)
  if (search.value.trim()) {
    const q = search.value.trim().toLowerCase()
    list = list.filter(c =>
      c.displayName.toLowerCase().includes(q) ||
      c.name.toLowerCase().includes(q) ||
      (c.description || '').toLowerCase().includes(q)
    )
  }
  return list
})

function selectComponent(comp: PipelineComponent) {
  selected.value = comp
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await listComponents()
    components.value = res.data
  } catch (err) {
    console.error('Failed to load components', err)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.filter-row { display: flex; gap: 8px; margin-bottom: 16px; }
.filter-select {
  padding: 5px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 12px; background: #fff; color: #666; cursor: pointer;
}
.filter-search {
  flex: 1; padding: 5px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 12px; outline: none;
}
.filter-search:focus { border-color: #2a4d6a; }

.comp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.comp-card {
  border: 1px solid #e8e4df; border-left: 3px solid; border-radius: 8px;
  padding: 14px; cursor: pointer; background: #fff; transition: box-shadow 0.15s;
}
.comp-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.comp-card-header { display: flex; align-items: center; gap: 6px; }
.comp-card-icon { font-size: 16px; }
.comp-card-name { font-size: 13px; font-weight: 600; color: #2c3e50; flex: 1; }
.builtin-badge { font-size: 9px; padding: 1px 6px; border-radius: 3px; background: #eef6fe; color: #1a5276; }
.comp-card-desc { font-size: 12px; color: #999; margin-top: 4px; }
.comp-card-meta { display: flex; gap: 6px; margin-top: 8px; }
.meta-tag { font-size: 10px; padding: 1px 6px; border-radius: 3px; background: #f5f3f0; color: #666; }

/* 详情弹窗 */
.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3); display: flex;
  align-items: center; justify-content: center; z-index: 100;
}
.comp-detail-dialog { width: 480px; }
.dialog { background: #fff; border-radius: 10px; padding: 24px; box-shadow: 0 8px 32px rgba(0,0,0,0.12); }
.dialog h3 { margin: 0 0 16px; font-size: 16px; color: #2c3e50; }
.comp-detail-row { display: flex; padding: 4px 0; font-size: 13px; }
.comp-detail-row label { width: 80px; color: #999; flex-shrink: 0; }
.comp-detail-row span { color: #2c3e50; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakeComponents.vue
git commit -m "feat(pipeline): add component library page with filtering and detail view"
```

---

## Task 14: 组件注册页 — DatalakeComponentRegister.vue

**Files:**
- Create: `lakeon-console/src/views/datalake/DatalakeComponentRegister.vue`

- [ ] **Step 1: 编写 DatalakeComponentRegister.vue**

```vue
<template>
  <div class="page-container">
    <div class="page-header">
      <div class="breadcrumb">
        <router-link to="/datalake/components" class="breadcrumb-link">组件库</router-link>
        <span class="breadcrumb-sep"> / </span>
        <span>注册组件</span>
      </div>
    </div>

    <div class="register-form">
      <div class="form-section">
        <h3>基本信息</h3>
        <div class="form-row">
          <label>组件名称 <span class="required">*</span></label>
          <input v-model="form.name" placeholder="video_scene_split（英文标识符）" />
          <div class="field-help">Python 模块名格式，小写字母 + 下划线</div>
        </div>
        <div class="form-row">
          <label>显示名称 <span class="required">*</span></label>
          <input v-model="form.display_name" placeholder="视频镜头切分" />
        </div>
        <div class="form-row-pair">
          <div class="form-row">
            <label>类别 <span class="required">*</span></label>
            <select v-model="form.category">
              <option value="">请选择</option>
              <option value="DATA_PREP">数据准备</option>
              <option value="EXTRACT">提取</option>
              <option value="CLEAN">清洗</option>
              <option value="FILTER">过滤</option>
              <option value="QC">质检</option>
              <option value="LABEL">标注</option>
              <option value="PUBLISH">发布</option>
            </select>
          </div>
          <div class="form-row">
            <label>数据类型 <span class="required">*</span></label>
            <select v-model="form.data_type">
              <option value="">请选择</option>
              <option value="TEXT">文本</option>
              <option value="VIDEO">视频</option>
              <option value="IMAGE">图片</option>
              <option value="AUDIO">音频</option>
              <option value="DOCUMENT">文档</option>
              <option value="UNIVERSAL">通用</option>
            </select>
          </div>
        </div>
        <div class="form-row">
          <label>描述</label>
          <textarea v-model="form.description" placeholder="组件功能描述..." rows="3"></textarea>
        </div>
      </div>

      <div class="form-section">
        <h3>执行配置</h3>
        <div class="form-row">
          <label>入口函数 <span class="required">*</span></label>
          <input v-model="form.entrypoint" placeholder="lakeon.components.video.scene_split" />
          <div class="field-help">Python 模块路径，指向 @Component 装饰的函数</div>
        </div>
        <div class="form-row-pair">
          <div class="form-row">
            <label>执行模式</label>
            <select v-model="form.execution_mode">
              <option value="FUNCTION">函数执行</option>
              <option value="HUMAN_REVIEW">人工审核</option>
            </select>
          </div>
          <div class="form-row">
            <label>GPU 要求</label>
            <label class="toggle-label">
              <input type="checkbox" v-model="form.requires_gpu" />
              <span>需要 GPU</span>
            </label>
          </div>
        </div>
        <div class="form-row" v-if="form.requires_gpu">
          <label>模型标识</label>
          <input v-model="form.requires_model" placeholder="pyscenedetect (可选)" />
        </div>
      </div>

      <div class="form-section">
        <h3>Schema 配置</h3>
        <div class="form-row">
          <label>参数 Schema (JSON)</label>
          <textarea v-model="form.params_schema" placeholder='{"threshold": {"type": "number", "default": 27, "description": "切分灵敏度"}}' rows="5" class="mono-textarea"></textarea>
          <div class="field-help">JSON Schema 格式，定义组件参数。前端 DAG 编辑器据此自动渲染表单。</div>
        </div>
        <div class="form-row">
          <label>输出分支 (逗号分隔)</label>
          <input v-model="branchesInput" placeholder="passed, needs_crop, dropped" />
          <div class="field-help">条件分支组件声明多个输出端口，留空表示单输出。</div>
        </div>
      </div>

      <div class="form-section">
        <h3>代码文件</h3>
        <div class="form-row">
          <label>上传 .py 文件</label>
          <div class="upload-area" @click="fileInput?.click()" @drop.prevent="onFileDrop" @dragover.prevent>
            <div v-if="!uploadedFile">
              <div class="upload-icon">+</div>
              <div class="upload-text">点击或拖拽上传 Python 文件</div>
            </div>
            <div v-else class="uploaded-file">
              <span>{{ uploadedFile.name }}</span>
              <span class="file-size">{{ (uploadedFile.size / 1024).toFixed(1) }} KB</span>
            </div>
          </div>
          <input ref="fileInput" type="file" accept=".py" style="display: none;" @change="onFileSelect" />
          <div class="field-help">组件代码文件将上传到 OBS 存储。</div>
        </div>
      </div>

      <!-- 提交 -->
      <div class="form-actions">
        <button class="btn btn-secondary" @click="router.push('/datalake/components')">取消</button>
        <button class="btn btn-primary" @click="handleSubmit" :disabled="submitting || !isValid">
          {{ submitting ? '注册中...' : '注册组件' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { registerComponent, type RegisterComponentRequest, type ComponentCategory, type ComponentDataType } from '@/api/pipeline'

const router = useRouter()
const submitting = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadedFile = ref<File | null>(null)
const branchesInput = ref('')

const form = ref<{
  name: string
  display_name: string
  category: ComponentCategory | ''
  data_type: ComponentDataType | ''
  description: string
  entrypoint: string
  execution_mode: 'FUNCTION' | 'HUMAN_REVIEW'
  requires_gpu: boolean
  requires_model: string
  params_schema: string
}>({
  name: '',
  display_name: '',
  category: '',
  data_type: '',
  description: '',
  entrypoint: '',
  execution_mode: 'FUNCTION',
  requires_gpu: false,
  requires_model: '',
  params_schema: '',
})

const isValid = computed(() =>
  form.value.name &&
  form.value.display_name &&
  form.value.category &&
  form.value.data_type &&
  form.value.entrypoint
)

function onFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files?.[0]) uploadedFile.value = input.files[0]
}

function onFileDrop(event: DragEvent) {
  const file = event.dataTransfer?.files[0]
  if (file && file.name.endsWith('.py')) uploadedFile.value = file
}

async function handleSubmit() {
  if (!isValid.value) return
  submitting.value = true

  try {
    const branches = branchesInput.value
      ? branchesInput.value.split(',').map(s => s.trim()).filter(Boolean)
      : undefined

    const body: RegisterComponentRequest = {
      name: form.value.name,
      display_name: form.value.display_name,
      category: form.value.category as ComponentCategory,
      data_type: form.value.data_type as ComponentDataType,
      description: form.value.description || undefined,
      entrypoint: form.value.entrypoint,
      execution_mode: form.value.execution_mode,
      requires_gpu: form.value.requires_gpu,
      requires_model: form.value.requires_model || undefined,
      params_schema: form.value.params_schema || undefined,
      output_branches: branches,
    }

    await registerComponent(body)
    // TODO: 上传 .py 文件到 OBS（需要 OBS STS 接口）
    router.push('/datalake/components')
  } catch (err) {
    console.error('Failed to register component', err)
    alert('注册失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.register-form { max-width: 640px; }
.form-section { margin-bottom: 24px; }
.form-section h3 { font-size: 14px; font-weight: 600; color: #2c3e50; margin: 0 0 12px; padding-bottom: 6px; border-bottom: 1px solid #f0ede8; }

.form-row { margin-bottom: 12px; }
.form-row label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.required { color: #ef4444; }
.form-row input, .form-row select, .form-row textarea {
  width: 100%; padding: 6px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 13px; outline: none; background: #fff;
}
.form-row input:focus, .form-row select:focus, .form-row textarea:focus { border-color: #2a4d6a; }
.mono-textarea { font-family: monospace; font-size: 12px; }
.field-help { font-size: 11px; color: #bbb; margin-top: 3px; }

.form-row-pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.toggle-label {
  display: flex; align-items: center; gap: 6px; font-size: 13px; color: #2c3e50;
  cursor: pointer; margin-top: 4px;
}

.upload-area {
  border: 2px dashed #e8e4df; border-radius: 8px; padding: 24px;
  text-align: center; cursor: pointer; transition: border-color 0.12s;
}
.upload-area:hover { border-color: #2a4d6a; }
.upload-icon { font-size: 24px; color: #ccc; }
.upload-text { font-size: 12px; color: #999; margin-top: 4px; }
.uploaded-file { display: flex; align-items: center; justify-content: center; gap: 8px; }
.uploaded-file span { font-size: 13px; color: #2c3e50; }
.file-size { color: #999; font-size: 11px; }

.form-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 16px; border-top: 1px solid #e8e4df; }

.breadcrumb { font-size: 13px; color: #999; }
.breadcrumb-link { color: #2a4d6a; text-decoration: none; }
.breadcrumb-link:hover { text-decoration: underline; }
.breadcrumb-sep { margin: 0 4px; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/datalake/DatalakeComponentRegister.vue
git commit -m "feat(pipeline): add component registration page with form and file upload"
```

---

## Task 15: 最终验证 + 完整 Commit

- [ ] **Step 1: TypeScript 类型检查**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

修复所有类型错误。常见问题：
- Vue Flow 类型需要 `@vue-flow/core` 的准确版本匹配
- `markRaw()` 包裹组件避免 reactive 警告
- EdgeProps 需要显式泛型参数

- [ ] **Step 2: 开发服务器验证**

```bash
cd lakeon-console && npm run dev
```

在浏览器中访问：
1. `/datalake/pipelines` — 确认列表页渲染
2. `/datalake/pipelines/new` — 确认 DAG 画布加载，Vue Flow 正常
3. `/datalake/components` — 确认组件库页渲染

- [ ] **Step 3: 完整文件列表确认**

确保以下文件全部创建/修改：

新建文件：
- `lakeon-console/src/api/pipeline.ts`
- `lakeon-console/src/views/datalake/DatalakePipelines.vue`
- `lakeon-console/src/views/datalake/DatalakePipelineEditor.vue`
- `lakeon-console/src/views/datalake/DatalakePipelineDetail.vue`
- `lakeon-console/src/views/datalake/DatalakePipelineRun.vue`
- `lakeon-console/src/views/datalake/DatalakeComponents.vue`
- `lakeon-console/src/views/datalake/DatalakeComponentRegister.vue`
- `lakeon-console/src/views/datalake/components/pipeline/nodeStyles.ts`
- `lakeon-console/src/views/datalake/components/pipeline/dagUtils.ts`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeBase.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeFanOut.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeMerge.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineNodeHumanReview.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineCustomEdge.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineToolbar.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineComponentPanel.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelinePropertyPanel.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineYamlEditor.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineRunStats.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineRunStepDetail.vue`
- `lakeon-console/src/views/datalake/components/pipeline/PipelineRunHumanReview.vue`

修改文件：
- `lakeon-console/package.json` (新增 @vue-flow/*, @codemirror/lang-yaml, js-yaml)
- `lakeon-console/src/router/index.ts` (新增 7 条路由)

- [ ] **Step 4: 最终 Commit（如果之前未逐步 commit）**

```bash
git add lakeon-console/
git commit -m "feat(pipeline): complete Plan 3 — DAG editor, run monitor, and component library frontend"
```

Now let me create the file and commit.

<function_calls>
<invoke name="Bash">
<parameter name="command">mkdir -p /Users/jacky/code/lakeon/docs/superpowers/plans