import { describe, it, expect, vi, beforeEach } from 'vitest'
import { diffApi } from '../api/diff'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('diffApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('schema — GET /databases/{dbId}/diff/schema with query params', async () => {
    const mockResponse = {
      data: {
        tables: { added: [], removed: [], modified: [] },
        indexes: { added: [], removed: [] },
      },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await diffApi.schema('db_abc', 'branch', 'br_main', 'branch', 'br_feat')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/diff/schema', {
      params: {
        source_type: 'branch',
        source_id: 'br_main',
        target_type: 'branch',
        target_id: 'br_feat',
      },
    })
    expect(result.data.tables.added).toHaveLength(0)
  })

  it('schema — passes correct source_type and target_type for version comparison', async () => {
    const mockResponse = {
      data: {
        tables: {
          added: [{ name: 'users', schema: 'public', columns: [] }],
          removed: [],
          modified: [],
        },
        indexes: { added: [], removed: [] },
      },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await diffApi.schema('db_abc', 'version', 'v_1', 'version', 'v_2')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/diff/schema', {
      params: {
        source_type: 'version',
        source_id: 'v_1',
        target_type: 'version',
        target_id: 'v_2',
      },
    })
    expect(result.data.tables.added[0].name).toBe('users')
  })
})
