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

    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
    await flushPromises()
    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
    await flushPromises()
    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
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

    const temporaryRadio = wrapper.find('input[type="radio"][value="TEMPORARY"]')
    await temporaryRadio.setValue()
    await wrapper.find('input[placeholder="例如: 192.168.0.100"]').setValue('10.0.0.5')
    await wrapper.find('input[type="number"]').setValue(5433)
    await wrapper.find('input[placeholder="postgres"]').setValue('appdb')
    const inputs = wrapper.findAll('input.form-input')
    await inputs[3].setValue('dbuser')
    await inputs[4].setValue('secret')

    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
    await flushPromises()
    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
    await flushPromises()
    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
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

    await wrapper.find('input[type="radio"][value="TEMPORARY"]').setValue()
    await wrapper.find('input[placeholder="例如: 192.168.0.100"]').setValue('10.0.0.5')
    await wrapper.find('input[type="number"]').setValue(5432)
    await wrapper.find('input[placeholder="postgres"]').setValue('appdb')
    const inputs = wrapper.findAll('input.form-input')
    await inputs[3].setValue('dbuser')
    await inputs[4].setValue('secret')

    const testButton = wrapper.findAll('button').find(button => button.text() === '测试连接')
    expect(testButton).toBeTruthy()
    await testButton!.trigger('click')
    await flushPromises()

    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
    await flushPromises()

    const temporarySyncRadio = wrapper.find('input[type="radio"][value="SYNC"]')
    expect((temporarySyncRadio.element as HTMLInputElement).disabled).toBe(false)
    await temporarySyncRadio.setValue()

    await wrapper.find('.dialog-footer > .btn-default').trigger('click')
    await flushPromises()
    await wrapper.find('input[type="radio"][value="CONNECTOR"]').setValue()
    await wrapper.find('.dialog-footer .btn-primary').trigger('click')
    await flushPromises()

    const connectorSyncRadio = wrapper.find('input[type="radio"][value="SYNC"]')
    expect((connectorSyncRadio.element as HTMLInputElement).disabled).toBe(true)
  })
})
