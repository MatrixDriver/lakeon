import { describe, it, expect, vi, beforeEach } from 'vitest'
import { backupApi } from '../api/backup'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('backupApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('create — POST /databases/{dbId}/backups', async () => {
    const mockResponse = { data: { id: 'bk_001', name: 'my-backup', status: 'COMPLETED' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await backupApi.create('db_abc', { name: 'my-backup' })

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/backups', { name: 'my-backup' })
    expect(result.data.id).toBe('bk_001')
  })

  it('list — GET /databases/{dbId}/backups', async () => {
    const mockResponse = { data: [{ id: 'bk_001' }, { id: 'bk_002' }] }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await backupApi.list('db_abc')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/backups')
    expect(result.data).toHaveLength(2)
  })

  it('get — GET /databases/{dbId}/backups/{backupId}', async () => {
    const mockResponse = { data: { id: 'bk_001', name: 'my-backup' } }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await backupApi.get('db_abc', 'bk_001')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/backups/bk_001')
    expect(result.data.id).toBe('bk_001')
  })

  it('restore — POST /databases/{dbId}/backups/{backupId}/restore', async () => {
    const mockResponse = { data: { id: 'db_restored', name: 'restored-db', status: 'SUSPENDED' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await backupApi.restore('db_abc', 'bk_001', { name: 'restored-db' })

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/backups/bk_001/restore', { name: 'restored-db' })
    expect(result.data.status).toBe('SUSPENDED')
  })

  it('delete — DELETE /databases/{dbId}/backups/{backupId}', async () => {
    vi.mocked(client.delete).mockResolvedValue({})

    await backupApi.delete('db_abc', 'bk_001')

    expect(client.delete).toHaveBeenCalledWith('/databases/db_abc/backups/bk_001')
  })
})
