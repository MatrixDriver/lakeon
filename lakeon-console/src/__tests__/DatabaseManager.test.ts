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

  it('keeps the SQL editor compact and moves query results into the bottom workspace', async () => {
    const wrapper = mount(DatabaseManager, {
      global: {
        stubs: {
          'router-link': {
            props: ['to'],
            template: '<a><slot /></a>',
          },
          ObjectTree: {
            emits: ['select', 'schema-loaded'],
            template: '<button class="emit-select" @click="$emit(\'select\', \'public\', \'customer\')">select table</button>',
          },
          StructureView: true,
          SqlEditor: {
            emits: ['query-result'],
            template: `
              <button
                class="emit-result"
                @click="$emit('query-result', {
                  result: {
                    columns: ['count'],
                    rows: [[15000]],
                    row_count: 1,
                    execution_time_ms: 1,
                    is_select: true
                  },
                  error: ''
                })"
              >show result</button>
            `,
          },
        },
      },
    })
    await flushPromises()

    const top = wrapper.get('.content-top')
    expect(top.attributes('style')).toContain('height: 220px')
    expect(wrapper.text()).toContain('查询结果')
    expect(wrapper.text()).toContain('表数据预览')
    expect(wrapper.text()).toContain('表结构')

    await wrapper.get('.emit-result').trigger('click')

    expect(top.attributes('style')).toContain('height: 220px')
    expect(wrapper.find('.tab-button.active').text()).toBe('查询结果')
    expect(wrapper.text()).toContain('15000')

    await wrapper.get('.emit-select').trigger('click')

    expect(wrapper.find('.tab-button.active').text()).toBe('表数据预览')
    expect(wrapper.text()).toContain('public.customer')
  })
})
