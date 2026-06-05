import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ImportWizard from '../views/database/ImportWizard.vue'
import { connectorsApi } from '../api/connectors'
import { importApi } from '../api/import'

vi.mock('../api/connectors', () => ({
  connectorsApi: {
    list: vi.fn(),
    listPostgresTables: vi.fn(),
  },
}))

vi.mock('../api/import', () => ({
  importApi: {
    testConnection: vi.fn(),
    listSourceTables: vi.fn(),
    create: vi.fn(),
  },
}))

const pgConnector = {
  id: 'conn-pg-1',
  type: 'POSTGRESQL',
  name: '生产 PostgreSQL',
  status: 'CONNECTED',
  config: {},
  target_summary: 'prod.internal:5432/app',
  last_tested_at: null,
  last_error: null,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
  usage_count: 0,
  usage_hint: null,
}

function mountWizard() {
  return mount(ImportWizard, {
    props: {
      dbId: 'target-db-1',
      visible: true,
    },
  })
}

function byTest(wrapper: ReturnType<typeof mountWizard>, testId: string) {
  return wrapper.find(`[data-test="${testId}"]`)
}

async function chooseTemporary(wrapper: ReturnType<typeof mountWizard>) {
  await byTest(wrapper, 'source-mode-temporary').setValue()
}

async function chooseConnector(wrapper: ReturnType<typeof mountWizard>) {
  await byTest(wrapper, 'source-mode-connector').setValue()
}

async function fillManualConnection(wrapper: ReturnType<typeof mountWizard>, host = '10.0.0.5') {
  await byTest(wrapper, 'manual-host').setValue(host)
  await byTest(wrapper, 'manual-port').setValue(5432)
  await byTest(wrapper, 'manual-dbname').setValue('appdb')
  await byTest(wrapper, 'manual-user').setValue('dbuser')
  await byTest(wrapper, 'manual-password').setValue('secret')
}

async function clickNext(wrapper: ReturnType<typeof mountWizard>) {
  await byTest(wrapper, 'wizard-next').trigger('click')
  await flushPromises()
}

describe('ImportWizard connector mode', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(connectorsApi.list).mockResolvedValue({ data: [pgConnector] } as any)
    vi.mocked(connectorsApi.listPostgresTables).mockResolvedValue({
      data: [{ schema: 'public', table: 'users', estimated_rows: 12 }],
    } as any)
    vi.mocked(importApi.listSourceTables).mockResolvedValue({
      data: [{ schema: 'public', table: 'orders', estimated_rows: 3 }],
    } as any)
    vi.mocked(importApi.create).mockResolvedValue({ data: { id: 'task-1' } } as any)
  })

  it('shows connector and temporary connection choices with the default PostgreSQL connector', async () => {
    const wrapper = mountWizard()
    await flushPromises()

    expect(wrapper.text()).toContain('选择连接器')
    expect(wrapper.text()).toContain('生产 PostgreSQL')
    expect(wrapper.text()).toContain('临时连接')
  })

  it('creates connector imports with connectorId and no sourcePassword', async () => {
    const wrapper = mountWizard()
    await flushPromises()

    await clickNext(wrapper)
    await clickNext(wrapper)
    await byTest(wrapper, 'wizard-create').trigger('click')
    await flushPromises()

    expect(importApi.create).toHaveBeenCalledWith('target-db-1', expect.objectContaining({
      connectorId: 'conn-pg-1',
      mode: 'FULL',
      conflictStrategy: 'APPEND',
    }))
    expect(vi.mocked(importApi.create).mock.calls[0][1]).not.toHaveProperty('sourcePassword')
  })

  it('keeps temporary connection imports using the manual source payload', async () => {
    const wrapper = mountWizard()
    await flushPromises()

    await chooseTemporary(wrapper)
    await fillManualConnection(wrapper, '10.0.0.5')
    await byTest(wrapper, 'manual-port').setValue(5433)

    await clickNext(wrapper)
    await clickNext(wrapper)
    await byTest(wrapper, 'wizard-create').trigger('click')
    await flushPromises()

    expect(importApi.create).toHaveBeenCalledWith('target-db-1', expect.objectContaining({
      sourceHost: '10.0.0.5',
      sourcePort: 5433,
      sourceDbname: 'appdb',
      sourceUser: 'dbuser',
      sourcePassword: 'secret',
      mode: 'FULL',
      conflictStrategy: 'APPEND',
    }))
  })

  it('does not inherit temporary sync availability when switching back to connector mode', async () => {
    vi.mocked(importApi.testConnection).mockResolvedValue({
      data: { ok: true, wal_level: 'logical', has_replication: true },
    } as any)

    const wrapper = mountWizard()
    await flushPromises()

    await chooseTemporary(wrapper)
    await fillManualConnection(wrapper)

    await byTest(wrapper, 'test-connection').trigger('click')
    await flushPromises()

    await clickNext(wrapper)

    const temporarySyncRadio = byTest(wrapper, 'import-mode-sync')
    expect((temporarySyncRadio.element as HTMLInputElement).disabled).toBe(false)
    await temporarySyncRadio.setValue()

    await byTest(wrapper, 'wizard-back').trigger('click')
    await flushPromises()
    await chooseConnector(wrapper)
    await clickNext(wrapper)

    const connectorSyncRadio = byTest(wrapper, 'import-mode-sync')
    expect((connectorSyncRadio.element as HTMLInputElement).disabled).toBe(true)
  })

  it('clears temporary sync availability when manual source fields change', async () => {
    vi.mocked(importApi.testConnection).mockResolvedValue({
      data: { ok: true, wal_level: 'logical', has_replication: true },
    } as any)

    const wrapper = mountWizard()
    await flushPromises()

    await chooseTemporary(wrapper)
    await fillManualConnection(wrapper)
    await byTest(wrapper, 'test-connection').trigger('click')
    await flushPromises()
    await clickNext(wrapper)

    expect((byTest(wrapper, 'import-mode-sync').element as HTMLInputElement).disabled).toBe(false)

    await byTest(wrapper, 'wizard-back').trigger('click')
    await flushPromises()
    await byTest(wrapper, 'manual-host').setValue('10.0.0.6')
    await clickNext(wrapper)

    expect((byTest(wrapper, 'import-mode-sync').element as HTMLInputElement).disabled).toBe(true)
  })

  it('resets stale sync mode and selected tables when the wizard reopens', async () => {
    vi.mocked(importApi.testConnection).mockResolvedValue({
      data: { ok: true, wal_level: 'logical', has_replication: true },
    } as any)

    const wrapper = mountWizard()
    await flushPromises()

    await chooseTemporary(wrapper)
    await fillManualConnection(wrapper)
    await byTest(wrapper, 'test-connection').trigger('click')
    await flushPromises()
    await clickNext(wrapper)
    await byTest(wrapper, 'import-mode-sync').setValue()
    await byTest(wrapper, 'table-checkbox-public-orders').setValue(true)

    await wrapper.setProps({ visible: false })
    await flushPromises()
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await clickNext(wrapper)
    await clickNext(wrapper)
    await byTest(wrapper, 'wizard-create').trigger('click')
    await flushPromises()

    expect(importApi.create).toHaveBeenCalledWith('target-db-1', expect.objectContaining({
      mode: 'FULL',
      conflictStrategy: 'APPEND',
      tables: undefined,
    }))
  })

  it('shows temporary table load failures on the connection step', async () => {
    vi.mocked(importApi.listSourceTables).mockRejectedValue({
      response: { data: { error: { message: '源表加载失败' } } },
    })

    const wrapper = mountWizard()
    await flushPromises()

    await chooseTemporary(wrapper)
    await fillManualConnection(wrapper)
    await clickNext(wrapper)

    expect(byTest(wrapper, 'connection-error').text()).toContain('源表加载失败')
  })

  it('shows connector table load failures on the connection step', async () => {
    vi.mocked(connectorsApi.listPostgresTables).mockRejectedValue({
      response: { data: { error: { message: '连接器表加载失败' } } },
    })

    const wrapper = mountWizard()
    await flushPromises()

    await clickNext(wrapper)

    expect(byTest(wrapper, 'connection-error').text()).toContain('连接器表加载失败')
  })
})
