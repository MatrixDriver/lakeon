import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Icon from '@/components/Icon.vue'

describe('Icon', () => {
  it('renders the requested glyph', () => {
    const w = mount(Icon, { props: { name: 'plus' } })
    expect(w.find('svg').exists()).toBe(true)
    expect(w.attributes('data-icon')).toBe('plus')
  })

  it('renders nothing for unknown name', () => {
    const w = mount(Icon, { props: { name: 'nope' as never } })
    expect(w.find('svg').exists()).toBe(false)
  })
})
