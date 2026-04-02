import api from './client'

export interface ObsConnection {
  id: string
  name: string
  domain_name: string
  agency_name: string
  obs_endpoint: string
  bucket: string
  base_path: string | null
  status: string
  last_tested_at: string | null
  created_at: string
  updated_at: string
}

export interface ObsConnectionCreateRequest {
  name: string
  domain_name: string
  agency_name: string
  obs_endpoint?: string
  bucket: string
  base_path?: string
}

export interface ObsBrowseItem {
  key: string
  name: string
  type: 'file' | 'directory'
  size?: number
  last_modified?: string
}

export interface PlatformInfo {
  hwcloud_account_id: string
  region: string
}

export function listObsConnections() {
  return api.get<ObsConnection[]>('/obs-connections')
}

export function getObsConnection(id: string) {
  return api.get<ObsConnection>(`/obs-connections/${id}`)
}

export function createObsConnection(body: ObsConnectionCreateRequest) {
  return api.post<ObsConnection>('/obs-connections', body)
}

export function deleteObsConnection(id: string) {
  return api.delete(`/obs-connections/${id}`)
}

export function testObsConnection(id: string) {
  return api.post<ObsConnection>(`/obs-connections/${id}/test`)
}

export function browseObsConnection(id: string, path?: string) {
  const params: Record<string, string> = {}
  if (path) params.path = path
  return api.get<ObsBrowseItem[]>(`/obs-connections/${id}/browse`, { params })
}

export function getPlatformInfo() {
  return api.get<PlatformInfo>('/obs-connections/platform-info')
}
