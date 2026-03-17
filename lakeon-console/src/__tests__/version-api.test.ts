import { describe, it, expect, vi, beforeEach } from 'vitest'
import { versionApi } from '../api/version'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('versionApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('list — GET /databases/{dbId}/branches/{branchId}/versions', async () => {
    const mockResponse = { data: [{ id: 'v_1', name: 'v1.0', branch_id: 'br_main' }] }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await versionApi.list('db_abc', 'br_main')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/branches/br_main/versions')
    expect(result.data[0].name).toBe('v1.0')
  })

  it('create — POST /databases/{dbId}/branches/{branchId}/versions', async () => {
    const mockResponse = { data: { id: 'v_2', name: 'v2.0', branch_id: 'br_main' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await versionApi.create('db_abc', 'br_main', {
      name: 'v2.0',
      description: 'Second version',
    })

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/branches/br_main/versions', {
      name: 'v2.0',
      description: 'Second version',
    })
    expect(result.data.name).toBe('v2.0')
  })

  it('get — GET /databases/{dbId}/branches/{branchId}/versions/{versionId}', async () => {
    const mockResponse = { data: { id: 'v_1', name: 'v1.0', branch_id: 'br_main' } }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await versionApi.get('db_abc', 'br_main', 'v_1')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/branches/br_main/versions/v_1')
    expect(result.data.id).toBe('v_1')
  })

  it('delete — DELETE /databases/{dbId}/branches/{branchId}/versions/{versionId}', async () => {
    vi.mocked(client.delete).mockResolvedValue({})

    await versionApi.delete('db_abc', 'br_main', 'v_1')

    expect(client.delete).toHaveBeenCalledWith('/databases/db_abc/branches/br_main/versions/v_1')
  })

  it('squash — POST /databases/{dbId}/branches/{branchId}/versions/squash', async () => {
    const mockResponse = { data: [{ id: 'v_squashed', name: 'squashed', branch_id: 'br_main' }] }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await versionApi.squash('db_abc', 'br_main', {
      from_version_id: 'v_1',
      to_version_id: 'v_3',
    })

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/branches/br_main/versions/squash', {
      from_version_id: 'v_1',
      to_version_id: 'v_3',
    })
    expect(result.data[0].name).toBe('squashed')
  })
})
