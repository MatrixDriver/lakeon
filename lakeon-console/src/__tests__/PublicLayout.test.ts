import { describe, it, expect, h } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PublicLayout from '../layouts/PublicLayout.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div>home</div>' } }],
})

// Stub that renders its label prop so wrapper.text() can find nav labels
const NavDropdownStub = {
  props: ['label'],
  template: '<div class="nav-dropdown-stub">{{ label }}</div>',
}

describe('PublicLayout', () => {
  it('renders nav brand', async () => {
    const wrapper = mount(PublicLayout, {
      global: {
        plugins: [router],
        stubs: { NavDropdown: NavDropdownStub, MobileNav: true },
      },
    })
    await router.isReady()
    expect(wrapper.text()).toContain('DBay')
  })

  it('renders desktop nav items', async () => {
    const wrapper = mount(PublicLayout, {
      global: {
        plugins: [router],
        stubs: { NavDropdown: NavDropdownStub, MobileNav: true },
      },
    })
    await router.isReady()
    const text = wrapper.text()
    expect(text).toMatch(/产品|Product/)
    expect(text).toMatch(/集成|Integrations/)
    expect(text).toMatch(/博客|Blog/)
    expect(text).toMatch(/文档|Docs/)
  })
})
