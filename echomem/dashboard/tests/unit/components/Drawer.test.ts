import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Drawer from '@/components/Drawer.vue'

describe('Drawer', () => {
  it('renders when open=true', () => {
    const w = mount(Drawer, { props: { open: true, title: 'x' } })
    expect(w.find('.drawer').exists()).toBe(true)
  })

  it('emits close when close button clicked', async () => {
    const w = mount(Drawer, { props: { open: true, title: 'x' } })
    await w.find('.close').trigger('click')
    expect(w.emitted('close')).toBeTruthy()
  })

  it('hidden when open=false', () => {
    const w = mount(Drawer, { props: { open: false } })
    expect(w.find('.drawer').exists()).toBe(false)
  })
})
