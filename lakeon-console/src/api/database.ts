import client from './client'

export interface Database {
  id: string
  name: string
  status: string
  status_message?: string | null
  connection_uri: string
  password?: string
  compute_size: string
  suspend_timeout: string
  storage_limit_gb: number
  storage_used_gb: number
  active_connections: number
  neon_timeline_id: string
  branches: BranchSummary[]
  created_at: string
}

export interface BranchSummary {
  id: string
  name: string
  is_default: boolean
  status: string
  compute_status: string
}

export interface SchemaInfo {
  name: string
}

export interface TableInfo {
  name: string
  type: string
  row_count_estimate: number
}

export interface ColumnInfo {
  name: string
  data_type: string
  nullable: boolean
  default_value: string | null
  comment: string | null
  ordinal_position: number
}

export interface IndexInfo {
  name: string
  columns: string[]
  is_unique: boolean
  is_primary: boolean
}

export interface ConstraintInfo {
  name: string
  type: string
  columns: string[]
  ref_table: string | null
  ref_columns: string[] | null
}

export interface QueryResult {
  columns: string[]
  rows: unknown[][]
  row_count: number
  execution_time_ms: number
  is_select: boolean
}

export interface QueryHistoryItem {
  id: number
  database_id?: string
  database_name?: string
  sql: string
  success: boolean
  row_count: number | null
  duration_ms: number | null
  error: string | null
  created_at: string
}

export interface QueryHistoryPage {
  items: QueryHistoryItem[]
  total: number
  page: number
  pages: number
}

export interface AiModel {
  id: string
  name: string
  input_price: number
  output_price: number
  desc: string
}

export interface AiSqlResult {
  sql?: string
  error?: string
  model?: string
  input_tokens?: number
  output_tokens?: number
}

export interface ConnectionInfo {
  pid: number
  user: string
  client_ip: string | null
  state: string
  connected_seconds: number
  query_seconds: number | null
  current_query: string
  application_name: string
  wait_event: string | null
}

export interface ConnectionsData {
  total: number
  connections: ConnectionInfo[]
  by_ip: { ip: string; count: number }[]
  error?: string
}

export interface DataPage {
  columns: string[]
  rows: unknown[][]
  total_rows: number
  page: number
  page_size: number
}

export interface TableStats {
  row_count: number
  size_bytes: number
  size_pretty: string
}

export interface CreateTablePayload {
  name: string
  columns: { name: string; type: string; nullable: boolean; default_value?: string }[]
  primary_key?: string[]
}

export interface DatabaseMetrics {
  cpuUsage: number
  cpuLimit: number
  memoryUsageMb: number
  memoryLimitMb: number
  activeConnections: number
  slowQueries: number
  storageUsedGb: number
  storageLimitGb: number
  status: string
}

export const databaseApi = {
  list: () => client.get<Database[]>('/databases'),
  get: (id: string) => client.get<Database>(`/databases/${id}`),
  create: (data: { name: string; compute_size?: string; suspend_timeout?: string; storage_limit_gb?: number }) =>
    client.post<Database>('/databases', data),
  update: (id: string, data: { compute_size?: string; suspend_timeout?: string; storage_limit_gb?: number }) =>
    client.patch<Database>(`/databases/${id}`, data),
  delete: (id: string) => client.delete(`/databases/${id}`),
  listDeleted: () => client.get<Database[]>('/databases/recycle-bin'),
  restore: (id: string) => client.post<Database>(`/databases/${id}/restore`),
  suspend: (id: string) => client.post(`/databases/${id}/suspend`),
  resume: (id: string) => client.post(`/databases/${id}/resume`),
  resetPassword: (id: string) => client.post<{ password: string }>(`/databases/${id}/reset-password`),
  getMetrics: (id: string) => client.get<DatabaseMetrics>(`/databases/${id}/metrics`),
  getLogs: (id: string, tail?: number) => client.get<{ timestamp: string; level: string; message: string }[]>(`/databases/${id}/logs`, { params: { tail: tail || 200 } }),

  // Database Manager APIs
  listSchemas: (id: string) => client.get<SchemaInfo[]>(`/databases/${id}/schemas`),
  listTables: (id: string, schema: string) => client.get<TableInfo[]>(`/databases/${id}/schemas/${schema}/tables`),
  listColumns: (id: string, schema: string, table: string) => client.get<ColumnInfo[]>(`/databases/${id}/schemas/${schema}/tables/${table}/columns`),
  listIndexes: (id: string, schema: string, table: string) => client.get<IndexInfo[]>(`/databases/${id}/schemas/${schema}/tables/${table}/indexes`),
  listConstraints: (id: string, schema: string, table: string) => client.get<ConstraintInfo[]>(`/databases/${id}/schemas/${schema}/tables/${table}/constraints`),
  tableData: (id: string, schema: string, table: string, params: { page?: number; size?: number; sort?: string; dir?: string }) =>
    client.get<DataPage>(`/databases/${id}/schemas/${schema}/tables/${table}/data`, { params }),
  executeQuery: (id: string, sql: string) => client.post<QueryResult>(`/databases/${id}/query`, { sql }),
  tableStats: (id: string, schema: string, table: string) => client.get<TableStats>(`/databases/${id}/schemas/${schema}/tables/${table}/stats`),
  createTable: (id: string, schema: string, data: CreateTablePayload) => client.post(`/databases/${id}/schemas/${schema}/tables`, data),
  dropTable: (id: string, schema: string, table: string) => client.delete(`/databases/${id}/schemas/${schema}/tables/${table}`),

  // Connections
  getConnections: (id: string) => client.get<ConnectionsData>(`/databases/${id}/connections`),

  // IP Allowlist
  getAllowedIps: (id: string) => client.get<{ enabled: boolean; ips: string[] }>(`/databases/${id}/allowed-ips`),
  setAllowedIps: (id: string, ips: string[]) => client.put<{ enabled: boolean; ips: string[] }>(`/databases/${id}/allowed-ips`, { ips }),
  clearAllowedIps: (id: string) => client.delete(`/databases/${id}/allowed-ips`),

  // AI SQL Assistant
  getAiModels: (id: string) => client.get<AiModel[]>(`/databases/${id}/ai-sql/models`),
  generateSql: (id: string, prompt: string, model: string) =>
    client.post<AiSqlResult>(`/databases/${id}/ai-sql/generate`, { prompt, model }),

  // Query History
  getQueryHistory: (id: string, params: { page?: number; size?: number; q?: string }) =>
    client.get<QueryHistoryPage>(`/databases/${id}/query-history`, { params }),
  clearQueryHistory: (id: string) => client.delete(`/databases/${id}/query-history`),

  // Tenant-level query history (cross-database)
  getAllQueryHistory: (params: { page?: number; size?: number; q?: string }) =>
    client.get<QueryHistoryPage>(`/query-history`, { params }),
  clearAllQueryHistory: () => client.delete(`/query-history`),
}
