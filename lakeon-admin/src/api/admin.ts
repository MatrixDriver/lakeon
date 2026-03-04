import client from './client'

export const adminApi = {
  dashboard: () => client.get('/dashboard'),

  // Tenants
  listTenants: () => client.get('/tenants'),
  getTenant: (id: string) => client.get(`/tenants/${id}`),
  updateQuota: (id: string, data: { max_databases?: number; max_storage_gb?: number; max_compute_cu?: number }) =>
    client.put(`/tenants/${id}/quota`, data),

  // Databases
  listDatabases: (params?: { status?: string; tenant_id?: string }) =>
    client.get('/databases', { params }),

  // Compute
  computeStats: () => client.get('/compute/stats'),

  // System health
  systemHealth: () => client.get('/system/health'),
  componentHealth: (component: string) => client.get(`/system/health/${component}`),

  // Operations
  listOperations: (params?: { tenant_id?: string; type?: string; status?: string; page?: number; size?: number }) =>
    client.get('/operations', { params }),

  // Cost
  costSummary: () => client.get('/cost/summary'),
  costByTenant: () => client.get('/cost/tenants'),
}
