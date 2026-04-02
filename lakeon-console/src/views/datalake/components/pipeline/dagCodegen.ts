// dagCodegen.ts — DAG → Python 脚本编译器

import type { DagStep, DagDefinition } from './dagUtils'
import type { PipelineComponent, PipelineComponentVersion } from '@/api/pipeline'

export interface CodegenOptions {
  pipelineName: string
  dataType: string
  steps: DagStep[]
  componentMap: Map<string, PipelineComponent>
  componentVersionMap: Map<string, PipelineComponentVersion>
  executionEngine: 'python' | 'ray'
  inputPath?: string
}

/** 拓扑排序步骤（与 PipelineRunPreview 相同逻辑） */
function topoSort(steps: DagStep[]): DagStep[] {
  if (steps.length === 0) return []

  const deps = new Map<string, string[]>()
  for (const step of steps) {
    const d: string[] = [...(step.depends_on || [])]
    if (step.inputs) {
      for (const ref of Object.values(step.inputs)) {
        if (typeof ref === 'string' && ref.startsWith('$input')) continue
        const upstream = (ref as string).split('.')[0]
        if (upstream && !d.includes(upstream)) d.push(upstream)
      }
    }
    deps.set(step.id, d)
  }

  const sorted: DagStep[] = []
  const assigned = new Set<string>()
  const remaining = new Map(steps.map(s => [s.id, s]))

  while (remaining.size > 0) {
    const batch: DagStep[] = []
    for (const [id, step] of remaining) {
      const d = deps.get(id) || []
      if (d.every(dep => assigned.has(dep))) batch.push(step)
    }
    if (batch.length === 0) batch.push(...remaining.values())
    for (const s of batch) {
      sorted.push(s)
      assigned.add(s.id)
      remaining.delete(s.id)
    }
  }
  return sorted
}

/** 将 component name 转为 Python 变量安全名 */
function toVarName(id: string): string {
  return id.replace(/[^a-zA-Z0-9_]/g, '_')
}

/** 格式化 params 为 Python dict 字面量 */
function formatPythonValue(value: unknown, indent: number): string {
  const pad = ' '.repeat(indent)
  if (value === null || value === undefined) return 'None'
  if (typeof value === 'boolean') return value ? 'True' : 'False'
  if (typeof value === 'number') return String(value)
  if (typeof value === 'string') return `'${value.replace(/'/g, "\\'")}'`
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]'
    const items = value.map(v => formatPythonValue(v, indent + 4))
    if (items.join(', ').length < 60) return `[${items.join(', ')}]`
    return `[\n${items.map(i => pad + '    ' + i + ',').join('\n')}\n${pad}]`
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
    if (entries.length === 0) return '{}'
    const lines = entries.map(([k, v]) => `${pad}    '${k}': ${formatPythonValue(v, indent + 4)},`)
    return `{\n${lines.join('\n')}\n${pad}}`
  }
  return String(value)
}

/** 生成引擎描述 */
function engineLabel(step: DagStep, opts: CodegenOptions): string {
  if (step.execution_mode === 'HUMAN_REVIEW') return '人工审核'
  if (opts.executionEngine === 'ray') {
    if (step.component) {
      const cv = opts.componentVersionMap.get(step.component)
      if (cv?.requires_gpu) return 'Ray 分布式 (GPU)'
    }
    return 'Ray 分布式'
  }
  return 'Python 单机'
}

/** 获取步骤的展示名 */
function stepDisplayName(step: DagStep, opts: CodegenOptions): string {
  if (step.component) {
    const comp = opts.componentMap.get(step.component)
    if (comp) return comp.display_name
  }
  return step.type || step.id
}

/** 获取步骤的 entrypoint */
function stepEntrypoint(step: DagStep, opts: CodegenOptions): string | null {
  if (!step.component) return null
  const cv = opts.componentVersionMap.get(step.component)
  return cv?.entrypoint || null
}

/** 解析 entrypoint 为 import path 和函数名 */
function parseEntrypoint(entrypoint: string): { modulePath: string; funcName: string } | null {
  // 格式: components.text.text_dedup:text_dedup 或 components.text.text_dedup.text_dedup
  const colonIdx = entrypoint.lastIndexOf(':')
  if (colonIdx > 0) {
    return {
      modulePath: entrypoint.substring(0, colonIdx),
      funcName: entrypoint.substring(colonIdx + 1),
    }
  }
  const dotIdx = entrypoint.lastIndexOf('.')
  if (dotIdx > 0) {
    return {
      modulePath: entrypoint.substring(0, dotIdx),
      funcName: entrypoint.substring(dotIdx + 1),
    }
  }
  return null
}

/** 获取步骤输入引用（用于链接上一步输出） */
function resolveInputRef(step: DagStep): string | null {
  if (!step.inputs) return null
  const refs = Object.values(step.inputs)
  for (const ref of refs) {
    if (typeof ref === 'string') {
      if (ref.startsWith('$input')) return 'INPUT_PATH'
      // 引用上游步骤输出，如 "text_dedup" 或 "text_dedup.output"
      const upstream = ref.split('.')[0]
      if (upstream) return `result_${toVarName(upstream)}`
    }
  }
  return null
}

// ═══════════════════════════════════════════════════════
// 主生成函数
// ═══════════════════════════════════════════════════════

export function generatePythonScript(dag: DagDefinition, opts: CodegenOptions): string {
  const steps = topoSort(opts.steps)
  const totalSteps = steps.length
  const lines: string[] = []

  // ── 文件头 ──
  lines.push('#!/usr/bin/env python3')
  lines.push('"""')
  lines.push(`Pipeline: ${opts.pipelineName}`)
  if (dag.description) lines.push(`${dag.description}`)
  lines.push(`Generated by DBay Data Pipeline Engine`)
  lines.push(`Engine: ${opts.executionEngine === 'ray' ? 'Ray (分布式执行)' : 'Python (单机执行)'}`)
  lines.push('"""')
  lines.push('')

  // ── Imports ──
  lines.push('import os')
  lines.push('import json')
  lines.push('import logging')
  lines.push('')

  if (opts.executionEngine === 'ray') {
    lines.push('import ray')
    lines.push('')
  }

  lines.push("logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')")
  lines.push("logger = logging.getLogger(__name__)")
  lines.push('')

  // ── Ray init ──
  if (opts.executionEngine === 'ray') {
    lines.push('# ── Ray 初始化 ──')
    lines.push('ray.init()')
    lines.push('')
  }

  // ── 输入数据集 ──
  const inputPath = opts.inputPath || 'obs://your-bucket/path/to/data'
  lines.push('# ── 输入数据集 ' + '─'.repeat(52))
  lines.push(`INPUT_PATH = os.environ.get('INPUT_PATH', '${inputPath}')`)
  lines.push('')

  // ── 每个步骤 ──
  for (let idx = 0; idx < steps.length; idx++) {
    const step = steps[idx]!
    const stepNum = idx + 1
    const displayName = stepDisplayName(step, opts)
    const compId = step.component || step.type || step.id
    const engine = engineLabel(step, opts)
    const varName = toVarName(step.id)

    // 分隔线
    lines.push('# ' + '═'.repeat(58))
    lines.push(`# Step ${stepNum}: ${displayName} (${compId})`)
    lines.push('# ' + '─'.repeat(58))

    // 参数描述
    if (step.params && Object.keys(step.params).length > 0) {
      const paramDesc = Object.entries(step.params)
        .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`)
        .join(', ')
      lines.push(`# 参数: ${paramDesc}`)
    }
    lines.push(`# 引擎: ${engine}`)
    lines.push('# ' + '═'.repeat(58))

    lines.push(`logger.info('Step ${stepNum}/${totalSteps}: ${displayName}...')`)
    lines.push('')

    // Import 组件
    const entrypoint = stepEntrypoint(step, opts)
    if (entrypoint) {
      const parsed = parseEntrypoint(entrypoint)
      if (parsed) {
        lines.push(`from ${parsed.modulePath} import ${parsed.funcName}`)
      }
    }

    // ComponentContext import (first step only)
    if (idx === 0) {
      lines.push('from lakeon_orchestrator.component.context import ComponentContext')
    }
    lines.push('')

    // 构建 context
    const inputRef = resolveInputRef(step)
    const inputDict: Record<string, string> = {}
    if (step.inputs) {
      for (const [key, ref] of Object.entries(step.inputs)) {
        if (typeof ref === 'string') {
          if (ref.startsWith('$input')) {
            inputDict[key] = 'INPUT_PATH'
          } else {
            const upstream = ref.split('.')[0]
            inputDict[key] = `result_${toVarName(upstream!)}`
          }
        }
      }
    } else if (inputRef) {
      inputDict['data'] = inputRef
    } else if (idx === 0) {
      inputDict['data'] = 'INPUT_PATH'
    } else {
      // 链接上一步
      const prevStep = steps[idx - 1]!
      inputDict['data'] = `result_${toVarName(prevStep.id)}`
    }

    // 构建输入表达式
    const inputEntries = Object.entries(inputDict)
    const inputExpr = inputEntries.length > 0
      ? `{${inputEntries.map(([k, v]) => `'${k}': ${v}`).join(', ')}}`
      : '{}'

    lines.push(`ctx_${varName} = ComponentContext(`)
    lines.push(`    input=${inputExpr},`)
    if (step.params && Object.keys(step.params).length > 0) {
      lines.push(`    params=${formatPythonValue(step.params, 4)},`)
    } else {
      lines.push(`    params={},`)
    }
    lines.push(')')

    // 调用函数
    const funcName = entrypoint ? (parseEntrypoint(entrypoint)?.funcName || step.id) : step.id
    if (opts.executionEngine === 'ray' && step.execution_mode !== 'HUMAN_REVIEW') {
      lines.push(`result_${varName} = ray.get(ray.remote(${funcName}).remote(ctx_${varName}))`)
    } else {
      lines.push(`result_${varName} = ${funcName}(ctx_${varName})`)
    }
    lines.push(`logger.info(f'  完成: {ctx_${varName}.reported_metrics}')`)
    lines.push('')
  }

  // ── 完成 ──
  lines.push('# ── 完成 ' + '─'.repeat(54))
  lines.push("logger.info('Pipeline 完成!')")

  if (opts.executionEngine === 'ray') {
    lines.push('')
    lines.push('ray.shutdown()')
  }

  lines.push('')
  return lines.join('\n')
}
