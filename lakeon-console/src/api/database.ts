import client from './client'

export interface Database {
  id: string
  name: string
  status: string
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

export const databaseApi = {
  list: () => client.get<Database[]>('/databases'),
  get: (id: string) => client.get<Database>(`/databases/${id}`),
  create: (data: { name: string; compute_size?: string; suspend_timeout?: string; storage_limit_gb?: number }) =>
    client.post<Database>('/databases', data),
  update: (id: string, data: { compute_size?: string; suspend_timeout?: string; storage_limit_gb?: number }) =>
    client.patch<Database>(`/databases/${id}`, data),
  delete: (id: string) => client.delete(`/databases/${id}`),
  suspend: (id: string) => client.post(`/databases/${id}/suspend`),
  resume: (id: string) => client.post(`/databases/${id}/resume`),
  resetPassword: (id: string) => client.post<{ password: string }>(`/databases/${id}/reset-password`),

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
}
