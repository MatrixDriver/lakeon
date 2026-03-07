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
}

export interface ImportTask {
  id: string
  database_id: string
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
}

export const importApi = {
  testConnection: (data: { host: string; port: number; dbname: string; user: string; password: string }) =>
    client.post<{ ok: boolean; version?: string; error?: string }>('/import/test-connection', data, { timeout: 30000 }),

  listSourceTables: (data: { host: string; port: number; dbname: string; user: string; password: string }) =>
    client.post<SourceTableInfo[]>('/import/source-tables', data, { timeout: 60000 }),

  create: (dbId: string, data: {
    sourceHost: string; sourcePort: number; sourceDbname: string;
    sourceUser: string; sourcePassword: string;
    mode: string; conflictStrategy: string; tables?: string[]
  }) => client.post<ImportTask>(`/databases/${dbId}/import`, {
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
}
