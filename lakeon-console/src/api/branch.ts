import client from './client'

export interface Branch {
  id: string
  name: string
  parent_branch: string
  is_default: boolean
  status: string
  compute_status: string
  connection_uri: string
  parent_branch_id: string | null
  neon_timeline_id: string | null
  ancestor_lsn: string | null
  last_record_lsn: string | null
  current_logical_size_bytes: number | null
  created_at: string
}

export interface BranchTreeNode {
  id: string
  name: string
  parent_branch_id: string | null
  is_default: boolean
  ancestor_lsn: string | null
  last_record_lsn: string | null
  current_logical_size_bytes: number | null
  created_at: string
}

export interface BranchTreeResponse {
  nodes: BranchTreeNode[]
}

export interface CreateBranchParams {
  name: string
  start_compute?: boolean
  parent_branch_id?: string
  ancestor_lsn?: string
}

export interface RestoreParams {
  target_version_id?: string
  target_lsn?: string
}

export const branchApi = {
  list: (dbId: string) => client.get<Branch[]>(`/databases/${dbId}/branches`),
  create: (dbId: string, data: CreateBranchParams) =>
    client.post<Branch>(`/databases/${dbId}/branches`, data),
  delete: (dbId: string, branchId: string) =>
    client.delete(`/databases/${dbId}/branches/${branchId}`),
  getTree: (dbId: string) =>
    client.get<BranchTreeResponse>(`/databases/${dbId}/branches/tree`),
  promote: (dbId: string, branchId: string) =>
    client.post<Branch>(`/databases/${dbId}/branches/${branchId}/promote`),
  restore: (dbId: string, branchId: string, data: RestoreParams) =>
    client.post<Branch>(`/databases/${dbId}/branches/${branchId}/restore`, data),
}
