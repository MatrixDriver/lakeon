import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DatabaseList from '../views/databases/DatabaseList.vue'

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('../stores/tenants', () => ({
  useTenantStore: () => ({ load: vi.fn() }),
}))

vi.mock('../api/admin', () => ({
  adminApi: {
    coldStartAnalysis: vi.fn(),
    listDatabases: vi.fn(),
  },
}))

import { adminApi } from '../api/admin'

describe('DatabaseList cold start analysis', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.listDatabases).mockResolvedValue({ data: [] } as any)
  })

  it('shows an error state when cold-start stats API is unavailable', async () => {
    vi.mocked(adminApi.coldStartAnalysis).mockRejectedValue(new Error('No static resource'))

    const wrapper = mount(DatabaseList)
    await flushPromises()

    expect(wrapper.text()).toContain('冷启动统计接口不可用')
    expect(wrapper.text()).not.toContain('最近 7 天没有冷启动发生')
  })
})
