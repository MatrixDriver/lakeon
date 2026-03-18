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

export interface Chunk {
  id: number
  document_id: string
  chunk_index: number
  content: string
  metadata: Record<string, any>
  char_count: number
  overlap_prev: number
  char_offset_start: number | null
  char_offset_end: number | null
  page_start: number | null
  page_end: number | null
  level: number
  edited: boolean
  created_at: string
  updated_at: string | null
}

export interface ChunkListResponse {
  chunks: Chunk[]
  total: number
  offset: number
  limit: number
}

export interface ChunkContext {
  prev: Chunk | null
  next: Chunk | null
}

export interface ChunkStats {
  total_chunks: number
  avg_char_count: number
  anomaly_count: number
  duplicate_count: number
  length_distribution: { bucket: number; count: number }[]
  anomalous_chunks: { id: number; chunk_index: number; char_count: number }[]
  adjacent_similarities: { chunk_index: number; similarity: number }[]
  duplicates: { chunk_index: number; duplicate_of: number; similarity: number }[]
}

export interface RechunkResponse {
  job_id: string
  branch_id: string
  branch_name: string
}

// Chunks
export function listChunks(kbId: string, docId: string, level = 0, offset = 0, limit = 50) {
  return api.get<ChunkListResponse>(`/knowledge/bases/${kbId}/documents/${docId}/chunks`, {
    params: { level, offset, limit },
  })
}

export function getChunk(kbId: string, docId: string, chunkIndex: number) {
  return api.get<Chunk>(`/knowledge/bases/${kbId}/documents/${docId}/chunks/${chunkIndex}`)
}

export function getChunkContext(kbId: string, docId: string, chunkIndex: number) {
  return api.get<ChunkContext>(`/knowledge/bases/${kbId}/documents/${docId}/chunks/${chunkIndex}/context`)
}

export function getChunkStats(kbId: string, docId: string) {
  return api.get<ChunkStats>(`/knowledge/bases/${kbId}/documents/${docId}/chunks/stats`)
}

export function getFulltext(kbId: string, docId: string) {
  return api.get<string>(`/knowledge/bases/${kbId}/documents/${docId}/fulltext`)
}

export function editChunk(kbId: string, docId: string, chunkIndex: number, content: string) {
  return api.put<Chunk>(`/knowledge/bases/${kbId}/documents/${docId}/chunks/${chunkIndex}`, { content })
}

export function deleteChunk(kbId: string, docId: string, chunkIndex: number) {
  return api.delete(`/knowledge/bases/${kbId}/documents/${docId}/chunks/${chunkIndex}`)
}

export function createChunk(kbId: string, docId: string, content: string, insertAfterIndex: number) {
  return api.post<Chunk>(`/knowledge/bases/${kbId}/documents/${docId}/chunks`, {
    content,
    insert_after_index: insertAfterIndex,
  })
}

export function rechunk(
  kbId: string,
  docId: string,
  params: { max_tokens?: number; overlap_ratio?: number; custom_separator?: string }
) {
  return api.post<RechunkResponse>(`/knowledge/bases/${kbId}/documents/${docId}/rechunk`, params)
}

export function rechunkRollback(kbId: string, docId: string, branchId: string) {
  return api.post(`/knowledge/bases/${kbId}/documents/${docId}/rechunk/rollback`, { branch_id: branchId })
}

export function listKbChunks(
  kbId: string,
  options?: { doc_id?: string; status?: string; offset?: number; limit?: number }
) {
  return api.get<ChunkListResponse>(`/knowledge/bases/${kbId}/chunks`, { params: options })
}
