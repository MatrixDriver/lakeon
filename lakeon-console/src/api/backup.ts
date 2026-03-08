import client from './client'

export interface Backup {
  id: string
  database_id: string
  tenant_id: string
  name: string
  status: string
  type: string
  neon_tenant_id: string
  neon_timeline_id: string
  source_tenant_id: string
  source_timeline_id: string
  lsn: string | null
  size_bytes: number | null
  created_at: string
  completed_at: string | null
  error_message: string | null
}

export interface RestoreResult {
  id: string
  name: string
  status: string
}

export const backupApi = {
  create: (dbId: string, data: { name?: string }) =>
    client.post<Backup>(`/databases/${dbId}/backups`, data),

  list: (dbId: string) =>
    client.get<Backup[]>(`/databases/${dbId}/backups`),

  get: (dbId: string, backupId: string) =>
    client.get<Backup>(`/databases/${dbId}/backups/${backupId}`),

  restore: (dbId: string, backupId: string, data: { name: string }) =>
    client.post<RestoreResult>(`/databases/${dbId}/backups/${backupId}/restore`, data),

  delete: (dbId: string, backupId: string) =>
    client.delete(`/databases/${dbId}/backups/${backupId}`),
}
