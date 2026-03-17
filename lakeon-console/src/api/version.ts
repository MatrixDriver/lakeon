import client from './client'

export interface Version {
  id: string
  branch_id: string
  name: string
  description: string | null
  lsn: string
  snapshot_timeline_id: string
  created_by: string
  created_at: string
}

export interface CreateVersionParams {
  name: string
  description?: string
  at?: string
  at_lsn?: string
}

export interface SquashParams {
  from_version_id: string
  to_version_id: string
}

export const versionApi = {
  list: (dbId: string, branchId: string) =>
    client.get<Version[]>(`/databases/${dbId}/branches/${branchId}/versions`),
  create: (dbId: string, branchId: string, data: CreateVersionParams) =>
    client.post<Version>(`/databases/${dbId}/branches/${branchId}/versions`, data),
  get: (dbId: string, branchId: string, versionId: string) =>
    client.get<Version>(`/databases/${dbId}/branches/${branchId}/versions/${versionId}`),
  delete: (dbId: string, branchId: string, versionId: string) =>
    client.delete(`/databases/${dbId}/branches/${branchId}/versions/${versionId}`),
  squash: (dbId: string, branchId: string, data: SquashParams) =>
    client.post<Version[]>(`/databases/${dbId}/branches/${branchId}/versions/squash`, data),
}
