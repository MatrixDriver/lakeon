import client from './client'

export interface PitrWindow {
  earliest: string
  latest: string
  earliest_lsn: string | null
  latest_lsn: string
}

export interface PitrRequest {
  target_time: string
  new_db_name?: string
}

export interface PitrResponse {
  new_db_id: string
  branch_id: string
  lsn: string
  compute_endpoint: string | null
  status: 'ready' | 'pending'
}

export async function getPitrWindow(dbId: string): Promise<PitrWindow> {
  const { data } = await client.get<PitrWindow>(`/databases/${dbId}/pitr-window`)
  return data
}

export async function pitr(dbId: string, req: PitrRequest): Promise<PitrResponse> {
  const { data } = await client.post<PitrResponse>(`/databases/${dbId}/pitr`, req)
  return data
}
