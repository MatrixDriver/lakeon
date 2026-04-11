<template>
  <div class="run-preview">
    <div class="preview-header">
      <h3 class="preview-title">运行预览</h3>
      <div class="preview-meta">
        <div class="meta-row" v-if="datasetName">
          <span class="meta-label">输入数据集</span>
          <span class="meta-value">{{ datasetName }}</span>
        </div>
        <div class="meta-row">
          <span class="meta-label">Pipeline</span>
          <span class="meta-value">{{ dagDef.name || '—' }}</span>
        </div>
        <div class="meta-row">
          <span class="meta-label">总步骤</span>
          <span class="meta-value">{{ steps.length }}</span>
        </div>
      </div>
    </div>

    <div class="steps-list">
      <template v-for="(step, idx) in steps" :key="step.id">
        <!-- 箭头连接 -->
        <div v-if="idx > 0" class="step-connector">
          <svg width="16" height="24" viewBox="0 0 16 24">
            <line x1="8" y1="0" x2="8" y2="18" stroke="#d1ccc4" stroke-width="1.5" />
            <polygon points="4,16 8,22 12,16" fill="#d1ccc4" />
          </svg>
        </div>

        <!-- 步骤卡片 -->
        <div
          class="step-card"
          :style="{ borderLeftColor: stepColor(step).border }"
        >
          <div class="step-card-header">
            <span class="step-number">Step {{ idx + 1 }}</span>
            <span class="step-name">{{ stepDisplayName(step) }}</span>
            <span
              v-if="step.execution_mode === 'HUMAN_REVIEW'"
              class="step-badge badge-review"
            >需人工审核</span>
            <span
              v-if="step.checkpoint"
              class="step-badge badge-checkpoint"
            >checkpoint</span>
          </div>

          <div class="step-card-body">
            <div class="step-info-row" v-if="step.component">
              <span class="info-label">组件</span>
              <span class="info-value mono">{{ step.component }} v{{ resolveVersion(step) }}</span>
            </div>
            <div class="step-info-row" v-if="step.type && !step.component">
              <span class="info-label">类型</span>
              <span class="info-value mono">{{ step.type }}</span>
            </div>
            <div class="step-info-row">
              <span class="info-label">引擎</span>
              <span class="info-value">{{ engineLabel(step) }}</span>
            </div>
            <div class="step-info-row" v-if="step.params && Object.keys(step.params).length > 0">
              <span class="info-label">参数</span>
              <span class="info-value mono params-value">{{ formatParams(step.params) }}</span>
            </div>
            <div class="step-info-row" v-if="stepBranches(step).length > 0">
              <span class="info-label">输出分支</span>
              <span class="info-value">
                <span v-for="b in stepBranches(step)" :key="b" class="branch-tag">{{ b }}</span>
              </span>
            </div>
            <div class="step-info-row" v-if="step.condition">
              <span class="info-label">条件</span>
              <span class="info-value mono">{{ step.condition }}</span>
            </div>
          </div>
        </div>
      </template>
    </div>

    <div v-if="steps.length === 0" class="empty-hint">
      DAG 中没有步骤
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { PipelineComponent, PipelineComponentVersion } from '@/api/pipeline'
import { parseDagYaml, type DagStep } from './dagUtils'
import { categoryColors } from './nodeStyles'

const props = defineProps<{
  dagYaml: string
  components: PipelineComponent[]
  componentVersions: Map<string, PipelineComponentVersion>
  datasetName?: string
}>()

const dagDef = computed(() => parseDagYaml(props.dagYaml))

/** Topologically sorted steps (same logic as dagToFlow) */
const steps = computed(() => {
  const raw = dagDef.value.steps
  if (raw.length === 0) return []

  // Build dependency map
  const deps = new Map<string, string[]>()
  for (const step of raw) {
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

  // Topological sort
  const sorted: DagStep[] = []
  const assigned = new Set<string>()
  const remaining = new Map(raw.map(s => [s.id, s]))

  while (remaining.size > 0) {
    const batch: DagStep[] = []
    for (const [id, step] of remaining) {
      const d = deps.get(id) || []
      if (d.every(dep => assigned.has(dep))) batch.push(step)
    }
    if (batch.length === 0) {
      // Cycle fallback
      batch.push(...remaining.values())
    }
    for (const s of batch) {
      sorted.push(s)
      assigned.add(s.id)
      remaining.delete(s.id)
    }
  }
  return sorted
})

/** Component lookup by name */
const compMap = computed(() => {
  const m = new Map<string, PipelineComponent>()
  for (const c of props.components) m.set(c.name, c)
  return m
})

function stepDisplayName(step: DagStep): string {
  if (step.component) {
    const comp = compMap.value.get(step.component)
    if (comp) return comp.display_name
  }
  return step.type || step.id
}

function stepColor(step: DagStep): { bg: string; border: string; text: string } {
  if (step.component) {
    const comp = compMap.value.get(step.component)
    if (comp) return categoryColors[comp.category] || categoryColors.DATA_PREP
  }
  return categoryColors.DATA_PREP
}

function resolveVersion(step: DagStep): number {
  if (step.component_version) return step.component_version
  const comp = compMap.value.get(step.component || '')
  if (comp) return comp.latest_version
  return 1
}

function engineLabel(step: DagStep): string {
  if (step.execution_mode === 'HUMAN_REVIEW') return '人工审核'
  // Check componentVersion for requires_gpu as proxy for Ray
  if (step.component) {
    const cv = props.componentVersions.get(step.component)
    if (cv?.requires_gpu) return 'Ray 分布式 (GPU)'
    if (cv?.requires_model) return 'Ray 分布式'
  }
  return 'Python 单机'
}

function formatParams(params: Record<string, any>): string {
  return Object.entries(params)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`)
    .join(', ')
}

function stepBranches(step: DagStep): string[] {
  return step.output_branches || []
}
</script>

<style scoped>
.run-preview {
  max-height: 60vh;
  overflow-y: auto;
  padding-right: 4px;
}

.preview-header {
  margin-bottom: 16px;
}

.preview-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: #2c3e50;
}

.preview-meta {
  background: #faf8f5;
  border-radius: 6px;
  padding: 10px 12px;
}

.meta-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  padding: 2px 0;
}

.meta-label {
  color: #999;
}

.meta-value {
  color: #2c3e50;
  font-weight: 500;
}

/* Steps */
.steps-list {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.step-connector {
  display: flex;
  justify-content: center;
  height: 24px;
}

.step-card {
  width: 100%;
  border: 1px solid #e8e4df;
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
  transition: border-color 0.15s;
}

.step-card:hover {
  border-color: #d0c8bc;
}

.step-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #fcfbf9;
  border-bottom: 1px solid #f0ede8;
}

.step-number {
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
  text-transform: uppercase;
  white-space: nowrap;
}

.step-name {
  font-size: 13px;
  font-weight: 600;
  color: #2c3e50;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 3px;
  white-space: nowrap;
}

.badge-review {
  background: color-mix(in oklch, var(--c-accent) 10%, #fff);
  color: #9a5b25;
  border: 1px solid #e9d5ff;
}

.badge-checkpoint {
  background: #eef6fe;
  color: #2a4d6a;
  border: 1px solid #bfdbfe;
}

.step-card-body {
  padding: 8px 12px;
}

.step-info-row {
  display: flex;
  gap: 8px;
  font-size: 12px;
  padding: 2px 0;
}

.step-info-row .info-label {
  color: #999;
  min-width: 56px;
  flex-shrink: 0;
}

.step-info-row .info-value {
  color: #555;
  word-break: break-word;
}

.step-info-row .info-value.mono {
  font-family: 'SF Mono', 'Menlo', 'Monaco', monospace;
  font-size: 11px;
}

.params-value {
  line-height: 1.5;
}

.branch-tag {
  display: inline-block;
  font-size: 10px;
  padding: 1px 6px;
  margin-right: 4px;
  border-radius: 3px;
  background: #f5f3f0;
  color: #666;
}

.empty-hint {
  text-align: center;
  color: #ccc;
  padding: 24px 0;
  font-size: 13px;
}
</style>
