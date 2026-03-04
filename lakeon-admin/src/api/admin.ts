import client from './client'

export const adminApi = {
  dashboard: () => client.get('/dashboard'),

  // Tenants
  listTenants: () => client.get('/tenants'),
  getTenant: (id: string) => client.get(`/tenants/${id}`),
  updateQuota: (id: string, data: { max_databases?: number; max_storage_gb?: number; max_compute_cu?: number }) =>
    client.put(`/tenants/${id}/quota`, data),
  batchDeleteTenants: (ids: string[]) =>
    client.delete('/tenants/batch', { data: { ids } }),

  // Databases
  listDatabases: (params?: { status?: string; tenant_id?: string }) =>
    client.get('/databases', { params }),
  batchDeleteDatabases: (ids: string[]) =>
    client.delete('/databases/batch', { data: { ids } }),

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
  costCbc: (billCycle?: string) => client.get('/cost/cbc', { params: billCycle ? { bill_cycle: billCycle } : {} }),
}
