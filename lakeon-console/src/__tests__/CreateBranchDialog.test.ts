import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CreateBranchDialog from '../views/database/CreateBranchDialog.vue'
import type { Branch } from '../api/branch'

// Mock the branch API
vi.mock('../api/branch', () => ({
  branchApi: {
    create: vi.fn(),
  },
}))

import { branchApi } from '../api/branch'

const mockBranches: Branch[] = [
  {
    id: 'br_main',
    name: 'main',
    parent_branch: '',
    is_default: true,
    status: 'active',
    compute_status: '',
    connection_uri: 'postgres://user:pass@host/db',
    parent_branch_id: null,
    neon_timeline_id: 'tl-main',
    ancestor_lsn: null,
    last_record_lsn: '0/5000',
    current_logical_size_bytes: 10240,
    created_at: '2026-03-01T00:00:00Z',
  },
  {
    id: 'br_feat',
    name: 'feature',
    parent_branch: 'main',
    is_default: false,
    status: 'active',
    compute_status: '',
    connection_uri: 'postgres://user:pass@host/db?branch=feature',
    parent_branch_id: 'br_main',
    neon_timeline_id: 'tl-feat',
    ancestor_lsn: '0/3000',
    last_record_lsn: '0/4000',
    current_logical_size_bytes: 5120,
    created_at: '2026-03-02T00:00:00Z',
  },
]

describe('CreateBranchDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('not visible when visible=false', () => {
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: false,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })

    expect(wrapper.find('.dialog-overlay').exists()).toBe(false)
  })

  it('renders when visible=true', () => {
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: true,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })

    expect(wrapper.find('.dialog-overlay').exists()).toBe(true)
    expect(wrapper.find('h3').text()).toBe('创建分支')
  })

  it('shows all branches in source dropdown', () => {
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: true,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })

    const options = wrapper.findAll('option')
    expect(options).toHaveLength(2)
    expect(options[0].text()).toContain('main')
    expect(options[0].text()).toContain('(默认)')
    expect(options[1].text()).toContain('feature')
  })

  it('disables submit button when name is empty', () => {
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: true,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    expect(submitBtn.attributes('disabled')).toBeDefined()
  })

  it('enables submit button when name is filled', async () => {
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: true,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })

    await wrapper.find('input').setValue('new-branch')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    expect(submitBtn.attributes('disabled')).toBeUndefined()
  })

  it('calls branchApi.create and emits events on submit', async () => {
    vi.mocked(branchApi.create).mockResolvedValue({ data: { id: 'br_new' } } as any)

    // Start with visible=false, then switch to true to trigger watch
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: false,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await wrapper.find('input').setValue('new-branch')
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(branchApi.create).toHaveBeenCalledWith('db_abc', {
      name: 'new-branch',
      parent_branch_id: 'br_main',
      ancestor_lsn: undefined,
    })
    expect(wrapper.emitted('created')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close on cancel button', async () => {
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: true,
        branches: mockBranches,
        dbId: 'db_abc',
      },
    })

    const cancelBtn = wrapper.findAll('button').find(b => b.text() === '取消')!
    await cancelBtn.trigger('click')

    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('uses preselectedParentId when provided', async () => {
    // Start with visible=false, then switch to true to trigger watch
    const wrapper = mount(CreateBranchDialog, {
      props: {
        visible: false,
        branches: mockBranches,
        preselectedParentId: 'br_feat',
        dbId: 'db_abc',
      },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    const select = wrapper.find('select')
    expect((select.element as HTMLSelectElement).value).toBe('br_feat')
  })
})
