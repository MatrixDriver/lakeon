import client from './client'

export interface SourceTableInfo {
  schema: string
  table: string
  estimated_rows: number
}

export interface ImportTableTask {
  id: string
  schema_name: string
  table_name: string
  status: string
  row_count: number | null
  error_message: string | null
  started_at: string | null
  finished_at: string | null
  sync_state: string | null
  synced_rows: number | null
}

export interface ImportTask {
  id: string
  database_id: string
  connector_id: string | null
  connector_name: string | null
  source_host: string
  source_port: number
  source_dbname: string
  mode: string
  conflict_strategy: string
  status: string
  total_tables: number
  completed_tables: number
  error_message: string | null
  created_at: string
  started_at: string | null
  finished_at: string | null
  tables: ImportTableTask[] | null
  publication_name: string | null
  subscription_name: string | null
  sync_status: string | null
  replay_lag_seconds: number | null
  sync_rate_rows_per_sec: number | null
  last_sync_at: string | null
  wal_retained_bytes: number | null
  wal_warning: boolean | null
}

export interface TableSyncStatus {
  table_name: string
  schema_name: string
  status: string
  synced_rows: number | null
  error: string | null
}

export interface SyncStatusResponse {
  overall_status: string
  replay_lag_seconds: number | null
  sync_rate_rows_per_sec: number | null
  last_sync_at: string | null
  wal_retained_bytes: number | null
  wal_warning: boolean | null
  tables: TableSyncStatus[]
}

export const importApi = {
  testConnection: (data: { host: string; port: number; dbname: string; user: string; password: string }) =>
    client.post<{ ok: boolean; version?: string; error?: string; wal_level?: string; has_replication?: boolean }>('/import/test-connection', data, { timeout: 30000 }),

  listSourceTables: (data: { host: string; port: number; dbname: string; user: string; password: string }) =>
    client.post<SourceTableInfo[]>('/import/source-tables', data, { timeout: 60000 }),

  create: (dbId: string, data: {
    connectorId?: string;
    sourceHost?: string; sourcePort?: number; sourceDbname?: string;
    sourceUser?: string; sourcePassword?: string;
    mode: string; conflictStrategy: string; tables?: string[]
  }) => client.post<ImportTask>(`/databases/${dbId}/import`, {
    connector_id: data.connectorId,
    source_host: data.sourceHost,
    source_port: data.sourcePort,
    source_dbname: data.sourceDbname,
    source_user: data.sourceUser,
    source_password: data.sourcePassword,
    mode: data.mode,
    conflict_strategy: data.conflictStrategy,
    tables: data.tables,
  }),

  list: (dbId: string) =>
    client.get<ImportTask[]>(`/databases/${dbId}/import`),

  get: (dbId: string, taskId: string) =>
    client.get<ImportTask>(`/databases/${dbId}/import/${taskId}`),

  pause: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/pause`),

  resume: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/resume`),

  cancel: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/cancel`),

  retry: (dbId: string, taskId: string) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/retry`),

  syncStatus: (dbId: string, taskId: string) =>
    client.get<SyncStatusResponse>(`/databases/${dbId}/import/${taskId}/sync-status`),

  stop: (dbId: string, taskId: string, cleanup: boolean = true) =>
    client.post<ImportTask>(`/databases/${dbId}/import/${taskId}/stop`, { cleanup }),
}
