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
  coldStartAnalysis: (days = 7) => client.get('/compute/cold-start', { params: { days } }),

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

  // Structured Logs
  logSearch: (params: { component?: string; level?: string; keyword?: string; tenant_id?: string; since?: string; limit?: number }) =>
    client.get('/structured-logs/search', { params }),
  logTrace: (requestId: string) =>
    client.get(`/structured-logs/trace/${requestId}`),
  logErrors: (params?: { since?: string; component?: string }) =>
    client.get('/structured-logs/errors', { params }),
  logStats: (params?: { since?: string }) =>
    client.get('/structured-logs/stats', { params }),

  // Metrics
  metricsSummary: () => client.get('/metrics/summary'),
  pageserverMetrics: () => client.get('/pageserver/metrics'),

  // Alerts
  alerts: () => client.get('/alerts'),
  alertRules: () => client.get('/alerts/rules'),
  updateAlertRule: (id: string, data: Record<string, unknown>) => client.put(`/alerts/rules/${id}`, data),
  testWebhook: (webhookUrl: string) => client.post('/alerts/test-webhook', { webhook_url: webhookUrl }),

  // Infrastructure
  infraNodes: () => client.get('/infra/nodes'),
  infraEvents: (namespace = 'lakeon-compute') => client.get(`/infra/events?namespace=${namespace}`),
  nodePoolStatus: () => client.get('/infra/node-pool'),
  autoscalingEvents: () => client.get('/infra/autoscaling-events'),
  computeSummary: () => client.get('/infra/compute-summary'),
  cleanupIdlePods: () => client.post('/infra/cleanup-idle-pods'),

  // Audit
  auditLogs: (params?: { tenant_id?: string; db_id?: string; type?: string; page?: number; size?: number }) =>
    client.get('/audit/logs', { params }),

  // Knowledge Base Admin
  knowledgeStats: () => client.get('/knowledge/stats'),
  listKnowledgeBases: (params?: { tenant_id?: string; status?: string; type?: string }) =>
    client.get('/knowledge/bases', { params }),
  getKnowledgeBase: (id: string) => client.get(`/knowledge/bases/${id}`),
  deleteKnowledgeBase: (id: string) => client.delete(`/knowledge/bases/${id}`),
  listKnowledgeDocuments: (params?: { kb_id?: string; tenant_id?: string; status?: string }) =>
    client.get('/knowledge/documents', { params }),
  deleteKnowledgeDocument: (id: string) => client.delete(`/knowledge/documents/${id}`),
  reprocessDocument: (id: string) => client.post(`/knowledge/documents/${id}/reprocess`),
  resummarizeDocument: (kbId: string, docId: string) =>
    client.post(`/knowledge/admin/bases/${kbId}/documents/${docId}/resummarize`),
  listWriteTasks: (params?: { status?: string; type?: string; limit?: number }) =>
    client.get('/knowledge/write-tasks', { params }),
  pipelineTasks: (params?: { status?: string; kbId?: string; from?: string; to?: string; page?: number; size?: number }) =>
    client.get('/knowledge/pipeline/tasks', { params }),
  pipelineStats: (params?: { from?: string; to?: string }) =>
    client.get('/knowledge/pipeline/stats', { params }),

  // Memory Admin
  memoryStats: () => client.get('/memory/stats'),
  listMemoryBases: (params?: { tenant_id?: string; status?: string }) =>
    client.get('/memory/bases', { params }),
  getMemoryBase: (id: string) => client.get(`/memory/bases/${id}`),
  deleteMemoryBase: (id: string) => client.delete(`/memory/bases/${id}`),
  batchDeleteMemoryBases: (ids: string[]) =>
    client.delete('/memory/bases/batch', { data: { ids } }),
  triggerDigest: (id: string) => client.post(`/memory/bases/${id}/digest`),

  // MCP Tool Descriptions
  getMcpDescriptions: () => client.get('/mcp/descriptions'),
  updateMcpDescriptions: (data: { server_instructions: string; tools: { name: string; description: string }[] }) =>
    client.put('/mcp/descriptions', data),

  // Datalake Admin
  datalakeStats: () => client.get('/datalake/stats'),
  listDatalakeJobs: (params?: { tenant_id?: string; status?: string; type?: string }) =>
    client.get('/datalake/jobs', { params }),
  getDatalakeJob: (id: string) => client.get(`/datalake/jobs/${id}`),
  cancelDatalakeJob: (id: string) => client.delete(`/datalake/jobs/${id}`),

  // Warm Pool
  getWarmPoolStatus: () => client.get('/datalake/warm-pool'),

  // Dataset Admin
  listDatasets: (params?: { tenant_id?: string; status?: string }) =>
    client.get('/datalake/datasets', { params }),
  getDataset: (id: string) => client.get(`/datalake/datasets/${id}`),
  deleteDataset: (id: string) => client.delete(`/datalake/datasets/${id}`),

  // Pipeline Admin
  pipelineAdminStats: () => client.get('/pipelines/stats'),
  listAllPipelines: (params?: { tenant_id?: string }) =>
    client.get('/pipelines', { params }),
  listAllPipelineRuns: (params?: { tenant_id?: string; status?: string; pipeline_id?: string }) =>
    client.get('/pipelines/runs', { params }),
  getPipelineRunAdmin: (id: string) => client.get(`/pipelines/runs/${id}`),
  listAllPipelineComponents: () => client.get('/pipelines/components'),

  // Invite Codes
  listInviteCodes: () => client.get('/invite-codes'),
  createInviteCode: (data?: Record<string, number>) => client.post('/invite-codes', data || {}),
  deleteInviteCode: (code: string) => client.delete(`/invite-codes/${code}`),

  // AI Assistant
  aiChat: (messages: Array<{role: string; content: string}>, context?: {resource_type: string; resource_id: string}) => {
    const token = localStorage.getItem('lakeon_admin_token')
    const baseUrl = '/api/v1/admin'
    const directUrl = 'https://api.dbay.cloud:8443/api/v1/admin'

    // Try proxy first, fall back to direct — use fetch for SSE (axios doesn't support streaming)
    return fetch(`${baseUrl}/ai/chat`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ messages, context }),
    }).then(res => {
      if (res.status === 502 || res.status === 503 || res.status === 504) {
        // Retry with direct URL
        return fetch(`${directUrl}/ai/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
          },
          body: JSON.stringify({ messages, context }),
        })
      }
      return res
    })
  },
}
