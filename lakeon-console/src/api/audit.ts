import client from './client'

export interface AuditConfig {
  id: string
  database_id: string
  tenant_id: string
  enabled: boolean
  log_ddl: boolean
  log_dml: boolean
  log_select: boolean
  retention_days: number
  created_at: string
  updated_at: string
}

export interface AuditLog {
  id: string
  database_id: string
  tenant_id: string
  timestamp: string
  user_name: string | null
  statement: string | null
  statement_type: string
  object_name: string | null
  client_addr: string | null
  duration: number | null
}

export interface AuditLogPage {
  data: AuditLog[]
  total: number
  page: number
  total_pages: number
}

export const auditApi = {
  getConfig: (dbId: string) =>
    client.get<AuditConfig>(`/databases/${dbId}/audit/config`),

  updateConfig: (dbId: string, data: {
    enabled?: boolean
    log_ddl?: boolean
    log_dml?: boolean
    log_select?: boolean
    retention_days?: number
  }) =>
    client.put<AuditConfig>(`/databases/${dbId}/audit/config`, data),

  getLogs: (dbId: string, params?: { type?: string; page?: number; size?: number }) =>
    client.get<AuditLogPage>(`/databases/${dbId}/audit/logs`, { params }),
}
