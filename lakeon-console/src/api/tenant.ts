import client from './client'

export interface Tenant {
  id: string
  name: string
  api_key?: string
  created_at: string
}

export const tenantApi = {
  get: (id: string) => client.get<Tenant>(`/tenants/${id}`),
  regenerateKey: (id: string) => client.post<Tenant>(`/tenants/${id}/regenerate-key`),
}
