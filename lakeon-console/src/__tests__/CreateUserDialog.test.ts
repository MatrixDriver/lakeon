import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CreateUserDialog from '../views/database/CreateUserDialog.vue'

// Mock the dbuser API
vi.mock('../api/dbuser', () => ({
  dbuserApi: {
    createUser: vi.fn(),
  },
}))

// Mock clipboard
vi.mock('../utils/clipboard', () => ({
  copyToClipboard: vi.fn().mockResolvedValue(true),
}))

import { dbuserApi } from '../api/dbuser'

describe('CreateUserDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('not visible when visible=false', () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    expect(wrapper.find('.dialog-overlay').exists()).toBe(false)
  })

  it('renders when visible=true', () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: true, dbId: 'db_abc' },
    })
    expect(wrapper.find('.dialog-overlay').exists()).toBe(true)
    expect(wrapper.find('h3').text()).toBe('添加用户')
  })

  it('shows role dropdown with 3 options', () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: true, dbId: 'db_abc' },
    })
    const options = wrapper.findAll('select option')
    expect(options).toHaveLength(3)
    expect(options[0].text()).toContain('Admin')
    expect(options[1].text()).toContain('Writer')
    expect(options[2].text()).toContain('Reader')
  })

  it('defaults role to READER', () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: true, dbId: 'db_abc' },
    })
    const select = wrapper.find('select')
    expect((select.element as HTMLSelectElement).value).toBe('READER')
  })

  it('disables submit button when username is empty', () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: true, dbId: 'db_abc' },
    })
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    expect(submitBtn.attributes('disabled')).toBeDefined()
  })

  it('enables submit button when username is filled', async () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: true, dbId: 'db_abc' },
    })
    await wrapper.find('input').setValue('testuser')
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    expect(submitBtn.attributes('disabled')).toBeUndefined()
  })

  it('calls dbuserApi.createUser on submit with correct params', async () => {
    vi.mocked(dbuserApi.createUser).mockResolvedValue({
      data: { id: 'u_1', username: 'newuser', password: 'gen-pass-123', role: 'WRITER', is_owner: false, database_id: 'db_abc', created_at: '', updated_at: '' },
    } as any)

    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await wrapper.find('input').setValue('newuser')
    await wrapper.find('select').setValue('WRITER')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(dbuserApi.createUser).toHaveBeenCalledWith('db_abc', {
      username: 'newuser',
      role: 'WRITER',
    })
  })

  it('shows password result after successful creation', async () => {
    vi.mocked(dbuserApi.createUser).mockResolvedValue({
      data: { id: 'u_1', username: 'newuser', password: 'secret-pw-456', role: 'READER', is_owner: false, database_id: 'db_abc', created_at: '', updated_at: '' },
    } as any)

    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await wrapper.find('input').setValue('newuser')
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(wrapper.find('.password-success-msg').text()).toBe('用户创建成功')
    expect(wrapper.find('.password-value').text()).toBe('secret-pw-456')
    expect(wrapper.find('.readonly-value').text()).toBe('newuser')
  })

  it('sends custom password when provided', async () => {
    vi.mocked(dbuserApi.createUser).mockResolvedValue({
      data: { id: 'u_1', username: 'newuser', password: 'my-custom-pw', role: 'READER', is_owner: false, database_id: 'db_abc', created_at: '', updated_at: '' },
    } as any)

    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('newuser')
    await inputs[1].setValue('my-custom-pw')

    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(dbuserApi.createUser).toHaveBeenCalledWith('db_abc', {
      username: 'newuser',
      role: 'READER',
      password: 'my-custom-pw',
    })
  })

  it('shows error message on API failure', async () => {
    vi.mocked(dbuserApi.createUser).mockRejectedValue({
      response: { data: { error: { message: '用户名已存在' } } },
    })

    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await wrapper.find('input').setValue('dupuser')
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()

    expect(wrapper.find('.error-msg').text()).toBe('用户名已存在')
  })

  it('emits close on cancel button', async () => {
    const wrapper = mount(CreateUserDialog, {
      props: { visible: true, dbId: 'db_abc' },
    })
    const cancelBtn = wrapper.findAll('button').find(b => b.text() === '取消')!
    await cancelBtn.trigger('click')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits created and close on done after password shown', async () => {
    vi.mocked(dbuserApi.createUser).mockResolvedValue({
      data: { id: 'u_1', username: 'newuser', password: 'pw123', role: 'READER', is_owner: false, database_id: 'db_abc', created_at: '', updated_at: '' },
    } as any)

    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    await wrapper.find('input').setValue('newuser')
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()

    // Now in password result view, click 确定 to dismiss
    const doneBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await doneBtn.trigger('click')

    expect(wrapper.emitted('created')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('resets form when visibility toggles', async () => {
    vi.mocked(dbuserApi.createUser).mockResolvedValue({
      data: { id: 'u_1', username: 'old', password: 'pw', role: 'READER', is_owner: false, database_id: 'db_abc', created_at: '', updated_at: '' },
    } as any)

    const wrapper = mount(CreateUserDialog, {
      props: { visible: false, dbId: 'db_abc' },
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    // Fill and submit
    await wrapper.find('input').setValue('old')
    const submitBtn = wrapper.findAll('button').find(b => b.text() === '确定')!
    await submitBtn.trigger('click')
    await flushPromises()
    expect(wrapper.find('.password-success-msg').exists()).toBe(true)

    // Close and reopen
    await wrapper.setProps({ visible: false })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    // Should be back to form view
    expect(wrapper.find('.password-success-msg').exists()).toBe(false)
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('')
  })
})
