// dagUtils.ts — DAG YAML ↔ Vue Flow nodes/edges 双向转换

import type { Node, Edge } from '@vue-flow/core'
import yaml from 'js-yaml'
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
 * 解析 DAG YAML 文本为 DagDefinition。
 * 优先使用 js-yaml，fallback 到 JSON.parse。
 */
export function parseDagYaml(yamlText: string): DagDefinition {
  try {
    const parsed = yaml.load(yamlText) as DagDefinition
    if (parsed && Array.isArray(parsed.steps)) return parsed
    return { steps: [] }
  } catch {
    // Fallback: try JSON parse (backend may return JSON format)
    try {
      return JSON.parse(yamlText)
    } catch {
      console.warn('Failed to parse DAG YAML')
      return { steps: [] }
    }
  }
}

/**
 * 将 DagDefinition 序列化为 YAML 文本。
 */
export function serializeDagYaml(dag: DagDefinition): string {
  return yaml.dump(dag, { indent: 2, lineWidth: 120, noRefs: true })
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
