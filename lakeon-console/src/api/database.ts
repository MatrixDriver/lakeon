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
}
