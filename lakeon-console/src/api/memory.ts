import api from './client'

export interface MemoryBase {
  id: string
  tenant_id: string
  name: string
  description: string | null
  type: 'BUILTIN' | 'MEM0' | 'HINDSIGHT' | 'CUSTOM'
  scene: 'DEVELOPER_TOOL' | 'CHAT_ASSISTANT'
  status: string
  database_id: string | null
  memory_count: number
  trait_count: number
  embedding_model: string | null
  one_llm_mode: boolean
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
  scene?: MemoryBase['scene']
  embedding_model?: string
  one_llm_mode?: boolean
}) {
  return api.post<MemoryBase>('/memory/bases', { name, description, ...options })
}

export function deleteMemoryBase(id: string) {
  return api.delete(`/memory/bases/${id}`)
}

export interface MemoryItem {
  id: number
  content: string
  memory_type: 'fact' | 'episode' | 'procedural' | 'decision' | 'rejection' | 'convention'
  importance: number
  access_count: number
  last_accessed_at: string | null
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

export function recallMemories(memId: string, query: string, topK = 10, memoryTypes?: string[]) {
  const body: Record<string, any> = { query, top_k: topK }
  if (memoryTypes && memoryTypes.length > 0) body.memory_types = memoryTypes
  return api.post<{ memories: MemoryItem[] }>(`/memory/bases/${memId}/recall`, body)
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

export function triggerDigest(memId: string) {
  return api.post(`/memory/bases/${memId}/digest`)
}

export interface GraphData {
  nodes: { node_type: string; node_id: string; properties: Record<string, any> }[]
  edges: { source_type: string; source_id: string; target_type: string; target_id: string; edge_type: string }[]
}

export function getGraph(memId: string) {
  return api.get<GraphData>(`/memory/bases/${memId}/graph`)
}

export interface RawMessage {
  id: string
  content: string
  content_preview: string
  role: string
  source: string | null
  created_at: string
}

export interface RawMessageDetail {
  message: RawMessage
  extracted_memories: {
    id: number
    content: string
    memory_type: string
    importance: number
    metadata: Record<string, any>
    created_at: string
  }[]
}

export function listRawMessages(memId: string, options?: { offset?: number; limit?: number }) {
  return api.get<{ messages: RawMessage[]; total: number }>(`/memory/bases/${memId}/raw_messages`, { params: options })
}

export function getRawMessage(memId: string, messageId: string) {
  return api.get<RawMessageDetail>(`/memory/bases/${memId}/raw_messages/${messageId}`)
}
