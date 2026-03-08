import client from './client'

export const adminApi = {
  dashboard: () => client.get('/dashboard'),

  // Tenants
  listTenants: () => client.get('/tenants'),
  getTenant: (id: string) => client.get(`/tenants/${id}`),
  updateQuota: (id: string, data: { max_databases?: number; max_storage_gb?: number; max_compute_cu?: number }) =>
    client.put(`/tenants/${id}/quota`, data),
  disableTenant: (id: string) => client.post(`/tenants/${id}/disable`),
  enableTenant: (id: string) => client.post(`/tenants/${id}/enable`),
  batchDeleteTenants: (ids: string[]) =>
    client.delete('/tenants/batch', { data: { ids } }),

  // Databases
  listDatabases: (params?: { status?: string; tenant_id?: string }) =>
    client.get('/databases', { params }),
  getDatabase: (id: string) => client.get(`/databases/${id}`),
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
  costTrend: (days = 30) => client.get('/cost/trend', { params: { days } }),

  // Cloud Resources
  cloudResources: () => client.get('/cloud/resources'),

  // Logs
  componentLogs: (component: string, tail = 200) =>
    client.get(`/logs/${component}`, { params: { tail }, transformResponse: [(d: string) => d] }),

  // Metrics
  metricsSummary: () => client.get('/metrics/summary'),

  // Alerts
  alerts: () => client.get('/alerts'),
  alertRules: () => client.get('/alerts/rules'),
  updateAlertRule: (id: string, data: Record<string, unknown>) => client.put(`/alerts/rules/${id}`, data),
  testWebhook: (webhookUrl: string) => client.post('/alerts/test-webhook', { webhook_url: webhookUrl }),

  // Infrastructure
  infraNodes: () => client.get('/infra/nodes'),

  // Audit
  auditLogs: (params?: { tenant_id?: string; db_id?: string; type?: string; page?: number; size?: number }) =>
    client.get('/audit/logs', { params }),
}
