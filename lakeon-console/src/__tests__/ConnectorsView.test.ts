import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ConnectorsView from '../views/connectors/ConnectorsView.vue'
import router from '../router'
import { connectorsApi } from '../api/connectors'

vi.mock('../api/connectors', () => ({
  connectorsApi: {
    list: vi.fn(),
    createPostgres: vi.fn(),
    test: vi.fn(),
  },
}))

const connectors = [
  {
    id: 'pg_1',
    type: 'POSTGRESQL',
    name: '生产 PostgreSQL',
    status: 'CONNECTED',
    config: { host: 'pg.internal', port: 5432, dbname: 'app' },
    target_summary: 'pg.internal:5432/app',
    last_tested_at: '2026-06-04T12:00:00Z',
    last_error: null,
    created_at: '2026-06-04T11:00:00Z',
    updated_at: '2026-06-04T12:00:00Z',
    usage_count: 3,
    usage_hint: '迁移任务使用中',
  },
  {
    id: 'obs_1',
    type: 'OBS',
    name: '归档 OBS',
    status: 'UNTESTED',
    config: {},
    target_summary: 'obs://bucket/raw',
    last_tested_at: null,
    last_error: null,
    created_at: '2026-06-04T11:00:00Z',
    updated_at: '2026-06-04T11:00:00Z',
    usage_count: 0,
    usage_hint: null,
  },
]

describe('ConnectorsView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(connectorsApi.list).mockResolvedValue({ data: connectors } as any)
    vi.mocked(connectorsApi.createPostgres).mockResolvedValue({ data: connectors[0] } as any)
    vi.mocked(connectorsApi.test).mockResolvedValue({ data: { ok: true, error: null, metadata: {} } } as any)
  })

  it('loads and renders PostgreSQL and OBS connectors', async () => {
    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    expect(connectorsApi.list).toHaveBeenCalled()
    expect(wrapper.text()).toContain('生产 PostgreSQL')
    expect(wrapper.text()).toContain('归档 OBS')
    expect(wrapper.text()).toContain('PostgreSQL')
    expect(wrapper.text()).toContain('OBS')
    expect(wrapper.text()).toContain('pg.internal:5432/app')
    expect(wrapper.text()).toContain('obs://bucket/raw')
  })

  it('creates a PostgreSQL connector and reloads the list', async () => {
    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    await wrapper.find('[data-test="open-create-postgres"]').trigger('click')
    await wrapper.find('[data-test="pg-name"]').setValue('新生产库')
    await wrapper.find('[data-test="pg-host"]').setValue('db.internal')
    await wrapper.find('[data-test="pg-port"]').setValue('5433')
    await wrapper.find('[data-test="pg-dbname"]').setValue('orders')
    await wrapper.find('[data-test="pg-user"]').setValue('reader')
    await wrapper.find('[data-test="pg-password"]').setValue('secret')
    await wrapper.find('[data-test="save-postgres"]').trigger('submit')
    await flushPromises()

    expect(connectorsApi.createPostgres).toHaveBeenCalledWith({
      name: '新生产库',
      host: 'db.internal',
      port: 5433,
      dbname: 'orders',
      user: 'reader',
      password: 'secret',
    })
    expect(connectorsApi.list).toHaveBeenCalledTimes(2)
  })

  it('tests only PostgreSQL connectors', async () => {
    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    const pgTestButton = wrapper.find('[data-test="test-pg_1"]')
    const obsTestButton = wrapper.find('[data-test="test-obs_1"]')
    expect(obsTestButton.attributes('disabled')).toBeDefined()

    await pgTestButton.trigger('click')
    await flushPromises()

    expect(connectorsApi.test).toHaveBeenCalledWith('pg_1')
    expect(connectorsApi.list).toHaveBeenCalledTimes(2)
  })

  it('disables all PostgreSQL test buttons while one test is pending', async () => {
    const multiPostgresConnectors = [
      connectors[0],
      {
        ...connectors[0],
        id: 'pg_2',
        name: '分析 PostgreSQL',
        target_summary: 'analytics.internal:5432/warehouse',
      },
      connectors[1],
    ]
    let resolveTest: (value: { data: { ok: boolean; error: null; metadata: Record<string, unknown> } }) => void = () => {}
    const pendingTest = new Promise<{ data: { ok: boolean; error: null; metadata: Record<string, unknown> } }>((resolve) => {
      resolveTest = resolve
    })
    vi.mocked(connectorsApi.list).mockResolvedValue({ data: multiPostgresConnectors } as any)
    vi.mocked(connectorsApi.test).mockReturnValue(pendingTest as any)

    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    const firstPgTestButton = wrapper.find('[data-test="test-pg_1"]')
    const secondPgTestButton = wrapper.find('[data-test="test-pg_2"]')
    expect(firstPgTestButton.attributes('disabled')).toBeUndefined()
    expect(secondPgTestButton.attributes('disabled')).toBeUndefined()

    await firstPgTestButton.trigger('click')
    expect(connectorsApi.test).toHaveBeenCalledTimes(1)
    expect(connectorsApi.test).toHaveBeenCalledWith('pg_1')
    expect(secondPgTestButton.attributes('disabled')).toBeDefined()

    await secondPgTestButton.trigger('click')
    expect(connectorsApi.test).toHaveBeenCalledTimes(1)

    resolveTest({ data: { ok: true, error: null, metadata: {} } })
    await flushPromises()

    expect(firstPgTestButton.attributes('disabled')).toBeUndefined()
    expect(secondPgTestButton.attributes('disabled')).toBeUndefined()
  })

  it('shows and preserves ok=false test errors after reloading connectors', async () => {
    vi.mocked(connectorsApi.test).mockResolvedValue({
      data: { ok: false, error: '认证失败', metadata: {} },
    } as any)

    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    await wrapper.find('[data-test="test-pg_1"]').trigger('click')
    await flushPromises()

    expect(connectorsApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-banner').text()).toBe('认证失败')
  })

  it('shows and preserves thrown test errors after reloading connectors', async () => {
    vi.mocked(connectorsApi.test).mockRejectedValue({
      response: { data: { error: { message: '网络不可达' } } },
    })

    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    await wrapper.find('[data-test="test-pg_1"]').trigger('click')
    await flushPromises()

    expect(connectorsApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-banner').text()).toBe('网络不可达')
  })

  it('disables save for ports outside the PostgreSQL range or fractional ports', async () => {
    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    await wrapper.find('[data-test="open-create-postgres"]').trigger('click')
    await wrapper.find('[data-test="pg-name"]').setValue('新生产库')
    await wrapper.find('[data-test="pg-host"]').setValue('db.internal')
    await wrapper.find('[data-test="pg-dbname"]').setValue('orders')
    await wrapper.find('[data-test="pg-user"]').setValue('reader')
    await wrapper.find('[data-test="pg-password"]').setValue('secret')

    const saveButton = wrapper.find('[data-test="save-postgres"] button[type="submit"]')
    await wrapper.find('[data-test="pg-port"]').setValue('65536')
    expect(saveButton.attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="pg-port"]').setValue('5432.5')
    expect(saveButton.attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="pg-port"]').setValue('5432')
    expect(saveButton.attributes('disabled')).toBeUndefined()
  })

  it('renders mobile row labels in connector cells', async () => {
    const wrapper = mount(ConnectorsView, {
      global: {
        stubs: { RouterLink: true },
      },
    })
    await flushPromises()

    const labels = wrapper.findAll('[data-label]').map((cell) => cell.attributes('data-label'))
    expect(labels).toContain('类型')
    expect(labels).toContain('状态')
    expect(labels).toContain('目标')
    expect(labels).toContain('使用')
    expect(labels).toContain('最近测试')
    expect(labels).toContain('操作')
  })
})

describe('Connectors route', () => {
  it('redirects the legacy OBS connections route to connectors', async () => {
    localStorage.setItem('lakeon_api_key', 'test-key')
    await router.push('/datalake/connections')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/connectors')
    expect(router.currentRoute.value.name).toBe('Connectors')
    localStorage.removeItem('lakeon_api_key')
  })

  it('exposes /connectors as a console route', () => {
    const testRouter = createRouter({
      history: createMemoryHistory(),
      routes: router.getRoutes(),
    })

    const resolved = testRouter.resolve('/connectors')
    expect(resolved.name).toBe('Connectors')
  })
})
