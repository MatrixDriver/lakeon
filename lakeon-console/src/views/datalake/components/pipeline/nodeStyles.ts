// nodeStyles.ts — 节点分类颜色和样式常量

import type { ComponentCategory, StepRunStatus } from '@/api/pipeline'

/** SVG icon paths (24x24 viewBox, stroke-based) */
export const categoryIcons: Record<ComponentCategory, string> = {
  DATA_PREP: 'M12 3v18M3 12h18M7.5 7.5L12 3l4.5 4.5M7.5 16.5L12 21l4.5-4.5',  // 十字箭头 — 规整适配
  EXTRACT:   'M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 0 2-2h3M3 16h3a2 2 0 0 0 2 2v3',  // 切分框
  CLEAN:     'M4 7h16M4 12h16M4 17h10',  // 文本行 — 清洗
  FILTER:    'M22 3H2l8 9.46V19l4 2v-8.54L22 3',  // 漏斗
  QC:        'M9 11l3 3L22 4M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11',  // 勾选框
  LABEL:     'M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82zM7 7h.01',  // 标签
  PUBLISH:   'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12',  // 上传箭头
}

/** 组件分类 → 颜色映射 */
export const categoryColors: Record<ComponentCategory, { bg: string; border: string; text: string }> = {
  DATA_PREP: { bg: '#fef9ee', border: '#f0c674', text: '#92700c' },
  EXTRACT:   { bg: '#eef6fe', border: '#6ca6e0', text: '#1a5276' },
  CLEAN:     { bg: '#eefbf4', border: '#52c07e', text: '#1a6b3c' },
  FILTER:    { bg: '#fff5f0', border: '#e8825a', text: '#8b3a0e' },
  QC:        { bg: '#f5eeff', border: '#9b7dd4', text: '#4a2d7a' },
  LABEL:     { bg: '#eef5fe', border: '#5b9bd5', text: '#1a3d6b' },
  PUBLISH:   { bg: '#f0faf5', border: '#3aa76d', text: '#145a32' },
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
