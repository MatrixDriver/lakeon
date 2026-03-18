import api from './client'

export interface KnowledgeBase {
  id: string
  tenant_id: string
  name: string
  description: string | null
  database_id: string | null
  status: string
  document_count: number
  error: string | null
  created_at: string
  updated_at: string
}

export interface Document {
  id: string
  kb_id: string
  filename: string
  format: string
  size_bytes: number
  chunks_count: number | null
  status: string
  progress?: number
  error: string | null
  created_at: string
}

export interface SearchResult {
  content: string
  score: number
  metadata: {
    filename?: string
    section?: string
    document_id?: string
  }
}

// Knowledge Bases
export function listKnowledgeBases() {
  return api.get<KnowledgeBase[]>('/knowledge/bases')
}

export function getKnowledgeBase(id: string) {
  return api.get<KnowledgeBase>(`/knowledge/bases/${id}`)
}

export function createKnowledgeBase(name: string, description?: string) {
  return api.post<KnowledgeBase>('/knowledge/bases', { name, description })
}

export function deleteKnowledgeBase(id: string) {
  return api.delete(`/knowledge/bases/${id}`)
}

// Documents
export function listDocuments(kbId: string) {
  return api.get<Document[]>('/knowledge/documents', { params: { kb_id: kbId } })
}

export function getUploadUrl(kbId: string, filename: string) {
  return api.get<{ document_id: string; upload_url: string; obs_key: string; expires_in: number }>(
    '/knowledge/upload-url', { params: { kb_id: kbId, filename } }
  )
}

export function processDocument(documentId: string) {
  return api.post(`/knowledge/documents/${documentId}/process`)
}

export function getDocument(documentId: string) {
  return api.get<Document>(`/knowledge/documents/${documentId}`)
}

export function deleteDocument(documentId: string) {
  return api.delete(`/knowledge/documents/${documentId}`)
}

// Search
export function searchKnowledge(kbId: string, query: string, topK: number = 5) {
  return api.post<{ results: SearchResult[] }>('/knowledge/search', {
    kb_id: kbId,
    query,
    top_k: topK,
  })
}
