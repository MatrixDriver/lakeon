import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ImportEntry from '../views/import/ImportEntry.vue'
import { databaseApi } from '../api/database'
import { importApi } from '../api/import'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  RouterLink: {
    props: ['to'],
    template: '<a><slot /></a>',
  },
}))

vi.mock('../api/database', () => ({
  databaseApi: {
    list: vi.fn(),
  },
}))

vi.mock('../api/import', () => ({
  importApi: {
    list: vi.fn(),
    pause: vi.fn(),
    resume: vi.fn(),
    cancel: vi.fn(),
    stop: vi.fn(),
    retry: vi.fn(),
  },
}))

function makeDatabase(id: string, name: string) {
  return {
    id,
    name,
    status: 'RUNNING',
    connection_uri: '',
    compute_size: '1x',
    suspend_timeout: '5m',
    storage_limit_gb: 10,
    storage_used_gb: 0,
    active_connections: 0,
    neon_timeline_id: '',
    branches: [],
    created_at: '2026-06-30T00:00:00Z',
  }
}

describe('ImportEntry', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows database choices before per-database import task counts finish loading', async () => {
    vi.mocked(databaseApi.list).mockResolvedValue({
      data: [
        makeDatabase('db_1', 'orders'),
        makeDatabase('db_2', 'billing'),
      ],
    })
    vi.mocked(importApi.list).mockReturnValue(new Promise(() => {}))

    const wrapper = mount(ImportEntry, {
      global: {
        stubs: {
          'router-link': {
            props: ['to'],
            template: '<a><slot /></a>',
          },
          ImportWizard: true,
          ImportTaskDetail: true,
        },
      },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('选择目标数据库')
    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).toContain('billing')
    expect(wrapper.text()).not.toContain('加载中')
  })
})
