import { describe, it, expect, vi, beforeEach } from 'vitest'
import { dbuserApi } from '../api/dbuser'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('dbuserApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('createUser — POST /databases/{dbId}/users', async () => {
    const mockResponse = { data: { id: 'du_001', username: 'reader1', role: 'READER', password: 'abc123' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await dbuserApi.createUser('db_abc', { username: 'reader1', role: 'READER' })

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/users', { username: 'reader1', role: 'READER' })
    expect(result.data.id).toBe('du_001')
    expect(result.data.password).toBe('abc123')
  })

  it('listUsers — GET /databases/{dbId}/users', async () => {
    const mockResponse = { data: [{ id: 'du_001' }, { id: 'du_002' }] }
    vi.mocked(client.get).mockResolvedValue(mockResponse)

    const result = await dbuserApi.listUsers('db_abc')

    expect(client.get).toHaveBeenCalledWith('/databases/db_abc/users')
    expect(result.data).toHaveLength(2)
  })

  it('updateRole — PUT /databases/{dbId}/users/{userId}/role', async () => {
    const mockResponse = { data: { id: 'du_001', role: 'WRITER' } }
    vi.mocked(client.put).mockResolvedValue(mockResponse)

    const result = await dbuserApi.updateRole('db_abc', 'du_001', { role: 'WRITER' })

    expect(client.put).toHaveBeenCalledWith('/databases/db_abc/users/du_001/role', { role: 'WRITER' })
    expect(result.data.role).toBe('WRITER')
  })

  it('deleteUser — DELETE /databases/{dbId}/users/{userId}', async () => {
    vi.mocked(client.delete).mockResolvedValue({})

    await dbuserApi.deleteUser('db_abc', 'du_001')

    expect(client.delete).toHaveBeenCalledWith('/databases/db_abc/users/du_001')
  })

  it('resetPassword — POST /databases/{dbId}/users/{userId}/reset-password', async () => {
    const mockResponse = { data: { password: 'newpass123' } }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await dbuserApi.resetPassword('db_abc', 'du_001')

    expect(client.post).toHaveBeenCalledWith('/databases/db_abc/users/du_001/reset-password')
    expect(result.data.password).toBe('newpass123')
  })
})
