import { describe, it, expect, vi, beforeEach } from 'vitest'
import { auditApi } from '../api/audit'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
  },
}))

describe('auditApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('getConfig — GET /databases/{dbId}/audit/config', async () => {
    const mockResponse = {
      data: {
        id: 'ak_001',
        database_id: 'db_abc',
        enabled: true,
        log_ddl: true,
        log_dml: false,
        log_select: false,
        retention_days: 30,
      },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await auditApi.getConfig('db_abc')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/audit/config')
    expect(result.data.id).toBe('ak_001')
    expect(result.data.enabled).toBe(true)
  })

  it('updateConfig — PUT /databases/{dbId}/audit/config', async () => {
    const mockResponse = {
      data: {
        id: 'ak_001',
        database_id: 'db_abc',
        enabled: true,
        log_ddl: true,
        log_dml: true,
        log_select: false,
        retention_days: 60,
      },
    }
    vi.mocked(client.put).mockResolvedValue(mockResponse)

    const result = await auditApi.updateConfig('db_abc', {
      enabled: true,
      log_dml: true,
      retention_days: 60,
    })

    expect(client.put).toHaveBeenCalledWith('/databases/db_abc/audit/config', {
      enabled: true,
      log_dml: true,
      retention_days: 60,
    })
    expect(result.data.log_dml).toBe(true)
    expect(result.data.retention_days).toBe(60)
  })

  it('getLogs — GET /databases/{dbId}/audit/logs', async () => {
    const mockResponse = {
      data: {
        data: [
          { id: 'al_001', statement_type: 'DDL', statement: 'CREATE TABLE users' },
          { id: 'al_002', statement_type: 'SELECT', statement: 'SELECT * FROM users' },
        ],
        total: 2,
        page: 0,
        total_pages: 1,
      },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await auditApi.getLogs('db_abc')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/audit/logs', { params: undefined })
    expect(result.data.data).toHaveLength(2)
    expect(result.data.total).toBe(2)
  })

  it('getLogs with type filter — GET /databases/{dbId}/audit/logs?type=DDL', async () => {
    const mockResponse = {
      data: {
        data: [{ id: 'al_001', statement_type: 'DDL' }],
        total: 1,
        page: 0,
        total_pages: 1,
      },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await auditApi.getLogs('db_abc', { type: 'DDL', page: 0, size: 20 })

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/audit/logs', {
      params: { type: 'DDL', page: 0, size: 20 },
    })
    expect(result.data.data).toHaveLength(1)
  })
})
