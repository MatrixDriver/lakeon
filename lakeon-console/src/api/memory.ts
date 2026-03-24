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
