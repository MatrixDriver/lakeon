export const MEMORY_TYPES = ['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] as const
export type MemoryType = typeof MEMORY_TYPES[number]

// Colors from existing MemoryBaseDetail.vue — DO NOT change to preserve visual consistency
export const MEMORY_TYPE_COLORS: Record<string, { bg: string; text: string }> = {
  fact:       { bg: '#e6f7ff', text: '#1890ff' },
  episode:    { bg: '#f9f0ff', text: '#722ed1' },
  procedural: { bg: '#fff7e6', text: '#d48806' },
  decision:   { bg: '#e6fffb', text: '#13c2c2' },
  rejection:  { bg: '#fff1f0', text: '#f5222d' },
  convention: { bg: '#f6ffed', text: '#52c41a' },
}

export const MEMORY_TYPE_LABELS: Record<string, string> = {
  fact: '事实',
  episode: '情景',
  procedural: '流程',
  decision: '决策',
  rejection: '排除',
  convention: '约定',
}

export const TRAIT_STAGE_ORDER = ['core', 'established', 'emerging'] as const
export const TRAIT_EARLIER_STAGES = ['trend', 'candidate'] as const

export const TRAIT_STAGE_COLORS: Record<string, { bg: string; text: string }> = {
  core:        { bg: '#fffbe6', text: '#d48806' },
  established: { bg: '#f6ffed', text: '#389e0d' },
  emerging:    { bg: '#e6f7ff', text: '#1890ff' },
  trend:       { bg: '#f5f5f5', text: '#8c8c8c' },
  candidate:   { bg: '#f5f5f5', text: '#8c8c8c' },
}

export const TRAIT_STAGE_LABELS: Record<string, string> = {
  core: '核心',
  established: '稳定',
  emerging: '萌芽',
  trend: '趋势',
  candidate: '候选',
}
