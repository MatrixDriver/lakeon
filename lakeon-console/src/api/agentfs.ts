import api from './client'

export interface AgentFileEntry {
  path: string
  kind: 'file' | 'dir'
  size: number
  mtime_ns: number
  etag: string
  properties: Record<string, unknown>
}

export interface AgentFSStats {
  file_count: number
  total_bytes: number
  last_write_ns: number
}

function toB64Url(s: string): string {
  const b64 = btoa(unescape(encodeURIComponent(s)))
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function getAgentFSStats() {
  return api.get<AgentFSStats>('/agentfs/stats')
}

export function listAgentFiles(prefix: string = '/', recursive: boolean = false) {
  const p = toB64Url(prefix)
  return api.get<{ entries: AgentFileEntry[]; next_cursor: string | null }>(
    `/agentfs/list?prefix=${p}&recursive=${recursive}`
  )
}

export function headAgentFile(path: string) {
  const p = toB64Url(path)
  return api.get<AgentFileEntry>(`/agentfs/files/head?path=${p}`)
}

export async function readAgentFile(path: string): Promise<string> {
  const p = toB64Url(path)
  const resp = await api.get(`/agentfs/files?path=${p}`, { responseType: 'text' })
  return resp.data as string
}
