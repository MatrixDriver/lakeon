import { describe, it, expect, vi, beforeEach } from 'vitest'
import client from '../api/client'
import { cdfApi } from '../api/cdf'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

describe('cdfApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists CDF streams for a database', async () => {
    vi.mocked(client.get).mockResolvedValue({
      data: [
        {
          id: 'cdf_1',
          source_schema: 'public',
          source_table: 'orders',
          target_namespace: 'public',
          target_table: 'orders_cdf',
          status: 'RUNNING',
        },
      ],
    })

    const result = await cdfApi.listStreams('db_123')

    expect(client.get).toHaveBeenCalledWith('/databases/db_123/cdf-streams')
    expect(result.data[0].target_table).toBe('orders_cdf')
  })

  it('creates a CDF stream with initial backfill enabled', async () => {
    vi.mocked(client.post).mockResolvedValue({
      data: {
        id: 'cdf_2',
        status: 'PAUSED',
      },
    })

    const result = await cdfApi.createStream('db_123', {
      database_id: 'db_123',
      branch_id: 'br_main',
      source_schema: 'public',
      source_table: 'orders',
      target_namespace: 'public',
      target_table: 'orders_cdf',
      mode: 'APPEND_CHANGELOG',
      initial_backfill: true,
    })

    expect(client.post).toHaveBeenCalledWith('/databases/db_123/cdf-streams', {
      database_id: 'db_123',
      branch_id: 'br_main',
      source_schema: 'public',
      source_table: 'orders',
      target_namespace: 'public',
      target_table: 'orders_cdf',
      mode: 'APPEND_CHANGELOG',
      initial_backfill: true,
    })
    expect(result.data.status).toBe('PAUSED')
  })

  it('controls stream lifecycle and export materialization', async () => {
    vi.mocked(client.post).mockResolvedValue({ data: { status: 'RUNNING' } })
    vi.mocked(client.get).mockResolvedValue({ data: { status: 'MATERIALIZED' } })

    await cdfApi.resumeStream('db_123', 'cdf_1')
    await cdfApi.pauseStream('db_123', 'cdf_1')
    await cdfApi.materializeExport('db_123', 'cdf_1')
    const exportStatus = await cdfApi.getExportStatus('db_123', 'cdf_1')

    expect(client.post).toHaveBeenNthCalledWith(1, '/databases/db_123/cdf-streams/cdf_1/resume')
    expect(client.post).toHaveBeenNthCalledWith(2, '/databases/db_123/cdf-streams/cdf_1/pause')
    expect(client.post).toHaveBeenNthCalledWith(3, '/databases/db_123/cdf-streams/cdf_1/export')
    expect(client.get).toHaveBeenCalledWith('/databases/db_123/cdf-streams/cdf_1/export')
    expect(exportStatus.data.status).toBe('MATERIALIZED')
  })
})
