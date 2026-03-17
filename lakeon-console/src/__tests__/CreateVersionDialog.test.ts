import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CreateVersionDialog from '../views/database/CreateVersionDialog.vue'

// Mock the version API
vi.mock('../api/version', () => ({
  versionApi: {
    create: vi.fn(),
  },
}))

import { versionApi } from '../api/version'

describe('CreateVersionDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('not visible when visible=false', () => {
    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: false,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    expect(wrapper.find('.dialog-overlay').exists()).toBe(false)
  })

  it('renders form fields when visible=true', () => {
    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    expect(wrapper.find('.dialog-overlay').exists()).toBe(true)
    expect(wrapper.find('h3').text()).toBe('创建版本')
    expect(wrapper.find('input').exists()).toBe(true)
    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  it('create button is disabled when name is empty', () => {
    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '创建版本')!
    expect(submitBtn.attributes('disabled')).toBeDefined()
  })

  it('create button is enabled when name is filled', async () => {
    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    await wrapper.find('input').setValue('v1.0')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '创建版本')!
    expect(submitBtn.attributes('disabled')).toBeUndefined()
  })

  it('emits close when cancel button is clicked', async () => {
    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    const cancelBtn = wrapper.findAll('button').find(b => b.text() === '取消')!
    await cancelBtn.trigger('click')

    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close when overlay is clicked', async () => {
    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    await wrapper.find('.dialog-overlay').trigger('click')

    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('calls versionApi.create on submit with correct params', async () => {
    vi.mocked(versionApi.create).mockResolvedValue({ data: { id: 'v_new' } } as any)

    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: false,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await wrapper.find('input').setValue('v1.0')
    await wrapper.find('textarea').setValue('Initial version')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '创建版本')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(versionApi.create).toHaveBeenCalledWith('db_abc', 'br_main', {
      name: 'v1.0',
      description: 'Initial version',
    })
  })

  it('calls versionApi.create without description when description is empty', async () => {
    vi.mocked(versionApi.create).mockResolvedValue({ data: { id: 'v_new' } } as any)

    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    await wrapper.find('input').setValue('v1.0')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '创建版本')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(versionApi.create).toHaveBeenCalledWith('db_abc', 'br_main', {
      name: 'v1.0',
      description: undefined,
    })
  })

  it('emits created and close on successful creation', async () => {
    vi.mocked(versionApi.create).mockResolvedValue({ data: { id: 'v_new' } } as any)

    const wrapper = mount(CreateVersionDialog, {
      props: {
        visible: true,
        dbId: 'db_abc',
        branchId: 'br_main',
      },
    })

    await wrapper.find('input').setValue('v1.0')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '创建版本')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('created')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
