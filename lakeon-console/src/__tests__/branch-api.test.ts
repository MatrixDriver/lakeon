import { describe, it, expect, vi, beforeEach } from 'vitest'
import { branchApi } from '../api/branch'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('branchApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('list — GET /databases/{dbId}/branches', async () => {
    const mockResponse = { data: [{ id: 'br_main', name: 'main' }] }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await branchApi.list('db_abc')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/branches')
    expect(result.data[0].name).toBe('main')
  })

  it('create — POST /databases/{dbId}/branches', async () => {
    const mockResponse = { data: { id: 'br_feat', name: 'feature' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await branchApi.create('db_abc', {
      name: 'feature',
      parent_branch_id: 'br_main',
      ancestor_lsn: '0/1000',
    })

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/branches', {
      name: 'feature',
      parent_branch_id: 'br_main',
      ancestor_lsn: '0/1000',
    })
    expect(result.data.name).toBe('feature')
  })

  it('getTree — GET /databases/{dbId}/branches/tree', async () => {
    const mockResponse = {
      data: {
        nodes: [
          { id: 'br_main', name: 'main', parent_branch_id: null, is_default: true },
          { id: 'br_feat', name: 'feature', parent_branch_id: 'br_main', is_default: false },
        ],
      },
    }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await branchApi.getTree('db_abc')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/branches/tree')
    expect(result.data.nodes).toHaveLength(2)
    expect(result.data.nodes[1].parent_branch_id).toBe('br_main')
  })

  it('activate — POST /databases/{dbId}/branches/{branchId}/activate', async () => {
    const mockResponse = { data: { id: 'br_feat', name: 'feature' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await branchApi.activate('db_abc', 'br_feat')

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/branches/br_feat/activate')
    expect(result.data.id).toBe('br_feat')
  })

  it('delete — DELETE /databases/{dbId}/branches/{branchId}', async () => {
    vi.mocked(client.delete).mockResolvedValue({})

    await branchApi.delete('db_abc', 'br_feat')

    expect(client.delete).toHaveBeenCalledWith('/databases/db_abc/branches/br_feat')
  })
})
