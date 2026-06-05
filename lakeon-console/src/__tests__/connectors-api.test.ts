import { describe, it, expect, vi, beforeEach } from 'vitest'
import { connectorsApi } from '../api/connectors'
import client from '../api/client'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

describe('connectorsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('createPostgres posts connector config and secret fields', async () => {
    const mockResponse = {
      data: {
        id: 'conn_001',
        type: 'POSTGRESQL',
        name: 'production',
        status: 'UNTESTED',
      },
    }
    vi.mocked(client.post).mockResolvedValue(mockResponse)

    const result = await connectorsApi.createPostgres({
      name: 'production',
      host: 'postgres.internal',
      port: 5432,
      dbname: 'app',
      user: 'readonly',
      password: 'secret',
    })

    expect(client.post).toHaveBeenCalledWith('/connectors', {
      type: 'POSTGRESQL',
      name: 'production',
      config: {
        host: 'postgres.internal',
        port: 5432,
        dbname: 'app',
      },
      secret: {
        user: 'readonly',
        password: 'secret',
      },
    })
    expect(result.data.id).toBe('conn_001')
  })
})
