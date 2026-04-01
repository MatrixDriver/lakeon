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
