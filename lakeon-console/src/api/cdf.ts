import client from './client'

export type CdfStreamStatus = 'PAUSED' | 'RUNNING' | 'FAILED'
export type CdfBackfillStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'BACKFILL_FAILED'
export type CdfExportStatus = 'NOT_MATERIALIZED' | 'MATERIALIZING' | 'MATERIALIZED' | 'FAILED'
export type CdfMode = 'APPEND_CHANGELOG'

export interface CdfStream {
  id: string
  database_id: string
  branch_id: string
  source_schema: string
  source_table: string
  target_namespace: string
  target_table: string
  mode: CdfMode
  status: CdfStreamStatus
  backfill_status: CdfBackfillStatus
  backfill_lsn?: string | null
  last_commit_lsn?: string | null
  last_snapshot_id?: number | null
  export_status: CdfExportStatus
  observed_lag_ms?: number | null
  last_error?: string | null
  readable?: boolean
  created_at?: string
  updated_at?: string
}

export interface CreateCdfStreamRequest {
  database_id: string
  branch_id: string
  source_schema: string
  source_table: string
  target_namespace: string
  target_table: string
  mode: CdfMode
  initial_backfill: boolean
}

export interface CdfExportStatusResponse {
  status: CdfExportStatus
  metadata_location?: string | null
  exported_at?: string | null
}

export const cdfApi = {
  listStreams: (databaseId: string) =>
    client.get<CdfStream[]>(`/databases/${databaseId}/cdf-streams`),

  createStream: (databaseId: string, data: CreateCdfStreamRequest) =>
    client.post<CdfStream>(`/databases/${databaseId}/cdf-streams`, data),

  resumeStream: (databaseId: string, streamId: string) =>
    client.post<CdfStream>(`/databases/${databaseId}/cdf-streams/${streamId}/resume`),

  pauseStream: (databaseId: string, streamId: string) =>
    client.post<CdfStream>(`/databases/${databaseId}/cdf-streams/${streamId}/pause`),

  materializeExport: (databaseId: string, streamId: string) =>
    client.post<CdfExportStatusResponse>(`/databases/${databaseId}/cdf-streams/${streamId}/export`),

  getExportStatus: (databaseId: string, streamId: string) =>
    client.get<CdfExportStatusResponse>(`/databases/${databaseId}/cdf-streams/${streamId}/export`),
}
