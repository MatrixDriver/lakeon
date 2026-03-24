import api from './client'

export interface MemoryBase {
  id: string
  tenant_id: string
  name: string
  description: string | null
  type: 'BUILTIN' | 'MEM0' | 'HINDSIGHT' | 'CUSTOM'
  status: string
  database_id: string | null
  memory_count: number
  trait_count: number
  embedding_model: string | null
  error: string | null
  created_at: string
  updated_at: string
}

export function listMemoryBases() {
  return api.get<MemoryBase[]>('/memory/bases')
}

export function getMemoryBase(id: string) {
  return api.get<MemoryBase>(`/memory/bases/${id}`)
}

export function createMemoryBase(name: string, description?: string, options?: {
  type?: MemoryBase['type']
  embedding_model?: string
}) {
  return api.post<MemoryBase>('/memory/bases', { name, description, ...options })
}

export function deleteMemoryBase(id: string) {
  return api.delete(`/memory/bases/${id}`)
}

export interface MemoryItem {
  id: number
  content: string
  memory_type: 'fact' | 'episode' | 'procedural'
  importance: number
  access_count: number
  metadata: Record<string, any>
  event_time: string | null
  created_at: string
}

export interface MemoryStats {
  total: number
  by_type: Record<string, number>
  trait_count: number
}

export function getMemoryStats(memId: string) {
  return api.get<MemoryStats>(`/memory/bases/${memId}/stats`)
}

export function listMemories(memId: string, options?: {
  memory_type?: string
  offset?: number
  limit?: number
}) {
  return api.get<{ memories: MemoryItem[]; total: number }>(`/memory/bases/${memId}/memories`, { params: options })
}

export function deleteMemory(memId: string, memoryId: number) {
  return api.delete(`/memory/bases/${memId}/memories/${memoryId}`)
}

export function recallMemories(memId: string, query: string, topK = 10) {
  return api.post<{ memories: MemoryItem[] }>(`/memory/bases/${memId}/recall`, { query, top_k: topK })
}

export interface Trait {
  id: number
  content: string
  trait_stage: 'trend' | 'candidate' | 'emerging' | 'established' | 'core'
  trait_subtype: string | null
  confidence: number
  reinforcement_count: number
  contradiction_count: number
  context: string | null
  created_at: string
}

export function listTraits(memId: string) {
  return api.get<Trait[]>(`/memory/bases/${memId}/traits`)
}
