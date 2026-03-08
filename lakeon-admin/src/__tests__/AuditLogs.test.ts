import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AuditLogs from '../views/AuditLogs.vue'

// Mock the admin API
vi.mock('../api/admin', () => ({
  adminApi: {
    auditLogs: vi.fn(),
  },
}))

// Mock format
vi.mock('../utils/format', () => ({
  formatDate: (s: string) => s || '-',
}))

import { adminApi } from '../api/admin'

const mockLogsPage = {
  data: {
    data: [
      {
        id: 'al_1',
        database_id: 'db_abc',
        tenant_id: 'tn_001',
        timestamp: '2026-03-08T10:00:00Z',
        user_name: 'admin',
        statement: 'CREATE TABLE test (id INT)',
        statement_type: 'DDL',
        object_name: 'test',
        client_addr: '127.0.0.1',
        duration: 15,
      },
      {
        id: 'al_2',
        database_id: 'db_abc',
        tenant_id: 'tn_001',
        timestamp: '2026-03-08T10:01:00Z',
        user_name: 'app',
        statement: 'INSERT INTO test VALUES (1)',
        statement_type: 'DML',
        object_name: 'test',
        client_addr: '127.0.0.1',
        duration: 3,
      },
      {
        id: 'al_3',
        database_id: 'db_xyz',
        tenant_id: 'tn_002',
        timestamp: '2026-03-08T10:02:00Z',
        user_name: null,
        statement: 'SELECT * FROM users',
        statement_type: 'SELECT',
        object_name: 'users',
        client_addr: null,
        duration: null,
      },
    ],
    total: 3,
    total_pages: 1,
  },
}

describe('AuditLogs', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders page title', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    expect(wrapper.find('.page-title').text()).toBe('SQL 审计日志')
  })

  it('loads and displays audit logs on mount', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    expect(adminApi.auditLogs).toHaveBeenCalledWith({ page: 0, size: 20 })
    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(3)
  })

  it('displays log data correctly', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    const firstRow = wrapper.findAll('tbody tr')[0]
    const cells = firstRow.findAll('td')
    expect(cells[1].text()).toBe('tn_001')
    expect(cells[2].text()).toBe('db_abc')
    expect(cells[3].text()).toBe('admin')
    expect(cells[4].text()).toContain('DDL')
    expect(cells[5].text()).toBe('test')
    expect(cells[7].text()).toBe('15')
  })

  it('shows dash for null user_name and duration', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    const thirdRow = wrapper.findAll('tbody tr')[2]
    const cells = thirdRow.findAll('td')
    expect(cells[3].text()).toBe('-')
    expect(cells[7].text()).toBe('-')
  })

  it('shows empty state when no logs', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue({
      data: { data: [], total: 0, total_pages: 0 },
    } as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    expect(wrapper.find('.empty-state').text()).toBe('暂无数据')
  })

  it('has filter inputs for tenant, db, and type', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    const inputs = wrapper.findAll('input.search-input')
    expect(inputs).toHaveLength(2)
    expect(inputs[0].attributes('placeholder')).toContain('租户')
    expect(inputs[1].attributes('placeholder')).toContain('数据库')

    const select = wrapper.find('select')
    const options = select.findAll('option')
    expect(options).toHaveLength(4)
    expect(options[0].text()).toBe('全部类型')
  })

  it('applies filters when filter button clicked', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    vi.clearAllMocks()
    vi.mocked(adminApi.auditLogs).mockResolvedValue({
      data: { data: [], total: 0, total_pages: 0 },
    } as any)

    const inputs = wrapper.findAll('input.search-input')
    await inputs[0].setValue('tn_001')
    await inputs[1].setValue('db_abc')
    await wrapper.find('select').setValue('DDL')

    const filterBtn = wrapper.findAll('button').find(b => b.text() === '筛选')!
    await filterBtn.trigger('click')
    await flushPromises()

    expect(adminApi.auditLogs).toHaveBeenCalledWith({
      page: 0,
      size: 20,
      tenant_id: 'tn_001',
      db_id: 'db_abc',
      type: 'DDL',
    })
  })

  it('pagination: prev button disabled on first page', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    const prevBtn = wrapper.findAll('.page-btn')[0]
    expect(prevBtn.attributes('disabled')).toBeDefined()
  })

  it('pagination: next button disabled on last page', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    const nextBtn = wrapper.findAll('.page-btn')[1]
    expect(nextBtn.attributes('disabled')).toBeDefined()
  })

  it('pagination: navigates to next page', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue({
      data: {
        data: Array.from({ length: 20 }, (_, i) => ({
          id: `al_${i}`, database_id: 'db1', tenant_id: 'tn1',
          timestamp: '2026-03-08T10:00:00Z', statement_type: 'DDL',
        })),
        total: 40,
        total_pages: 2,
      },
    } as any)

    const wrapper = mount(AuditLogs)
    await flushPromises()

    vi.clearAllMocks()
    vi.mocked(adminApi.auditLogs).mockResolvedValue({
      data: { data: [], total: 40, total_pages: 2 },
    } as any)

    const nextBtn = wrapper.findAll('.page-btn')[1]
    expect(nextBtn.attributes('disabled')).toBeUndefined()
    await nextBtn.trigger('click')
    await flushPromises()

    expect(adminApi.auditLogs).toHaveBeenCalledWith({ page: 1, size: 20 })
  })

  it('shows correct page info', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    expect(wrapper.find('.page-info').text()).toContain('第 1 页')
    expect(wrapper.find('.page-info').text()).toContain('共 1 页')
    expect(wrapper.find('.page-info').text()).toContain('3 条')
  })

  it('has export CSV button', async () => {
    vi.mocked(adminApi.auditLogs).mockResolvedValue(mockLogsPage as any)
    const wrapper = mount(AuditLogs)
    await flushPromises()

    const exportBtn = wrapper.findAll('button').find(b => b.text() === '导出 CSV')
    expect(exportBtn).toBeTruthy()
  })
})
