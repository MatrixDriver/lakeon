import client from './client'

export interface Tenant {
  id: string
  name: string
  username?: string
  api_key?: string
  created_at: string
}

export interface ApiKeyItem {
  id: string
  name: string
  masked_key: string
  api_key?: string
  created_at: string
}

export const tenantApi = {
  me: () => client.get<Tenant>('/tenants/me'),
  get: (id: string) => client.get<Tenant>(`/tenants/${id}`),
  register: (data: { username: string; password: string; inviteCode?: string }) =>
    client.post<Tenant>('/tenants', data),
  regenerateKey: (id: string) => client.post<Tenant>(`/tenants/${id}/regenerate-key`),

  checkUsername: (username: string) =>
    client.get<{ available: boolean }>('/auth/check-username', { params: { username } }),

  // Multi API Key management
  listApiKeys: () => client.get<ApiKeyItem[]>('/api-keys'),
  createApiKey: (name: string) => client.post<ApiKeyItem>('/api-keys', { name }),
  deleteApiKey: (keyId: string) => client.delete(`/api-keys/${keyId}`),

  // OAuth
  oauthExchangeToken: (code: string) =>
    client.post<Tenant>('/auth/oauth/token', { code }),
}
