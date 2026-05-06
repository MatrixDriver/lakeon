// Memory
export interface Memory {
  id: string
  agent_id: string
  source_kind: string
  source_ref: string | null
  text: string
  meta: Record<string, unknown> | null
  created_at: number
  updated_at: number
}

export interface MemoryListResponse { items: Memory[] }
export interface IngestRequest {
  text: string
  agent_id: string
  source_kind?: string
  source_ref?: string
  meta?: Record<string, unknown>
}
export interface IngestResponse {
  id: string
  agent_id: string
  created_at: number
}
export interface RecallHit {
  id: string
  text: string
  score: number
  source_kind: string
  source_ref: string | null
  meta: Record<string, unknown> | null
}

// Derivatives
export interface TimelineEvent {
  id: string
  window_start: number
  window_end: number
  agent_id: string
  title: string
  summary: string | null
  member_memory_ids: string[]
  rationale: string | null
}
export interface TimelineResponse { events: TimelineEvent[] }

export interface TreeNode {
  id: string
  level: number
  parent_id: string | null
  text: string
  token_estimate: number | null
  rationale: string | null
}
export interface TreeResponse { levels: TreeNode[] }

export interface GraphNode { id: string; name: string; kind: string | null }
export interface GraphEdge {
  subject_id: string
  object_id: string
  predicate: string
  confidence: number
}
export interface GraphResponse { nodes: GraphNode[]; edges: GraphEdge[] }

export interface Skill {
  id: string
  name: string
  trigger_pattern: string
  steps: string[]
  source: string
  observed_count: number
  success_count: number
}
export interface SkillsResponse { skills: Skill[] }

// Diagnostic
export interface DaemonHealth {
  status: string
  version: string
  data_dir: string
  db_size_bytes: number
}
export interface OllamaHealth {
  status: 'ok' | 'unreachable' | 'timeout'
  latency_ms: number | null
  generate_model: string
  embedding_model: string
  embedding_dim: number
}
export interface WorkerStatus {
  queue_depth: number
  last_run_at: number | null
  processed_total: number
  throttle: string | null
}
export interface DiagnosticCounts {
  memories: number
  cognitions: number
  entities: number
  skills: number
}
export interface DeadLetterEntry {
  mem_id: string | null
  worker: string
  kind: string
  retries: number
  at: number
  traceback: string | null
}
export interface DiagnosticResponse {
  daemon: DaemonHealth
  ollama: OllamaHealth
  workers: Record<string, WorkerStatus>
  counts: DiagnosticCounts
  dead_letter: DeadLetterEntry[]
}

// Errors
export type ApiErrorKind = 'network' | 'client' | 'server' | 'parse'
export interface ApiError {
  kind: ApiErrorKind
  status?: number
  message: string
  body?: string
}
