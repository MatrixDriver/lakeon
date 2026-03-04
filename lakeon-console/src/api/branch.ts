import client from './client'

export interface Branch {
  id: string
  name: string
  parent_branch: string
  is_default: boolean
  status: string
  compute_status: string
  connection_uri: string
  created_at: string
}

export const branchApi = {
  list: (dbId: string) => client.get<Branch[]>(`/databases/${dbId}/branches`),
  create: (dbId: string, data: { name: string; start_compute?: boolean }) =>
    client.post<Branch>(`/databases/${dbId}/branches`, data),
  delete: (dbId: string, branchId: string) =>
    client.delete(`/databases/${dbId}/branches/${branchId}`),
}
