export const MEMORY_TYPES = ['fact', 'episode', 'procedural', 'decision', 'rejection', 'convention'] as const
export type MemoryType = typeof MEMORY_TYPES[number]

// Warm-tone palette that matches the console's harbor theme
export const MEMORY_TYPE_COLORS: Record<string, { bg: string; text: string }> = {
  fact:       { bg: '#f0f0ea', text: '#6b7064' },
  episode:    { bg: '#f5f0eb', text: '#8b6f52' },
  procedural: { bg: '#faf5ed', text: '#9a7b4f' },
  decision:   { bg: '#eef2ed', text: '#5a7a5a' },
  rejection:  { bg: '#f5efee', text: '#9a6b6b' },
  convention: { bg: '#eff0f5', text: '#6b6e82' },
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
