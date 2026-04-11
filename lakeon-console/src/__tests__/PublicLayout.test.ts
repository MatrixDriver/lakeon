import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import PublicLayout from '../layouts/PublicLayout.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div>home</div>' } },
    { path: '/product', component: { template: '<div>product</div>' } },
    { path: '/integrations', component: { template: '<div>integrations</div>' } },
    { path: '/docs', component: { template: '<div>docs</div>' } },
    { path: '/login', component: { template: '<div>login</div>' } },
    { path: '/dashboard', component: { template: '<div>dashboard</div>' } },
  ],
})

describe('PublicLayout', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders nav brand', async () => {
    const wrapper = mount(PublicLayout, {
      global: {
        plugins: [router],
        stubs: { MobileNav: true },
      },
    })
    await router.isReady()
    expect(wrapper.text()).toContain('DBay')
  })

  it('renders the Harbor Editorial nav items', async () => {
    const wrapper = mount(PublicLayout, {
      global: {
        plugins: [router],
        stubs: { MobileNav: true },
      },
    })
    await router.isReady()
    const text = wrapper.text()
    // Nav: Products, Integrations, Docs, Sign in, Get started
    expect(text).toMatch(/产品|Products/)
    expect(text).toMatch(/集成|Integrations/)
    expect(text).toMatch(/文档|Docs/)
    expect(text).toMatch(/登录|Sign in/)
    expect(text).toMatch(/开始使用|Get started/)
  })
})
