import { describe, it, expect, vi, beforeEach } from 'vitest'
import { adminApi } from '../api/admin'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('adminApi — audit', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('auditLogs — GET /audit/logs with no params', async () => {
    const mockResponse = { data: { data: [], total: 0, total_pages: 0 } }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await adminApi.auditLogs()

    expect(client.get).toHaveBeenCalledWith('/audit/logs', { params: undefined })
    expect(result.data.total).toBe(0)
  })

  it('auditLogs — GET /audit/logs with filters', async () => {
    const mockResponse = {
      data: { data: [{ id: 'al_1', statement_type: 'DDL' }], total: 1, total_pages: 1 },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const params = { tenant_id: 'tn_001', type: 'DDL', page: 0, size: 20 }
    const result = await adminApi.auditLogs(params)

    expect(client.get).toHaveBeenCalledWith('/audit/logs', { params })
    expect(result.data.data).toHaveLength(1)
  })
})

describe('adminApi — tenants', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listTenants — GET /tenants', async () => {
    const mockResponse = { data: [{ id: 'tn_1' }] }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await adminApi.listTenants()

    expect(client.get).toHaveBeenCalledWith('/tenants')
    expect(result.data).toHaveLength(1)
  })

  it('disableTenant — POST /tenants/{id}/disable', async () => {
    vi.mocked(client.post).mockResolvedValue({})
    await adminApi.disableTenant('tn_1')
    expect(client.post).toHaveBeenCalledWith('/tenants/tn_1/disable')
  })

  it('updateQuota — PUT /tenants/{id}/quota', async () => {
    vi.mocked(client.put).mockResolvedValue({})
    await adminApi.updateQuota('tn_1', { max_databases: 10 })
    expect(client.put).toHaveBeenCalledWith('/tenants/tn_1/quota', { max_databases: 10 })
  })
})
