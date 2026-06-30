import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import LakebaseCdfList from '../views/cdf/LakebaseCdfList.vue'
import { databaseApi } from '../api/database'
import { cdfApi } from '../api/cdf'

vi.mock('../api/database', () => ({
  databaseApi: {
    list: vi.fn(),
  },
}))

vi.mock('../api/cdf', () => ({
  cdfApi: {
    listStreams: vi.fn(),
    createStream: vi.fn(),
    resumeStream: vi.fn(),
    pauseStream: vi.fn(),
    materializeExport: vi.fn(),
  },
}))

describe('LakebaseCdfList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('explains why the create button is disabled when the source table is empty', async () => {
    vi.mocked(databaseApi.list).mockResolvedValue({
      data: [
        {
          id: 'db_1',
          name: 'tpch-bench',
          status: 'SUSPENDED',
          connection_uri: '',
          compute_size: '1x',
          suspend_timeout: '5m',
          storage_limit_gb: 10,
          storage_used_gb: 0,
          active_connections: 0,
          neon_timeline_id: '',
          branches: [{ id: 'br_main', name: 'main', is_default: true, status: 'READY', compute_status: 'SUSPENDED' }],
          created_at: '2026-06-30T00:00:00Z',
        },
      ],
    })
    vi.mocked(cdfApi.listStreams).mockResolvedValue({ data: [] })

    const wrapper = mount(LakebaseCdfList)
    await flushPromises()

    const createButton = wrapper.get('button.create-submit')
    expect(createButton.attributes('disabled')).toBeDefined()
    expect(createButton.text()).toBe('填写源表后创建')
    expect(wrapper.get('#cdf-source-table').attributes('placeholder')).toBe('输入源表名')
  })
})
