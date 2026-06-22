import api from './client'

export interface LBFSFileEntry {
  path: string
  kind: 'file' | 'dir'
  size: number
  mtime_ns: number
  etag: string
  properties: Record<string, unknown>
}

export interface LBFSStats {
  file_count: number
  total_bytes: number
  last_write_ns: number
}

export type LBFSDirectoryKind =
  | 'codex-home'
  | 'claude-home'
  | 'openclaw-home'
  | 'opencode-home'
  | 'iceberg-table'
  | 'lance-table'
  | 'data-dir'
  | 'files'

export type LBFSStoragePolicy = 'auto' | 'inline-only' | 'object-first' | 'object-only' | 'table-native'
export type LBFSProcessingProfile = 'none' | 'agent-home' | 'dataset' | 'iceberg' | 'lance'

export interface LBFSFolder {
  id: string
  display_name: string
  directory_kind: LBFSDirectoryKind
  storage_policy: LBFSStoragePolicy
  processing_profile: LBFSProcessingProfile
  status: string
  created_at?: string
  updated_at?: string
}

export interface LBFSFolderCreate {
  display_name: string
  directory_kind: LBFSDirectoryKind
  storage_policy?: LBFSStoragePolicy
  processing_profile?: LBFSProcessingProfile
}

function toB64Url(s: string): string {
  const b64 = btoa(unescape(encodeURIComponent(s)))
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function listLBFSFolders() {
  return api.get<{ folders: LBFSFolder[] }>('/lbfs/folders')
}

export function createLBFSFolder(payload: LBFSFolderCreate) {
  return api.post<LBFSFolder>('/lbfs/folders', payload)
}

export function getLBFSStats() {
  return api.get<LBFSStats>('/lbfs/stats')
}

export function listLBFSFiles(prefix: string = '/', recursive: boolean = false) {
  const p = toB64Url(prefix)
  return api.get<{ entries: LBFSFileEntry[]; next_cursor: string | null }>(
    `/lbfs/list?prefix=${p}&recursive=${recursive}`
  )
}

export function headLBFSFile(path: string) {
  const p = toB64Url(path)
  return api.get<LBFSFileEntry>(`/lbfs/files/head?path=${p}`)
}

export async function readLBFSFile(path: string): Promise<string> {
  const p = toB64Url(path)
  const resp = await api.get(`/lbfs/files?path=${p}`, { responseType: 'text' })
  return resp.data as string
}
