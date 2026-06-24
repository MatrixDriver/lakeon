import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DatabaseDetail from '../views/database/DatabaseDetail.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'db_pool' } }),
  useRouter: () => ({ push: vi.fn() }),
  RouterLink: { template: '<a><slot /></a>' },
}))

vi.mock('../api/database', () => ({
  databaseApi: {
    get: vi.fn(),
  },
}))

vi.mock('../api/dbuser', () => ({
  dbuserApi: {},
}))

vi.mock('../api/extension', () => ({
  extensionApi: {},
}))

vi.mock('../api/backup', () => ({
  backupApi: {},
}))

vi.mock('../utils/clipboard', () => ({
  copyToClipboard: vi.fn(),
}))

vi.mock('../composables/useToast', () => ({
  useToast: () => ({
    success: vi.fn(),
    error: vi.fn(),
  }),
}))

import { databaseApi } from '../api/database'

describe('DatabaseDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.location.hash = ''
  })

  it('shows direct and pooled connection strings when API returns both', async () => {
    vi.mocked(databaseApi.get).mockResolvedValue({
      data: {
        id: 'db_pool',
        name: 'appdb',
        status: 'RUNNING',
        connection_uri: 'postgres://user@pg.dbay.cloud:4432/appdb?options=endpoint%3Dappdb',
        pooled_connection_uri: 'postgres://user@pg.dbay.cloud:4432/appdb?options=endpoint%3Dappdb-pooler',
        compute_size: '1cu',
        suspend_timeout: '5m',
        storage_limit_gb: 10,
        storage_used_gb: 0,
        active_connections: 0,
        neon_timeline_id: 'tl1',
        branches: [],
        created_at: '2026-06-25T00:00:00Z',
      },
    } as any)

    const wrapper = mount(DatabaseDetail, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
          RestoreDialog: true,
          CreateUserDialog: true,
        },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Direct')
    expect(wrapper.text()).toContain('Pooled')
    expect(wrapper.text()).toContain('endpoint%3Dappdb')
    expect(wrapper.text()).toContain('endpoint%3Dappdb-pooler')

    wrapper.unmount()
  })
})
