import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DatabaseManager from '../views/database/DatabaseManager.vue'
import { databaseApi } from '../api/database'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'db_1' } }),
  RouterLink: {
    props: ['to'],
    template: '<a><slot /></a>',
  },
}))

vi.mock('../api/database', () => ({
  databaseApi: {
    get: vi.fn(),
  },
}))

describe('DatabaseManager', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(databaseApi.get).mockResolvedValue({
      data: { id: 'db_1', name: 'tpch-bench' },
    })
    Object.defineProperty(window, 'innerHeight', {
      configurable: true,
      value: 900,
    })
  })

  it('expands the SQL editor area when query results are shown', async () => {
    const wrapper = mount(DatabaseManager, {
      global: {
        stubs: {
          'router-link': {
            props: ['to'],
            template: '<a><slot /></a>',
          },
          ObjectTree: true,
          StructureView: true,
          SqlEditor: {
            emits: ['result-state-change'],
            template: '<button class="emit-result" @click="$emit(\'result-state-change\', true)">show result</button>',
          },
        },
      },
    })
    await flushPromises()

    const top = wrapper.get('.content-top')
    expect(top.attributes('style')).toContain('height: 280px')

    await wrapper.get('.emit-result').trigger('click')

    expect(top.attributes('style')).toContain('height: 560px')
  })
})
