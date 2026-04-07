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
  type: 'DOCUMENT' | 'TABLE'
  source_database_id: string | null
  table_names: string[]
  embedding_model: string | null
  summary?: string
  total_size_bytes?: number
}

export interface TableSearchResult {
  type: 'sql'
  sql: string
  columns: string[]
  rows: any[][]
  model: string
  tokens: { input: number; output: number }
}

export interface Document {
  id: string
  kb_id: string
  filename: string
  format: string
  type?: string  // doc_type: 'raw' | 'wiki' | 'index'
  size_bytes: number
  chunks_count: number | null
  status: string
  progress?: number
  progress_message?: string
  error: string | null
  tags: string[]
  folder: string
  metadata: Record<string, string>
  created_at: string
}

export interface DocumentListResponse {
  documents: Document[]
  total: number
  page: number
  page_size: number
}

export interface DocumentStats {
  total: number
  processing: number
  ready: number
  failed: number
  pending: number
}

export interface Folder {
  name: string
  path: string
  document_count: number
  total_size: number
}

export function listFolders(kbId: string, parent: string = '') {
  return api.get<Folder[]>('/knowledge/folders', {
    params: { kb_id: kbId, parent }
  })
}

export interface SearchResult {
  content: string
  score: number
  level?: number
  metadata: {
    filename?: string
    section?: string
    document_id?: string
    kb_id?: string
    kb_name?: string
  }
}

// Knowledge Bases
export function listKnowledgeBases() {
  return api.get<KnowledgeBase[]>('/knowledge/bases')
}

export function getKnowledgeBase(id: string) {
  return api.get<KnowledgeBase>(`/knowledge/bases/${id}`)
}

export function createKnowledgeBase(name: string, description?: string, options?: {
  type?: 'DOCUMENT' | 'TABLE'
  source_database_id?: string
  table_names?: string[]
  embedding_model?: string
}) {
  return api.post<KnowledgeBase>('/knowledge/bases', { name, description, ...options })
}

export function getTableInfo(kbId: string) {
  return api.get<{ tables: { table_name: string; columns: { name: string; type: string }[] }[] }>(
    `/knowledge/bases/${kbId}/tables`
  )
}

export function deleteKnowledgeBase(id: string) {
  return api.delete(`/knowledge/bases/${id}`)
}

// Documents
export function listDocuments(kbId: string, params?: {
  page?: number
  page_size?: number
  status?: string
  sort_by?: string
  sort_order?: string
  folder?: string
}) {
  return api.get<DocumentListResponse>('/knowledge/documents', {
    params: { kb_id: kbId, ...params },
  })
}

export function getDocumentStats(kbId: string) {
  return api.get<DocumentStats>('/knowledge/documents/stats', {
    params: { kb_id: kbId },
  })
}

export function getUploadUrl(kbId: string, filename: string) {
  return api.get<{ document_id: string; upload_url: string; obs_key: string; expires_in: number }>(
    '/knowledge/upload-url', { params: { kb_id: kbId, filename } }
  )
}

export function processDocument(documentId: string) {
  return api.post(`/knowledge/documents/${documentId}/process`)
}

export function batchGetUploadUrls(kbId: string, files: { filename: string; tags?: string[]; folder?: string }[]) {
  return api.post<{ documents: { document_id: string; filename: string; upload_url: string; expires_in: number }[] }>(
    '/knowledge/batch-upload-urls', { kb_id: kbId, files }
  )
}

export function batchProcessDocuments(documentIds: string[]) {
  return api.post<{ task_id: string; document_count: number }>(
    '/knowledge/batch-process', { document_ids: documentIds }
  )
}

export function ingestDocuments(documentIds: string[], metadata?: Record<string, string>) {
  const body: Record<string, unknown> = { document_ids: documentIds }
  if (metadata) body.metadata = metadata
  return api.post<{ task_ids: string[]; pod_count: number; documents_per_pod: number[]; total_documents: number }>(
    '/knowledge/ingest', body
  )
}

export function getDocument(documentId: string) {
  return api.get<Document>(`/knowledge/documents/${documentId}`)
}

export function getDocumentSummary(kbId: string, docId: string) {
  return api.get<{ content: string }>(`/knowledge/${kbId}/documents/${docId}/summary`)
}

export function deleteDocument(documentId: string) {
  return api.delete(`/knowledge/documents/${documentId}`)
}

export function clearAllDocuments(kbId: string) {
  return api.delete<{ deleted: number }>(`/knowledge/bases/${kbId}/documents`)
}

export function setDocumentTags(docId: string, tags: string[]) {
  return api.put<{ tags: string[] }>(`/knowledge/documents/${docId}/tags`, { tags })
}

// Search
export function searchKnowledge(kbId: string, query: string, topK: number = 5, options?: {
  tags?: string[]
  document_ids?: string[]
  rerank?: boolean
  conversation_history?: { role: string; content: string }[]
}) {
  return api.post<{ results: SearchResult[]; rewritten_query?: string }>('/knowledge/search', {
    ...(kbId ? { kb_id: kbId } : {}),
    query,
    top_k: topK,
    ...options,
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

// Datasources
export interface DataSource {
  id: string
  kb_id: string
  name: string
  obs_prefix: string
  status: string
  file_count: number
  last_synced_at: string | null
  last_sync_stats: { added: number; modified: number; deleted: number; skipped: number } | null
  error: string | null
  created_at: string
}

export interface DataSourceCredentials {
  endpoint: string
  bucket: string
  prefix: string
  access_key: string
  secret_key: string
  security_token: string
  expires_at: string
  upload_commands: { hcloud: string; obsutil: string }
}

export function listDataSources(kbId: string) {
  return api.get<DataSource[]>(`/knowledge/${kbId}/datasources`)
}

export function createDataSource(kbId: string, name: string) {
  return api.post<DataSource>(`/knowledge/${kbId}/datasources`, { name })
}

export function deleteDataSource(kbId: string, dsId: string) {
  return api.delete(`/knowledge/${kbId}/datasources/${dsId}`)
}

export function syncDataSource(kbId: string, dsId: string) {
  return api.post<{ datasource_id: string; status: string; sync_stats: any }>(`/knowledge/${kbId}/datasources/${dsId}/sync`)
}

export function getDataSourceCredentials(kbId: string, dsId: string) {
  return api.get<DataSourceCredentials>(`/knowledge/${kbId}/datasources/${dsId}/credentials`)
}

// ── Wiki API ──

export interface WikiPageItem {
  id: string
  filename: string
  tags: string[]
  metadata: Record<string, string>
  created_at: string
  updated_at: string | null
}

export function listWikiPages(kbId: string) {
  return api.get<WikiPageItem[]>('/knowledge/wiki/pages', { params: { kb_id: kbId } })
}

export function getWikiPageContent(kbId: string, docId: string) {
  return api.get<{ content: string }>(`/knowledge/wiki/pages/${docId}/content`, {
    params: { kb_id: kbId }
  })
}

export interface WikiGraph {
  nodes: { id: string; label: string; document_id: string }[]
  edges: { source: string; target: string }[]
}

export function getWikiGraph(kbId: string) {
  return api.get<WikiGraph>('/knowledge/wiki/graph', { params: { kb_id: kbId } })
}

export function wikiChat(kbId: string, question: string, history: { role: string; content: string }[] = []) {
  return api.post<{ answer: string; depth: string; sources: string[] }>('/knowledge/wiki/chat', {
    kb_id: kbId, question, history
  })
}

export function wikiChatStream(kbId: string, question: string, history: { role: string; content: string }[] = []) {
  const apiKey = localStorage.getItem('lakeon_api_key') || ''
  return fetch(`${api.defaults.baseURL}/knowledge/wiki/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`,
    },
    body: JSON.stringify({ kb_id: kbId, question, history }),
  })
}

export function saveWikiResponse(kbId: string, title: string, content: string) {
  return api.post('/knowledge/wiki/save-response', { kb_id: kbId, title, content })
}

export function ingestUrl(kbId: string, url: string) {
  return api.post<{ document_id: string; status: string }>('/knowledge/wiki/ingest-url', {
    kb_id: kbId, url
  })
}

export interface WikiStats {
  document_count: number
  source_doc_count: number
  wiki_page_count: number
  graph_nodes: number
  graph_edges: number
  chat_count: number
  settlement_count: number
  llm_tokens_used: number
}

export function getWikiStats(kbId: string) {
  return api.get<WikiStats>('/knowledge/wiki/stats', { params: { kb_id: kbId } })
}
