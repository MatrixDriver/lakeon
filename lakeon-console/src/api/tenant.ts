import client from './client'

export interface Tenant {
  id: string
  name: string
  api_key?: string
  created_at: string
}

export const tenantApi = {
  me: () => client.get<Tenant>('/tenants/me'),
  get: (id: string) => client.get<Tenant>(`/tenants/${id}`),
  register: (name: string) => client.post<Tenant>('/tenants', { name }),
  regenerateKey: (id: string) => client.post<Tenant>(`/tenants/${id}/regenerate-key`),
}
